package com.shahboun.multi

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
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
    override val name: String = "Shahboun Clone Engine 2.0"

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
        recoverInterruptedUpdates()
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
            listOf("data", "device_data", "cache", "files", "databases", "no_backup", "code_cache", "native", "external").forEach {
                require(File(dir, it).mkdirs()) { "Unable to create clone directory: $it" }
            }
            File(dir, "clone.meta").writeText(buildString {
                appendLine("format=4")
                appendLine("package=$packageName")
                appendLine("slot=$slot")
                appendLine("created=${System.currentTimeMillis()}")
            })
            installer.snapshot(packageName, slot, dir)
            val processIndex = RuntimeProcessPool.allocateProcess(packageName, slot)
            RuntimeDiagnostics.log("CLONE", "created $packageName/$slot process=:clone$processIndex engine=2")
        } catch (t: Throwable) {
            RuntimeProcessPool.releaseProcess(packageName, slot)
            dir.deleteRecursively()
            throw t
        }
    }

    override fun updateClone(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        require(packageName.isNotBlank()) { "Package name is empty" }
        require(slot >= 0) { "Invalid clone slot" }
        appContext.packageManager.getApplicationInfo(packageName, 0)
        forceStop(packageName, slot).getOrThrow()
        RuntimeProcessPool.allocateProcess(packageName, slot)
        val dir = runtimeSlotDir(packageName, slot)
        require(dir.isDirectory) { "Clone does not exist" }
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

    fun needsUpdate(packageName: String, slot: Int): Boolean = runCatching {
        requireInitialized()
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else @Suppress("DEPRECATION") appContext.packageManager.getPackageInfo(packageName, 0)
        val installedVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else @Suppress("DEPRECATION") installed.versionCode.toLong()
        val snapshot = runtimePackageFor(packageName, slot)
        installedVersion > snapshot.versionCode
    }.getOrDefault(false)

    /** Reads immutable snapshot metadata only. Safe in the host process; it never loads guest code. */
    fun runtimePackageFor(packageName: String, slot: Int): RuntimePackage {
        requireInitialized()
        val dir = runtimeSlotDir(packageName, slot)
        require(dir.isDirectory) { "Clone does not exist" }
        return installer.read(packageName, slot, dir)
    }

    /** Full guest sessions are deliberately created only inside the assigned :cloneN process. */
    fun sessionFor(packageName: String, slot: Int): RuntimeSession {
        requireInitialized()
        requireCloneProcess(packageName, slot)
        RuntimeRegistry.getOrNull(packageName, slot)?.let { return it }
        synchronized(sessionLock) {
            RuntimeRegistry.getOrNull(packageName, slot)?.let { return it }
            val dir = runtimeSlotDir(packageName, slot)
            val pkg = runtimePackageFor(packageName, slot)
            val session = sessionFactory.create(pkg, dir)
            RuntimeRegistry.put(session)
            try {
                RuntimeDiagnostics.log("RUNTIME", "restoring guest session $packageName/$slot process=${currentProcessName()}")
                session.ensureGuestApplication(appContext, dir)
                RuntimeDiagnostics.log("RUNTIME", "restored guest session $packageName/$slot process=${currentProcessName()}")
                return session
            } catch (t: Throwable) {
                RuntimeRegistry.remove(packageName, slot)
                throw t
            }
        }
    }

    override fun launch(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        (appContext as? MultiApplication)?.requireRuntimeBridge() ?: error("Shahboun application context غير صالح")
        val pkg = runtimePackageFor(packageName, slot)
        val processIndex = RuntimeProcessPool.allocateProcess(pkg.packageName, pkg.slot)
        val requested = pkg.launchAlias ?: pkg.launchActivity
        val original = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg.packageName, requested)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val stub = RuntimeProcessPool.activityStub(pkg.packageName, pkg.slot)
        val wrapper = Intent(original).apply {
            component = ComponentName(BuildConfig.APPLICATION_ID, stub.name)
            `package` = BuildConfig.APPLICATION_ID
            putExtra(EXTRA_RUNTIME_PACKAGE, pkg.packageName)
            putExtra(EXTRA_RUNTIME_SLOT, pkg.slot)
            putExtra(EXTRA_RUNTIME_ACTIVITY, requested)
            putExtra(EXTRA_RUNTIME_ORIGINAL_INTENT, Intent(original))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        RuntimeDiagnostics.log("LAUNCH", "dispatch descriptor $packageName/$slot hostProcess=${currentProcessName()} targetProcess=:clone$processIndex")
        appContext.startActivity(wrapper)
    }

    fun forceStop(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        RuntimeActivityBindings.finishClone(packageName, slot)
        RuntimeJobSchedulerBridge.cancelClone(packageName, slot)
        val stub = RuntimeProcessPool.serviceStub(packageName, slot)
        runCatching {
            appContext.startService(Intent(appContext, stub).apply {
                action = ACTION_RUNTIME_STOP_CLONE
                putExtra(EXTRA_RUNTIME_PACKAGE, packageName)
                putExtra(EXTRA_RUNTIME_SLOT, slot)
            })
        }
        RuntimeRegistry.remove(packageName, slot)
        RuntimeDiagnostics.log("ENGINE", "clone force-stopped $packageName/$slot")
    }

    override fun remove(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        forceStop(packageName, slot).getOrThrow()
        RuntimeClipboardBridge.clearClone(packageName, slot)
        deleteCloneSharedPreferences(packageName, slot)
        val dir = runtimeSlotDir(packageName, slot)
        if (dir.exists()) require(dir.deleteRecursively()) { "Unable to remove clone storage" }
        RuntimeProcessPool.releaseProcess(packageName, slot)
        RuntimeDiagnostics.log("CLONE", "removed $packageName/$slot and released process allocation")
    }

    override fun clearData(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        forceStop(packageName, slot).getOrThrow()
        RuntimeClipboardBridge.clearClone(packageName, slot)
        deleteCloneSharedPreferences(packageName, slot)
        val dir = runtimeSlotDir(packageName, slot)
        require(dir.isDirectory) { "Clone does not exist" }
        val keep = setOf("apk", "runtime.meta", "clone.meta", "native")
        dir.listFiles().orEmpty().filter { it.name !in keep && !it.name.endsWith("update-backup") }.forEach { child ->
            if (child.exists()) require(child.deleteRecursively()) { "Unable to clear ${child.name}" }
        }
        listOf("data", "device_data", "cache", "files", "databases", "no_backup", "code_cache", "external").forEach { name ->
            val child = File(dir, name)
            require(child.exists() || child.mkdirs()) { "Unable to reset $name" }
        }
        RuntimeDiagnostics.log("ENGINE", "clone data cleared $packageName/$slot")
    }

    fun clearCache(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized()
        forceStop(packageName, slot).getOrThrow()
        val dir = runtimeSlotDir(packageName, slot)
        require(dir.isDirectory) { "Clone does not exist" }
        listOf("cache", "code_cache", "external/cache").forEach { name ->
            val child = File(dir, name)
            if (child.exists()) child.deleteRecursively()
            require(child.mkdirs()) { "Unable to reset $name" }
        }
        RuntimeDiagnostics.log("ENGINE", "clone cache cleared $packageName/$slot")
    }

    fun cloneSizeBytes(packageName: String, slot: Int): Long {
        requireInitialized()
        val dir = runtimeSlotDir(packageName, slot)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } + sharedPreferenceBytes(packageName, slot)
    }

    fun runtimeSlotDir(packageName: String, slot: Int): File {
        requireInitialized()
        val digest = MessageDigest.getInstance("SHA-256").digest(packageName.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(20)
        return File(rootDir, "$digest/$slot")
    }

    private fun requireCloneProcess(packageName: String, slot: Int) {
        val expected = "${BuildConfig.APPLICATION_ID}:clone${RuntimeProcessPool.processIndex(packageName, slot)}"
        val actual = currentProcessName()
        check(actual == expected) {
            "رفض إنشاء Guest Session خارج عملية النسخة: actual=$actual expected=$expected package=$packageName/$slot"
        }
    }

    private fun currentProcessName(): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID

    private fun recoverInterruptedUpdates() {
        rootDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { packageHashDir ->
            packageHashDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { slotDir ->
                val backupApk = File(slotDir, "apk.update-backup")
                val backupMeta = File(slotDir, "runtime.meta.update-backup")
                if (!backupApk.exists() && !backupMeta.exists()) return@forEach
                val liveApk = File(slotDir, "apk")
                val liveMeta = File(slotDir, "runtime.meta")
                if (liveApk.isDirectory && liveMeta.isFile) {
                    backupApk.deleteRecursively(); backupMeta.delete()
                    RuntimeDiagnostics.log("UPDATE", "discarded stale update backup ${slotDir.absolutePath}")
                } else if (backupApk.isDirectory && backupMeta.isFile) {
                    liveApk.deleteRecursively(); liveMeta.delete()
                    require(backupApk.renameTo(liveApk)) { "تعذر استعادة APK بعد تحديث متقطع" }
                    require(backupMeta.renameTo(liveMeta)) { "تعذر استعادة metadata بعد تحديث متقطع" }
                    RuntimeDiagnostics.log("UPDATE", "recovered interrupted update ${slotDir.absolutePath}")
                }
            }
        }
    }

    private fun sharedPreferencePrefix(packageName: String, slot: Int) = "clone_${packageName}_${slot}_"

    private fun deleteCloneSharedPreferences(packageName: String, slot: Int) {
        val prefix = sharedPreferencePrefix(packageName, slot)
        val shared = File(appContext.applicationInfo.dataDir, "shared_prefs")
        shared.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.name.endsWith(".xml") }.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            runCatching { appContext.deleteSharedPreferences(name) }
            if (file.exists()) file.delete()
        }
    }

    private fun sharedPreferenceBytes(packageName: String, slot: Int): Long {
        val prefix = sharedPreferencePrefix(packageName, slot)
        val shared = File(appContext.applicationInfo.dataDir, "shared_prefs")
        return shared.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.name.endsWith(".xml") }.sumOf { it.length() }
    }

    private fun requireInitialized() {
        check(::appContext.isInitialized && ::rootDir.isInitialized && ::installer.isInitialized && ::sessionFactory.isInitialized) { "Engine not initialized" }
    }
}
