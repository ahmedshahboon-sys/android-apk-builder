package com.shahboun.multi

import android.app.AlarmManager
import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Keeps AlarmManager calls valid under the physical host UID while preserving clone namespaces. */
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
        val proxy = Proxy.newProxyInstance(
            interfaces.first().classLoader,
            interfaces,
            Handler(context.applicationContext, delegate)
        )
        require(RuntimeCompatibility.write(field, manager, proxy)) { "تعذر تثبيت AlarmManager proxy" }
        installed = true
        RuntimeDiagnostics.log(
            "ALARM",
            "clone-aware AlarmManager bridge installed field=${field.name} owner=${field.declaringClass.name}"
        )
    }

    private class Handler(private val context: Context, private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args, null)
            val session = RuntimeExecutionScope.current()
            if (session == null) return invokeDelegate(method, args, null)

            val source = args ?: emptyArray()
            val mutable = Array<Any?>(source.size) { source[it] }
            val guestPackage = session.runtimePackage.packageName
            // Namespace listener tags only. Package/opPackage/AttributionSource rewriting is handled
            // centrally below so Android 14/15/16 signature changes keep exact declared types.
            source.forEachIndexed { index, value ->
                if (value is String && value.startsWith(guestPackage) && value != guestPackage &&
                    (method.name.contains("set", true) || method.name.contains("alarm", true))) {
                    mutable[index] = "shahboun:${session.runtimePackage.slot}:$value"
                }
            }
            RuntimeDiagnostics.log("ALARM", "${method.name} routed $guestPackage/${session.runtimePackage.slot}")
            return invokeDelegate(method, mutable, session)
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?, session: RuntimeSession?): Any? = try {
            val safe = RuntimeBinderIdentitySanitizer.sanitize(context, session, method, args)
            val result = method.invoke(delegate, *(safe ?: emptyArray()))
            RuntimeBinderIdentitySanitizer.restoreResult(session, result)
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }
}
