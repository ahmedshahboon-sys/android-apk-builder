package com.shahboun.multi

import android.content.Context
import java.io.File
import java.security.MessageDigest

interface CloneEngine {
    val name: String
    fun isAvailable(): Boolean
    fun initialize(context: Context): Result<Unit>
    fun createClone(packageName: String, slot: Int): Result<Unit>
    fun updateClone(packageName: String, slot: Int): Result<Unit>
    fun launch(packageName: String, slot: Int): Result<Unit>
    fun remove(packageName: String, slot: Int): Result<Unit>
    fun clearData(packageName: String, slot: Int): Result<Unit>
}

/** Shahboun-owned runtime and clone lifecycle. */
class ShahbounCloneEngine : CloneEngine {
    override val name: String = "Shahboun Clone Engine"

    private lateinit var appContext: Context
    private lateinit var rootDir: File
    private lateinit var installer: RuntimePackageInstaller
    private lateinit var sessionFactory: RuntimeSessionFactory
    private val sessionLock = Any()

    override fun isAvailable(): Boolean = true

    override fun initialize(context: Context): Result<Unit> = runCatching {
        appContext = context.applicationContext
        rootDir = File(appContext.filesDir, "clone_engine")
        require(rootDir.exists() || rootDir.mkdirs()) { "Unable to initialize clone storage" }
        installer = RuntimePackageInstaller(appContext)
        sessionFactory = RuntimeSessionFactory(appContext)
    }

    override fun createClone(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        require(packageName.isNotBlank()) { "Package name is empty" }
        require(slot >= 0) { "Invalid clone slot" }
        appContext.packageManager.getApplicationInfo(packageName, 0)

        val dir = runtimeSlotDir(packageName, slot)
        require(!dir.exists()) { "Clone already exists" }
        require(dir.mkdirs()) { "Unable to create isolated clone storage" }
        try {
            listOf("data", "cache", "files", "databases", "no_backup", "code_cache", "native").forEach {
                require(File(dir, it).mkdirs()) { "Unable to create clone directory: $it" }
            }
            File(dir, "clone.meta").writeText(
                buildString {
                    appendLine("format=2")
                    appendLine("package=$packageName")
                    appendLine("slot=$slot")
                    appendLine("created=${System.currentTimeMillis()}")
                }
            )
            installer.snapshot(packageName, slot, dir)
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    override fun updateClone(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        require(packageName.isNotBlank()) { "Package name is empty" }
        require(slot >= 0) { "Invalid clone slot" }
        appContext.packageManager.getApplicationInfo(packageName, 0)

        val dir = runtimeSlotDir(packageName, slot)
        require(dir.isDirectory) { "Clone does not exist" }
        RuntimeRegistry.remove(packageName, slot)

        val apkDir = File(dir, "apk")
        val runtimeMeta = File(dir, "runtime.meta")
        require(apkDir.isDirectory && runtimeMeta.isFile) { "Clone runtime snapshot is incomplete" }

        val backupApk = File(dir, "apk.update-backup")
        val backupMeta = File(dir, "runtime.meta.update-backup")
        if (backupApk.exists()) backupApk.deleteRecursively()
        if (backupMeta.exists()) backupMeta.delete()

        require(apkDir.renameTo(backupApk)) { "Unable to prepare APK update backup" }
        require(runtimeMeta.renameTo(backupMeta)) {
            backupApk.renameTo(apkDir)
            "Unable to prepare metadata update backup"
        }

        try {
            val updated = installer.snapshot(packageName, slot, dir)
            installer.read(packageName, slot, dir)
            backupApk.deleteRecursively()
            backupMeta.delete()
            listOf("code_cache", "native").forEach { name ->
                val child = File(dir, name)
                if (child.exists()) child.deleteRecursively()
                require(child.mkdirs()) { "Unable to reset $name after update" }
            }
            RuntimeDiagnostics.log("UPDATE", "clone updated $packageName/$slot version=${updated.versionCode}")
        } catch (t: Throwable) {
            File(dir, "apk").deleteRecursively()
            File(dir, "runtime.meta").delete()
            require(backupApk.renameTo(apkDir)) { "Update failed and APK backup could not be restored" }
            require(backupMeta.renameTo(runtimeMeta)) { "Update failed and metadata backup could not be restored" }
            RuntimeDiagnostics.log("UPDATE", "clone update rolled back $packageName/$slot: ${t.stackTraceToString()}")
            throw t
        }
    }

    fun sessionFor(packageName: String, slot: Int): RuntimeSession {
        requireInitialized()
        RuntimeRegistry.getOrNull(packageName, slot)?.let { return it }
        synchronized(sessionLock) {
            RuntimeRegistry.getOrNull(packageName, slot)?.let { return it }
            val dir = runtimeSlotDir(packageName, slot)
            require(dir.isDirectory) { "Clone does not exist" }
            val pkg = installer.read(packageName, slot, dir)
            val session = sessionFactory.create(pkg, dir)
            RuntimeRegistry.put(session)
            try {
                RuntimeDiagnostics.log("RUNTIME", "restoring guest session $packageName/$slot")
                session.ensureGuestApplication(appContext, dir)
                RuntimeDiagnostics.log("RUNTIME", "restored guest session $packageName/$slot")
                return session
            } catch (t: Throwable) {
                RuntimeRegistry.remove(packageName, slot)
                throw t
            }
        }
    }

    override fun launch(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        (appContext as? MultiApplication)?.requireRuntimeBridge()
            ?: error("Shahboun application context غير صالح")
        val session = sessionFor(packageName, slot)
        val intent = RuntimeIntentRouter.launchIntent(appContext, session)
        appContext.startActivity(intent)
    }

    override fun remove(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        RuntimeRegistry.remove(packageName, slot)
        val dir = runtimeSlotDir(packageName, slot)
        if (dir.exists()) require(dir.deleteRecursively()) { "Unable to remove clone storage" }
    }

    override fun clearData(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        RuntimeRegistry.remove(packageName, slot)
        val dir = runtimeSlotDir(packageName, slot)
        require(dir.isDirectory) { "Clone does not exist" }
        listOf("data", "cache", "files", "databases", "no_backup", "code_cache").forEach { name ->
            val child = File(dir, name)
            if (child.exists()) child.deleteRecursively()
            require(child.mkdirs()) { "Unable to reset $name" }
        }
    }

    fun runtimeSlotDir(packageName: String, slot: Int): File {
        requireInitialized()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(packageName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(20)
        return File(rootDir, "$digest/$slot")
    }

    private fun requireInitialized() {
        check(::appContext.isInitialized && ::rootDir.isInitialized && ::installer.isInitialized && ::sessionFactory.isInitialized) {
            "Engine not initialized"
        }
    }
}
