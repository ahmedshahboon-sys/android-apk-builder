package com.shahboun.multi

import android.accounts.AccountManager
import android.app.AppOpsManager
import android.content.Context
import android.os.UserManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Android system services validate package strings against the caller UID. Guest code must keep
 * seeing its original package name, while Binder-facing identity is rewritten to the real host UID.
 * Account/User data themselves remain Android-owned; this bridge never fabricates credentials.
 */
object RuntimeIdentityServiceBridge {
    fun install(context: Context): Result<Unit> = runCatching {
        val app = context.applicationContext
        installManager(app.getSystemService(AppOpsManager::class.java), "mService", app.packageName, "APPOPS")
        installManager(AccountManager.get(app), "mService", app.packageName, "ACCOUNT")
        installManager(app.getSystemService(UserManager::class.java), "mService", app.packageName, "USER")
        RuntimeDiagnostics.log("IDENTITY", "AppOps/Account/User identity bridges ready")
    }

    private fun installManager(manager: Any?, fieldName: String, hostPackage: String, label: String) {
        if (manager == null) {
            RuntimeDiagnostics.log("IDENTITY", "$label manager unavailable")
            return
        }
        val field = findField(manager.javaClass, fieldName)
        if (field == null) {
            RuntimeDiagnostics.log("IDENTITY", "$label binder field unavailable")
            return
        }
        field.isAccessible = true
        val delegate = field.get(manager) ?: return
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) return
        val interfaces = collectInterfaces(delegate.javaClass)
        if (interfaces.isEmpty()) {
            RuntimeDiagnostics.log("IDENTITY", "$label binder interfaces unavailable")
            return
        }
        field.set(manager, Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(delegate, hostPackage, label)))
        RuntimeDiagnostics.log("IDENTITY", "$label identity proxy installed")
    }

    private class Handler(
        private val delegate: Any,
        private val hostPackage: String,
        private val label: String
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args)
            val session = RuntimeExecutionScope.current() ?: return invokeDelegate(method, args)
            val guestPackage = session.runtimePackage.packageName
            val source = args ?: return invokeDelegate(method, args)
            var changed = false
            val routed = Array<Any?>(source.size) { index ->
                val value = source[index]
                if (value is String && value == guestPackage) {
                    changed = true
                    hostPackage
                } else value
            }
            if (changed) RuntimeDiagnostics.log("IDENTITY", "$label ${method.name} $guestPackage/${session.runtimePackage.slot} -> host UID")
            return invokeDelegate(method, routed)
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? = try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun collectInterfaces(type: Class<*>): Array<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        var current: Class<*>? = type
        while (current != null) {
            out.addAll(current.interfaces)
            current = current.superclass
        }
        return out.toTypedArray()
    }
}
