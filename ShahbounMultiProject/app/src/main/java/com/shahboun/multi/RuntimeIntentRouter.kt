package com.shahboun.multi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

internal const val EXTRA_RUNTIME_ORIGINAL_INTENT = "shahboun.runtime.original_intent"

/** Routes only intents that target the current guest package back through our declared host stub. */
object RuntimeIntentRouter {
    fun wrap(context: Context, session: RuntimeSession, original: Intent): Intent {
        if (original.hasExtra(EXTRA_RUNTIME_PACKAGE)) return original
        val pkg = session.runtimePackage
        val target = resolveGuestActivity(context, pkg, original) ?: return original
        val hostPackage = BuildConfig.APPLICATION_ID
        val stub = RuntimeProcessPool.activityStub(pkg.packageName, pkg.slot)
        return Intent(original).apply {
            component = ComponentName(hostPackage, stub.name)
            `package` = hostPackage
            putExtra(EXTRA_RUNTIME_PACKAGE, pkg.packageName)
            putExtra(EXTRA_RUNTIME_SLOT, pkg.slot)
            putExtra(EXTRA_RUNTIME_ACTIVITY, target)
            putExtra(EXTRA_RUNTIME_ORIGINAL_INTENT, Intent(original))
        }
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
            if (component.packageName != pkg.packageName) return null
            return component.className.takeIf(pkg::ownsActivity)
        }
        if (intent.`package` != null && intent.`package` != pkg.packageName) return null
        val probe = Intent(intent).apply { `package` = pkg.packageName }
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.resolveActivity(probe, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.resolveActivity(probe, 0)
        }
        val name = info?.activityInfo?.takeIf { it.packageName == pkg.packageName }?.name ?: return null
        return name.takeIf(pkg::ownsActivity)
    }
}
