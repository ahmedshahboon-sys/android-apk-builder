package com.shahboun.multi

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Extends Runtime 4 identity virtualization to Android managers that keep an AIDL service in mService/sService.
 * The proxy preserves the physical host identity at system_server while guest code continues to see its logical package.
 */
internal object RuntimeSystemServiceVirtualizer {
    private val patched = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
    private val names = setOf(
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
        val manager = base.getSystemService(name) ?: return null
        if (name !in names) return manager
        patchManager(base, session, manager, name)
        return manager
    }

    private fun patchManager(context: Context, session: RuntimeSession, manager: Any, serviceName: String) {
        if (!patched.add(manager)) return
        val field = findServiceField(manager.javaClass) ?: run {
            RuntimeDiagnostics.log("SERVICE4", "$serviceName public-manager passthrough; binder field unavailable")
            return
        }
        val original = runCatching { field.isAccessible = true; field.get(manager) }.getOrNull() ?: return
        if (Proxy.isProxyClass(original.javaClass)) return
        val interfaces = collectInterfaces(original.javaClass)
        if (interfaces.isEmpty()) {
            RuntimeDiagnostics.log("SERVICE4", "$serviceName passthrough; service interface unavailable ${original.javaClass.name}")
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
        val proxy = runCatching { Proxy.newProxyInstance(original.javaClass.classLoader, interfaces.toTypedArray(), handler) }.getOrNull() ?: return
        runCatching { field.set(manager, proxy) }
            .onSuccess { RuntimeDiagnostics.log("SERVICE4", "$serviceName binder identity virtualization active field=${field.name}") }
            .onFailure { RuntimeDiagnostics.log("SERVICE4", "$serviceName patch failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun findServiceField(type: Class<*>): java.lang.reflect.Field? {
        var cursor: Class<*>? = type
        while (cursor != null) {
            cursor.declaredFields.firstOrNull { field ->
                field.name == "mService" || field.name == "sService" || field.name == "mManagerService"
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
