package com.shahboun.multi

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.view.LayoutInflater
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Context presented to guest components with clone-scoped identity, storage and component routing. */
class RuntimeGuestContext(
    base: Context,
    private val session: RuntimeSession,
    private val slotDir: File
) : ContextWrapper(base) {
    private val guestTheme: Resources.Theme by lazy {
        session.resources.newTheme().apply {
            val appTheme = runCatching {
                baseContext.packageManager.getApplicationInfo(session.runtimePackage.packageName, 0).theme
            }.getOrDefault(0)
            if (appTheme != 0) applyStyle(appTheme, true)
        }
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

    override fun getApplicationInfo(): ApplicationInfo {
        val original = baseContext.packageManager.getApplicationInfo(session.runtimePackage.packageName, 0)
        return ApplicationInfo(original).apply {
            sourceDir = session.runtimePackage.baseApk.absolutePath
            publicSourceDir = session.runtimePackage.baseApk.absolutePath
            splitSourceDirs = session.runtimePackage.splitApks.map { it.absolutePath }.toTypedArray()
            splitPublicSourceDirs = splitSourceDirs
            dataDir = cloneDir("data").absolutePath
            deviceProtectedDataDir = cloneDir("device_data").absolutePath
            nativeLibraryDir = cloneDir("native").absolutePath
        }
    }

    override fun createPackageContext(packageName: String, flags: Int): Context {
        return if (packageName == session.runtimePackage.packageName) this else super.createPackageContext(packageName, flags)
    }

    override fun getDataDir(): File = cloneDir("data")
    override fun getFilesDir(): File = cloneDir("files")
    override fun getCacheDir(): File = cloneDir("cache")
    override fun getCodeCacheDir(): File = cloneDir("code_cache")
    override fun getNoBackupFilesDir(): File = cloneDir("no_backup")
    override fun getDir(name: String, mode: Int): File = cloneDir("app_${safeName(name)}")

    override fun openFileInput(name: String): FileInputStream = FileInputStream(File(filesDir, safeName(name)))

    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        val target = File(filesDir, safeName(name))
        target.parentFile?.mkdirs()
        return FileOutputStream(target, mode and Context.MODE_APPEND != 0)
    }

    override fun deleteFile(name: String): Boolean = File(filesDir, safeName(name)).delete()
    override fun fileList(): Array<String> = filesDir.list()?.map { it }.orEmpty().toTypedArray()

    override fun getExternalFilesDir(type: String?): File {
        val base = cloneDir("external/files")
        return if (type.isNullOrBlank()) base else File(base, safeName(type)).apply { mkdirs() }
    }

    override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(getExternalFilesDir(type))
    override fun getExternalCacheDir(): File = cloneDir("external/cache")
    override fun getExternalCacheDirs(): Array<File> = arrayOf(externalCacheDir)
    override fun getExternalMediaDirs(): Array<File> = arrayOf(cloneDir("external/media"))
    override fun getObbDir(): File = cloneDir("external/obb")
    override fun getObbDirs(): Array<File> = arrayOf(obbDir)

    override fun getDatabasePath(name: String): File = File(cloneDir("databases"), safeName(name))

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
        val path = getDatabasePath(name)
        path.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(path, factory)
    }

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
        val path = getDatabasePath(name)
        path.parentFile?.mkdirs()
        return if (errorHandler != null) SQLiteDatabase.openOrCreateDatabase(path.absolutePath, factory, errorHandler)
        else SQLiteDatabase.openOrCreateDatabase(path, factory)
    }

    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun databaseList(): Array<String> = cloneDir("databases").list()?.map { it }.orEmpty().toTypedArray()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val safe = safeName(name)
        val key = "clone_${session.runtimePackage.packageName}_${session.runtimePackage.slot}_$safe"
        return baseContext.getSharedPreferences(key, mode)
    }

    override fun startService(service: Intent): ComponentName? {
        val wrapper = session.componentHost?.wrapServiceIntent(service)
        if (wrapper != null) {
            RuntimeDiagnostics.log("SERVICE", "route startService ${session.runtimePackage.packageName}/${session.runtimePackage.slot} target=${service.component?.className ?: service.action}")
            return baseContext.startService(wrapper)
        }
        return super.startService(service)
    }

    override fun startForegroundService(service: Intent): ComponentName? {
        val wrapper = session.componentHost?.wrapServiceIntent(service)
        if (wrapper != null) {
            RuntimeDiagnostics.log("SERVICE", "route startForegroundService ${session.runtimePackage.packageName}/${session.runtimePackage.slot} target=${service.component?.className ?: service.action}")
            return if (Build.VERSION.SDK_INT >= 26) baseContext.startForegroundService(wrapper) else baseContext.startService(wrapper)
        }
        return super.startForegroundService(service)
    }

    override fun stopService(name: Intent): Boolean {
        val wrapper = session.componentHost?.wrapServiceIntent(name)
        return if (wrapper != null) baseContext.stopService(wrapper) else super.stopService(name)
    }

    override fun sendBroadcast(intent: Intent) {
        if (session.componentHost?.dispatchExplicitReceiver(intent) == true) return
        super.sendBroadcast(intent)
    }

    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) {
            val inflater = baseContext.getSystemService(name) as? LayoutInflater
            return inflater?.cloneInContext(this)
        }
        return super.getSystemService(name)
    }

    private fun cloneDir(relative: String): File = File(slotDir, relative).apply {
        if (!exists()) require(mkdirs()) { "Unable to create clone directory: $relative" }
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    companion object {
        fun attachIfNeeded(activity: Activity) {
            val wrapperIntent = activity.intent ?: return
            val packageName = wrapperIntent.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return
            val slot = wrapperIntent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
            val guestActivity = wrapperIntent.getStringExtra(EXTRA_RUNTIME_ACTIVITY) ?: return
            if (slot < 0 || activity.javaClass.name != guestActivity) return

            val hostApp = activity.applicationContext as? MultiApplication ?: return
            val session = RuntimeRegistry.get(packageName, slot)
            val slotDir = hostApp.engine.runtimeSlotDir(packageName, slot)
            val originalBase = activity.baseContext
            val guest = RuntimeGuestContext(originalBase, session, slotDir)

            val baseField = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
            baseField.set(activity, guest)

            val guestApplication = session.ensureGuestApplication(originalBase, slotDir)
            setActivityApplication(activity, guestApplication)

            RuntimeIntentRouter.originalIntent(wrapperIntent)?.let { activity.intent = it }

            applyGuestTheme(activity, originalBase, packageName, guestActivity)
            RuntimeDiagnostics.log("RUNTIME", "attached guest context $packageName/$slot activity=$guestActivity")
        }

        /**
         * Android 16 no longer exposes the old ContextThemeWrapper.mTheme layout used by
         * earlier releases. The guest base context already owns the guest Resources/Theme,
         * so rebuilding framework-private theme fields is unnecessary and crashes on SDK 36.
         * Resolve the guest activity theme through PackageManager and apply it only through
         * the public Activity.setTheme API.
         */
        private fun applyGuestTheme(activity: Activity, hostBase: Context, packageName: String, activityName: String) {
            val themeId = runCatching {
                val component = ComponentName(packageName, activityName)
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    hostBase.packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION") hostBase.packageManager.getActivityInfo(component, 0)
                }
                if (info.theme != 0) info.theme else hostBase.packageManager.getApplicationInfo(packageName, 0).theme
            }.onFailure {
                RuntimeDiagnostics.log("RUNTIME", "guest theme lookup failed $packageName activity=$activityName: ${it.message}")
            }.getOrDefault(0)

            if (themeId != 0) {
                runCatching { activity.setTheme(themeId) }
                    .onSuccess { RuntimeDiagnostics.log("RUNTIME", "guest theme applied $packageName activity=$activityName theme=0x${themeId.toString(16)}") }
                    .onFailure { RuntimeDiagnostics.log("RUNTIME", "guest theme apply failed $packageName activity=$activityName: ${it.stackTraceToString()}") }
            } else {
                RuntimeDiagnostics.log("RUNTIME", "guest theme defaulted $packageName activity=$activityName")
            }
        }

        private fun setActivityApplication(activity: Activity, application: Application) {
            val field = Activity::class.java.getDeclaredField("mApplication").apply { isAccessible = true }
            field.set(activity, application)
        }
    }
}
