package com.shahboun.multi

import android.app.Application
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent cross-process issue ledger. Raw diagnostics stay separate and verbose. */
object RuntimeIssueLedger {
    private const val FILE_NAME = "shahboun-issues.jsonl"
    private const val MAX_BYTES = 768 * 1024L
    private lateinit var appContext: Context

    data class Issue(
        val time: Long,
        val packageName: String,
        val slot: Int,
        val code: String,
        val type: String,
        val summary: String,
        val process: String,
        val fingerprint: String
    )

    fun initialize(context: Context) { appContext = context.applicationContext }
    fun clear() { if (::appContext.isInitialized) runCatching { ledgerFile().delete() } }
    fun hasIssues(): Boolean = ::appContext.isInitialized && ledgerFile().let { it.isFile && it.length() > 0L }

    fun recordThrowable(error: Throwable, packageName: String? = null, slot: Int? = null) {
        if (!::appContext.isInitialized) return
        val owner = RuntimeExecutionScope.processOwner()
        val pkg = packageName ?: owner?.first ?: "host"
        val cloneSlot = slot ?: owner?.second ?: -1
        val root = rootCause(error)
        val code = classifyThrowable(root)
        val top = root.stackTrace.firstOrNull { !it.className.startsWith("java.") && !it.className.startsWith("kotlin.") }
        val message = root.message.orEmpty().replace(Regex("\\s+"), " ").trim()
        val summary = buildString {
            append(root.javaClass.simpleName)
            if (message.isNotBlank()) append(": ").append(message.take(220))
            if (top != null) append(" @ ").append(top.className.substringAfterLast('.')).append('.').append(top.methodName)
        }.take(320)
        append(Issue(System.currentTimeMillis(), pkg, cloneSlot, code, root.javaClass.simpleName, summary, processName(), fingerprint(pkg, cloneSlot, code, root.javaClass.name, message, top?.className)))
    }

    /** Only record live actionable failures. Historical EXIT snapshots are intentionally ignored. */
    fun observe(tag: String, message: String) {
        if (!::appContext.isInitialized) return
        val normalized = message.replace(Regex("\\s+"), " ").trim()
        val code = when {
            tag == "LAUNCH2" && normalized.contains("transaction patch fallback", true) -> "LAUNCH-TRANSACTION"
            tag == "PROVIDER3" && (normalized.contains("failed", true) || normalized.contains("rejected", true)) -> "PROVIDER"
            tag == "LAUNCH" && normalized.contains("failed", true) -> "LAUNCH"
            tag == "STARTUP" && normalized.contains("failed", true) -> "STARTUP"
            normalized.contains("ActivityNotFoundException") -> "ACTIVITY-ROUTE"
            normalized.contains("SecurityException") -> "IDENTITY/SECURITY"
            normalized.contains("PROCESS COLLISION") -> "PROCESS-COLLISION"
            normalized.contains("resource probe failed", true) || normalized.contains("NotFoundException") -> "RESOURCES"
            tag == "SERVICE" && normalized.contains("failed", true) -> "SERVICE"
            tag == "RECEIVER" && normalized.contains("failed", true) -> "RECEIVER"
            else -> return
        }
        val owner = RuntimeExecutionScope.processOwner()
        val pkg = owner?.first ?: extractPackage(normalized) ?: "host"
        val slot = owner?.second ?: extractSlot(normalized) ?: -1
        val summary = normalized.take(300)
        append(Issue(System.currentTimeMillis(), pkg, slot, code, tag, summary, processName(), fingerprint(pkg, slot, code, tag, summary, null)))
    }

    fun recordProcessExit(packageName: String, slot: Int, reason: String, detail: String) {
        if (!::appContext.isInitialized) return
        val code = when (reason) {
            "LOW_MEMORY" -> "LOW-MEMORY"
            "ANR" -> "ANR"
            else -> "PROCESS-EXIT"
        }
        val summary = "$reason: ${detail.replace(Regex("\\s+"), " ").take(220)}"
        append(Issue(System.currentTimeMillis(), packageName, slot, code, reason, summary, processName(), fingerprint(packageName, slot, code, reason, summary, null)))
    }

    fun renderCompact(): String {
        if (!::appContext.isInitialized) return "SHAHBOUN DEBUG\nNo issue ledger"
        val issues = readAll()
        if (issues.isEmpty()) return "SHAHBOUN DEBUG • ${versionLabel()}\n✓ لا توجد مشاكل Runtime مسجلة"
        val grouped = issues.groupBy { it.fingerprint }.values.map { it.sortedBy(Issue::time) }.sortedByDescending { it.last().time }
        return buildString {
            appendLine("SHAHBOUN DEBUG • ${versionLabel()}")
            appendLine("ISSUES: ${grouped.size} • EVENTS: ${issues.size}")
            appendLine()
            grouped.forEachIndexed { index, group ->
                val first = group.first(); val last = group.last()
                val clone = if (last.slot >= 0) "#${last.slot + 1}" else "host"
                appendLine("${index + 1}) ${last.packageName} $clone")
                appendLine("${last.code}${if (group.size > 1) " ×${group.size}" else ""}")
                appendLine(last.summary)
                if (group.size > 1) appendLine("First ${time(first.time)} • Last ${time(last.time)}")
                if (index != grouped.lastIndex) appendLine()
            }
        }.trimEnd()
    }

    private fun append(issue: Issue) {
        val file = ledgerFile(); file.parentFile?.mkdirs()
        if (file.exists() && file.length() > MAX_BYTES) compactFile(file)
        val line = JSONObject().put("t", issue.time).put("p", issue.packageName).put("s", issue.slot)
            .put("c", issue.code).put("y", issue.type).put("m", issue.summary).put("r", issue.process).put("f", issue.fingerprint).toString() + "\n"
        runCatching {
            FileOutputStream(file, true).channel.use { channel ->
                val lock = channel.lock()
                try {
                    channel.position(channel.size())
                    channel.write(java.nio.ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8)))
                    channel.force(false)
                } finally { lock.release() }
            }
        }
    }

    private fun readAll(): List<Issue> = runCatching {
        ledgerFile().takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            runCatching {
                val j = JSONObject(line)
                Issue(j.getLong("t"), j.optString("p", "host"), j.optInt("s", -1), j.optString("c", "RUNTIME"), j.optString("y", "Error"), j.optString("m", ""), j.optString("r", ""), j.optString("f", ""))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun compactFile(file: File) {
        val grouped = readAll().groupBy { it.fingerprint }.values.map { it.last() }.takeLast(120)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        runCatching {
            tmp.bufferedWriter().use { writer ->
                grouped.forEach { issue ->
                    writer.append(JSONObject().put("t", issue.time).put("p", issue.packageName).put("s", issue.slot).put("c", issue.code).put("y", issue.type).put("m", issue.summary).put("r", issue.process).put("f", issue.fingerprint).toString()).append('\n')
                }
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error; val seen = HashSet<Throwable>()
        while (current.cause != null && current.cause !== current && seen.add(current)) current = current.cause!!
        return current
    }

    private fun classifyThrowable(error: Throwable): String = when {
        error is SecurityException -> "IDENTITY/SECURITY"
        error.javaClass.name.contains("ActivityNotFoundException") -> "ACTIVITY-ROUTE"
        error.javaClass.name.contains("ContentProvider") || error.message.orEmpty().contains("provider", true) || error.message.orEmpty().contains("authority", true) -> "PROVIDER"
        error.javaClass.name.contains("Resources") || error.message.orEmpty().contains("resource", true) -> "RESOURCES"
        error.javaClass.name.contains("OutOfMemoryError") -> "LOW-MEMORY"
        error.message.orEmpty().contains("manifest", true) || error.message.orEmpty().contains("meta-data", true) -> "MANIFEST"
        else -> "CRASH"
    }

    private fun extractPackage(text: String): String? = Regex("(?:package|clone)=([A-Za-z0-9_.]+)").find(text)?.groupValues?.getOrNull(1)
    private fun extractSlot(text: String): Int? = Regex("(?:slot=|/)([0-9]{1,2})(?:\\b|$)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun fingerprint(pkg: String, slot: Int, code: String, type: String, message: String, frame: String?): String {
        val stable = message.replace(Regex("0x[0-9a-fA-F]+|pid=\\d+|@[0-9a-fA-F]+|\\b\\d{5,}\\b"), "#").take(180)
        val raw = "$pkg|$slot|$code|$type|$stable|${frame.orEmpty()}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
    }

    private fun ledgerFile(): File = File(appContext.filesDir, FILE_NAME)
    private fun processName(): String = runCatching { Application.getProcessName() }.getOrDefault("unknown")
    private fun time(value: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(value))
    @Suppress("DEPRECATION") private fun versionLabel(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName ?: "?"} ($code)"
    }.getOrDefault("unknown")
}
