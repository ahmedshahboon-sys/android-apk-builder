package com.shahboun.optimizer

import android.app.ActivityManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

class OptimizerJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            val prefs = getSharedPreferences("optimizer_settings", Context.MODE_PRIVATE)
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val ramUsed = ((1.0 - mi.availMem.toDouble() / mi.totalMem.toDouble()) * 100).toInt().coerceIn(0, 100)

            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempC = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val ramThreshold = prefs.getInt("ram_threshold", 90)
            val maxTemp = prefs.getInt("max_temp", 42)

            var deleted = 0L
            val shouldOptimize = ramUsed >= ramThreshold && (tempC <= 0 || tempC < maxTemp)
            if (shouldOptimize) {
                deleted += cleanOldCache(cacheDir)
                System.gc()
            }

            val intervalHours = prefs.getInt("interval_hours", 4)
            val now = System.currentTimeMillis()
            val action = when {
                ramUsed < ramThreshold -> "تم الفحص: RAM أقل من حد التدخل"
                tempC >= maxTemp -> "تم الفحص: أُجّل التحسين بسبب الحرارة"
                deleted > 0L -> "تم التحسين الآمن وحذف ${deleted / 1024} KB"
                else -> "تم الفحص والتحسين: لا ملفات آمنة تحتاج حذف"
            }

            prefs.edit()
                .putLong("last_scan", now)
                .putLong("next_scan_estimate", now + intervalHours * 60L * 60L * 1000L)
                .putInt("last_ram", ramUsed)
                .putFloat("last_temp", tempC.toFloat())
                .putString("last_action", action)
                .apply()

            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    private fun cleanOldCache(dir: File): Long {
        var deleted = 0L
        val cutoff = System.currentTimeMillis() - 6L * 60L * 60L * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleted += cleanOldCache(file)
                if (file.listFiles().isNullOrEmpty()) file.delete()
            } else if (file.lastModified() < cutoff) {
                val size = file.length()
                if (file.delete()) deleted += size
            }
        }
        return deleted
    }
}
