package com.shahboun.multi

import android.app.Activity
import android.content.Intent

/**
 * Connects an Activity created from the virtual LoadedApk back to Shahboun runtime bookkeeping.
 * It does not replace Context/Resources: Android already created those from the guest LoadedApk.
 */
object RuntimeFrameworkActivityBinder {
    fun bind(activity: Activity): RuntimeSession? {
        val intent = activity.intent ?: return null
        val packageName = intent.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return null
        val slot = intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
        val requested = intent.getStringExtra(EXTRA_RUNTIME_ACTIVITY) ?: return null
        if (slot < 0) return null
        val session = RuntimeRegistry.getOrNull(packageName, slot)
            ?: runCatching { MultiApplication.current?.engine?.sessionFor(packageName, slot) }.getOrNull()
            ?: return null
        val resolved = session.runtimePackage.resolveActivity(requested)
        if (activity.javaClass.name != resolved) return null

        RuntimeActivityBindings.bind(activity, packageName, slot)
        val base = activity.baseContext
        val frameworkGuest = runCatching {
            base.packageName == packageName &&
                base.classLoader === session.classLoader &&
                base.applicationInfo.sourceDir == session.runtimePackage.baseApk.absolutePath
        }.getOrDefault(false)
        RuntimeDiagnostics.log(
            "ACTIVITY2",
            "bound $packageName/$slot activity=$resolved frameworkGuest=$frameworkGuest basePkg=${runCatching { base.packageName }.getOrNull()} loader=${base.classLoader.javaClass.simpleName}"
        )
        return session
    }

    fun restorePublicIntent(activity: Activity) {
        val wrapper = activity.intent ?: return
        if (!wrapper.hasExtra(EXTRA_RUNTIME_PACKAGE)) return
        val original = RuntimeIntentRouter.originalIntent(wrapper) ?: return
        // Preserve the guest's original public Intent. Runtime bookkeeping already lives in the
        // Activity binding and process session, so guest code should not see Shahboun extras.
        activity.intent = Intent(original)
    }
}
