package com.shahboun.multi

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Removes Runtime 2 execution state only after the matching Runtime 3 clone exists. */
object Runtime3LegacyCleaner {
    private const val PREFS = "shahboun_runtime3_migration"
    private const val KEY_DONE = "runtime2_removed"

    fun cleanup(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        val legacyRoot = File(app.filesDir, "clone_engine")
        val v3Root = File(app.filesDir, "clone_engine_v3")
        val profiles = CloneStore(app).list()
        var migrated = 0
        val pending = mutableListOf<String>()

        profiles.forEach { profile ->
            val hash = packageHash(profile.packageName)
            val v3Slot = File(v3Root, "$hash/${profile.slot}")
            val legacySlot = File(legacyRoot, "$hash/${profile.slot}")
            val v3Ready = v3Slot.isDirectory && File(v3Slot, "runtime.meta").isFile && File(v3Slot, "clone.meta").isFile
            if (!v3Ready) {
                pending += "${profile.packageName}/${profile.slot}"
                return@forEach
            }
            if (legacySlot.exists()) require(legacySlot.deleteRecursively()) { "Unable to remove Runtime 2 slot ${profile.packageName}/${profile.slot}" }
            migrated++
        }

        // Old scheduler/process records are invalid in Runtime 3 regardless of per-clone data state.
        val oldJobsCleared = app.getSharedPreferences("shahboun_runtime_jobs", Context.MODE_PRIVATE).edit().clear().commit()
        val oldProcessMapCleared = app.getSharedPreferences("shahboun_runtime_processes_v2", Context.MODE_PRIVATE).edit().clear().commit()

        if (pending.isEmpty()) {
            // Any remaining directories are orphaned Runtime 2 snapshots not represented by CloneStore.
            if (legacyRoot.exists()) require(legacyRoot.deleteRecursively()) { "Unable to remove orphan Runtime 2 storage" }
            check(prefs.edit().putBoolean(KEY_DONE, true).commit()) { "Unable to persist Runtime 3 migration state" }
        }

        RuntimeDiagnostics.log(
            "ENGINE3",
            "legacy cleanup migrated=$migrated pending=${pending.size} jobs=$oldJobsCleared processes=$oldProcessMapCleared pendingIds=${pending.take(6).joinToString()}"
        )
    }

    private fun packageHash(packageName: String): String = MessageDigest.getInstance("SHA-256")
        .digest(packageName.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(20)
}
