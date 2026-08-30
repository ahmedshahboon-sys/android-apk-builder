package com.shahboun.multi

import android.app.Activity
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
            val intent = activity.intent ?: return
            val packageName = intent.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return
            val slot = intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
            val guestActivity = intent.getStringExtra(EXTRA_RUNTIME_ACTIVITY) ?: return
            if (slot < 0 || activity.javaClass.name != guestActivity) return

            val app = activity.applicationContext as? MultiApplication ?: return
            val session = RuntimeRegistry.get(packageName, slot)
            val slotDir = app.engine.runtimeSlotDir(packageName, slot)
            val guest = RuntimeGuestContext(activity.baseContext, session, slotDir)

            // Activity is already attached by ActivityThread at this point. Replace only
            // its ContextWrapper base before guest onCreate executes.
            val contextWrapper = ContextWrapper::class.java
            val baseField = contextWrapper.getDeclaredField("mBase").apply { isAccessible = true }
            baseField.set(activity, guest)

            // Clear cached ContextThemeWrapper resources when present so getResources()
            // resolves through the guest context/session.
            runCatching {
                val themeWrapper = Class.forName("android.view.ContextThemeWrapper")
                val resourcesField = themeWrapper.getDeclaredField("mResources").apply { isAccessible = true }
                resourcesField.set(activity, null)
            }
        }
    }
}
