package com.shahboun.multi

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.MessageDigest

/** Cross-process-safe JobScheduler metadata. Each clone owns its own job directory. */
object Runtime3JobStore {
    fun save(context: Context, record: RuntimeJobSchedulerBridge.JobRecord) {
        val dir = jobsDir(context, record.packageName, record.slot).apply { require(exists() || mkdirs()) }
        val target = File(dir, "${record.hostJobId}.job")
        val temp = File(dir, ".${record.hostJobId}.${android.os.Process.myPid()}.tmp")
        temp.writeText(serialize(record))
        if (target.exists()) require(target.delete()) { "Unable to replace Runtime 3 job ${record.hostJobId}" }
        require(temp.renameTo(target)) { "Unable to commit Runtime 3 job ${record.hostJobId}" }
    }

    fun lookup(context: Context, hostJobId: Int): RuntimeJobSchedulerBridge.JobRecord? {
        val root = File(context.applicationContext.filesDir, "clone_engine_v3")
        if (!root.isDirectory) return null
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { packageDir ->
            packageDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { slotDir ->
                val file = File(slotDir, "jobs/$hostJobId.job")
                if (file.isFile) return parse(file.readText())?.takeIf { it.hostJobId == hostJobId }
            }
        }
        return null
    }

    fun recordsFor(context: Context, packageName: String, slot: Int, namespace: String? = ANY_NAMESPACE): List<RuntimeJobSchedulerBridge.JobRecord> {
        val dir = jobsDir(context, packageName, slot)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".job") }
            .mapNotNull { runCatching { parse(it.readText()) }.getOrNull() }
            .filter { it.packageName == packageName && it.slot == slot && (namespace === ANY_NAMESPACE || it.namespace == namespace) }
    }

    fun remove(context: Context, packageName: String, slot: Int, hostJobId: Int) {
        File(jobsDir(context, packageName, slot), "$hostJobId.job").delete()
    }

    fun clear(context: Context, packageName: String, slot: Int) {
        jobsDir(context, packageName, slot).deleteRecursively()
    }

    private fun jobsDir(context: Context, packageName: String, slot: Int): File =
        File(context.applicationContext.filesDir, "clone_engine_v3/${packageHash(packageName)}/$slot/jobs")

    private fun packageHash(packageName: String): String = MessageDigest.getInstance("SHA-256")
        .digest(packageName.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(20)

    private fun serialize(record: RuntimeJobSchedulerBridge.JobRecord): String = buildString {
        appendLine("package=${encode(record.packageName)}")
        appendLine("slot=${record.slot}")
        appendLine("service=${encode(record.serviceName)}")
        appendLine("guest=${record.guestJobId}")
        appendLine("host=${record.hostJobId}")
        appendLine("namespace=${record.namespace?.let(::encode) ?: "~"}")
    }

    private fun parse(raw: String): RuntimeJobSchedulerBridge.JobRecord? = runCatching {
        val map = raw.lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()
        RuntimeJobSchedulerBridge.JobRecord(
            packageName = decode(map.getValue("package")),
            slot = map.getValue("slot").toInt(),
            serviceName = decode(map.getValue("service")),
            guestJobId = map.getValue("guest").toInt(),
            hostJobId = map.getValue("host").toInt(),
            namespace = map["namespace"]?.takeUnless { it == "~" }?.let(::decode)
        )
    }.getOrNull()

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)

    // Sentinel distinct from every real namespace, including null (the default namespace).
    private val ANY_NAMESPACE: String = String(charArrayOf('\u0000'))
}
