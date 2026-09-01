package com.shahboun.multi

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeDiagnostics {
    private const val MAX_BYTES = 768 * 1024
    private lateinit var appContext: Context
    private val lock = Any()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        log("APP", "diagnostics initialized sdk=${Build.VERSION.SDK_INT} android=${Build.VERSION.RELEASE} device=${Build.MANUFACTURER}/${Build.MODEL} process=${processName()} pid=${Process.myPid()} uid=${Process.myUid()} version=${versionLabel()}")
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                log("CRASH", "thread=${thread.name} process=${processName()} pid=${Process.myPid()}\n${error.stackTraceToString()}")
                log("HEALTH", "fatal=${error.javaClass.name} message=${error.message.orEmpty().take(300)}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun log(tag: String, message: String) {
        if (!::appContext.isInitialized) return
        synchronized(lock) {
            val file = logFile()
            if (file.exists() && file.length() > MAX_BYTES) rotate(file)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$stamp [$tag] $message\n")
        }
    }

    fun snapshot(): String {
        if (!::appContext.isInitialized) return "Diagnostics not initialized"
        val raw = runCatching { logFile().takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("Unable to read log")
        val crashCount = Regex("\\[CRASH]\\s").findAll(raw).count()
        val fallbackCount = Regex("=fallback").findAll(raw).count()
        val header = buildString {
            appendLine("Shahboun Multi Debug")
            appendLine("Version: ${versionLabel()}")
            appendLine("SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Android: ${Build.VERSION.RELEASE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Package: ${appContext.packageName}")
            appendLine("Process: ${processName()} | pid=${Process.myPid()} | uid=${Process.myUid()}")
            appendLine("Captured crashes: $crashCount | bridge fallbacks: $fallbackCount")
        }
        return header + RuntimeReadinessReport.render(raw) + "--- LOG ---\n" + raw
    }

    fun clear() {
        if (!::appContext.isInitialized) return
        synchronized(lock) {
            runCatching { logFile().delete() }
            runCatching { oldLogFile().delete() }
        }
        log("APP", "diagnostics cleared process=${processName()} version=${versionLabel()}")
    }

    private fun processName(): String = runCatching { Application.getProcessName() }.getOrDefault("unknown")

    @Suppress("DEPRECATION")
    private fun versionLabel(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName ?: "?"} ($code)"
    }.getOrDefault("unknown")

    private fun logFile(): File = File(appContext.filesDir, "shahboun-debug.log")
    private fun oldLogFile(): File = File(appContext.filesDir, "shahboun-debug.old.log")

    private fun rotate(file: File) {
        val old = oldLogFile()
        old.delete()
        file.renameTo(old)
    }
}
