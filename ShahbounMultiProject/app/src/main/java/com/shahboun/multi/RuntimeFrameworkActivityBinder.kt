package com.shahboun.multi

import android.app.Activity
import android.content.Intent

/**
 * Connects every guest Activity back to its immutable clone process, including framework
 * recreations (configuration/theme/locale changes) where Android may restore the public Intent
 * without Shahboun's private wrapper extras.
 */
object RuntimeFrameworkActivityBinder {
    fun bind(activity: Activity): RuntimeSession? {
        val intent = activity.intent

        val explicitPackage = intent?.getStringExtra(EXTRA_RUNTIME_PACKAGE)
        val explicitSlot = intent?.getIntExtra(EXTRA_RUNTIME_SLOT, -1) ?: -1
        val explicitRequested = intent?.getStringExtra(EXTRA_RUNTIME_ACTIVITY)

        val owner = RuntimeExecutionScope.processOwner()
        val packageName = explicitPackage ?: owner?.first ?: return null
        val slot = if (explicitSlot >= 0) explicitSlot else owner?.second ?: return null
        val session = RuntimeRegistry.getOrNull(packageName, slot)
            ?: runCatching { MultiApplication.current?.engine?.sessionFor(packageName, slot) }.getOrNull()
            ?: return null

        val actual = activity.javaClass.name
        val requested = explicitRequested
        val resolved = when {
            !requested.isNullOrBlank() -> session.runtimePackage.resolveActivity(requested)
            session.runtimePackage.ownsActivity(actual) -> actual
            else -> return null
        }
        if (actual != resolved && !session.runtimePackage.ownsActivity(actual)) return null

        RuntimeActivityBindings.bind(activity, packageName, slot)
        val base = activity.baseContext
        val frameworkGuest = runCatching {
            base.classLoader === session.classLoader ||
                base.applicationInfo.sourceDir == session.runtimePackage.baseApk.absolutePath
        }.getOrDefault(false)
        RuntimeDiagnostics.log(
            "ACTIVITY2",
            "bound $packageName/$slot activity=$actual frameworkGuest=$frameworkGuest recreated=${explicitPackage == null} basePkg=${runCatching { base.packageName }.getOrNull()} loader=${runCatching { base.classLoader.javaClass.simpleName }.getOrNull()}"
        )
        return session
    }

    fun restorePublicIntent(activity: Activity) {
        val wrapper = activity.intent ?: return
        if (!wrapper.hasExtra(EXTRA_RUNTIME_PACKAGE)) return
        val original = RuntimeIntentRouter.originalIntent(wrapper) ?: return
        activity.intent = Intent(original)
    }
}
