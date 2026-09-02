package com.shahboun.multi

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Fully namespaces notification traffic, channels and groups per Runtime 3 clone identity. */
object RuntimeNotificationBridge {
    @Volatile private var installed = false

    fun install(context: Context): Result<Unit> = runCatching {
        if (installed) return@runCatching
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: error("NotificationManager غير متاح")
        runCatching { manager.areNotificationsEnabled() }

        val handle = findNotificationService(manager) ?: error("INotificationManager غير متاح")
        val field = handle.first
        val owner = handle.second
        val delegate = handle.third
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) {
            installed = true
            return@runCatching
        }
        val interfaces = RuntimeCompatibility.collectInterfaces(delegate.javaClass)
        require(interfaces.isNotEmpty()) { "واجهة INotificationManager غير متاحة" }
        val proxy = Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(delegate))
        require(RuntimeCompatibility.write(field, owner, proxy)) { "تعذر تثبيت Notification proxy" }
        installed = true
        RuntimeDiagnostics.log("NOTIFY", "Runtime3 notification bridge installed field=${field.name} owner=${field.declaringClass.name}")
    }

    private fun findNotificationService(manager: NotificationManager): Triple<java.lang.reflect.Field, Any?, Any>? {
        val fields = RuntimeCompatibility.allFields(NotificationManager::class.java)
        val ordered = listOf("sService", "mService")
        ordered.forEach { name ->
            fields.firstOrNull { it.name == name }?.let { field ->
                val owner: Any? = if (Modifier.isStatic(field.modifiers)) null else manager
                val value = runCatching { field.get(owner) }.getOrNull()
                if (value != null) return Triple(field, owner, value)
            }
        }
        fields.forEach { field ->
            val owner: Any? = if (Modifier.isStatic(field.modifiers)) null else manager
            val value = runCatching { field.get(owner) }.getOrNull() ?: return@forEach
            val names = listOf(field.type.name, value.javaClass.name) + RuntimeCompatibility.collectInterfaces(value.javaClass).map { it.name }
            if (names.any { it.contains("INotificationManager", true) || it.contains("NotificationManagerService", true) }) {
                return Triple(field, owner, value)
            }
        }
        return null
    }

    private class Handler(private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args)
            val session = RuntimeExecutionScope.current() ?: return invokeDelegate(method, args)
            val pkg = session.runtimePackage
            val source = args ?: emptyArray()
            val mutable = Array<Any?>(source.size) { source[it] }
            var touched = false

            // Android must see the real host package/UID; guest objects and IDs are namespaced below.
            mutable.indices.forEach { index ->
                if (mutable[index] == pkg.packageName) {
                    mutable[index] = BuildConfig.APPLICATION_ID
                    touched = true
                }
            }

            val notificationMethod = method.name.contains("Notification", ignoreCase = true)
            val channelMethod = method.name.contains("NotificationChannel", ignoreCase = true)
            val groupMethod = method.name.contains("NotificationChannelGroup", ignoreCase = true)

            if (channelMethod || groupMethod) {
                mutable.forEach { Runtime3NotificationNamespace.namespaceObjects(pkg.packageName, pkg.slot, it) }
                namespaceChannelOrGroupId(method, mutable, pkg.packageName, pkg.slot)
                touched = true
            }

            when {
                method.name.contains("enqueueNotification", ignoreCase = true) -> {
                    namespaceTag(method, mutable, pkg.packageName, pkg.slot, beforeNotification = true)
                    mutable.filterIsInstance<Notification>().forEach { notification ->
                        Runtime3NotificationNamespace.namespaceNotification(pkg.packageName, pkg.slot, notification)
                        @Suppress("DEPRECATION")
                        if (notification.icon == 0) notification.icon = R.drawable.ic_launcher_mokarrer
                    }
                    touched = true
                }
                method.name.contains("cancelNotification", ignoreCase = true) -> {
                    namespaceTag(method, mutable, pkg.packageName, pkg.slot, beforeNotification = false)
                    touched = true
                }
                method.name.contains("cancelAll", ignoreCase = true) -> {
                    // INotificationManager.cancelAll has no per-channel/tag predicate. Suppress it so
                    // one clone can never erase notifications belonging to another clone.
                    RuntimeDiagnostics.log("NOTIFY", "clone-scoped cancelAll suppressed ${pkg.packageName}/${pkg.slot}")
                    return defaultValue(method.returnType)
                }
            }

            if (touched) RuntimeDiagnostics.log("NOTIFY", "routed ${method.name} ${pkg.packageName}/${pkg.slot}")

            return try {
                val result = invokeDelegate(method, mutable)
                if (channelMethod || groupMethod) {
                    Runtime3NotificationNamespace.restoreObjects(pkg.packageName, pkg.slot, result)
                }
                result
            } finally {
                // Binder has already consumed the namespaced parcel. Restore guest-owned objects so
                // their visible channel/group IDs remain exactly what the original app supplied.
                if (notificationMethod || channelMethod || groupMethod) {
                    mutable.forEach { Runtime3NotificationNamespace.restoreObjects(pkg.packageName, pkg.slot, it) }
                }
            }
        }

        private fun namespaceChannelOrGroupId(method: Method, args: Array<Any?>, packageName: String, slot: Int) {
            // Conversation creation carries a conversationId string, not a raw channel lookup ID;
            // its parent NotificationChannel object is already namespaced by namespaceObjects().
            if (method.name.startsWith("createConversationNotificationChannel", ignoreCase = true)) return

            val host = BuildConfig.APPLICATION_ID
            val candidate = method.parameterTypes.indices.firstOrNull { index ->
                method.parameterTypes[index] == String::class.java &&
                    (args.getOrNull(index) as? String)?.let { value ->
                        value != host && value != packageName && !value.startsWith("android")
                    } == true
            } ?: return
            val original = args[candidate] as? String ?: return
            args[candidate] = Runtime3NotificationNamespace.namespaceId(packageName, slot, original)
        }

        private fun namespaceTag(
            method: Method,
            args: Array<Any?>,
            packageName: String,
            slot: Int,
            beforeNotification: Boolean
        ) {
            val notificationIndex = if (beforeNotification) {
                method.parameterTypes.indexOfFirst { Notification::class.java.isAssignableFrom(it) }
            } else -1
            val upper = if (notificationIndex >= 0) notificationIndex else method.parameterTypes.size
            val candidates = (0 until upper).filter { method.parameterTypes[it] == String::class.java }
            if (candidates.size < 2) return
            val tagIndex = candidates.last()
            val current = args[tagIndex] as? String
            val prefix = Runtime3NotificationNamespace.prefix(packageName, slot)
            if (current?.startsWith(prefix) == true) return
            args[tagIndex] = "$prefix${current.orEmpty()}"
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? = try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}
