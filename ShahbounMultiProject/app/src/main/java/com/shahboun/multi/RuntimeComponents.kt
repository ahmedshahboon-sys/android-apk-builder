package com.shahboun.multi

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.IBinder
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal const val EXTRA_RUNTIME_SERVICE = "shahboun.runtime.service"
internal const val EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT = "shahboun.runtime.original_service_intent"
internal const val ACTION_RUNTIME_STOP_CLONE = "com.shahboun.multi.runtime.STOP_CLONE"

class RuntimeComponentHost(
    private val hostContext: Context,
    private val session: RuntimeSession,
    private val slotDir: File
) : Closeable {
    private val guestContext by lazy { RuntimeGuestContext(hostContext, session, slotDir) }
    private val providers = mutableListOf<ContentProvider>()
    private val providersByAuthority = ConcurrentHashMap<String, ContentProvider>()

    @Synchronized
    fun initializeProviders() {
        if (providers.isNotEmpty()) return
        session.runtimePackage.providers.forEach { snapshot ->
            val name = snapshot.name
            runCatching {
                RuntimeExecutionScope.withSession(session) {
                    val clazz = session.classLoader.loadClass(name)
                    require(ContentProvider::class.java.isAssignableFrom(clazz)) { "Provider class غير صالح: $name" }
                    val provider = clazz.getDeclaredConstructor().newInstance() as ContentProvider
                    snapshot.authority.orEmpty().split(';').map { it.trim() }.filter { it.isNotBlank() }.forEach { authority -> providersByAuthority[authority] = provider }
                    val providerInfo = ProviderInfo().apply {
                        this.name = name
                        packageName = session.runtimePackage.packageName
                        authority = snapshot.authority
                        exported = snapshot.exported
                        grantUriPermissions = snapshot.grantUriPermissions
                        applicationInfo = guestContext.applicationInfo
                    }
                    provider.attachInfo(guestContext, providerInfo)
                    providers.add(provider)
                }
                RuntimeDiagnostics.log("PROVIDER", "initialized snapshot ${session.runtimePackage.packageName}/${session.runtimePackage.slot} $name authority=${snapshot.authority}")
            }.onFailure {
                snapshot.authority.orEmpty().split(';').forEach { providersByAuthority.remove(it.trim()) }
                RuntimeDiagnostics.log("PROVIDER", "failed $name: ${it.stackTraceToString()}")
                throw it
            }
        }
    }

    fun providerForAuthority(authority: String?): ContentProvider? = authority?.let(providersByAuthority::get)

    fun dispatchExplicitReceiver(intent: Intent): Boolean {
        val component = intent.component ?: return false
        val pkg = session.runtimePackage
        if (component.packageName != pkg.packageName || !pkg.ownsReceiver(component.className)) return false
        val name = component.className
        return runCatching {
            RuntimeExecutionScope.withSession(session) {
                val clazz = session.classLoader.loadClass(name)
                require(BroadcastReceiver::class.java.isAssignableFrom(clazz)) { "Receiver class غير صالح: $name" }
                val receiver = clazz.getDeclaredConstructor().newInstance() as BroadcastReceiver
                val guestIntent = Intent(intent).setComponent(ComponentName(pkg.packageName, name))
                receiver.onReceive(guestContext, guestIntent)
            }
            RuntimeDiagnostics.log("RECEIVER", "delivered ${pkg.packageName}/${pkg.slot} $name")
            true
        }.getOrElse {
            RuntimeDiagnostics.log("RECEIVER", "failed $name: ${it.stackTraceToString()}")
            throw it
        }
    }

    fun wrapServiceIntent(original: Intent): Intent? {
        val target = resolveGuestService(original) ?: return null
        val pkg = session.runtimePackage
        val stub = RuntimeProcessPool.serviceStub(pkg.packageName, pkg.slot)
        return Intent(hostContext, stub).apply {
            putExtra(EXTRA_RUNTIME_PACKAGE, pkg.packageName)
            putExtra(EXTRA_RUNTIME_SLOT, pkg.slot)
            putExtra(EXTRA_RUNTIME_SERVICE, target)
            putExtra(EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT, Intent(original))
        }
    }

    private fun resolveGuestService(intent: Intent): String? {
        val pkg = session.runtimePackage
        intent.component?.let { component ->
            if (component.packageName != pkg.packageName) return null
            return component.className.takeIf(pkg::ownsService)
        }
        if (intent.`package` != null && intent.`package` != pkg.packageName) return null
        val probe = Intent(intent).apply { `package` = pkg.packageName }
        val resolved = if (Build.VERSION.SDK_INT >= 33) hostContext.packageManager.resolveService(probe, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") hostContext.packageManager.resolveService(probe, 0)
        val name = resolved?.serviceInfo?.takeIf { it.packageName == pkg.packageName }?.name ?: return null
        return name.takeIf(pkg::ownsService)
    }

    override fun close() {
        providersByAuthority.clear()
        providers.clear()
    }
}

open class RuntimeStubService : Service() {
    private data class GuestService(val service: Service, val session: RuntimeSession)
    private data class ServiceRequest(val packageName: String, val slot: Int, val serviceName: String, val session: RuntimeSession, val original: Intent)
    private val running = ConcurrentHashMap<String, GuestService>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RUNTIME_STOP_CLONE) {
            val packageName = intent.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return START_NOT_STICKY
            val slot = intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
            if (slot >= 0) stopCloneServices(packageName, slot)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val request = parseRequest(intent) ?: return START_NOT_STICKY
        val key = key(request.packageName, request.slot, request.serviceName)
        val guest = running.getOrPut(key) { createGuestService(request.session, request.serviceName, request.packageName, request.slot) }
        return runCatching { RuntimeExecutionScope.withSession(guest.session) { guest.service.onStartCommand(request.original, flags, startId) } }
            .onFailure { RuntimeDiagnostics.log("SERVICE", "onStartCommand failed $key: ${it.stackTraceToString()}") }
            .getOrDefault(START_NOT_STICKY)
    }

    private fun stopCloneServices(packageName: String, slot: Int) {
        val prefix = "$packageName#$slot#"
        val entries = running.entries.filter { it.key.startsWith(prefix) }
        entries.forEach { entry ->
            if (running.remove(entry.key, entry.value)) {
                runCatching { RuntimeExecutionScope.withSession(entry.value.session) { entry.value.service.onDestroy() } }
                    .onFailure { RuntimeDiagnostics.log("SERVICE", "stop failed ${entry.key}: ${it.stackTraceToString()}") }
            }
        }
        RuntimeDiagnostics.log("SERVICE", "stopped clone services $packageName/$slot count=${entries.size}")
    }

    override fun onBind(intent: Intent?): IBinder? {
        val request = parseRequest(intent) ?: return null
        val key = key(request.packageName, request.slot, request.serviceName)
        val guest = running.getOrPut(key) { createGuestService(request.session, request.serviceName, request.packageName, request.slot) }
        return runCatching {
            RuntimeExecutionScope.withSession(guest.session) {
                guest.service.onBind(request.original).also { RuntimeDiagnostics.log("SERVICE", "bound ${request.packageName}/${request.slot} ${request.serviceName} binder=${if (it == null) "null" else "present"}") }
            }
        }.onFailure { RuntimeDiagnostics.log("SERVICE", "onBind failed $key: ${it.stackTraceToString()}") }.getOrNull()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val request = parseRequest(intent) ?: return super.onUnbind(intent)
        val key = key(request.packageName, request.slot, request.serviceName)
        val guest = running[key] ?: return false
        return runCatching { RuntimeExecutionScope.withSession(guest.session) { guest.service.onUnbind(request.original) } }
            .onFailure { RuntimeDiagnostics.log("SERVICE", "onUnbind failed $key: ${it.stackTraceToString()}") }.getOrDefault(false)
    }

    override fun onRebind(intent: Intent?) {
        val request = parseRequest(intent) ?: return
        val key = key(request.packageName, request.slot, request.serviceName)
        running[key]?.let { guest ->
            runCatching { RuntimeExecutionScope.withSession(guest.session) { guest.service.onRebind(request.original) } }
                .onFailure { RuntimeDiagnostics.log("SERVICE", "onRebind failed $key: ${it.stackTraceToString()}") }
        }
    }

    override fun onDestroy() {
        running.values.forEach { guest -> runCatching { RuntimeExecutionScope.withSession(guest.session) { guest.service.onDestroy() } } }
        running.clear()
        super.onDestroy()
    }

    private fun parseRequest(intent: Intent?): ServiceRequest? {
        if (intent == null) return null
        val packageName = intent.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return null
        val slot = intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
        val serviceName = intent.getStringExtra(EXTRA_RUNTIME_SERVICE) ?: return null
        if (slot < 0) return null
        val hostApp = applicationContext as? MultiApplication ?: return null
        val session = runCatching { hostApp.engine.sessionFor(packageName, slot) }.getOrElse {
            RuntimeDiagnostics.log("SERVICE", "session restore failed $packageName/$slot: ${it.stackTraceToString()}")
            return null
        }
        if (!session.runtimePackage.ownsService(serviceName)) {
            RuntimeDiagnostics.log("SERVICE", "rejected unknown component $packageName/$slot $serviceName")
            return null
        }
        val original = readOriginalServiceIntent(intent) ?: Intent().setComponent(ComponentName(packageName, serviceName))
        return ServiceRequest(packageName, slot, serviceName, session, original)
    }

    private fun createGuestService(session: RuntimeSession, serviceName: String, packageName: String, slot: Int): GuestService {
        return RuntimeExecutionScope.withSession(session) {
            val clazz = session.classLoader.loadClass(serviceName)
            require(Service::class.java.isAssignableFrom(clazz)) { "Service class غير صالح: $serviceName" }
            val service = clazz.getDeclaredConstructor().newInstance() as Service
            val hostApp = applicationContext as MultiApplication
            val guestContext = RuntimeGuestContext(baseContext, session, hostApp.engine.runtimeSlotDir(packageName, slot))
            attachGuestService(service, guestContext, session, serviceName)
            service.onCreate()
            RuntimeDiagnostics.log("SERVICE", "created $packageName/$slot $serviceName process=${if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else packageName} attached=true")
            GuestService(service, session)
        }
    }

    private fun attachGuestService(service: Service, guestContext: Context, session: RuntimeSession, serviceName: String) {
        ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(service, guestContext)
        val serviceClass = Service::class.java
        fun field(name: String) = serviceClass.getDeclaredField(name).apply { isAccessible = true }
        field("mApplication").set(service, session.guestApplication ?: application)
        field("mClassName").set(service, serviceName)
        listOf("mThread", "mToken", "mActivityManager", "mStartCompatibility").forEach { name ->
            runCatching {
                val f = field(name)
                f.set(service, f.get(this))
            }.onFailure { RuntimeDiagnostics.log("SERVICE", "attach field $name unavailable for $serviceName: ${it.javaClass.simpleName}") }
        }
    }

    private fun key(packageName: String, slot: Int, serviceName: String) = "$packageName#$slot#$serviceName"

    @Suppress("DEPRECATION")
    private fun readOriginalServiceIntent(wrapper: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) wrapper.getParcelableExtra(EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT, Intent::class.java)
    else wrapper.getParcelableExtra(EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT)
}

class RuntimeStubService0 : RuntimeStubService()
class RuntimeStubService1 : RuntimeStubService()
class RuntimeStubService2 : RuntimeStubService()
class RuntimeStubService3 : RuntimeStubService()
class RuntimeStubService4 : RuntimeStubService()
