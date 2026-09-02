package com.shahboun.multi

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Shahboun Runtime 3.0 orchestration layer.
 *
 * Runtime 3 has one host coordinator and isolated clone restore processes. Host-only operations
 * mutate snapshots/process allocations; :cloneN processes only restore their assigned immutable
 * snapshot. Runtime 2 storage is never read by this engine.
 */
class ShahbounRuntime3Engine {
    val name: String = "Shahboun Runtime 3.0"

    private lateinit var appContext: Context
    private lateinit var rootDir: File
    private lateinit var installer: RuntimePackageInstaller
    private lateinit var sessionFactory: RuntimeSessionFactory
    private val sessionLock = Any()

    fun initialize(context: Context): Result<Unit> = runCatching {
        appContext = context.applicationContext
        rootDir = File(appContext.filesDir, "clone_engine_v3")
        require(rootDir.exists() || rootDir.mkdirs()) { "Unable to initialize Runtime 3 storage" }
        installer = RuntimePackageInstaller(appContext)
        sessionFactory = RuntimeSessionFactory(appContext)
        if (isHostProcess()) {
            recoverInterruptedUpdates()
            bootstrapExistingProfiles()
        }
        RuntimeDiagnostics.log(
            "ENGINE3",
            "initialized root=${rootDir.absolutePath} processCapacity=${RuntimeProcessPool.size} role=${if (isHostProcess()) "host" else "clone"}"
        )
    }

    fun createClone(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized(); requireHostProcess()
        require(packageName.isNotBlank() && slot >= 0) { "Invalid clone identity" }
        appContext.packageManager.getApplicationInfo(packageName, 0)
        val dir = runtimeSlotDir(packageName, slot)
        require(!dir.exists()) { "Clone already exists" }
        prepareFreshSlot(packageName, slot, dir)
    }

    fun updateClone(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized(); requireHostProcess()
        appContext.packageManager.getApplicationInfo(packageName, 0)
        forceStop(packageName, slot).getOrThrow()
        RuntimeProcessPool.allocateProcess(packageName, slot)
        val dir = runtimeSlotDir(packageName, slot)
        require(dir.isDirectory) { "Clone does not exist" }
        val apkDir = File(dir, "apk")
        val meta = File(dir, "runtime.meta")
        val apkBackup = File(dir, "apk.v3-backup")
        val metaBackup = File(dir, "runtime.meta.v3-backup")
        apkBackup.deleteRecursively(); metaBackup.delete()
        require(apkDir.renameTo(apkBackup)) { "Unable to prepare APK backup" }
        require(meta.renameTo(metaBackup)) { apkBackup.renameTo(apkDir); "Unable to prepare metadata backup" }
        try {
            val updated = installer.snapshot(packageName, slot, dir)
            installer.read(packageName, slot, dir)
            apkBackup.deleteRecursively(); metaBackup.delete()
            resetEphemeral(dir)
            RuntimeDiagnostics.log("ENGINE3", "updated $packageName/$slot version=${updated.versionCode}")
        } catch (t: Throwable) {
            File(dir, "apk").deleteRecursively(); File(dir, "runtime.meta").delete()
            require(apkBackup.renameTo(apkDir)) { "APK rollback failed" }
            require(metaBackup.renameTo(meta)) { "metadata rollback failed" }
            throw t
        }
    }

    fun launch(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized(); requireHostProcess()
        (appContext as? MultiApplication)?.requireRuntimeBridge() ?: error("Runtime 3 application context invalid")
        val pkg = runtimePackageFor(packageName, slot)
        val processIndex = RuntimeProcessPool.allocateProcess(pkg.packageName, pkg.slot)
        val requested = pkg.launchAlias ?: pkg.launchActivity
        val original = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg.packageName, requested)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val wrapper = Intent(original).apply {
            component = ComponentName(BuildConfig.APPLICATION_ID, RuntimeProcessPool.activityStub(pkg.packageName, pkg.slot).name)
            `package` = BuildConfig.APPLICATION_ID
            putExtra(EXTRA_RUNTIME_PACKAGE, pkg.packageName)
            putExtra(EXTRA_RUNTIME_SLOT, pkg.slot)
            putExtra(EXTRA_RUNTIME_ACTIVITY, requested)
            putExtra(EXTRA_RUNTIME_ORIGINAL_INTENT, Intent(original))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        RuntimeDiagnostics.log("ENGINE3", "launch $packageName/$slot process=:clone$processIndex")
        appContext.startActivity(wrapper)
    }

    fun runtimePackageFor(packageName: String, slot: Int): RuntimePackage {
        requireInitialized()
        val dir = runtimeSlotDir(packageName, slot)
        require(dir.isDirectory) { "Clone does not exist in Runtime 3" }
        return installer.read(packageName, slot, dir)
    }

    fun sessionFor(packageName: String, slot: Int): RuntimeSession {
        requireInitialized()
        requireCloneProcess(packageName, slot)
        RuntimeRegistry.getOrNull(packageName, slot)?.let { return it }
        synchronized(sessionLock) {
            RuntimeRegistry.getOrNull(packageName, slot)?.let { return it }
            val dir = runtimeSlotDir(packageName, slot)
            val session = sessionFactory.create(runtimePackageFor(packageName, slot), dir)
            RuntimeRegistry.put(session)
            try {
                RuntimeGuestProcessIdentity.pin(session)
                RuntimeExecutionScope.bindProcessSession(session)
                RuntimeLoadedApkBridge.bind(appContext, session).getOrThrow()
                session.ensureGuestApplication(appContext, dir)
                RuntimeLoadedApkBridge.bind(appContext, session).getOrThrow()
                RuntimeDiagnostics.log("ENGINE3", "session ready $packageName/$slot process=${hostProcessName()}")
                return session
            } catch (t: Throwable) {
                RuntimeRegistry.remove(packageName, slot)
                RuntimeExecutionScope.clearProcessSession(session)
                throw t
            }
        }
    }

    fun needsUpdate(packageName: String, slot: Int): Boolean = runCatching {
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else @Suppress("DEPRECATION") appContext.packageManager.getPackageInfo(packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else @Suppress("DEPRECATION") installed.versionCode.toLong()
        version > runtimePackageFor(packageName, slot).versionCode
    }.getOrDefault(false)

    fun forceStop(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized(); requireHostProcess()
        RuntimeActivityBindings.finishClone(packageName, slot)
        RuntimeJobSchedulerBridge.cancelClone(packageName, slot)
        runCatching {
            appContext.startService(Intent(appContext, RuntimeProcessPool.serviceStub(packageName, slot)).apply {
                action = ACTION_RUNTIME_STOP_CLONE
                putExtra(EXTRA_RUNTIME_PACKAGE, packageName)
                putExtra(EXTRA_RUNTIME_SLOT, slot)
            })
        }
        RuntimeRegistry.getOrNull(packageName, slot)?.let { RuntimeExecutionScope.clearProcessSession(it) }
        RuntimeRegistry.remove(packageName, slot)
        RuntimeDiagnostics.log("ENGINE3", "force-stop $packageName/$slot")
    }

    fun remove(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized(); requireHostProcess()
        forceStop(packageName, slot).getOrThrow()
        RuntimeClipboardBridge.clearClone(packageName, slot)
        deleteCloneSharedPreferences(packageName, slot)
        runtimeSlotDir(packageName, slot).deleteRecursively()
        RuntimeProcessPool.releaseProcess(packageName, slot)
        RuntimeDiagnostics.log("ENGINE3", "removed $packageName/$slot")
    }

    fun clearData(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized(); requireHostProcess()
        forceStop(packageName, slot).getOrThrow()
        RuntimeClipboardBridge.clearClone(packageName, slot)
        deleteCloneSharedPreferences(packageName, slot)
        val dir = runtimeSlotDir(packageName, slot)
        val keep = setOf("apk", "runtime.meta", "clone.meta", "native")
        dir.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.deleteRecursively() }
        createDataDirs(dir)
    }

    fun clearCache(packageName: String, slot: Int): Result<Unit> = runCatching {
        requireInitialized(); requireHostProcess()
        forceStop(packageName, slot).getOrThrow()
        val dir = runtimeSlotDir(packageName, slot)
        listOf("cache", "code_cache", "external/cache").forEach { name ->
            File(dir, name).apply { deleteRecursively(); require(mkdirs()) }
        }
    }

    fun cloneSizeBytes(packageName: String, slot: Int): Long {
        val dir = runtimeSlotDir(packageName, slot)
        return if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } + sharedPreferenceBytes(packageName, slot)
    }

    fun runtimeSlotDir(packageName: String, slot: Int): File {
        requireInitialized()
        val digest = MessageDigest.getInstance("SHA-256").digest(packageName.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        return File(rootDir, "$digest/$slot")
    }

    private fun bootstrapExistingProfiles() {
        requireHostProcess()
        CloneStore(appContext).list().forEach { profile ->
            val dir = runtimeSlotDir(profile.packageName, profile.slot)
            if (dir.isDirectory && File(dir, "runtime.meta").isFile) return@forEach
            runCatching {
                if (dir.exists()) dir.deleteRecursively()
                prepareFreshSlot(profile.packageName, profile.slot, dir)
                RuntimeDiagnostics.log("ENGINE3", "rebuilt legacy profile ${profile.packageName}/${profile.slot}")
            }.onFailure {
                RuntimeDiagnostics.log("ENGINE3", "profile bootstrap skipped ${profile.packageName}/${profile.slot}: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private fun prepareFreshSlot(packageName: String, slot: Int, dir: File) {
        requireHostProcess()
        require(dir.mkdirs()) { "Unable to create clone storage" }
        try {
            createDataDirs(dir)
            File(dir, "clone.meta").writeText("format=5\nengine=3\npackage=$packageName\nslot=$slot\ncreated=${System.currentTimeMillis()}\n")
            installer.snapshot(packageName, slot, dir)
            val process = RuntimeProcessPool.allocateProcess(packageName, slot)
            RuntimeDiagnostics.log("ENGINE3", "created $packageName/$slot process=:clone$process")
        } catch (t: Throwable) {
            RuntimeProcessPool.releaseProcess(packageName, slot)
            dir.deleteRecursively()
            throw t
        }
    }

    private fun createDataDirs(dir: File) {
        listOf("data", "device_data", "cache", "files", "databases", "no_backup", "code_cache", "native", "external").forEach {
            val child = File(dir, it); require(child.exists() || child.mkdirs()) { "Unable to create $it" }
        }
    }

    private fun resetEphemeral(dir: File) {
        listOf("code_cache", "native").forEach { name -> File(dir, name).apply { deleteRecursively(); require(mkdirs()) } }
    }

    private fun recoverInterruptedUpdates() {
        requireHostProcess()
        rootDir.walkTopDown().filter { it.isDirectory }.forEach { dir ->
            val apkBackup = File(dir, "apk.v3-backup")
            val metaBackup = File(dir, "runtime.meta.v3-backup")
            if (!apkBackup.exists() && !metaBackup.exists()) return@forEach
            val liveApk = File(dir, "apk"); val liveMeta = File(dir, "runtime.meta")
            if (liveApk.isDirectory && liveMeta.isFile) { apkBackup.deleteRecursively(); metaBackup.delete() }
            else if (apkBackup.isDirectory && metaBackup.isFile) {
                liveApk.deleteRecursively(); liveMeta.delete(); require(apkBackup.renameTo(liveApk)); require(metaBackup.renameTo(liveMeta))
            }
        }
    }

    private fun requireCloneProcess(packageName: String, slot: Int) {
        val expected = "${BuildConfig.APPLICATION_ID}:clone${RuntimeProcessPool.processIndex(packageName, slot)}"
        check(hostProcessName() == expected) { "Runtime 3 process mismatch: actual=${hostProcessName()} expected=$expected" }
    }

    private fun requireHostProcess() {
        check(isHostProcess()) { "Runtime 3 host-only operation attempted from ${hostProcessName()}" }
    }

    private fun isHostProcess(): Boolean = hostProcessName() == BuildConfig.APPLICATION_ID
    private fun hostProcessName(): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID
    private fun prefPrefix(packageName: String, slot: Int) = "clone_${packageName}_${slot}_"

    private fun deleteCloneSharedPreferences(packageName: String, slot: Int) {
        val prefix = prefPrefix(packageName, slot)
        File(appContext.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.name.endsWith(".xml") }.forEach {
            appContext.deleteSharedPreferences(it.name.removeSuffix(".xml")); it.delete()
        }
    }

    private fun sharedPreferenceBytes(packageName: String, slot: Int): Long {
        val prefix = prefPrefix(packageName, slot)
        return File(appContext.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.name.endsWith(".xml") }.sumOf { it.length() }
    }

    private fun requireInitialized() {
        check(::appContext.isInitialized && ::rootDir.isInitialized && ::installer.isInitialized && ::sessionFactory.isInitialized)
    }
}
