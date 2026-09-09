package com.shahboun.multi

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Clone-aware identity boundary for Android manager services that keep an AIDL delegate.
 * Guest-facing objects remain normal framework managers; package/attribution arguments are sanitized
 * only when calls cross to system_server.
 */
internal object RuntimeSystemServiceVirtualizer {
    enum class State { FULL, PASSTHROUGH, FAIL }
    data class Capability(val state: State, val detail: String)

    private val patched = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
    private val capabilities = ConcurrentHashMap<String, Capability>()

    private val names = setOf(
        "connectivity", "wifi", "location", "phone", "telephony_subscription_service",
        "audio", "media_session", "media_router", "camera", "sensor", "vibrator",
        "vibrator_manager", "storage", "download", "power", "device_policy", "display",
        "shortcut", "launcherapps", "usagestats", "bluetooth", "input_method", "autofill",
        "companiondevice", "biometric", "role", "appwidget", "netstats", "network_stats",
        "ethernet", "credential", "search", "textservices", "print", "wallpaper",
        Context.CONNECTIVITY_SERVICE,
        Context.WIFI_SERVICE,
        Context.LOCATION_SERVICE,
        Context.TELEPHONY_SERVICE,
        Context.AUDIO_SERVICE,
        Context.SENSOR_SERVICE,
        Context.CAMERA_SERVICE,
        Context.STORAGE_SERVICE,
        Context.VIBRATOR_SERVICE,
        Context.NOTIFICATION_SERVICE,
        Context.APP_OPS_SERVICE,
        Context.USER_SERVICE
    )

    fun serviceFor(base: Context, session: RuntimeSession, name: String): Any? {
        val manager = base.getSystemService(name) ?: run {
            if (name in names) capabilities[name] = Capability(State.FAIL, "manager unavailable")
            return null
        }
        if (name !in names) return manager
        RuntimeRecursionGuard.call(
            key = "system-service:$name",
            fallback = {
                capabilities.putIfAbsent(name, Capability(State.PASSTHROUGH, "recursion guard fallback"))
                manager
            }
        ) {
            patchManager(base, session, manager, name)
            manager
        }
        return manager
    }

    fun snapshot(): Map<String, Capability> = capabilities.toSortedMap()

    fun stateFor(name: String): Capability? = capabilities[name]

    private fun patchManager(context: Context, session: RuntimeSession, manager: Any, serviceName: String) {
        if (!patched.add(manager)) return
        val field = findServiceField(manager.javaClass) ?: run {
            capabilities[serviceName] = Capability(State.PASSTHROUGH, "binder field unavailable on ${manager.javaClass.simpleName}")
            RuntimeDiagnostics.log("SERVICE5", "$serviceName passthrough binder-field-unavailable manager=${manager.javaClass.name}")
            return
        }
        val original = runCatching { field.isAccessible = true; field.get(manager) }.getOrElse {
            capabilities[serviceName] = Capability(State.FAIL, "binder delegate read failed: ${it.javaClass.simpleName}")
            RuntimeDiagnostics.log("SERVICE5", "$serviceName failed delegate-read ${it.javaClass.simpleName}: ${it.message}")
            return
        } ?: run {
            capabilities[serviceName] = Capability(State.PASSTHROUGH, "binder delegate is null")
            return
        }
        if (Proxy.isProxyClass(original.javaClass)) {
            capabilities.putIfAbsent(serviceName, Capability(State.FULL, "already proxied"))
            return
        }
        val interfaces = collectInterfaces(original.javaClass)
        if (interfaces.isEmpty()) {
            capabilities[serviceName] = Capability(State.PASSTHROUGH, "service interface unavailable")
            RuntimeDiagnostics.log("SERVICE5", "$serviceName passthrough service-interface-unavailable ${original.javaClass.name}")
            return
        }
        val handler = InvocationHandler { _, method, args ->
            val safe = RuntimeBinderIdentitySanitizer.sanitize(context, session, args)
            try {
                method.invoke(original, *(safe ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw (e.targetException ?: e)
            }
        }
        val loader = interfaces.firstOrNull()?.classLoader ?: original.javaClass.classLoader
        val proxy = runCatching { Proxy.newProxyInstance(loader, interfaces.toTypedArray(), handler) }.getOrElse {
            capabilities[serviceName] = Capability(State.FAIL, "proxy create failed: ${it.javaClass.simpleName}")
            RuntimeDiagnostics.log("SERVICE5", "$serviceName failed proxy-create ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        runCatching { field.isAccessible = true; field.set(manager, proxy) }
            .onSuccess {
                capabilities[serviceName] = Capability(State.FULL, "binder identity proxy field=${field.name}")
                RuntimeDiagnostics.log("SERVICE5", "$serviceName full binder-identity field=${field.name} manager=${manager.javaClass.simpleName}")
            }
            .onFailure {
                patched.remove(manager)
                capabilities[serviceName] = Capability(State.FAIL, "proxy write failed: ${it.javaClass.simpleName}")
                RuntimeDiagnostics.log("SERVICE5", "$serviceName failed proxy-write ${it.javaClass.simpleName}: ${it.message}")
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
