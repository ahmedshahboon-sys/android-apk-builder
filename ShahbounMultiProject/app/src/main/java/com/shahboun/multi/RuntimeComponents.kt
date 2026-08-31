package com.shahboun.multi

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
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

class RuntimeComponentHost(
    private val hostContext: Context,
    private val session: RuntimeSession,
    private val slotDir: File
) : Closeable {
    private val guestContext by lazy { RuntimeGuestContext(hostContext, session, slotDir) }
    private val providers = mutableListOf<android.content.ContentProvider>()

    @Synchronized
    fun initializeProviders() {
        if (providers.isNotEmpty()) return
        session.runtimePackage.providers.forEach { snapshot ->
            val name = snapshot.name
            runCatching {
                val clazz = session.classLoader.loadClass(name)
                require(android.content.ContentProvider::class.java.isAssignableFrom(clazz)) { "Provider class غير صالح: $name" }
                val provider = clazz.getDeclaredConstructor().newInstance() as android.content.ContentProvider
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
                RuntimeDiagnostics.log("PROVIDER", "initialized snapshot ${session.runtimePackage.packageName}/${session.runtimePackage.slot} $name authority=${snapshot.authority}")
            }.onFailure {
                RuntimeDiagnostics.log("PROVIDER", "failed $name: ${it.stackTraceToString()}")
                throw it
            }
        }
    }

    fun dispatchExplicitReceiver(intent: Intent): Boolean {
        val component = intent.component ?: return false
        if (component.packageName != session.runtimePackage.packageName) return false
        val name = component.className
        return runCatching {
            val clazz = session.classLoader.loadClass(name)
            require(BroadcastReceiver::class.java.isAssignableFrom(clazz)) { "Receiver class غير صالح: $name" }
            val receiver = clazz.getDeclaredConstructor().newInstance() as BroadcastReceiver
            val guestIntent = Intent(intent).setComponent(ComponentName(session.runtimePackage.packageName, name))
            receiver.onReceive(guestContext, guestIntent)
            RuntimeDiagnostics.log("RECEIVER", "delivered ${session.runtimePackage.packageName}/${session.runtimePackage.slot} $name")
            true
        }.getOrElse {
            RuntimeDiagnostics.log("RECEIVER", "failed $name: ${it.stackTraceToString()}")
            throw it
        }
    }

    fun wrapServiceIntent(original: Intent): Intent? {
        val target = resolveGuestService(original) ?: return null
        return Intent(hostContext, RuntimeStubService::class.java).apply {
            putExtra(EXTRA_RUNTIME_PACKAGE, session.runtimePackage.packageName)
            putExtra(EXTRA_RUNTIME_SLOT, session.runtimePackage.slot)
            putExtra(EXTRA_RUNTIME_SERVICE, target)
            putExtra(EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT, Intent(original))
        }
    }

    private fun resolveGuestService(intent: Intent): String? {
        intent.component?.let { component ->
            return component.className.takeIf { component.packageName == session.runtimePackage.packageName }
        }
        if (intent.`package` != null && intent.`package` != session.runtimePackage.packageName) return null
        val probe = Intent(intent).apply { `package` = session.runtimePackage.packageName }
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            hostContext.packageManager.resolveService(probe, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") hostContext.packageManager.resolveService(probe, 0)
        }
        return resolved?.serviceInfo?.takeIf { it.packageName == session.runtimePackage.packageName }?.name
    }

    override fun close() {
        providers.clear()
    }
}

class RuntimeStubService : Service() {
    private data class GuestService(val service: Service, val session: RuntimeSession)
    private data class ServiceRequest(
        val packageName: String,
        val slot: Int,
        val serviceName: String,
        val session: RuntimeSession,
        val original: Intent
    )

    private val running = ConcurrentHashMap<String, GuestService>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = parseRequest(intent) ?: return START_NOT_STICKY
        val key = key(request.packageName, request.slot, request.serviceName)
        val guest = running.getOrPut(key) {
            createGuestService(request.session, request.serviceName, request.packageName, request.slot)
        }
        return runCatching { guest.service.onStartCommand(request.original, flags, startId) }
            .onFailure { RuntimeDiagnostics.log("SERVICE", "onStartCommand failed $key: ${it.stackTraceToString()}") }
            .getOrDefault(START_NOT_STICKY)
    }

    override fun onBind(intent: Intent?): IBinder? {
        val request = parseRequest(intent) ?: return null
        val key = key(request.packageName, request.slot, request.serviceName)
        val guest = running.getOrPut(key) {
            createGuestService(request.session, request.serviceName, request.packageName, request.slot)
        }
        return runCatching {
            guest.service.onBind(request.original).also {
                RuntimeDiagnostics.log("SERVICE", "bound ${request.packageName}/${request.slot} ${request.serviceName} binder=${if (it == null) "null" else "present"}")
            }
        }.onFailure {
            RuntimeDiagnostics.log("SERVICE", "onBind failed $key: ${it.stackTraceToString()}")
        }.getOrNull()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val request = parseRequest(intent) ?: return super.onUnbind(intent)
        val key = key(request.packageName, request.slot, request.serviceName)
        val guest = running[key] ?: return false
        return runCatching { guest.service.onUnbind(request.original) }
            .onFailure { RuntimeDiagnostics.log("SERVICE", "onUnbind failed $key: ${it.stackTraceToString()}") }
            .getOrDefault(false)
    }

    override fun onRebind(intent: Intent?) {
        val request = parseRequest(intent) ?: return
        val key = key(request.packageName, request.slot, request.serviceName)
        running[key]?.let { guest ->
            runCatching { guest.service.onRebind(request.original) }
                .onFailure { RuntimeDiagnostics.log("SERVICE", "onRebind failed $key: ${it.stackTraceToString()}") }
        }
    }

    override fun onDestroy() {
        running.values.forEach { runCatching { it.service.onDestroy() } }
        running.clear()
        super.onDestroy()
    }

    private fun parseRequest(intent: Intent?): ServiceRequest? {
        if (intent == null) return null
        val packageName = intent.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return null
        val slot = intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
        val serviceName = intent.getStringExtra(EXTRA_RUNTIME_SERVICE) ?: return null
        if (slot < 0) return null
        val session = runCatching { RuntimeRegistry.get(packageName, slot) }.getOrElse {
            RuntimeDiagnostics.log("SERVICE", "session missing $packageName/$slot")
            return null
        }
        val original = readOriginalServiceIntent(intent)
            ?: Intent().setComponent(ComponentName(packageName, serviceName))
        return ServiceRequest(packageName, slot, serviceName, session, original)
    }

    private fun createGuestService(session: RuntimeSession, serviceName: String, packageName: String, slot: Int): GuestService {
        val clazz = session.classLoader.loadClass(serviceName)
        require(Service::class.java.isAssignableFrom(clazz)) { "Service class غير صالح: $serviceName" }
        val service = clazz.getDeclaredConstructor().newInstance() as Service
        val hostApp = applicationContext as MultiApplication
        val guestContext = RuntimeGuestContext(baseContext, session, hostApp.engine.runtimeSlotDir(packageName, slot))
        val baseField = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        baseField.set(service, guestContext)
        val applicationField = Service::class.java.getDeclaredField("mApplication").apply { isAccessible = true }
        applicationField.set(service, session.guestApplication ?: application)
        service.onCreate()
        RuntimeDiagnostics.log("SERVICE", "created $packageName/$slot $serviceName")
        return GuestService(service, session)
    }

    private fun key(packageName: String, slot: Int, serviceName: String) = "$packageName#$slot#$serviceName"

    @Suppress("DEPRECATION")
    private fun readOriginalServiceIntent(wrapper: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        wrapper.getParcelableExtra(EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT, Intent::class.java)
    } else wrapper.getParcelableExtra(EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT)
}
