package com.shahboun.multi

import android.content.Context

/**
 * Assigns clone identities to declared :cloneN host processes.
 *
 * A clone identity keeps the same process across Activity/Service/Receiver/Job launches. The first
 * POOL_SIZE installed identities get unique processes, preventing static/native/WebView cross-talk.
 * If the pool is exhausted we retain the deterministic legacy index, but RuntimeExecutionScope will
 * reject concurrent identity mixing rather than silently contaminate another clone.
 */
object RuntimeProcessAllocator {
    private const val PREFS = "shahboun_runtime_processes_v2"
    private const val KEY_PREFIX = "identity."

    fun allocate(context: Context, packageName: String, slot: Int, poolSize: Int): Int = synchronized(this) {
        require(poolSize > 0)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(packageName, slot)
        val existing = prefs.getInt(key, -1)
        if (existing in 0 until poolSize) return existing

        val used = HashSet<Int>()
        prefs.all.forEach { (name, value) ->
            if (name.startsWith(KEY_PREFIX) && value is Int && value in 0 until poolSize) used += value
        }
        val preferred = legacyIndex(packageName, slot, poolSize)
        val selected = if (preferred !in used) preferred else (0 until poolSize).firstOrNull { it !in used } ?: preferred
        prefs.edit().putInt(key, selected).commit()
        RuntimeDiagnostics.log(
            "PROCESS",
            "allocated $packageName/$slot -> :clone$selected preferred=$preferred unique=${selected !in used} used=${used.size}/$poolSize"
        )
        selected
    }

    fun lookup(context: Context?, packageName: String, slot: Int, poolSize: Int): Int? {
        context ?: return null
        val value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key(packageName, slot), -1)
        return value.takeIf { it in 0 until poolSize }
    }

    fun release(context: Context, packageName: String, slot: Int) = synchronized(this) {
        val removed = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(packageName, slot)).commit()
        RuntimeDiagnostics.log("PROCESS", "released $packageName/$slot removed=$removed")
    }

    fun migrateIfNeeded(context: Context, packageName: String, slot: Int, poolSize: Int): Int =
        lookup(context, packageName, slot, poolSize) ?: allocate(context, packageName, slot, poolSize)

    fun snapshot(context: Context): Map<String, Int> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (name, value) ->
            if (!name.startsWith(KEY_PREFIX) || value !is Int) null else name.removePrefix(KEY_PREFIX) to value
        }.toMap()

    fun legacyIndex(packageName: String, slot: Int, poolSize: Int): Int =
        Math.floorMod(31 * packageName.hashCode() + slot, poolSize)

    private fun key(packageName: String, slot: Int): String = "$KEY_PREFIX$packageName#$slot"
}
