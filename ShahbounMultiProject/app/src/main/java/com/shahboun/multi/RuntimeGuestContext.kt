package com.shahboun.multi

import android.app.Activity
import android.content.AttributionSource
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Handler
import android.os.Process
import android.view.LayoutInflater
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Context presented to guest components with clone-scoped identity, storage and component routing. */
class RuntimeGuestContext(
    base: Context,
    private val session: RuntimeSession,
    private val slotDir: File,
    private val deviceProtected: Boolean = false
) : ContextWrapper(base) {
    private val dynamicReceivers = ConcurrentHashMap<BroadcastReceiver, BroadcastReceiver>()
    private val preferenceStores = ConcurrentHashMap<String, SharedPreferences>()
    private val guestTheme: Resources.Theme by lazy {
        session.resources.newTheme().apply { session.runtimePackage.appTheme.takeIf { it != 0 }?.let { applyStyle(it, true) } }
    }
    private val cloneContentResolver: ContentResolver by lazy {
        val host = session.componentHost
        if (host != null) RuntimeContentResolverBridge(session, host, baseContext.contentResolver).resolver else baseContext.contentResolver
    }
    private val guestJobScheduler by lazy { RuntimeJobSchedulerBridge.facadeFor(baseContext, session) }

    init {
        val registered = RuntimeNativeRuntime.register(session.runtimePackage.packageName, session.runtimePackage.slot, slotDir)
        if (!deviceProtected) migrateLegacyCredentialStorage()
        RuntimeDiagnostics.log("CONTEXT7", "guest context init ${session.runtimePackage.packageName}/${session.runtimePackage.slot} nativeRegistered=$registered root=${slotDir.absolutePath} deviceProtected=$deviceProtected")
        RuntimeWebGmsCompatibility.prepareCloneStorage(baseContext, session, slotDir)
    }

    override fun getPackageName(): String = session.runtimePackage.packageName
    override fun getClassLoader(): ClassLoader = session.classLoader
    override fun getResources(): Resources = session.resources
    override fun getAssets() = session.resources.assets
    override fun getTheme(): Resources.Theme = guestTheme
    override fun setTheme(resid: Int) { if (resid != 0) guestTheme.applyStyle(resid, true) }
    override fun getApplicationContext(): Context = session.applicationForContext() ?: this
    override fun getPackageCodePath(): String = session.runtimePackage.baseApk.absolutePath
    override fun getPackageResourcePath(): String = session.runtimePackage.baseApk.absolutePath
    override fun getContentResolver(): ContentResolver = cloneContentResolver

    // Binder-facing AppOps identity remains physical. Ordinary app-facing package identity remains guest.
    override fun getOpPackageName(): String = baseContext.opPackageName
    override fun getAttributionTag(): String? = baseContext.attributionTag
    override fun getAttributionSource(): AttributionSource = baseContext.attributionSource

    override fun getApplicationInfo(): ApplicationInfo {
        val pkg = session.runtimePackage
        val original = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                baseContext.packageManager.getApplicationInfo(pkg.packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.GET_META_DATA.toLong()))
            } else @Suppress("DEPRECATION") baseContext.packageManager.getApplicationInfo(pkg.packageName, android.content.pm.PackageManager.GET_META_DATA)
        }.getOrNull()
        val credential = bucket("data")
        val device = bucket("device_data")
        return (original?.let(::ApplicationInfo) ?: ApplicationInfo()).apply {
            packageName = pkg.packageName
            className = pkg.applicationClass
            processName = pkg.packageName
            uid = Process.myUid()
            sourceDir = pkg.baseApk.absolutePath
            publicSourceDir = pkg.baseApk.absolutePath
            splitSourceDirs = pkg.splitApks.map { it.absolutePath }.toTypedArray()
            splitPublicSourceDirs = splitSourceDirs
            if (Build.VERSION.SDK_INT >= 26) splitNames = pkg.splitNames.toTypedArray()
            dataDir = if (deviceProtected) device.absolutePath else credential.absolutePath
            deviceProtectedDataDir = device.absolutePath
            nativeLibraryDir = bucket("native").absolutePath
            theme = pkg.appTheme
            targetSdkVersion = pkg.targetSdk
            if (Build.VERSION.SDK_INT >= 24) minSdkVersion = pkg.minSdk
            flags = pkg.appFlags or ApplicationInfo.FLAG_HAS_CODE
        }
    }

    override fun createPackageContext(packageName: String, flags: Int): Context =
        if (packageName == session.runtimePackage.packageName) this else super.createPackageContext(packageName, flags)

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
        RuntimeGuestContext(baseContext.createConfigurationContext(overrideConfiguration), session, slotDir, deviceProtected)

    override fun createDeviceProtectedStorageContext(): Context =
        RuntimeGuestContext(baseContext.createDeviceProtectedStorageContext(), session, slotDir, true)

    override fun createAttributionContext(attributionTag: String?): Context =
        RuntimeGuestContext(baseContext.createAttributionContext(attributionTag), session, slotDir, deviceProtected)

    override fun isDeviceProtectedStorage(): Boolean = deviceProtected

    override fun getDataDir(): File = if (deviceProtected) bucket("device_data") else bucket("data")
    override fun getFilesDir(): File = dataChild("files")
    override fun getCacheDir(): File = dataChild("cache")
    override fun getCodeCacheDir(): File = dataChild("code_cache")
    override fun getNoBackupFilesDir(): File = dataChild("no_backup")
    override fun getDir(name: String, mode: Int): File = dataChild("app_${RuntimePathPolicy.safeLeaf(name)}")

    override fun openFileInput(name: String): FileInputStream = FileInputStream(File(filesDir, RuntimePathPolicy.safeLeaf(name)))
    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        val target = File(filesDir, RuntimePathPolicy.safeLeaf(name)); target.parentFile?.mkdirs()
        return FileOutputStream(target, mode and Context.MODE_APPEND != 0)
    }
    override fun deleteFile(name: String): Boolean = File(filesDir, RuntimePathPolicy.safeLeaf(name)).delete()
    override fun fileList(): Array<String> = filesDir.list()?.copyOf() ?: emptyArray()

    override fun getExternalFilesDir(type: String?): File {
        val base = bucket("external/files")
        return if (type.isNullOrBlank()) base else child(base, RuntimePathPolicy.safeLeaf(type))
    }
    override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(getExternalFilesDir(type))
    override fun getExternalCacheDir(): File = bucket("external/cache")
    override fun getExternalCacheDirs(): Array<File> = arrayOf(externalCacheDir)
    override fun getExternalMediaDirs(): Array<File> = arrayOf(bucket("external/media"))
    override fun getObbDir(): File = bucket("external/obb")
    override fun getObbDirs(): Array<File> = arrayOf(obbDir)

    override fun getDatabasePath(name: String): File = File(dataChild("databases"), RuntimePathPolicy.safeLeaf(name))
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
        val path = getDatabasePath(name); path.parentFile?.mkdirs(); return SQLiteDatabase.openOrCreateDatabase(path, factory)
    }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
        val path = getDatabasePath(name); path.parentFile?.mkdirs()
        return if (errorHandler != null) SQLiteDatabase.openOrCreateDatabase(path.absolutePath, factory, errorHandler) else SQLiteDatabase.openOrCreateDatabase(path, factory)
    }
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun databaseList(): Array<String> = dataChild("databases").list()?.copyOf() ?: emptyArray()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val safe = RuntimePathPolicy.safeLeaf(name)
        return preferenceStores.getOrPut(safe) { RuntimeFileSharedPreferences(File(dataChild("shared_prefs"), "$safe.json")) }
    }

    override fun startService(service: Intent): android.content.ComponentName? {
        val wrapper = session.componentHost?.wrapServiceIntent(service)
        if (wrapper != null) return baseContext.startService(wrapper)
        return super.startService(service)
    }
    override fun startForegroundService(service: Intent): android.content.ComponentName? {
        val wrapper = session.componentHost?.wrapServiceIntent(service)
        return if (wrapper != null) { if (Build.VERSION.SDK_INT >= 26) baseContext.startForegroundService(wrapper) else baseContext.startService(wrapper) }
        else super.startForegroundService(service)
    }
    override fun stopService(name: Intent): Boolean {
        val wrapper = session.componentHost?.wrapServiceIntent(name)
        return if (wrapper != null) baseContext.stopService(wrapper) else super.stopService(name)
    }
    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        val wrapper = session.componentHost?.wrapServiceIntent(service)
        return if (wrapper != null) baseContext.bindService(wrapper, conn, flags) else super.bindService(service, conn, flags)
    }
    override fun unbindService(conn: ServiceConnection) { baseContext.unbindService(conn) }
    override fun sendBroadcast(intent: Intent) { if (session.componentHost?.dispatchExplicitReceiver(intent) == true) return; super.sendBroadcast(intent) }
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
        if (receiver == null) baseContext.registerReceiver(null, filter) else baseContext.registerReceiver(wrapDynamicReceiver(receiver), filter)
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? =
        if (receiver == null) baseContext.registerReceiver(null, filter, flags) else baseContext.registerReceiver(wrapDynamicReceiver(receiver), filter, flags)
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, broadcastPermission: String?, scheduler: Handler?): Intent? =
        if (receiver == null) baseContext.registerReceiver(null, filter, broadcastPermission, scheduler)
        else baseContext.registerReceiver(wrapDynamicReceiver(receiver), filter, broadcastPermission, scheduler)
    override fun unregisterReceiver(receiver: BroadcastReceiver?) {
        if (receiver == null) { RuntimeDiagnostics.log("RECEIVER", "ignored unregisterReceiver(null) ${session.runtimePackage.packageName}/${session.runtimePackage.slot}"); return }
        val wrapped = dynamicReceivers.remove(receiver) ?: receiver
        runCatching { baseContext.unregisterReceiver(wrapped) }
            .onFailure { RuntimeDiagnostics.log("RECEIVER", "unregister fallback ${session.runtimePackage.packageName}/${session.runtimePackage.slot}: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun wrapDynamicReceiver(receiver: BroadcastReceiver): BroadcastReceiver = dynamicReceivers.getOrPut(receiver) {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                RuntimeComponentSupervisor.receiver(session)
                RuntimeExecutionScope.withSession(session) { receiver.onReceive(this@RuntimeGuestContext, intent) }
            }
        }
    }

    override fun getSystemService(name: String): Any? {
        val key = "guest-service:${session.runtimePackage.packageName}:${session.runtimePackage.slot}:$name"
        return RuntimeRecursionGuard.call(
            key = key,
            fallback = { RuntimeDiagnostics.log("CONTEXT7", "service recursion fallback ${session.runtimePackage.packageName}/${session.runtimePackage.slot} name=$name"); baseContext.getSystemService(name) }
        ) {
            when (name) {
                Context.LAYOUT_INFLATER_SERVICE -> (baseContext.getSystemService(name) as? LayoutInflater)?.cloneInContext(this)
                Context.JOB_SCHEDULER_SERVICE -> guestJobScheduler
                Context.CLIPBOARD_SERVICE -> RuntimeClipboardBridge.serviceFor(baseContext, session)
                Context.ACCOUNT_SERVICE -> RuntimeIdentityServiceBridge.accountManagerFor(baseContext, session)
                else -> RuntimeSystemServiceVirtualizer.serviceFor(baseContext, session, name) ?: baseContext.getSystemService(name)
            }
        }
    }

    private fun dataChild(relative: String): File = child(dataDir, relative)

    private fun bucket(relative: String): File {
        val direct = RuntimePathPolicy.child(slotDir, relative)
        if (!direct.exists()) require(direct.mkdirs()) { "Unable to create clone directory: $relative" }
        check(RuntimePathPolicy.isContained(slotDir, direct)) { "Clone path escaped slot root: ${direct.absolutePath}" }
        check(RuntimeNativeRuntime.isWithinRoot(session.runtimePackage.packageName, session.runtimePackage.slot, direct)) { "Native path policy rejected clone path: ${direct.absolutePath}" }
        return direct.canonicalFile
    }

    private fun child(parent: File, relative: String): File {
        val direct = RuntimePathPolicy.child(parent, relative)
        if (!direct.exists()) require(direct.mkdirs()) { "Unable to create clone directory: $relative" }
        check(RuntimePathPolicy.isContained(slotDir, direct)) { "Clone path escaped slot root: ${direct.absolutePath}" }
        return direct.canonicalFile
    }

    private fun migrateLegacyCredentialStorage() {
        val data = File(slotDir, "data").apply { mkdirs() }
        listOf("files", "cache", "code_cache", "no_backup", "databases", "shared_prefs").forEach { name ->
            val old = File(slotDir, name)
            val target = File(data, name)
            if (!old.exists() || target.exists()) return@forEach
            runCatching {
                if (!old.renameTo(target)) { old.copyRecursively(target, overwrite = false); old.deleteRecursively() }
            }.onFailure { RuntimeDiagnostics.log("STORAGE7", "legacy migration skipped bucket=$name ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    companion object {
        fun attachIfNeeded(activity: Activity) {
            val wrapperIntent = activity.intent
            val explicitPackage = wrapperIntent?.getStringExtra(EXTRA_RUNTIME_PACKAGE)
            val explicitSlot = wrapperIntent?.getIntExtra(EXTRA_RUNTIME_SLOT, -1) ?: -1
            val owner = RuntimeExecutionScope.processOwner()
            val packageName = explicitPackage ?: owner?.first ?: return
            val slot = if (explicitSlot >= 0) explicitSlot else owner?.second ?: return
            val hostApp = activity.applicationContext as? MultiApplication ?: return
            if (wrapperIntent != null && explicitPackage != null && explicitSlot >= 0) {
                val requested = wrapperIntent.getStringExtra(EXTRA_RUNTIME_ACTIVITY) ?: return
                val snapshot = runCatching { hostApp.engine.runtimePackageFor(packageName, slot) }.getOrNull() ?: return
                if (!RuntimeIntentSecurity.verify(hostApp, wrapperIntent, snapshot, "activity", requested)) {
                    RuntimeDiagnostics.log("SECURITY7", "attachIfNeeded rejected unauthenticated activity $packageName/$slot")
                    return
                }
            }
            val session = RuntimeRegistry.getOrNull(packageName, slot)
                ?: runCatching { hostApp.engine.sessionFor(packageName, slot) }.getOrNull()
                ?: return
            val requested = wrapperIntent?.getStringExtra(EXTRA_RUNTIME_ACTIVITY)
            val actual = activity.javaClass.name
            val guestActivity = if (!requested.isNullOrBlank()) session.runtimePackage.resolveActivity(requested) else actual
            if (actual != guestActivity && !session.runtimePackage.ownsActivity(actual)) return
            RuntimeComponentSupervisor.activity(session)
            val slotDir = hostApp.engine.runtimeSlotDir(packageName, slot)
            val originalBase = activity.baseContext
            val guest = RuntimeGuestContext(originalBase, session, slotDir)
            ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(activity, guest)
            val guestApplication = session.ensureGuestApplication(originalBase, slotDir)
            Activity::class.java.getDeclaredField("mApplication").apply { isAccessible = true }.set(activity, guestApplication)
            RuntimeActivityBindings.bind(activity, packageName, slot)
            if (wrapperIntent != null) RuntimeIntentRouter.originalIntent(wrapperIntent)?.let { activity.intent = it }
            applyGuestTheme(activity, session.runtimePackage, requested ?: actual, guestActivity)
            RuntimeDiagnostics.log("RUNTIME", "attached guest context $packageName/$slot activity=$actual requested=${requested ?: "recreated"}")
        }

        private fun applyGuestTheme(activity: Activity, pkg: RuntimePackage, requestedName: String, resolvedName: String) {
            val themeId = pkg.activityTheme(requestedName).takeIf { it != 0 }
                ?: pkg.activityTheme(resolvedName).takeIf { it != 0 }
                ?: if (resolvedName == pkg.launchActivity && pkg.launchActivityTheme != 0) pkg.launchActivityTheme else pkg.appTheme
            if (themeId != 0) runCatching { activity.setTheme(themeId) }
        }
    }
}
