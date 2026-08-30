package com.shahboun.multi

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import android.view.LayoutInflater
import java.io.File

/** Context presented to a guest Activity before its onCreate callback. */
class RuntimeGuestContext(
    base: Context,
    private val session: RuntimeSession,
    private val slotDir: File
) : ContextWrapper(base) {
    override fun getPackageName(): String = session.runtimePackage.packageName
    override fun getClassLoader(): ClassLoader = session.classLoader
    override fun getResources(): Resources = session.resources
    override fun getAssets() = session.resources.assets
    override fun getApplicationContext(): Context = session.guestApplication ?: this

    override fun getFilesDir(): File = File(slotDir, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(slotDir, "cache").apply { mkdirs() }
    override fun getCodeCacheDir(): File = File(slotDir, "code_cache").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = File(slotDir, "no_backup").apply { mkdirs() }

    override fun getDatabasePath(name: String): File {
        val dir = File(slotDir, "databases").apply { mkdirs() }
        return File(dir, name)
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val key = "clone_${session.runtimePackage.packageName}_${session.runtimePackage.slot}_$safe"
        return baseContext.getSharedPreferences(key, mode)
    }

    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) {
            val inflater = baseContext.getSystemService(name) as? LayoutInflater
            return inflater?.cloneInContext(this)
        }
        return super.getSystemService(name)
    }

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

            runCatching {
                val themeWrapper = Class.forName("android.view.ContextThemeWrapper")
                val resourcesField = themeWrapper.getDeclaredField("mResources").apply { isAccessible = true }
                resourcesField.set(activity, null)
            }
        }

        private fun setActivityApplication(activity: Activity, application: Application) {
            val field = Activity::class.java.getDeclaredField("mApplication").apply { isAccessible = true }
            field.set(activity, application)
        }
    }
}
