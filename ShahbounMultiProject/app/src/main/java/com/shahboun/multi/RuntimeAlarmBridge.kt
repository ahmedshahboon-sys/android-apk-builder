package com.shahboun.multi

import android.app.AlarmManager
import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Keeps AlarmManager calls valid under the host UID while clone-visible identity stays virtual.
 * PendingIntent routing remains owned by RuntimePendingIntentBridge, so alarms return to the right slot.
 */
object RuntimeAlarmBridge {
    @Volatile private var installed = false

    fun install(context: Context): Result<Unit> = runCatching {
        if (installed) return@runCatching
        val manager = context.getSystemService(AlarmManager::class.java) ?: error("AlarmManager غير متاح")
        val handle = RuntimeCompatibility.findService(
            manager,
            interfaceHints = listOf("IAlarmManager", "AlarmManagerService"),
            candidateNames = listOf("mService", "sService")
        ) ?: error("IAlarmManager غير متاح")
        val field = handle.field
        val delegate = handle.delegate
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) {
            installed = true
            return@runCatching
        }
        val interfaces = RuntimeCompatibility.collectInterfaces(delegate.javaClass)
        require(interfaces.isNotEmpty()) { "واجهة IAlarmManager غير متاحة" }
        val proxy = Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(delegate, context.packageName))
        require(RuntimeCompatibility.write(field, manager, proxy)) { "تعذر تثبيت AlarmManager proxy" }
        installed = true
        RuntimeDiagnostics.log("ALARM", "clone-aware AlarmManager bridge installed field=${field.name} owner=${field.declaringClass.name}")
    }

    private class Handler(private val delegate: Any, private val hostPackage: String) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args)
            val session = RuntimeExecutionScope.current() ?: return invokeDelegate(method, args)
            val guestPackage = session.runtimePackage.packageName
            val source = args ?: return invokeDelegate(method, args)
            val mutable = Array<Any?>(source.size) { source[it] }
            var changed = false
            source.forEachIndexed { index, value ->
                if (value is String && value == guestPackage) {
                    mutable[index] = hostPackage
                    changed = true
                } else if (value is String && (method.name.contains("set", true) || method.name.contains("alarm", true)) && value.startsWith(guestPackage)) {
                    mutable[index] = "shahboun:${guestPackage}:${session.runtimePackage.slot}:$value"
                    changed = true
                }
            }
            if (changed) RuntimeDiagnostics.log("ALARM", "${method.name} routed $guestPackage/${session.runtimePackage.slot}")
            return invokeDelegate(method, mutable)
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? = try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }
}
