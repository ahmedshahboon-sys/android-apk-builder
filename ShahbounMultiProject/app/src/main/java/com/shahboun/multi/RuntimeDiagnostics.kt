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
        RuntimeIssueLedger.initialize(appContext)
        sessionGroup = resolveSessionGroup()
        log("SESSION", "group=$sessionGroup process=${processName()} version=${versionLabel()} engine=${RuntimeEngineVersion.ENGINE_VERSION}")
        log("APP", "diagnostics initialized sdk=${Build.VERSION.SDK_INT} android=${Build.VERSION.RELEASE} device=${Build.MANUFACTURER}/${Build.MODEL} process=${processName()} pid=${Process.myPid()} uid=${Process.myUid()} version=${versionLabel()} engine=${RuntimeEngineVersion.ENGINE_VERSION}")
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                RuntimeIssueLedger.recordThrowable(error)
                val identity = RuntimeExecutionScope.currentIdentity()
                log(
                    "CRASH",
                    "group=$sessionGroup thread=${thread.name} process=${processName()} pid=${Process.myPid()} guest=${identity?.guestPackage ?: "none"} clone=${identity?.cloneId ?: -1}\n${error.stackTraceToString()}"
                )
                log("HEALTH", "fatal=${error.javaClass.name} message=${error.message.orEmpty().take(300)}")
                RuntimeDeepDiagnostics.captureEmergencySnapshot("uncaught:${error.javaClass.simpleName}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun log(tag: String, message: String) {
        if (!::appContext.isInitialized) return
        runCatching { RuntimeIssueLedger.observe(tag, message) }
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
            appendLine("Engine: ${RuntimeEngineVersion.ENGINE_VERSION} / ${RuntimeEngineVersion.POLICY_VERSION}")
            appendLine("Diagnostic session: $sessionGroup")
            appendLine("SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Android: ${Build.VERSION.RELEASE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Package: ${appContext.packageName}")
            appendLine("Process: ${processName()} | pid=${Process.myPid()} | uid=${Process.myUid()}")
            appendLine("Current-session crashes: $crashCount | bridge fallbacks: $fallbackCount")
        }
        return header + RuntimeDeepDiagnostics.renderReport() + RuntimeReadinessReport.render(raw) + RuntimeGapAudit.render() + "--- ISSUE LEDGER ---\n" + RuntimeIssueLedger.renderCompact() + "\n--- LOG (CURRENT SESSION) ---\n" + raw
    }

    /** Small copy/paste summary intentionally excludes lifecycle/memory/audit-success spam. */
    fun compactSnapshot(): String {
        if (!::appContext.isInitialized) return "Diagnostics not initialized"
        val raw = currentRaw()
        val identity = RuntimeExecutionScope.currentIdentity()
        val readiness = RuntimeReadinessReport.render(raw)
        val status = readiness.lineSequence().firstOrNull { it.startsWith("Status:") }?.substringAfter(':')?.trim().orEmpty().ifBlank { "NOT TESTED" }
        val blocker = readiness.lineSequence().firstOrNull { it.startsWith("Current blocker:") }?.substringAfter(':')?.trim().orEmpty().ifBlank { "none" }
        val crashes = extractCrashSummaries(raw)
        val failBridges = raw.lineSequence().filter { it.contains("[BRIDGE]") && (it.contains("=fail") || it.contains("=failed")) }.toList().takeLast(8)
        val fallbackBridges = raw.lineSequence().filter { it.contains("[BRIDGE]") && it.contains("=fallback") }.toList().takeLast(8)
        val partial = RuntimeGapAudit.current().filter { it.state == RuntimeGapAudit.State.PARTIAL }.take(8)
        val exit = raw.lineSequence().lastOrNull { it.contains("process exit", ignoreCase = true) || it.contains("Last process exit", ignoreCase = true) }
        return buildString {
            appendLine("Shahboun Multi Error Summary")
            appendLine("Version: ${versionLabel()}")
            appendLine("Engine: ${RuntimeEngineVersion.ENGINE_VERSION}")
            appendLine("Session: $sessionGroup")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android/SDK: ${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT}")
            appendLine("Guest package: ${identity?.guestPackage ?: "none"}")
            appendLine("Clone: ${identity?.cloneId ?: -1}")
            appendLine("Virtual process: ${identity?.virtualProcessName ?: "none"}")
            appendLine("Physical process: ${processName()}")
            appendLine("Readiness: $status")
            appendLine("Current blocker: $blocker")
            appendLine("Crash count: ${crashes.size}")
            crashes.forEachIndexed { index, crash -> appendLine("Crash ${index + 1}: $crash") }
            if (failBridges.isNotEmpty()) appendLine("FAIL bridges: ${failBridges.joinToString(" | ") { compactLine(it) }}")
            if (fallbackBridges.isNotEmpty()) appendLine("FALLBACK bridges: ${fallbackBridges.joinToString(" | ") { compactLine(it) }}")
            if (partial.isNotEmpty()) appendLine("PARTIAL bridges: ${partial.joinToString { it.area }}")
            if (!exit.isNullOrBlank()) appendLine("Important process exit: ${compactLine(exit)}")
        }.trim()
    }

    fun hasPersistentIssues(): Boolean = ::appContext.isInitialized && RuntimeIssueLedger.hasIssues()

    fun clear() {
        if (!::appContext.isInitialized) return
        synchronized(lock) {
            runCatching { logFile().delete() }
            runCatching { oldLogFile().delete() }
            runCatching { RuntimeIssueLedger.clear() }
        }
        newSessionGroup(force = true)
        log("SESSION", "group=$sessionGroup process=${processName()} version=${versionLabel()} cleared=true")
        log("APP", "diagnostics cleared process=${processName()} version=${versionLabel()}")
    }

    fun currentSessionGroup(): String = sessionGroup

    private fun extractCrashSummaries(raw: String): List<String> {
        val starts = Regex("\\[CRASH]\\s").findAll(raw).map { it.range.first }.toList()
        return starts.takeLast(5).map { start ->
            val block = raw.substring(start).lineSequence().take(16).toList()
            val exception = block.firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim().orEmpty()
            val root = block.lastOrNull { it.trimStart().startsWith("Caused by:") }?.trim().orEmpty()
            val frame = block.firstOrNull { it.trimStart().startsWith("at ") && it.contains("com.") }?.trim().orEmpty()
            listOf(exception, root, frame).filter { it.isNotBlank() }.joinToString(" ; ").take(700).ifBlank { "Runtime crash" }
        }
    }

    private fun compactLine(line: String): String = line.substringAfter(']').trim().replace(Regex("\\s+"), " ").take(260)

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
