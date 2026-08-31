package com.shahboun.multi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class CloneStore(context: Context) {
    private val p = context.getSharedPreferences("clone_store", Context.MODE_PRIVATE)

    @Synchronized
    fun list(): MutableList<CloneProfile> {
        val primary = p.getString(KEY_ITEMS, null)
        val backup = p.getString(KEY_BACKUP, null)
        val expected = p.getString(KEY_CHECKSUM, null)

        val chosen = when {
            !primary.isNullOrBlank() && checksum(primary) == expected && canParse(primary) -> primary
            !backup.isNullOrBlank() && canParse(backup) -> {
                RuntimeDiagnostics.log("STORE", "primary clone store invalid; restored backup")
                persistRaw(backup, backup)
                backup
            }
            !primary.isNullOrBlank() && canParse(primary) -> {
                RuntimeDiagnostics.log("STORE", "checksum mismatch; accepted parseable primary and repaired checksum")
                persistRaw(primary, primary)
                primary
            }
            else -> {
                if (!primary.isNullOrBlank() || !backup.isNullOrBlank()) {
                    RuntimeDiagnostics.log("STORE", "clone store corrupted; reset to empty safely")
                }
                "[]"
            }
        }
        return parse(chosen)
    }

    @Synchronized
    fun save(items: List<CloneProfile>) {
        val next = encode(items)
        val oldPrimary = p.getString(KEY_ITEMS, null)
        val backup = oldPrimary?.takeIf(::canParse) ?: p.getString(KEY_BACKUP, null)?.takeIf(::canParse) ?: next
        persistRaw(next, backup)
    }

    private fun persistRaw(primary: String, backup: String) {
        val ok = p.edit()
            .putString(KEY_BACKUP, backup)
            .putString(KEY_ITEMS, primary)
            .putString(KEY_CHECKSUM, checksum(primary))
            .commit()
        check(ok) { "تعذر حفظ قائمة النسخ بشكل دائم" }
    }

    private fun canParse(raw: String): Boolean = runCatching { parse(raw) }.isSuccess

    private fun parse(raw: String): MutableList<CloneProfile> {
        val arr = JSONArray(raw)
        val out = mutableListOf<CloneProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val slot = o.getInt("slot")
            require(slot >= 0) { "slot غير صالح" }
            val pkg = o.getString("pkg").trim()
            require(pkg.isNotEmpty()) { "package فارغ" }
            out += CloneProfile(
                o.getLong("id"),
                pkg,
                o.getString("label"),
                o.getString("name"),
                slot,
                o.optBoolean("frozen"),
                o.optBoolean("hidden"),
                o.optBoolean("favorite"),
                o.optString("folder"),
                o.optLong("created", System.currentTimeMillis()),
                o.optString("iconPath", "")
            )
        }
        val duplicate = out.groupingBy { it.packageName to it.slot }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) { "نسخ مكررة لنفس package/slot" }
        return out
    }

    private fun encode(items: List<CloneProfile>): String {
        val a = JSONArray()
        items.forEach { c ->
            a.put(JSONObject().apply {
                put("id", c.id)
                put("pkg", c.packageName)
                put("label", c.sourceLabel)
                put("name", c.customName)
                put("slot", c.slot)
                put("frozen", c.frozen)
                put("hidden", c.hidden)
                put("favorite", c.favorite)
                put("folder", c.folder)
                put("created", c.createdAt)
                put("iconPath", c.customIconPath)
            })
        }
        return a.toString()
    }

    private fun checksum(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val KEY_BACKUP = "items_backup"
        private const val KEY_CHECKSUM = "items_sha256"
    }
}
