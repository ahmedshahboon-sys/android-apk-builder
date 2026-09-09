package com.shahboun.multi

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Clone-aware identity boundary for Android manager services that keep an AIDL delegate.
 * Installing an identity proxy is only PARTIAL evidence: it does not prove complete callbacks,
 * permissions, return semantics, or OEM-specific Binder signatures.
 */
internal object RuntimeSystemServiceVirtualizer {
    enum class State { FULL, PARTIAL, PASSTHROUGH, FALLBACK, NOT_TESTED, UNSUPPORTED, FAIL }
    data class Capability(val state: State, val detail: String)

    private val patched = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
    private val capabilities = ConcurrentHashMap<String, Capability>()

    private val names = setOf(
        "activity", "activity_task", "connectivity", "wifi", "location", "phone", "telephony_subscription_service",
        "audio", "media_session", "media_router", "camera", "sensor", "vibrator", "vibrator_manager",
        "storage", "download", "power", "device_policy", "display", "shortcut", "launcherapps", "usagestats",
        "bluetooth", "input_method", "autofill", "companiondevice", "biometric", "role", "appwidget", "appops",
        "user", "netstats", "network_stats", "ethernet", "credential", "search", "textservices", "print", "wallpaper",
        Context.CONNECTIVITY_SERVICE, Context.WIFI_SERVICE, Context.LOCATION_SERVICE, Context.TELEPHONY_SERVICE,
        Context.AUDIO_SERVICE, Context.SENSOR_SERVICE, Context.CAMERA_SERVICE, Context.STORAGE_SERVICE,
        Context.VIBRATOR_SERVICE, Context.NOTIFICATION_SERVICE, Context.APP_OPS_SERVICE, Context.USER_SERVICE,
        Context.POWER_SERVICE, Context.DISPLAY_SERVICE, Context.DOWNLOAD_SERVICE, Context.INPUT_METHOD_SERVICE
    )

    fun serviceFor(base: Context, session: RuntimeSession, name: String): Any? {
        val manager = base.getSystemService(name) ?: run {
            if (name in names) capabilities[name] = Capability(State.FAIL, "manager unavailable")
            return null
        }
        if (name !in names) return manager
        return RuntimeRecursionGuard.call(
            key = "system-service:$name",
            fallback = {
                capabilities[name] = Capability(State.FALLBACK, "recursion guard returned physical manager")
                manager
            }
        ) {
            patchManager(base, session, manager, name)
            manager
        }
    }

    fun snapshot(): Map<String, Capability> = capabilities.toSortedMap()
    fun stateFor(name: String): Capability? = capabilities[name]

    fun declaredCoverage(): Map<String, Capability> = names.associateWith { name ->
        capabilities[name] ?: Capability(State.NOT_TESTED, "manager not requested in this process")
    }.toSortedMap()

    private fun patchManager(context: Context, session: RuntimeSession, manager: Any, serviceName: String) {
        if (!patched.add(manager)) {
            capabilities.putIfAbsent(serviceName, Capability(State.PARTIAL, "manager previously identity-proxied; live behavior not proven"))
            return
        }
        val field = findServiceField(manager.javaClass) ?: run {
            capabilities[serviceName] = Capability(State.PASSTHROUGH, "binder field unavailable on ${manager.javaClass.simpleName}")
            RuntimeDiagnostics.log("SERVICE7", "$serviceName passthrough binder-field-unavailable manager=${manager.javaClass.name}")
            return
        }
        val original = runCatching { field.isAccessible = true; field.get(manager) }.getOrElse {
            capabilities[serviceName] = Capability(State.FAIL, "binder delegate read failed: ${it.javaClass.simpleName}")
            RuntimeDiagnostics.log("SERVICE7", "$serviceName failed delegate-read ${it.javaClass.simpleName}: ${it.message}")
            return
        } ?: run {
            capabilities[serviceName] = Capability(State.PASSTHROUGH, "binder delegate is null")
            return
        }
        if (Proxy.isProxyClass(original.javaClass)) {
            capabilities[serviceName] = Capability(State.PARTIAL, "already proxied; complete service behavior not live-tested")
            return
        }
        val interfaces = collectInterfaces(original.javaClass)
        if (interfaces.isEmpty()) {
            capabilities[serviceName] = Capability(State.PASSTHROUGH, "service interface unavailable")
            RuntimeDiagnostics.log("SERVICE7", "$serviceName passthrough service-interface-unavailable ${original.javaClass.name}")
            return
        }
        val handler = InvocationHandler { _, method, args ->
            val safe = RuntimeBinderIdentitySanitizer.sanitize(context, session, args)
            try {
                val result = method.invoke(original, *(safe ?: emptyArray()))
                RuntimeBinderResultVirtualizer.restore(session, method.name, result)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw (e.targetException ?: e)
            }
        }
        val loader = interfaces.firstOrNull()?.classLoader ?: original.javaClass.classLoader
        val proxy = runCatching { Proxy.newProxyInstance(loader, interfaces.toTypedArray(), handler) }.getOrElse {
            capabilities[serviceName] = Capability(State.FAIL, "proxy create failed: ${it.javaClass.simpleName}")
            RuntimeDiagnostics.log("SERVICE7", "$serviceName failed proxy-create ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        runCatching { field.isAccessible = true; field.set(manager, proxy) }
            .onSuccess {
                capabilities[serviceName] = Capability(State.PARTIAL, "argument sanitizer + method-aware return virtualization installed field=${field.name}; callbacks/live semantics require validation")
                RuntimeDiagnostics.log("SERVICE7", "$serviceName partial binder-boundary field=${field.name} manager=${manager.javaClass.simpleName}")
            }
            .onFailure {
                patched.remove(manager)
                capabilities[serviceName] = Capability(State.FAIL, "proxy write failed: ${it.javaClass.simpleName}")
                RuntimeDiagnostics.log("SERVICE7", "$serviceName failed proxy-write ${it.javaClass.simpleName}: ${it.message}")
            }
    }

    private fun findServiceField(type: Class<*>): java.lang.reflect.Field? {
        var cursor: Class<*>? = type
        while (cursor != null) {
            cursor.declaredFields.firstOrNull { field ->
                field.name == "mService" || field.name == "sService" || field.name == "mManagerService" ||
                    field.name == "mBinder" || field.name == "mRemote" || field.name == "mServiceImpl"
            }?.let { return it }
            cursor = cursor.superclass
        }
        return null
    }

    private fun collectInterfaces(type: Class<*>): LinkedHashSet<Class<*>> {
        val out = linkedSetOf<Class<*>>()
        var cursor: Class<*>? = type
        while (cursor != null) {
            cursor.interfaces.filter { it.isInterface }.forEach(out::add)
            cursor = cursor.superclass
        }
        return out
    }
}
