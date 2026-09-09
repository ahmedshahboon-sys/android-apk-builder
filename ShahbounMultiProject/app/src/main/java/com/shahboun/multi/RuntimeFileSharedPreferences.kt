package com.shahboun.multi

import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** Clone-private SharedPreferences persisted below the Runtime slot rather than host package prefs. */
internal class RuntimeFileSharedPreferences(private val file: File) : SharedPreferences {
    private val atomic = AtomicFile(file)
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    @Volatile private var values: MutableMap<String, Any?> = load()

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { HashMap(values) }
    override fun getString(key: String?, defValue: String?): String? = synchronized(lock) { values[key] as? String ?: defValue }
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(lock) {
        @Suppress("UNCHECKED_CAST")
        val v = values[key] as? Set<String>
        v?.toMutableSet() ?: defValues?.toMutableSet()
    }
    override fun getInt(key: String?, defValue: Int): Int = synchronized(lock) { (values[key] as? Number)?.toInt() ?: defValue }
    override fun getLong(key: String?, defValue: Long): Long = synchronized(lock) { (values[key] as? Number)?.toLong() ?: defValue }
    override fun getFloat(key: String?, defValue: Float): Float = synchronized(lock) { (values[key] as? Number)?.toFloat() ?: defValue }
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(lock) { values[key] as? Boolean ?: defValue }
    override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { if (listener != null) listeners.add(listener) }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { if (listener != null) listeners.remove(listener) }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { key?.let { changes[it] = value } }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { key?.let { changes[it] = values?.toSet() } }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { key?.let { changes[it] = value } }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { key?.let { changes[it] = value } }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { key?.let { changes[it] = value } }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { key?.let { changes[it] = value } }
        override fun remove(key: String?): SharedPreferences.Editor = apply { key?.let { changes[it] = TOMBSTONE } }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean = applyInternal(sync = true)
        override fun apply() { applyInternal(sync = false) }

        private fun applyInternal(sync: Boolean): Boolean {
            val changed = linkedSetOf<String>()
            val snapshot: Map<String, Any?>
            synchronized(lock) {
                val next = if (clear) linkedMapOf<String, Any?>() else LinkedHashMap(values)
                if (clear) changed.addAll(values.keys)
                changes.forEach { (key, value) ->
                    if (value === TOMBSTONE || value == null) {
                        if (next.remove(key) != null || values.containsKey(key)) changed += key
                    } else {
                        if (next[key] != value) changed += key
                        next[key] = value
                    }
                }
                values = next
                snapshot = HashMap(next)
            }
            val write = { writeSnapshot(snapshot) }
            val ok = if (sync) write() else { Thread({ write() }, "ShahbounPrefs").start(); true }
            if (ok && changed.isNotEmpty()) changed.forEach { key -> listeners.forEach { listener -> runCatching { listener.onSharedPreferenceChanged(this@RuntimeFileSharedPreferences, key) } } }
            return ok
        }
    }

    private fun load(): MutableMap<String, Any?> {
        if (!file.exists()) return linkedMapOf()
        return runCatching {
            val json = atomic.openRead().bufferedReader().use { it.readText() }
            decode(JSONObject(json))
        }.onFailure { RuntimeDiagnostics.log("PREFS4", "load failed ${file.name}: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(linkedMapOf())
    }

    private fun writeSnapshot(snapshot: Map<String, Any?>): Boolean {
        file.parentFile?.mkdirs()
        var stream: java.io.FileOutputStream? = null
        return runCatching {
            stream = atomic.startWrite()
            stream!!.bufferedWriter().use { it.write(encode(snapshot).toString()) }
            atomic.finishWrite(stream)
            true
        }.getOrElse {
            stream?.let { out -> runCatching { atomic.failWrite(out) } }
            RuntimeDiagnostics.log("PREFS4", "write failed ${file.name}: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    private fun encode(map: Map<String, Any?>): JSONObject = JSONObject().apply {
        map.forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is String -> { entry.put("t", "s"); entry.put("v", value) }
                is Int -> { entry.put("t", "i"); entry.put("v", value) }
                is Long -> { entry.put("t", "l"); entry.put("v", value) }
                is Float -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                is Double -> { entry.put("t", "f"); entry.put("v", value) }
                is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                is Set<*> -> { entry.put("t", "ss"); entry.put("v", JSONArray(value.filterIsInstance<String>())) }
                else -> return@forEach
            }
            put(key, entry)
        }
    }

    private fun decode(json: JSONObject): MutableMap<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = json.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "s" -> out[key] = entry.optString("v", "")
                "i" -> out[key] = entry.optInt("v")
                "l" -> out[key] = entry.optLong("v")
                "f" -> out[key] = entry.optDouble("v").toFloat()
                "b" -> out[key] = entry.optBoolean("v")
                "ss" -> {
                    val array = entry.optJSONArray("v") ?: JSONArray()
                    out[key] = buildSet { for (i in 0 until array.length()) add(array.optString(i)) }
                }
            }
        }
        return out
    }

    companion object { private val TOMBSTONE = Any() }
}
