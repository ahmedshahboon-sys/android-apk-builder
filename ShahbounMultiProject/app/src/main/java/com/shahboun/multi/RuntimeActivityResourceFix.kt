package com.shahboun.multi

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Resources
import android.util.TypedValue
import android.view.LayoutInflater

/**
 * Activity.attach() runs before the clone context is substituted, so framework objects may cache
 * host Resources/Theme/LayoutInflater. Force every cache to the clone graph before guest onCreate().
 */
object RuntimeActivityResourceFix {
    private const val WHATSAPP_LAYOUT_PROBE = 0x7f0e1351

    fun prepare(activity: Activity) {
        val session = RuntimeActivityBindings.sessionFor(activity) ?: return
        val pkg = session.runtimePackage
        val guestResources = session.resources

        // Do not merely clear these fields: Android may recreate them from the host Stub context.
        // Pin them to the already validated clone resource graph instead.
        setField(activity, "mResources", guestResources)
        setField(activity, "mTheme", null)
        setField(activity, "mInflater", null)

        val resolvedTheme = resolveActivityTheme(activity, session)
        val guestTheme = guestResources.newTheme().apply {
            if (resolvedTheme != 0) applyStyle(resolvedTheme, true)
        }
        setField(activity, "mTheme", guestTheme)

        if (resolvedTheme != 0) {
            RuntimeDiagnostics.log(
                "RES",
                "activity theme pinned ${pkg.packageName}/${pkg.slot} ${activity.javaClass.name} theme=0x${resolvedTheme.toString(16)}"
            )
        }

        runCatching {
            val inflater = (activity.baseContext.getSystemService(android.content.Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater)
                .cloneInContext(activity)
            setField(activity, "mInflater", inflater)
            findField(activity.window.javaClass, "mLayoutInflater")?.let { field ->
                field.isAccessible = true
                field.set(activity.window, inflater)
            }
        }.onFailure {
            RuntimeDiagnostics.log("RES", "inflater pin failed ${pkg.packageName}/${pkg.slot}: ${it.javaClass.simpleName}: ${it.message}")
        }

        logGraph("session", session.resources, pkg.packageName, pkg.slot)
        logGraph("activity", activity.resources, pkg.packageName, pkg.slot)
        logGraph("base", activity.baseContext.resources, pkg.packageName, pkg.slot)
        runCatching {
            val inflater = LayoutInflater.from(activity)
            logGraph("inflater", inflater.context.resources, pkg.packageName, pkg.slot)
        }

        if (pkg.packageName == "com.whatsapp") {
            probeResource("session", session.resources, pkg.packageName, pkg.slot)
            probeResource("activity", activity.resources, pkg.packageName, pkg.slot)
            probeResource("base", activity.baseContext.resources, pkg.packageName, pkg.slot)
            runCatching { probeResource("inflater", LayoutInflater.from(activity).context.resources, pkg.packageName, pkg.slot) }
        }
    }

    private fun probeResource(label: String, resources: Resources, packageName: String, slot: Int) {
        runCatching {
            val value = TypedValue()
            resources.getValue(WHATSAPP_LAYOUT_PROBE, value, true)
            resources.getLayout(WHATSAPP_LAYOUT_PROBE).close()
            RuntimeDiagnostics.log(
                "RES",
                "$label probe ok $packageName/$slot id=0x${WHATSAPP_LAYOUT_PROBE.toString(16)} res=${System.identityHashCode(resources)} impl=${resourcesImplId(resources)}"
            )
        }.onFailure {
            RuntimeDiagnostics.log(
                "RES",
                "$label probe missing $packageName/$slot id=0x${WHATSAPP_LAYOUT_PROBE.toString(16)} res=${System.identityHashCode(resources)} impl=${resourcesImplId(resources)} error=${it.javaClass.simpleName}"
            )
        }
    }

    private fun logGraph(label: String, resources: Resources, packageName: String, slot: Int) {
        RuntimeDiagnostics.log(
            "RES",
            "$label graph $packageName/$slot res=${System.identityHashCode(resources)} impl=${resourcesImplId(resources)} assets=${resources.assets}"
        )
    }

    private fun resourcesImplId(resources: Resources): Int {
        return runCatching {
            val field = findField(resources.javaClass, "mResourcesImpl") ?: return@runCatching -1
            field.isAccessible = true
            System.identityHashCode(field.get(resources))
        }.getOrDefault(-1)
    }

    private fun resolveActivityTheme(activity: Activity, session: RuntimeSession): Int {
        val pkg = session.runtimePackage
        val name = activity.javaClass.name
        val snapshotted = pkg.activityTheme(name)
        if (snapshotted != 0) return snapshotted
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

    private fun setField(instance: Any, name: String, value: Any?) {
        runCatching {
            val field = findField(instance.javaClass, name) ?: return
            field.isAccessible = true
            field.set(instance, value)
        }.onFailure {
            val session = (instance as? Activity)?.let(RuntimeActivityBindings::sessionFor)
            RuntimeDiagnostics.log(
                "RES",
                "field bind $name unavailable ${session?.runtimePackage?.packageName ?: "unknown"}: ${it.javaClass.simpleName}: ${it.message}"
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
