package com.shahboun.multi

import android.app.Activity

/**
 * Final gate before guest Activity.onCreate.
 *
 * Android 16 may arrive with a framework-patched guest base context already installed. In that case
 * RuntimeFrameworkActivityBinder can bind the Activity without RuntimeGuestContext.attachIfNeeded()
 * being called. Heavy apps such as Instagram expect their Application singleton and providers to be
 * completely initialized before the first Activity callback, so this gate enforces that invariant
 * regardless of which launch path Android chose.
 */
internal object RuntimeActivityBootstrap {
    fun ensure(activity: Activity, session: RuntimeSession?) {
        val resolved = session ?: RuntimeActivityBindings.sessionFor(activity) ?: return
        val hostApp = MultiApplication.current ?: return
        val pkg = resolved.runtimePackage
        val slotDir = hostApp.engine.runtimeSlotDir(pkg.packageName, pkg.slot)

        val guestApplication = resolved.ensureGuestApplication(hostApp, slotDir)
        val currentApplication = runCatching { activity.application }.getOrNull()
        if (currentApplication !== guestApplication) {
            runCatching {
                Activity::class.java.getDeclaredField("mApplication").apply { isAccessible = true }.set(activity, guestApplication)
            }.onFailure {
                RuntimeDiagnostics.log(
                    "BOOTSTRAP7",
                    "activity application patch failed ${pkg.packageName}/${pkg.slot} activity=${activity.javaClass.name}: ${it.javaClass.simpleName}: ${it.message}"
                )
                throw it
            }
        }
        RuntimeActivityBindings.bind(activity, pkg.packageName, pkg.slot)
        check(resolved.isApplicationReady()) {
            "Guest Application not ready before Activity.onCreate: ${pkg.packageName}/${pkg.slot} state=${resolved.bootstrapState}"
        }
        RuntimeDiagnostics.log(
            "BOOTSTRAP7",
            "activity gate ready ${pkg.packageName}/${pkg.slot} activity=${activity.javaClass.name} app=${guestApplication.javaClass.name} state=${resolved.bootstrapState}"
        )
    }
}
