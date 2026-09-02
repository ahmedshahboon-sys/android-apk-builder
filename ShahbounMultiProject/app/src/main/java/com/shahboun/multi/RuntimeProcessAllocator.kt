package com.shahboun.multi

import android.content.Context

/** Runtime 3 process allocator: one clone identity == one host process slot, never shared. */
object RuntimeProcessAllocator {
    private const val PREFS = "shahboun_runtime_processes_v3"
    private const val KEY_PREFIX = "identity."

    fun allocate(context: Context, packageName: String, slot: Int, poolSize: Int): Int = synchronized(this) {
        require(poolSize > 0)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(packageName, slot)
        val existing = prefs.getInt(key, -1)
        if (existing in 0 until poolSize) return existing

        val used = prefs.all.mapNotNull { (name, value) ->
            if (name.startsWith(KEY_PREFIX) && value is Int && value in 0 until poolSize) value else null
        }.toSet()

        val preferred = stableIndex(packageName, slot, poolSize)
        val selected = if (preferred !in used) preferred else (0 until poolSize).firstOrNull { it !in used }
            ?: throw IllegalStateException("Runtime 3 process capacity exhausted: used=${used.size}/$poolSize. No clone process sharing is allowed.")

        check(prefs.edit().putInt(key, selected).commit()) { "Unable to persist Runtime 3 process allocation" }
        RuntimeDiagnostics.log("PROCESS3", "allocated $packageName/$slot -> :clone$selected used=${used.size + 1}/$poolSize")
        selected
    }

    fun lookup(context: Context?, packageName: String, slot: Int, poolSize: Int): Int? {
        context ?: return null
        val value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key(packageName, slot), -1)
        return value.takeIf { it in 0 until poolSize }
    }

    fun release(context: Context, packageName: String, slot: Int) = synchronized(this) {
        val removed = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(packageName, slot)).commit()
        RuntimeDiagnostics.log("PROCESS3", "released $packageName/$slot removed=$removed")
    }

    fun migrateIfNeeded(context: Context, packageName: String, slot: Int, poolSize: Int): Int =
        lookup(context, packageName, slot, poolSize) ?: allocate(context, packageName, slot, poolSize)

    fun snapshot(context: Context): Map<String, Int> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (name, value) ->
            if (!name.startsWith(KEY_PREFIX) || value !is Int) null else name.removePrefix(KEY_PREFIX) to value
        }.toMap()

    fun stableIndex(packageName: String, slot: Int, poolSize: Int): Int =
        Math.floorMod(31 * packageName.hashCode() + slot, poolSize)

    fun legacyIndex(packageName: String, slot: Int, poolSize: Int): Int = stableIndex(packageName, slot, poolSize)

    private fun key(packageName: String, slot: Int): String = "$KEY_PREFIX$packageName#$slot"
}
