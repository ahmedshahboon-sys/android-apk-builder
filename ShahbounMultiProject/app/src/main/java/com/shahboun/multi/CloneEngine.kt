package com.shahboun.multi

import android.content.Context
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

/** Shahboun-owned runtime and clone lifecycle. */
class ShahbounCloneEngine : CloneEngine {
    override val name: String = "Shahboun Clone Engine"

    private lateinit var appContext: Context
    private lateinit var rootDir: File
    private lateinit var installer: RuntimePackageInstaller
    private lateinit var sessionFactory: RuntimeSessionFactory

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

    override fun launch(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        (appContext as? MultiApplication)?.requireRuntimeBridge()
            ?: error("Shahboun application context غير صالح")

        val dir = runtimeSlotDir(packageName, slot)
        require(dir.isDirectory) { "Clone does not exist" }
        val pkg = installer.read(packageName, slot, dir)
        val session = sessionFactory.create(pkg, dir)
        RuntimeRegistry.put(session)

        val intent = RuntimeIntentRouter.launchIntent(appContext, session)
        try {
            appContext.startActivity(intent)
        } catch (t: Throwable) {
            RuntimeRegistry.remove(packageName, slot)
            throw t
        }
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
