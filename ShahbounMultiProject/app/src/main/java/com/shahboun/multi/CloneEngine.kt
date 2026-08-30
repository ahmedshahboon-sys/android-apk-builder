package com.shahboun.multi

import android.content.Context
import android.content.Intent
import java.io.File
import java.security.MessageDigest

interface CloneEngine {
    val name: String
    fun isAvailable(): Boolean
    fun initialize(context: Context): Result<Unit>
    fun createClone(packageName: String, slot: Int): Result<Unit>
    fun launch(packageName: String, slot: Int): Result<Unit>
    fun remove(packageName: String, slot: Int): Result<Unit>
    fun clearData(packageName: String, slot: Int): Result<Unit>
}

/**
 * Shahboun-owned clone engine foundation.
 *
 * This implementation intentionally contains no third-party virtualization code.
 * It owns clone metadata, private per-slot storage, validation and lifecycle rules.
 * Executing another APK inside an isolated virtual Android process will be added in
 * later engine layers written in this repository.
 */
class ShahbounCloneEngine : CloneEngine {
    override val name: String = "Shahboun Clone Engine"

    private lateinit var appContext: Context
    private lateinit var rootDir: File

    override fun isAvailable(): Boolean = true

    override fun initialize(context: Context): Result<Unit> = runCatching {
        appContext = context.applicationContext
        rootDir = File(appContext.filesDir, "clone_engine")
        require(rootDir.exists() || rootDir.mkdirs()) { "Unable to initialize clone storage" }
    }

    override fun createClone(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        require(packageName.isNotBlank()) { "Package name is empty" }
        require(slot >= 0) { "Invalid clone slot" }

        val pm = appContext.packageManager
        pm.getApplicationInfo(packageName, 0)

        val dir = slotDir(packageName, slot)
        require(!dir.exists()) { "Clone already exists" }
        require(dir.mkdirs()) { "Unable to create isolated clone storage" }

        File(dir, "data").mkdirs()
        File(dir, "cache").mkdirs()
        File(dir, "files").mkdirs()
        File(dir, "clone.meta").writeText(
            buildString {
                appendLine("format=1")
                appendLine("package=$packageName")
                appendLine("slot=$slot")
                appendLine("created=${System.currentTimeMillis()}")
            }
        )
    }

    override fun launch(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        require(slotDir(packageName, slot).isDirectory) { "Clone does not exist" }

        // Until the isolated runtime layer is completed, never pretend this is a
        // real clone launch. This explicit failure protects the original app data.
        throw UnsupportedOperationException(
            "محرك Shahboun جهز مساحة النسخة وعزل بياناتها، لكن طبقة التشغيل الافتراضي ما زالت قيد البناء"
        )
    }

    override fun remove(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        val dir = slotDir(packageName, slot)
        if (dir.exists()) require(dir.deleteRecursively()) { "Unable to remove clone storage" }
    }

    override fun clearData(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        val dir = slotDir(packageName, slot)
        require(dir.isDirectory) { "Clone does not exist" }

        listOf("data", "cache", "files").forEach { name ->
            val child = File(dir, name)
            if (child.exists()) child.deleteRecursively()
            require(child.mkdirs()) { "Unable to reset $name" }
        }
    }

    private fun requireInitialized() {
        check(::appContext.isInitialized && ::rootDir.isInitialized) { "Engine not initialized" }
    }

    private fun slotDir(packageName: String, slot: Int): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(packageName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(20)
        return File(rootDir, "$digest/$slot")
    }
}

/** Normal Android launch helper, kept only for diagnostics and never used as cloning. */
class SafeFallbackEngine(private val context: Context) : CloneEngine {
    override val name = "Android launcher fallback"
    override fun isAvailable(): Boolean = true
    override fun initialize(context: Context): Result<Unit> = Result.success(Unit)
    override fun createClone(packageName: String, slot: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("Clone engine unavailable"))

    override fun launch(packageName: String, slot: Int): Result<Unit> = runCatching {
        val intent: Intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("No launch activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun remove(packageName: String, slot: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("Clone engine unavailable"))

    override fun clearData(packageName: String, slot: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("Clone engine unavailable"))
}
