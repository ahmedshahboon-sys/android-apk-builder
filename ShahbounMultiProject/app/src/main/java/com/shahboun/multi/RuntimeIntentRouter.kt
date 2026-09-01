package com.shahboun.multi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

internal const val EXTRA_RUNTIME_ORIGINAL_INTENT = "shahboun.runtime.original_intent"

/** Routes intents owned by the current guest back through the declared host stub. */
object RuntimeIntentRouter {
    fun wrap(context: Context, session: RuntimeSession, original: Intent): Intent {
        if (original.hasExtra(EXTRA_RUNTIME_PACKAGE)) return original
        val pkg = session.runtimePackage
        val target = resolveGuestActivity(context, pkg, original) ?: return original
        val hostPackage = BuildConfig.APPLICATION_ID
        val stub = RuntimeProcessPool.activityStub(pkg.packageName, pkg.slot)
        val wrapped = Intent(original).apply {
            component = ComponentName(hostPackage, stub.name)
            `package` = hostPackage
            putExtra(EXTRA_RUNTIME_PACKAGE, pkg.packageName)
            putExtra(EXTRA_RUNTIME_SLOT, pkg.slot)
            putExtra(EXTRA_RUNTIME_ACTIVITY, target)
            putExtra(EXTRA_RUNTIME_ORIGINAL_INTENT, normalizePublicIntent(pkg, target, original))
        }
        RuntimeDiagnostics.log(
            "ROUTE",
            "activity ${pkg.packageName}/${pkg.slot} target=$target from=${original.component?.flattenToShortString() ?: original.action} via=${wrapped.component?.flattenToShortString()}"
        )
        return wrapped
    }

    fun launchIntent(context: Context, session: RuntimeSession): Intent {
        val pkg = session.runtimePackage
        val original = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg.packageName, pkg.launchAlias ?: pkg.launchActivity)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return wrap(context, session, original).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    @Suppress("DEPRECATION")
    fun originalIntent(wrapper: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        wrapper.getParcelableExtra(EXTRA_RUNTIME_ORIGINAL_INTENT, Intent::class.java)
    } else wrapper.getParcelableExtra(EXTRA_RUNTIME_ORIGINAL_INTENT)

    private fun resolveGuestActivity(context: Context, pkg: RuntimePackage, intent: Intent): String? {
        intent.component?.let { component ->
            val className = component.className
            // Android-created guest Activities may still expose the host package through their
            // framework Context. Intent(this, GuestActivity::class.java) therefore becomes
            // hostPackage/guestClass. Treat it as guest-owned when the class is in the snapshot.
            if (pkg.ownsActivity(className) &&
                (component.packageName == pkg.packageName || component.packageName == BuildConfig.APPLICATION_ID)
            ) return className
            if (component.packageName != pkg.packageName) return null
            return className.takeIf(pkg::ownsActivity)
        }

        val requestedPackage = intent.`package`
        if (requestedPackage != null &&
            requestedPackage != pkg.packageName &&
            requestedPackage != BuildConfig.APPLICATION_ID
        ) return null

        val probe = Intent(intent).apply {
            `package` = pkg.packageName
            component = null
        }
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.resolveActivity(probe, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.resolveActivity(probe, 0)
        }
        val name = info?.activityInfo?.takeIf { it.packageName == pkg.packageName }?.name ?: return null
        return name.takeIf(pkg::ownsActivity)
    }

    private fun normalizePublicIntent(pkg: RuntimePackage, target: String, source: Intent): Intent =
        Intent(source).apply {
            component = ComponentName(pkg.packageName, target)
            if (`package` == BuildConfig.APPLICATION_ID) `package` = pkg.packageName
        }
}
