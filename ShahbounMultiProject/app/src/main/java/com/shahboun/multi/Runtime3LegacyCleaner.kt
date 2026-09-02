package com.shahboun.multi

import android.content.Context
import java.io.File

/** Removes Runtime 2 execution state after Runtime 3 has initialized successfully. */
object Runtime3LegacyCleaner {
    private const val PREFS = "shahboun_runtime3_migration"
    private const val KEY_DONE = "runtime2_removed"

    fun cleanup(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        val legacyRoot = File(app.filesDir, "clone_engine")
        val oldJobsCleared = app.getSharedPreferences("shahboun_runtime_jobs", Context.MODE_PRIVATE).edit().clear().commit()
        val oldProcessMapCleared = app.getSharedPreferences("shahboun_runtime_processes_v2", Context.MODE_PRIVATE).edit().clear().commit()
        val deleted = !legacyRoot.exists() || legacyRoot.deleteRecursively()
        check(deleted) { "Unable to remove Runtime 2 storage" }
        check(prefs.edit().putBoolean(KEY_DONE, true).commit()) { "Unable to persist Runtime 3 migration state" }
        RuntimeDiagnostics.log(
            "ENGINE3",
            "legacy Runtime 2 removed path=${legacyRoot.absolutePath} jobs=$oldJobsCleared processes=$oldProcessMapCleared"
        )
    }
}
