package com.shahboun.multi

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextThemeWrapper
import android.content.pm.PackageManager
import android.view.LayoutInflater

/**
 * Activity.attach() runs before the clone context is substituted, so framework objects may cache
 * host Resources/Theme/LayoutInflater. Rebind those caches before guest onCreate().
 */
object RuntimeActivityResourceFix {
    fun prepare(activity: Activity) {
        val session = RuntimeActivityBindings.sessionFor(activity) ?: return
        val pkg = session.runtimePackage

        clearField(activity, ContextThemeWrapper::class.java, "mResources")
        clearField(activity, ContextThemeWrapper::class.java, "mTheme")
        clearField(activity, ContextThemeWrapper::class.java, "mInflater")

        val resolvedTheme = resolveActivityTheme(activity, session)
        if (resolvedTheme != 0) {
            runCatching { activity.setTheme(resolvedTheme) }
                .onSuccess {
                    RuntimeDiagnostics.log(
                        "RES",
                        "activity theme rebound ${pkg.packageName}/${pkg.slot} ${activity.javaClass.name} theme=0x${resolvedTheme.toString(16)}"
                    )
                }
                .onFailure {
                    RuntimeDiagnostics.log("RES", "activity theme rebind failed ${pkg.packageName}/${pkg.slot}: ${it.javaClass.simpleName}: ${it.message}")
                }
        }

        runCatching {
            val inflater = LayoutInflater.from(activity)
            val field = findField(activity.window.javaClass, "mLayoutInflater")
            if (field != null) {
                field.isAccessible = true
                field.set(activity.window, inflater)
            }
        }.onFailure {
            RuntimeDiagnostics.log("RES", "window inflater rebind unavailable ${pkg.packageName}/${pkg.slot}: ${it.javaClass.simpleName}")
        }

        runCatching {
            val resources = activity.resources
            RuntimeDiagnostics.log(
                "RES",
                "activity resource graph ready ${pkg.packageName}/${pkg.slot} class=${resources.javaClass.name} assets=${resources.assets}"
            )
        }.onFailure {
            RuntimeDiagnostics.log("RES", "activity resource graph validation failed ${pkg.packageName}/${pkg.slot}: ${it.stackTraceToString()}")
        }
    }

    private fun resolveActivityTheme(activity: Activity, session: RuntimeSession): Int {
        val pkg = session.runtimePackage
        val name = activity.javaClass.name
        if (name == pkg.launchActivity && pkg.launchActivityTheme != 0) return pkg.launchActivityTheme

        val liveTheme = runCatching {
            val pm = MultiApplication.current?.packageManager ?: activity.packageManager
            val component = ComponentName(pkg.packageName, name)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0)).theme
            } else {
                @Suppress("DEPRECATION")
                pm.getActivityInfo(component, 0).theme
            }
        }.getOrDefault(0)
        if (liveTheme != 0) return liveTheme
        return pkg.appTheme
    }

    private fun clearField(instance: Any, ownerHint: Class<*>, name: String) {
        runCatching {
            val field = findField(instance.javaClass, name) ?: findField(ownerHint, name) ?: return
            field.isAccessible = true
            field.set(instance, null)
        }.onFailure {
            val session = (instance as? Activity)?.let(RuntimeActivityBindings::sessionFor)
            RuntimeDiagnostics.log(
                "RES",
                "cache clear $name unavailable ${session?.runtimePackage?.packageName ?: "unknown"}: ${it.javaClass.simpleName}"
            )
        }
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }
}
