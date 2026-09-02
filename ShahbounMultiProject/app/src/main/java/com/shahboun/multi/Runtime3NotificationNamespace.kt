package com.shahboun.multi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import java.security.MessageDigest

/** Namespaces Android notification objects without leaking Runtime 3 identity to guest code. */
object Runtime3NotificationNamespace {
    fun prefix(packageName: String, slot: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(packageName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(10)
        return "sbn3.$digest.$slot:"
    }

    fun namespaceId(packageName: String, slot: Int, value: String?): String? {
        if (value == null) return null
        val prefix = prefix(packageName, slot)
        return if (value.startsWith(prefix)) value else prefix + value
    }

    fun guestId(packageName: String, slot: Int, value: String?): String? {
        if (value == null) return null
        val prefix = prefix(packageName, slot)
        return if (value.startsWith(prefix)) value.removePrefix(prefix) else value
    }

    fun namespaceNotification(packageName: String, slot: Int, notification: Notification) {
        notification.channelId?.let { setField(notification, "mChannelId", namespaceId(packageName, slot, it)) }
        notification.group?.let { setField(notification, "mGroupKey", namespaceId(packageName, slot, it)) }
    }

    fun namespaceChannel(packageName: String, slot: Int, channel: NotificationChannel) {
        setChannelId(channel, namespaceId(packageName, slot, channel.id) ?: channel.id)
        channel.group?.let { channel.setGroup(namespaceId(packageName, slot, it)) }
        val parent = channel.parentChannelId
        val conversation = channel.conversationId
        if (parent != null && conversation != null) {
            channel.setConversationId(namespaceId(packageName, slot, parent) ?: parent, conversation)
        }
    }

    fun restoreChannel(packageName: String, slot: Int, channel: NotificationChannel): NotificationChannel {
        setChannelId(channel, guestId(packageName, slot, channel.id) ?: channel.id)
        channel.group?.let { channel.setGroup(guestId(packageName, slot, it)) }
        val parent = channel.parentChannelId
        val conversation = channel.conversationId
        if (parent != null && conversation != null) {
            channel.setConversationId(guestId(packageName, slot, parent) ?: parent, conversation)
        }
        return channel
    }

    fun namespaceGroup(packageName: String, slot: Int, group: NotificationChannelGroup) {
        setField(group, "mId", namespaceId(packageName, slot, group.id))
        group.channels?.forEach { namespaceChannel(packageName, slot, it) }
    }

    fun restoreGroup(packageName: String, slot: Int, group: NotificationChannelGroup): NotificationChannelGroup {
        setField(group, "mId", guestId(packageName, slot, group.id))
        group.channels?.forEach { restoreChannel(packageName, slot, it) }
        return group
    }

    fun namespaceObjects(packageName: String, slot: Int, value: Any?) {
        when (value) {
            is NotificationChannel -> namespaceChannel(packageName, slot, value)
            is NotificationChannelGroup -> namespaceGroup(packageName, slot, value)
            is Notification -> namespaceNotification(packageName, slot, value)
            is Iterable<*> -> value.forEach { namespaceObjects(packageName, slot, it) }
            is Array<*> -> value.forEach { namespaceObjects(packageName, slot, it) }
            null -> Unit
            else -> parceledList(value)?.forEach { namespaceObjects(packageName, slot, it) }
        }
    }

    fun restoreObjects(packageName: String, slot: Int, value: Any?): Any? {
        when (value) {
            is NotificationChannel -> restoreChannel(packageName, slot, value)
            is NotificationChannelGroup -> restoreGroup(packageName, slot, value)
            is Iterable<*> -> value.forEach { restoreObjects(packageName, slot, it) }
            is Array<*> -> value.forEach { restoreObjects(packageName, slot, it) }
            null -> Unit
            else -> parceledList(value)?.forEach { restoreObjects(packageName, slot, it) }
        }
        return value
    }

    private fun parceledList(value: Any): List<*>? {
        if (!value.javaClass.name.contains("ParceledListSlice")) return null
        return runCatching {
            value.javaClass.methods.firstOrNull { it.name == "getList" && it.parameterCount == 0 }
                ?.invoke(value) as? List<*>
        }.getOrNull()
    }

    private fun setChannelId(channel: NotificationChannel, id: String) {
        val success = runCatching {
            val method = channel.javaClass.getDeclaredMethod("setId", String::class.java).apply { isAccessible = true }
            method.invoke(channel, id)
            true
        }.getOrDefault(false)
        if (!success) setField(channel, "mId", id)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = RuntimeCompatibility.findField(target.javaClass, name)
            ?: error("Notification namespace field unavailable: ${target.javaClass.name}.$name")
        check(RuntimeCompatibility.write(field, target, value)) {
            "Unable to namespace ${target.javaClass.name}.$name"
        }
    }
}
