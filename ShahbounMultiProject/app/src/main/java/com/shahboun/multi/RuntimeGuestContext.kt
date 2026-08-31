package com.shahboun.multi

import android.app.Activity
import android.app.Application
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
import android.view.LayoutInflater
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Context presented to guest components with clone-scoped identity, storage and component routing. */
class RuntimeGuestContext(
    base: Context,
    private val session: RuntimeSession,
    private val slotDir: File
) : ContextWrapper(base) {
    private val dynamicReceivers = ConcurrentHashMap<BroadcastReceiver, BroadcastReceiver>()
    private val guestTheme: Resources.Theme by lazy {
        session.resources.newTheme().apply {
            val appTheme = session.runtimePackage.appTheme
            if (appTheme != 0) applyStyle(appTheme, true)
        }
    }
    private val cloneContentResolver: ContentResolver by lazy {
        val host = session.componentHost
        if (host != null) RuntimeContentResolverBridge(session, host, baseContext.contentResolver).resolver else baseContext.contentResolver
    }

    override fun getPackageName(): String = session.runtimePackage.packageName
    override fun getClassLoader(): ClassLoader = session.classLoader
    override fun getResources(): Resources = session.resources
    override fun getAssets() = session.resources.assets
    override fun getTheme(): Resources.Theme = guestTheme
    override fun setTheme(resid: Int) { if (resid != 0) guestTheme.applyStyle(resid, true) }
    override fun getApplicationContext(): Context = session.guestApplication ?: this
    override fun getPackageCodePath(): String = session.runtimePackage.baseApk.absolutePath
    override fun getPackageResourcePath(): String = session.runtimePackage.baseApk.absolutePath
    override fun getContentResolver(): ContentResolver = cloneContentResolver

    override fun getApplicationInfo(): ApplicationInfo {
        val pkg = session.runtimePackage
        val original = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                baseContext.packageManager.getApplicationInfo(pkg.packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.GET_META_DATA.toLong()))
            } else @Suppress("DEPRECATION") baseContext.packageManager.getApplicationInfo(pkg.packageName, android.content.pm.PackageManager.GET_META_DATA)
        }.getOrNull()
        return (original?.let(::ApplicationInfo) ?: ApplicationInfo()).apply {
            packageName = pkg.packageName
            className = pkg.applicationClass
            sourceDir = pkg.baseApk.absolutePath
            publicSourceDir = pkg.baseApk.absolutePath
            splitSourceDirs = pkg.splitApks.map { it.absolutePath }.toTypedArray()
            splitPublicSourceDirs = splitSourceDirs
            if (Build.VERSION.SDK_INT >= 26) splitNames = pkg.splitNames.toTypedArray()
            dataDir = cloneDir("data").absolutePath
            deviceProtectedDataDir = cloneDir("device_data").absolutePath
            nativeLibraryDir = cloneDir("native").absolutePath
            theme = pkg.appTheme
            targetSdkVersion = pkg.targetSdk
            if (Build.VERSION.SDK_INT >= 24) minSdkVersion = pkg.minSdk
            flags = pkg.appFlags
        }
    }

    override fun createPackageContext(packageName: String, flags: Int): Context = if (packageName == session.runtimePackage.packageName) this else super.createPackageContext(packageName, flags)
    override fun createConfigurationContext(overrideConfiguration: Configuration): Context = RuntimeGuestContext(baseContext.createConfigurationContext(overrideConfiguration), session, slotDir)
    override fun createDeviceProtectedStorageContext(): Context = RuntimeGuestContext(baseContext.createDeviceProtectedStorageContext(), session, slotDir)

    override fun getDataDir(): File = cloneDir("data")
    override fun getFilesDir(): File = cloneDir("files")
    override fun getCacheDir(): File = cloneDir("cache")
    override fun getCodeCacheDir(): File = cloneDir("code_cache")
    override fun getNoBackupFilesDir(): File = cloneDir("no_backup")
    override fun getDir(name: String, mode: Int): File = cloneDir("app_${safeName(name)}")
    override fun openFileInput(name: String): FileInputStream = FileInputStream(File(filesDir, safeName(name)))
    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        val target = File(filesDir, safeName(name)); target.parentFile?.mkdirs(); return FileOutputStream(target, mode and Context.MODE_APPEND != 0)
    }
    override fun deleteFile(name: String): Boolean = File(filesDir, safeName(name)).delete()
    override fun fileList(): Array<String> = filesDir.list()?.map { it }.orEmpty().toTypedArray()
    override fun getExternalFilesDir(type: String?): File { val base = cloneDir("external/files"); return if (type.isNullOrBlank()) base else File(base, safeName(type)).apply { mkdirs() } }
    override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(getExternalFilesDir(type))
    override fun getExternalCacheDir(): File = cloneDir("external/cache")
    override fun getExternalCacheDirs(): Array<File> = arrayOf(externalCacheDir)
    override fun getExternalMediaDirs(): Array<File> = arrayOf(cloneDir("external/media"))
    override fun getObbDir(): File = cloneDir("external/obb")
    override fun getObbDirs(): Array<File> = arrayOf(obbDir)
    override fun getDatabasePath(name: String): File = File(cloneDir("databases"), safeName(name))
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase { val path = getDatabasePath(name); path.parentFile?.mkdirs(); return SQLiteDatabase.openOrCreateDatabase(path, factory) }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase { val path = getDatabasePath(name); path.parentFile?.mkdirs(); return if (errorHandler != null) SQLiteDatabase.openOrCreateDatabase(path.absolutePath, factory, errorHandler) else SQLiteDatabase.openOrCreateDatabase(path, factory) }
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun databaseList(): Array<String> = cloneDir("databases").list()?.map { it }.orEmpty().toTypedArray()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = baseContext.getSharedPreferences("clone_${session.runtimePackage.packageName}_${session.runtimePackage.slot}_${safeName(name)}", mode)

    override fun startService(service: Intent): android.content.ComponentName? {
        val wrapper = session.componentHost?.wrapServiceIntent(service)
        if (wrapper != null) return baseContext.startService(wrapper)
        return super.startService(service)
    }
    override fun startForegroundService(service: Intent): android.content.ComponentName? { val wrapper = session.componentHost?.wrapServiceIntent(service); return if (wrapper != null) if (Build.VERSION.SDK_INT >= 26) baseContext.startForegroundService(wrapper) else baseContext.startService(wrapper) else super.startForegroundService(service) }
    override fun stopService(name: Intent): Boolean { val wrapper = session.componentHost?.wrapServiceIntent(name); return if (wrapper != null) baseContext.stopService(wrapper) else super.stopService(name) }
    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean { val wrapper = session.componentHost?.wrapServiceIntent(service); return if (wrapper != null) baseContext.bindService(wrapper, conn, flags) else super.bindService(service, conn, flags) }
    override fun unbindService(conn: ServiceConnection) { baseContext.unbindService(conn) }
    override fun sendBroadcast(intent: Intent) { if (session.componentHost?.dispatchExplicitReceiver(intent) == true) return; super.sendBroadcast(intent) }
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? = if (receiver == null) baseContext.registerReceiver(null, filter) else baseContext.registerReceiver(wrapDynamicReceiver(receiver), filter)
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = if (receiver == null) baseContext.registerReceiver(null, filter, flags) else baseContext.registerReceiver(wrapDynamicReceiver(receiver), filter, flags)
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, broadcastPermission: String?, scheduler: Handler?): Intent? = if (receiver == null) baseContext.registerReceiver(null, filter, broadcastPermission, scheduler) else baseContext.registerReceiver(wrapDynamicReceiver(receiver), filter, broadcastPermission, scheduler)
    override fun unregisterReceiver(receiver: BroadcastReceiver) { baseContext.unregisterReceiver(dynamicReceivers.remove(receiver) ?: receiver) }

    private fun wrapDynamicReceiver(receiver: BroadcastReceiver): BroadcastReceiver = dynamicReceivers.getOrPut(receiver) {
        object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { RuntimeExecutionScope.withSession(session) { receiver.onReceive(this@RuntimeGuestContext, intent) } } }
    }

    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) return (baseContext.getSystemService(name) as? LayoutInflater)?.cloneInContext(this)
        return super.getSystemService(name)
    }

    private fun cloneDir(relative: String): File = File(slotDir, relative).apply { if (!exists()) require(mkdirs()) { "Unable to create clone directory: $relative" } }
    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    companion object {
        fun attachIfNeeded(activity: Activity) {
            val wrapperIntent = activity.intent ?: return
            val packageName = wrapperIntent.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return
            val slot = wrapperIntent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
            val requested = wrapperIntent.getStringExtra(EXTRA_RUNTIME_ACTIVITY) ?: return
            if (slot < 0) return
            val hostApp = activity.applicationContext as? MultiApplication ?: return
            val session = RuntimeRegistry.get(packageName, slot)
            val guestActivity = session.runtimePackage.resolveActivity(requested)
            if (activity.javaClass.name != guestActivity) return
            val slotDir = hostApp.engine.runtimeSlotDir(packageName, slot)
            val originalBase = activity.baseContext
            val guest = RuntimeGuestContext(originalBase, session, slotDir)
            ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(activity, guest)
            val guestApplication = session.ensureGuestApplication(originalBase, slotDir)
            Activity::class.java.getDeclaredField("mApplication").apply { isAccessible = true }.set(activity, guestApplication)
            RuntimeActivityBindings.bind(activity, packageName, slot)
            RuntimeIntentRouter.originalIntent(wrapperIntent)?.let { activity.intent = it }
            applyGuestTheme(activity, session.runtimePackage, requested, guestActivity)
            RuntimeDiagnostics.log("RUNTIME", "attached guest context $packageName/$slot activity=$guestActivity requested=$requested")
        }

        private fun applyGuestTheme(activity: Activity, pkg: RuntimePackage, requestedName: String, resolvedName: String) {
            val themeId = pkg.activityTheme(requestedName).takeIf { it != 0 }
                ?: pkg.activityTheme(resolvedName).takeIf { it != 0 }
                ?: if (resolvedName == pkg.launchActivity && pkg.launchActivityTheme != 0) pkg.launchActivityTheme else pkg.appTheme
            if (themeId != 0) runCatching { activity.setTheme(themeId) }
        }
    }
}
