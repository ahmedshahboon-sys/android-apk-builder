package com.shahboun.multi

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Base64

/** Persistent clone-scoped notification ledger used to implement safe cancelAll and cleanup. */
object Runtime3NotificationLedger {
    private const val PREFS = "shahboun_runtime3_notifications"

    data class Entry(val tag: String?, val id: Int)

    fun record(context: Context, packageName: String, slot: Int, tag: String?, id: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(packageName, slot)
        val set = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        set.removeAll { decode(it)?.id == id && decode(it)?.tag == tag }
        set += encode(Entry(tag, id))
        prefs.edit().putStringSet(key, set).apply()
    }

    fun remove(context: Context, packageName: String, slot: Int, tag: String?, id: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(packageName, slot)
        val set = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        set.removeAll { encoded -> decode(encoded)?.let { it.id == id && it.tag == tag } == true }
        if (set.isEmpty()) prefs.edit().remove(key).apply() else prefs.edit().putStringSet(key, set).apply()
    }

    fun cancelAll(context: Context, packageName: String, slot: Int) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(packageName, slot)
        val manager = app.getSystemService(NotificationManager::class.java)
        val entries = prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull(::decode)
        entries.forEach { entry -> runCatching { manager?.cancel(entry.tag, entry.id) } }
        prefs.edit().remove(key).apply()
        RuntimeDiagnostics.log("NOTIFY3", "cancelAll $packageName/$slot count=${entries.size}")
    }

    fun clearClone(context: Context, packageName: String, slot: Int, removeChannels: Boolean) {
        val app = context.applicationContext
        cancelAll(app, packageName, slot)
        if (!removeChannels || Build.VERSION.SDK_INT < 26) return
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        val prefix = Runtime3NotificationNamespace.prefix(packageName, slot)
        runCatching {
            manager.notificationChannels.filter { it.id.startsWith(prefix) }.forEach { manager.deleteNotificationChannel(it.id) }
            if (Build.VERSION.SDK_INT >= 28) {
                manager.notificationChannelGroups.filter { it.id.startsWith(prefix) }.forEach { manager.deleteNotificationChannelGroup(it.id) }
            }
        }.onFailure {
            RuntimeDiagnostics.log("NOTIFY3", "channel cleanup failed $packageName/$slot: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun key(packageName: String, slot: Int) = "${Runtime3NotificationNamespace.prefix(packageName, slot)}ledger"

    private fun encode(entry: Entry): String {
        val tag = entry.tag?.toByteArray(Charsets.UTF_8)?.let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) } ?: "~"
        return "${entry.id}:$tag"
    }

    private fun decode(value: String): Entry? = runCatching {
        val split = value.indexOf(':')
        if (split <= 0) return@runCatching null
        val id = value.substring(0, split).toInt()
        val raw = value.substring(split + 1)
        val tag = if (raw == "~") null else String(Base64.decode(raw, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
        Entry(tag, id)
    }.getOrNull()
}
