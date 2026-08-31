package com.shahboun.multi

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Rewrites activity PendingIntents created by a guest so Android stores a host-declared
 * runtime intent carrying the immutable clone package+slot identity.
 */
object RuntimePendingIntentBridge {
    private const val INTENT_SENDER_ACTIVITY = 2
    @Volatile private var installed = false

    fun install(context: Context): Result<Unit> = runCatching {
        if (installed) return@runCatching

        // Force ActivityManager's singleton instance to exist first.
        runCatching {
            ActivityManager::class.java.getDeclaredMethod("getService").apply { isAccessible = true }.invoke(null)
        }

        val singletonField = ActivityManager::class.java.getDeclaredField("IActivityManagerSingleton").apply { isAccessible = true }
        val singleton = singletonField.get(null) ?: error("ActivityManager singleton غير متاح")
        val instanceField = findField(singleton.javaClass, "mInstance") ?: error("ActivityManager instance غير متاح")
        instanceField.isAccessible = true
        val delegate = instanceField.get(singleton) ?: error("IActivityManager غير متاح")
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) {
            installed = true
            return@runCatching
        }

        val interfaces = collectInterfaces(delegate.javaClass)
        require(interfaces.isNotEmpty()) { "واجهة IActivityManager غير متاحة" }
        val proxy = Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(context.applicationContext, delegate))
        instanceField.set(singleton, proxy)
        installed = true
        RuntimeDiagnostics.log("PENDING", "slot-aware PendingIntent bridge installed")
    }

    private class Handler(private val context: Context, private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args)
            if (!method.name.startsWith("getIntentSender")) return invokeDelegate(method, args)

            val session = RuntimeExecutionScope.current() ?: return invokeDelegate(method, args)
            val source = args ?: emptyArray()
            val mutable = Array<Any?>(source.size) { source[it] }
            val senderType = mutable.firstOrNull { it is Int } as? Int ?: return invokeDelegate(method, args)
            if (senderType != INTENT_SENDER_ACTIVITY) return invokeDelegate(method, args)

            val intentsIndex = method.parameterTypes.indexOfFirst { it.isArray && it.componentType == Intent::class.java }
            if (intentsIndex < 0) return invokeDelegate(method, args)
            val intents = mutable[intentsIndex] as? Array<*> ?: return invokeDelegate(method, args)
            val routed = intents.map { item ->
                val intent = item as? Intent ?: return@map item
                RuntimeIntentRouter.wrap(context, session, intent)
            }.toTypedArray()
            mutable[intentsIndex] = routed

            // The caller package submitted to system_server must be the actually installed host.
            val guestPackage = session.runtimePackage.packageName
            val stringIndexes = method.parameterTypes.indices.filter { method.parameterTypes[it] == String::class.java }
            stringIndexes.forEach { index -> if (mutable[index] == guestPackage) mutable[index] = BuildConfig.APPLICATION_ID }

            RuntimeDiagnostics.log("PENDING", "routed activity PendingIntent $guestPackage/${session.runtimePackage.slot} count=${routed.size}")
            return invokeDelegate(method, mutable)
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
        val all = LinkedHashSet<Class<*>>()
        var current: Class<*>? = type
        while (current != null) {
            all.addAll(current.interfaces)
            current = current.superclass
        }
        return all.toTypedArray()
    }
}
