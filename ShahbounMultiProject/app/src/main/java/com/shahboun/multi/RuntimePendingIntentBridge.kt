package com.shahboun.multi

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Routes guest PendingIntents and guest Service token calls through host-declared components. */
object RuntimePendingIntentBridge {
    private const val INTENT_SENDER_BROADCAST = 1
    private const val INTENT_SENDER_ACTIVITY = 2
    private const val INTENT_SENDER_SERVICE = 4
    private const val INTENT_SENDER_FOREGROUND_SERVICE = 5
    private val restrictedGuestQueries = setOf("getHistoricalProcessExitReasons")
    @Volatile private var installed = false

    fun install(context: Context): Result<Unit> = runCatching {
        if (installed) return@runCatching
        runCatching { ActivityManager::class.java.getDeclaredMethod("getService").apply { isAccessible = true }.invoke(null) }

        val singletonField = RuntimeCompatibility.findField(
            ActivityManager::class.java,
            "IActivityManagerSingleton", "sActivityManagerSingleton", "gDefault"
        ) ?: error("ActivityManager singleton غير متاح")
        singletonField.isAccessible = true
        val singletonOwner: Any? = if (Modifier.isStatic(singletonField.modifiers)) null else ActivityManager::class.java
        val singleton = singletonField.get(singletonOwner) ?: error("ActivityManager singleton فارغ")

        val handle = RuntimeCompatibility.findService(
            singleton,
            interfaceHints = listOf("IActivityManager", "ActivityManagerService"),
            candidateNames = listOf("mInstance", "mService")
        ) ?: error("IActivityManager غير متاح")
        val instanceField = handle.field
        val delegate = handle.delegate
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) {
            installed = true
            return@runCatching
        }
        val interfaces = RuntimeCompatibility.collectInterfaces(delegate.javaClass)
        require(interfaces.isNotEmpty()) { "واجهة IActivityManager غير متاحة" }
        val proxy = Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(context.applicationContext, delegate))
        require(RuntimeCompatibility.write(instanceField, singleton, proxy)) { "تعذر تثبيت IActivityManager proxy" }
        installed = true
        RuntimeDiagnostics.log("PENDING", "slot-aware ActivityManager/PendingIntent bridge installed singleton=${singletonField.name} field=${instanceField.name}")
    }

    private class Handler(private val context: Context, private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args)
            val session = RuntimeExecutionScope.current()

            if (session != null && method.name in restrictedGuestQueries) {
                RuntimeDiagnostics.log("AMS", "virtualized protected query ${method.name} ${session.runtimePackage.packageName}/${session.runtimePackage.slot}")
                return neutralFor(method.returnType)
            }

            if (session != null && method.name in setOf("setServiceForeground", "stopServiceToken", "getForegroundServiceType")) {
                return routeGuestServiceTokenCall(session, method, args)
            }
            if (!method.name.startsWith("getIntentSender") || session == null) return invokeDelegate(method, args)

            val source = args ?: emptyArray()
            val mutable = Array<Any?>(source.size) { source[it] }
            val senderType = findSenderType(method, mutable) ?: return invokeDelegate(method, args)
            if (senderType !in setOf(INTENT_SENDER_BROADCAST, INTENT_SENDER_ACTIVITY, INTENT_SENDER_SERVICE, INTENT_SENDER_FOREGROUND_SERVICE)) return invokeDelegate(method, args)

            val intentsIndex = method.parameterTypes.indexOfFirst { it.isArray && it.componentType == Intent::class.java }
            if (intentsIndex < 0) return invokeDelegate(method, args)
            val intents = mutable[intentsIndex] as? Array<*> ?: return invokeDelegate(method, args)
            val routed = Array(intents.size) { index -> route(session, senderType, intents[index] as? Intent ?: Intent()) }
            mutable[intentsIndex] = routed

            val identity = RuntimeVirtualIdentityRegistry.forSession(session)
            method.parameterTypes.indices.filter { method.parameterTypes[it] == String::class.java }.forEach { index ->
                if (mutable[index] == identity.guestPackage) mutable[index] = identity.hostPackage
            }
            RuntimeDiagnostics.log("PENDING", "routed method=${method.name} type=$senderType ${identity.guestPackage}/${identity.cloneId} count=${routed.size}")
            return invokeDelegate(method, mutable)
        }

        private fun findSenderType(method: Method, args: Array<Any?>): Int? {
            method.parameterTypes.indices.forEach { index ->
                if (method.parameterTypes[index] == Int::class.javaPrimitiveType) {
                    val value = args.getOrNull(index) as? Int
                    if (value in setOf(INTENT_SENDER_BROADCAST, INTENT_SENDER_ACTIVITY, INTENT_SENDER_SERVICE, INTENT_SENDER_FOREGROUND_SERVICE)) return value
                }
            }
            return null
        }

        private fun routeGuestServiceTokenCall(session: RuntimeSession, method: Method, args: Array<out Any?>?): Any? {
            val source = args ?: emptyArray()
            val mutable = Array<Any?>(source.size) { source[it] }
            val identity = RuntimeVirtualIdentityRegistry.forSession(session)
            val hostComponent = ComponentName(identity.hostPackage, RuntimeProcessPool.serviceStub(identity.guestPackage, identity.cloneId).name)
            mutable.indices.forEach { index ->
                val component = mutable[index] as? ComponentName ?: return@forEach
                if (component.packageName == identity.guestPackage) mutable[index] = hostComponent
            }
            RuntimeDiagnostics.log("SERVICE", "AMS ${method.name} routed ${identity.guestPackage}/${identity.cloneId} -> ${hostComponent.className}")
            return invokeDelegate(method, mutable)
        }

        private fun route(session: RuntimeSession, senderType: Int, original: Intent): Intent {
            val pkg = session.runtimePackage
            return when (senderType) {
                INTENT_SENDER_ACTIVITY -> RuntimeIntentRouter.wrap(context, session, original)
                INTENT_SENDER_SERVICE, INTENT_SENDER_FOREGROUND_SERVICE -> {
                    session.componentHost?.wrapServiceIntent(original) ?: original.component?.let { component ->
                        if (component.packageName == pkg.packageName && pkg.ownsService(component.className)) {
                            Intent(context, RuntimeProcessPool.serviceStub(pkg.packageName, pkg.slot)).apply {
                                putExtra(EXTRA_RUNTIME_PACKAGE, pkg.packageName)
                                putExtra(EXTRA_RUNTIME_SLOT, pkg.slot)
                                putExtra(EXTRA_RUNTIME_SERVICE, component.className)
                                putExtra(EXTRA_RUNTIME_ORIGINAL_SERVICE_INTENT, Intent(original))
                                RuntimeIntentSecurity.sign(context, this, session, "service", component.className)
                            }
                        } else original
                    } ?: original
                }
                INTENT_SENDER_BROADCAST -> {
                    original.component?.let { component ->
                        if (component.packageName == pkg.packageName && pkg.ownsReceiver(component.className)) {
                            Intent(context, RuntimeProcessPool.receiverStub(pkg.packageName, pkg.slot)).apply {
                                putExtra(EXTRA_RUNTIME_PACKAGE, pkg.packageName)
                                putExtra(EXTRA_RUNTIME_SLOT, pkg.slot)
                                putExtra(EXTRA_RUNTIME_RECEIVER, component.className)
                                putExtra(EXTRA_RUNTIME_ORIGINAL_RECEIVER_INTENT, Intent(original))
                                RuntimeIntentSecurity.sign(context, this, session, "receiver", component.className)
                            }
                        } else original
                    } ?: original
                }
                else -> original
            }
        }

        private fun neutralFor(type: Class<*>): Any? = when {
            java.util.List::class.java.isAssignableFrom(type) -> mutableListOf<Any>()
            type == Boolean::class.javaPrimitiveType -> false
            type == Int::class.javaPrimitiveType -> 0
            type == Long::class.javaPrimitiveType -> 0L
            else -> null
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? = try {
            val current = RuntimeExecutionScope.current()
            val safeArgs = RuntimeBinderIdentitySanitizer.sanitize(context, current, args)
            val result = method.invoke(delegate, *(safeArgs ?: emptyArray()))
            RuntimeBinderResultVirtualizer.restore(current, result)
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }
}
