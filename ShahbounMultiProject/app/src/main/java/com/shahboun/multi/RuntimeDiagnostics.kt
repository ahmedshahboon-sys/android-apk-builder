package com.shahboun.multi

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object RuntimeDiagnostics {
    private const val MAX_BYTES = 1024 * 1024
    private const val SESSION_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    private const val PREFS = "shahboun_diagnostics"
    private lateinit var appContext: Context
    private val lock = Any()
    @Volatile private var sessionGroup: String = "unknown"

    fun initialize(context: Context) {
        appContext = context.applicationContext
        sessionGroup = resolveSessionGroup()
        log("SESSION", "group=$sessionGroup process=${processName()} version=${versionLabel()}")
        log("APP", "diagnostics initialized sdk=${Build.VERSION.SDK_INT} android=${Build.VERSION.RELEASE} device=${Build.MANUFACTURER}/${Build.MODEL} process=${processName()} pid=${Process.myPid()} uid=${Process.myUid()} version=${versionLabel()}")
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                log("CRASH", "group=$sessionGroup thread=${thread.name} process=${processName()} pid=${Process.myPid()}\n${error.stackTraceToString()}")
                log("HEALTH", "fatal=${error.javaClass.name} message=${error.message.orEmpty().take(300)}")
                RuntimeDeepDiagnostics.captureEmergencySnapshot("uncaught:${error.javaClass.simpleName}")
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
        val raw = currentRaw()
        val crashCount = Regex("\\[CRASH]\\s").findAll(raw).count()
        val fallbackCount = Regex("=fallback").findAll(raw).count()
        val header = buildString {
            appendLine("Shahboun Multi Debug")
            appendLine("Version: ${versionLabel()}")
            appendLine("Diagnostic session: $sessionGroup")
            appendLine("SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Android: ${Build.VERSION.RELEASE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Package: ${appContext.packageName}")
            appendLine("Process: ${processName()} | pid=${Process.myPid()} | uid=${Process.myUid()}")
            appendLine("Current-session crashes: $crashCount | bridge fallbacks: $fallbackCount")
        }
        return header + RuntimeDeepDiagnostics.renderReport() + RuntimeReadinessReport.render(raw) + "--- LOG (CURRENT SESSION) ---\n" + raw
    }

    /** Small share-safe summary. Full logs remain on-device and are only shown in full mode. */
    fun compactSnapshot(): String {
        if (!::appContext.isInitialized) return "Diagnostics not initialized"
        val raw = currentRaw()
        val crashes = Regex("\\[CRASH]\\s").findAll(raw).count()
        val fallbacks = Regex("=fallback").findAll(raw).count()
        val lowMemory = Regex("reasonName=LOW_MEMORY").findAll(raw).count()
        val anr = Regex("reasonName=ANR").findAll(raw).count()
        val security = Regex("SecurityException").findAll(raw).count()
        val activityRouting = Regex("ActivityNotFoundException").findAll(raw).count()
        val processCollision = Regex("PROCESS COLLISION").findAll(raw).count()
        val resourceFailures = Regex("resource probe (failed|FAIL)|Resources\\$NotFoundException", RegexOption.IGNORE_CASE).findAll(raw).count()
        val lastHealth = raw.lineSequence().filter { "[HEALTH]" in it }.lastOrNull()?.substringAfter("[HEALTH] ")?.take(220)
        val lastCrashProcess = raw.lineSequence().filter { "[CRASH]" in it }.lastOrNull()?.let { line ->
            Regex("process=([^ ]+)").find(line)?.groupValues?.getOrNull(1)
        }
        return buildString {
            appendLine("SHAHBOUN DEBUG • ${versionLabel()}")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            appendLine("Session: $sessionGroup")
            appendLine("Runtime: crashes=$crashes fallback=$fallbacks lowmem=$lowMemory anr=$anr")
            if (security > 0) appendLine("! IDENTITY/SECURITY: $security")
            if (activityRouting > 0) appendLine("! ACTIVITY-ROUTE: $activityRouting")
            if (processCollision > 0) appendLine("! PROCESS-COLLISION: $processCollision")
            if (resourceFailures > 0) appendLine("! RESOURCES: $resourceFailures")
            if (lastCrashProcess != null) appendLine("Last crash process: $lastCrashProcess")
            if (!lastHealth.isNullOrBlank()) appendLine("Last fatal: $lastHealth")
            if (crashes == 0 && fallbacks == 0 && lowMemory == 0 && anr == 0 && security == 0 && activityRouting == 0 && processCollision == 0 && resourceFailures == 0) {
                appendLine("✓ لا توجد أخطاء Runtime مسجلة في الجلسة الحالية")
            }
        }.trimEnd()
    }

    fun clear() {
        if (!::appContext.isInitialized) return
        synchronized(lock) {
            runCatching { logFile().delete() }
            runCatching { oldLogFile().delete() }
        }
        newSessionGroup(force = true)
        log("SESSION", "group=$sessionGroup process=${processName()} version=${versionLabel()} cleared=true")
        log("APP", "diagnostics cleared process=${processName()} version=${versionLabel()}")
    }

    fun currentSessionGroup(): String = sessionGroup

    private fun currentRaw(): String {
        val rawAll = runCatching { logFile().takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("Unable to read log")
        return currentSessionWindow(rawAll)
    }

    private fun currentSessionWindow(raw: String): String {
        if (raw.isBlank() || sessionGroup == "unknown") return raw
        val marker = "[SESSION] group=$sessionGroup"
        val first = raw.indexOf(marker)
        if (first < 0) return raw
        val lineStart = raw.lastIndexOf('\n', first).let { if (it < 0) 0 else it + 1 }
        return raw.substring(lineStart)
    }

    private fun resolveSessionGroup(): String {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val existing = prefs.getString("group", null)
        val created = prefs.getLong("group_created", 0L)
        val version = prefs.getString("group_version", null)
        val currentVersion = versionLabel()
        val isHost = processName() == appContext.packageName
        val valid = !existing.isNullOrBlank() && now - created in 0..SESSION_MAX_AGE_MS && version == currentVersion
        if (valid) return existing!!
        if (!isHost && !existing.isNullOrBlank()) return existing!!
        return newSessionGroup(force = true)
    }

    private fun newSessionGroup(force: Boolean): String {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force) prefs.getString("group", null)?.let { return it }
        val value = "${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().take(8)}"
        prefs.edit()
            .putString("group", value)
            .putLong("group_created", System.currentTimeMillis())
            .putString("group_version", versionLabel())
            .apply()
        sessionGroup = value
        return value
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
