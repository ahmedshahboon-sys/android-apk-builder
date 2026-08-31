package com.shahboun.multi

import android.accounts.AccountManager
import android.app.AppOpsManager
import android.content.Context
import android.os.UserManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Binder-facing identity rewrite for Android system services. */
object RuntimeIdentityServiceBridge {
    fun install(context: Context): Result<Unit> = runCatching {
        val app = context.applicationContext
        installManager(app.getSystemService(AppOpsManager::class.java), app.packageName, "APPOPS", "IAppOpsService")
        installManager(AccountManager.get(app), app.packageName, "ACCOUNT", "IAccountManager")
        installManager(app.getSystemService(UserManager::class.java), app.packageName, "USER", "IUserManager")
        RuntimeDiagnostics.log("IDENTITY", "AppOps/Account/User identity bridges ready")
    }

    private fun installManager(manager: Any?, hostPackage: String, label: String, hint: String) {
        if (manager == null) {
            RuntimeDiagnostics.log("IDENTITY", "$label manager unavailable")
            return
        }
        val pair = findServiceField(manager, hint)
        if (pair == null) {
            RuntimeDiagnostics.log("IDENTITY", "$label binder service unavailable")
            return
        }
        val field = pair.first
        val delegate = pair.second
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) return
        val interfaces = collectInterfaces(delegate.javaClass)
        if (interfaces.isEmpty()) {
            RuntimeDiagnostics.log("IDENTITY", "$label binder interfaces unavailable")
            return
        }
        field.isAccessible = true
        field.set(manager, Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(delegate, hostPackage, label)))
        RuntimeDiagnostics.log("IDENTITY", "$label identity proxy installed field=${field.name} owner=${field.declaringClass.name}")
    }

    private class Handler(private val delegate: Any, private val hostPackage: String, private val label: String) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args)
            val session = RuntimeExecutionScope.current() ?: return invokeDelegate(method, args)
            val guestPackage = session.runtimePackage.packageName
            val source = args ?: return invokeDelegate(method, args)
            var changed = false
            val routed = Array<Any?>(source.size) { index ->
                val value = source[index]
                if (value is String && value == guestPackage) { changed = true; hostPackage } else value
            }
            if (changed) RuntimeDiagnostics.log("IDENTITY", "$label ${method.name} $guestPackage/${session.runtimePackage.slot} -> host UID")
            return invokeDelegate(method, routed)
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? = try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) { throw (e.targetException ?: e) }
    }

    private fun findServiceField(instance: Any, hint: String): Pair<java.lang.reflect.Field, Any>? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                val value = runCatching { field.isAccessible = true; field.get(instance) }.getOrNull() ?: continue
                val names = buildList {
                    add(value.javaClass.name); add(field.type.name)
                    value.javaClass.interfaces.forEach { add(it.name) }
                    field.type.interfaces.forEach { add(it.name) }
                }
                if (names.any { it.contains(hint, ignoreCase = true) }) return field to value
            }
            current = current.superclass
        }
        return null
    }

    private fun collectInterfaces(type: Class<*>): Array<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        var current: Class<*>? = type
        while (current != null) { out.addAll(current.interfaces); current = current.superclass }
        return out.toTypedArray()
    }
}
