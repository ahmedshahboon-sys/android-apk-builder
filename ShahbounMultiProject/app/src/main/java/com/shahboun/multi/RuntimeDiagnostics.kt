package com.shahboun.multi

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeDiagnostics {
    private const val MAX_BYTES = 512 * 1024
    private lateinit var appContext: Context
    private val lock = Any()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        log("APP", "diagnostics initialized sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}")
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { log("CRASH", "thread=${thread.name}\n${error.stackTraceToString()}") }
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
        val header = buildString {
            appendLine("Shahboun Multi Debug")
            appendLine("SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Android: ${Build.VERSION.RELEASE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Package: ${appContext.packageName}")
            appendLine("--- LOG ---")
        }
        return header + runCatching { logFile().takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("Unable to read log")
    }

    fun clear() {
        if (!::appContext.isInitialized) return
        synchronized(lock) { runCatching { logFile().delete() } }
        log("APP", "diagnostics cleared")
    }

    private fun logFile(): File = File(appContext.filesDir, "shahboun-debug.log")

    private fun rotate(file: File) {
        val old = File(file.parentFile, "shahboun-debug.old.log")
        old.delete()
        file.renameTo(old)
    }
}
