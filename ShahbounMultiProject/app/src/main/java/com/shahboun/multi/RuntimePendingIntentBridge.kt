package com.shahboun.multi

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Routes guest PendingIntents back through host-declared components carrying package+slot. */
object RuntimePendingIntentBridge {
    private const val INTENT_SENDER_BROADCAST = 1
    private const val INTENT_SENDER_ACTIVITY = 2
    private const val INTENT_SENDER_SERVICE = 4
    private const val INTENT_SENDER_FOREGROUND_SERVICE = 5
    @Volatile private var installed = false

    fun install(context: Context): Result<Unit> = runCatching {
        if (installed) return@runCatching
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
            if (senderType !in setOf(INTENT_SENDER_BROADCAST, INTENT_SENDER_ACTIVITY, INTENT_SENDER_SERVICE, INTENT_SENDER_FOREGROUND_SERVICE)) {
                return invokeDelegate(method, args)
            }

            val intentsIndex = method.parameterTypes.indexOfFirst { it.isArray && it.componentType == Intent::class.java }
            if (intentsIndex < 0) return invokeDelegate(method, args)
            val intents = mutable[intentsIndex] as? Array<*> ?: return invokeDelegate(method, args)
            val routed = Array(intents.size) { index ->
                val original = intents[index] as? Intent ?: Intent()
                route(session, senderType, original)
            }
            mutable[intentsIndex] = routed

            val guestPackage = session.runtimePackage.packageName
            method.parameterTypes.indices.filter { method.parameterTypes[it] == String::class.java }.forEach { index ->
                if (mutable[index] == guestPackage) mutable[index] = BuildConfig.APPLICATION_ID
            }
            RuntimeDiagnostics.log("PENDING", "routed type=$senderType $guestPackage/${session.runtimePackage.slot} count=${routed.size}")
            return invokeDelegate(method, mutable)
        }

        private fun route(session: RuntimeSession, senderType: Int, original: Intent): Intent {
            val pkg = session.runtimePackage
            return when (senderType) {
                INTENT_SENDER_ACTIVITY -> RuntimeIntentRouter.wrap(context, session, original)
                INTENT_SENDER_SERVICE, INTENT_SENDER_FOREGROUND_SERVICE -> {
                    session.componentHost?.wrapServiceIntent(original) ?: original.component?.let { component ->
                        if (component.packageName == pkg.packageName && pkg.ownsService(component.className)) {
                            Intent(context, RuntimeStubService::class.java).apply {
                                putExtra(EXTRA_RUNTIME_PACKAGE, pkg.packageName)
                                putExtra(EXTRA_RUNTIME_SLOT, pkg.slot)
                                putExtra(EXTRA_RUNTIME_SERVICE, component.className)
                                putExtra(EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT, Intent(original))
                            }
                        } else original
                    } ?: original
                }
                INTENT_SENDER_BROADCAST -> {
                    original.component?.let { component ->
                        if (component.packageName == pkg.packageName && pkg.ownsReceiver(component.className)) {
                            Intent(context, RuntimeStubReceiver::class.java).apply {
                                putExtra(EXTRA_RUNTIME_PACKAGE, pkg.packageName)
                                putExtra(EXTRA_RUNTIME_SLOT, pkg.slot)
                                putExtra(EXTRA_RUNTIME_RECEIVER, component.className)
                                putExtra(EXTRA_RUNTIME_ORIGINAL_RECEIVER_INTENT, Intent(original))
                            }
                        } else original
                    } ?: original
                }
                else -> original
            }
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
