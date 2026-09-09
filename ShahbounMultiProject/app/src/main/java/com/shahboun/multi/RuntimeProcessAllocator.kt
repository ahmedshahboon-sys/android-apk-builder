package com.shahboun.multi

import android.content.Context

/** Runtime 3 process allocator: one clone identity == one host process slot, never shared. */
object RuntimeProcessAllocator {
    private const val PREFS = "shahboun_runtime_processes_v3"
    private const val KEY_PREFIX = "identity."

    fun allocate(context: Context, packageName: String, slot: Int, poolSize: Int): Int = synchronized(this) {
        require(poolSize > 0)
        Runtime3ProcessMetadata.read(context, packageName, slot, poolSize)?.let { persisted ->
            persistPreference(context, packageName, slot, persisted)
            return persisted
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(packageName, slot)
        val existing = prefs.getInt(key, -1)
        if (existing in 0 until poolSize) {
            Runtime3ProcessMetadata.write(context, packageName, slot, existing)
            return existing
        }

        val used = prefs.all.mapNotNull { (name, value) ->
            if (name.startsWith(KEY_PREFIX) && value is Int && value in 0 until poolSize) value else null
        }.toSet()

        val preferred = stableIndex(packageName, slot, poolSize)
        val selected = if (preferred !in used) preferred else (0 until poolSize).firstOrNull { it !in used }
            ?: throw IllegalStateException("Runtime 3 process capacity exhausted: used=${used.size}/$poolSize. No clone process sharing is allowed.")

        check(prefs.edit().putInt(key, selected).commit()) { "Unable to persist Runtime 3 process allocation" }
        Runtime3ProcessMetadata.write(context, packageName, slot, selected)
        RuntimeDiagnostics.log("PROCESS3", "allocated $packageName/$slot -> :clone$selected used=${used.size + 1}/$poolSize")
        selected
    }

    /**
     * Clone processes are entered through a concrete RuntimeStubActivityN. That physical N is the
     * authoritative owner for the lifetime of the process. If stale/missing cross-process metadata
     * disagrees, repair metadata to the process we are already executing in instead of allocating a
     * second slot and crashing with actual=:cloneN expected=:cloneM.
     */
    fun reconcileRunningProcess(context: Context, packageName: String, slot: Int, actualIndex: Int, poolSize: Int): Int = synchronized(this) {
        require(actualIndex in 0 until poolSize) { "Invalid Runtime 3 process index $actualIndex" }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val collisions = prefs.all.mapNotNull { (name, value) ->
            if (name != key(packageName, slot) && name.startsWith(KEY_PREFIX) && value == actualIndex) name else null
        }
        check(collisions.isEmpty()) { "Runtime 3 process collision: :clone$actualIndex already owned by ${collisions.joinToString()}" }
        val before = lookup(context, packageName, slot, poolSize)
        if (before != actualIndex) {
            check(prefs.edit().putInt(key(packageName, slot), actualIndex).commit()) { "Unable to repair Runtime 3 process allocation" }
            Runtime3ProcessMetadata.write(context, packageName, slot, actualIndex)
            RuntimeDiagnostics.log("PROCESS3", "reconciled $packageName/$slot ${before?.let { ":clone$it" } ?: "unassigned"} -> :clone$actualIndex")
        }
        return actualIndex
    }

    fun lookup(context: Context?, packageName: String, slot: Int, poolSize: Int): Int? {
        context ?: return null
        Runtime3ProcessMetadata.read(context, packageName, slot, poolSize)?.let { return it }
        val value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key(packageName, slot), -1)
        if (value in 0 until poolSize) {
            runCatching { Runtime3ProcessMetadata.write(context, packageName, slot, value) }
            return value
        }
        return null
    }

    fun release(context: Context, packageName: String, slot: Int) = synchronized(this) {
        runCatching { RuntimeNotificationBridge.clearClone(context, packageName, slot, removeChannels = true) }
            .onFailure { RuntimeDiagnostics.log("NOTIFY3", "release cleanup failed $packageName/$slot: ${it.javaClass.simpleName}: ${it.message}") }
        Runtime3ProcessMetadata.delete(context, packageName, slot)
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

    private fun persistPreference(context: Context, packageName: String, slot: Int, value: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(key(packageName, slot), -1) != value) {
            check(prefs.edit().putInt(key(packageName, slot), value).commit()) { "Unable to mirror Runtime 3 process allocation" }
        }
    }

    private fun key(packageName: String, slot: Int): String = "$KEY_PREFIX$packageName#$slot"
}
