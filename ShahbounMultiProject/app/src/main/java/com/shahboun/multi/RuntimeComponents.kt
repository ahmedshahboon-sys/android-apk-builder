package com.shahboun.multi

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal const val EXTRA_RUNTIME_SERVICE = "shahboun.runtime.service"
internal const val EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT = "shahboun.runtime.original_service_intent"

/**
 * Own lifecycle bridge for guest providers, explicit receivers and services.
 * It never forwards a guest component to the installed original package.
 */
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
        val pm = hostContext.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 33) {
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong())
        } else null
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(session.runtimePackage.packageName, flags!!)
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(session.runtimePackage.packageName, PackageManager.GET_PROVIDERS)
        }
        info.providers.orEmpty().forEach { providerInfo ->
            val name = providerInfo.name ?: return@forEach
            runCatching {
                val clazz = session.classLoader.loadClass(name)
                require(android.content.ContentProvider::class.java.isAssignableFrom(clazz)) { "Provider class غير صالح: $name" }
                val provider = clazz.getDeclaredConstructor().newInstance() as android.content.ContentProvider
                provider.attachInfo(guestContext, providerInfo)
                providers += provider
                RuntimeDiagnostics.log("PROVIDER", "initialized ${session.runtimePackage.packageName}/${session.runtimePackage.slot} $name")
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
            receiver.onReceive(guestContext, Intent(intent).apply { component = ComponentName(session.runtimePackage.packageName, name) })
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

/** Declared host service that delegates lifecycle to a guest Service object. */
class RuntimeStubService : Service() {
    private data class GuestService(val service: Service, val session: RuntimeSession)
    private val running = ConcurrentHashMap<String, GuestService>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val packageName = intent.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return START_NOT_STICKY
        val slot = intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
        val serviceName = intent.getStringExtra(EXTRA_RUNTIME_SERVICE) ?: return START_NOT_STICKY
        if (slot < 0) return START_NOT_STICKY
        val session = runCatching { RuntimeRegistry.get(packageName, slot) }.getOrElse {
            RuntimeDiagnostics.log("SERVICE", "session missing $packageName/$slot")
            return START_NOT_STICKY
        }
        val key = "$packageName#$slot#$serviceName"
        val guest = running.getOrPut(key) { createGuestService(session, serviceName, packageName, slot) }
        val original = readOriginalServiceIntent(intent) ?: Intent().setComponent(ComponentName(packageName, serviceName))
        return runCatching { guest.service.onStartCommand(original, flags, startId) }
            .onFailure { RuntimeDiagnostics.log("SERVICE", "onStartCommand failed $key: ${it.stackTraceToString()}") }
            .getOrDefault(START_NOT_STICKY)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.values.forEach { runCatching { it.service.onDestroy() } }
        running.clear()
        super.onDestroy()
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

    @Suppress("DEPRECATION")
    private fun readOriginalServiceIntent(wrapper: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        wrapper.getParcelableExtra(EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT, Intent::class.java)
    } else wrapper.getParcelableExtra(EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT)
}
