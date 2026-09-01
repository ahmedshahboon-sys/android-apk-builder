package com.shahboun.multi

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater

/** Pins Activity/ContextThemeWrapper framework caches to the exact guest resource graph before guest onCreate(). */
object RuntimeActivityResourceFix {
    private const val WHATSAPP_LAYOUT_PROBE = 0x7f0e1351

    fun prepare(activity: Activity) {
        val session = RuntimeActivityBindings.sessionFor(activity) ?: return
        val pkg = session.runtimePackage
        val guestResources = session.resources
        val resolvedTheme = resolveActivityTheme(activity, session)
        val guestTheme = guestResources.newTheme().apply { if (resolvedTheme != 0) applyStyle(resolvedTheme, true) }

        // ActivityThread calls activity.setTheme() before Instrumentation.callActivityOnCreate().
        // That eagerly fills ContextThemeWrapper.mResources with the host Stub resources.
        // Pin the framework fields on ContextThemeWrapper itself; generic field-name scans can
        // hit app/OEM fields first and leave the real framework cache untouched.
        val frameworkPinned = pinContextThemeWrapper(activity, guestResources, guestTheme, resolvedTheme)
        val resourcePins = pinTypedFields(activity, Resources::class.java, guestResources)
        val themePins = pinTypedFields(activity, Resources.Theme::class.java, guestTheme)

        val inflater = runCatching {
            (activity.baseContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater)
                .cloneInContext(activity)
        }.getOrNull()
        if (inflater != null) {
            pinTypedFields(activity, LayoutInflater::class.java, inflater)
            pinTypedFields(activity.window, LayoutInflater::class.java, inflater)
            setNamedField(activity.window, "mLayoutInflater", inflater)
            setContextThemeWrapperInflater(activity, inflater)
        }

        RuntimeDiagnostics.log("RES", "activity graph pinned ${pkg.packageName}/${pkg.slot} framework=$frameworkPinned resources=$resourcePins themes=$themePins theme=0x${resolvedTheme.toString(16)}")
        logGraph("session", session.resources, pkg.packageName, pkg.slot)
        logGraph("activity", activity.resources, pkg.packageName, pkg.slot)
        logGraph("base", activity.baseContext.resources, pkg.packageName, pkg.slot)
        runCatching { logGraph("inflater", LayoutInflater.from(activity).context.resources, pkg.packageName, pkg.slot) }

        if (pkg.packageName == "com.whatsapp") {
            probeResource("session", session.resources, pkg.packageName, pkg.slot)
            probeResource("activity", activity.resources, pkg.packageName, pkg.slot)
            probeResource("base", activity.baseContext.resources, pkg.packageName, pkg.slot)
            runCatching { probeResource("inflater", LayoutInflater.from(activity).context.resources, pkg.packageName, pkg.slot) }
        }
    }

    private fun pinContextThemeWrapper(activity: Activity, resources: Resources, theme: Resources.Theme, themeResId: Int): Boolean {
        return runCatching {
            val type = ContextThemeWrapper::class.java
            type.getDeclaredField("mResources").apply { isAccessible = true }.set(activity, resources)
            type.getDeclaredField("mTheme").apply { isAccessible = true }.set(activity, theme)
            type.getDeclaredField("mThemeResource").apply { isAccessible = true }.setInt(activity, themeResId)
            // mInflater exists on current Android; keep this optional for OEM variance.
            runCatching { type.getDeclaredField("mInflater").apply { isAccessible = true }.set(activity, null) }
            true
        }.onFailure {
            RuntimeDiagnostics.log("RES", "ContextThemeWrapper pin failed ${activity.javaClass.name}: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    private fun setContextThemeWrapperInflater(activity: Activity, inflater: LayoutInflater) {
        runCatching {
            ContextThemeWrapper::class.java.getDeclaredField("mInflater").apply { isAccessible = true }.set(activity, inflater)
        }
    }

    private fun probeResource(label: String, resources: Resources, packageName: String, slot: Int) {
        runCatching {
            val value = TypedValue()
            resources.getValue(WHATSAPP_LAYOUT_PROBE, value, true)
            resources.getLayout(WHATSAPP_LAYOUT_PROBE).close()
            RuntimeDiagnostics.log("RES", "$label probe ok $packageName/$slot id=0x${WHATSAPP_LAYOUT_PROBE.toString(16)} res=${System.identityHashCode(resources)} impl=${resourcesImplId(resources)}")
        }.onFailure {
            RuntimeDiagnostics.log("RES", "$label probe missing $packageName/$slot id=0x${WHATSAPP_LAYOUT_PROBE.toString(16)} res=${System.identityHashCode(resources)} impl=${resourcesImplId(resources)} error=${it.javaClass.simpleName}")
        }
    }

    private fun logGraph(label: String, resources: Resources, packageName: String, slot: Int) {
        RuntimeDiagnostics.log("RES", "$label graph $packageName/$slot res=${System.identityHashCode(resources)} impl=${resourcesImplId(resources)} assets=${resources.assets}")
    }

    private fun resourcesImplId(resources: Resources): Int = runCatching {
        val field = findField(resources.javaClass, "mResourcesImpl") ?: return@runCatching -1
        field.isAccessible = true
        System.identityHashCode(field.get(resources))
    }.getOrDefault(-1)

    private fun resolveActivityTheme(activity: Activity, session: RuntimeSession): Int {
        val pkg = session.runtimePackage
        val name = activity.javaClass.name
        pkg.activityTheme(name).takeIf { it != 0 }?.let { return it }
        if (name == pkg.launchActivity && pkg.launchActivityTheme != 0) return pkg.launchActivityTheme
        val pm = MultiApplication.current?.packageManager ?: activity.packageManager
        val direct = runCatching { activityInfoTheme(pm, ComponentName(pkg.packageName, name)) }.getOrDefault(0)
        if (direct != 0) return direct
        val launcherTheme = runCatching {
            val launcher = pm.getLaunchIntentForPackage(pkg.packageName)?.component ?: return@runCatching 0
            activityInfoTheme(pm, launcher)
        }.getOrDefault(0)
        if (launcherTheme != 0) return launcherTheme
        val liveAppTheme = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg.packageName, PackageManager.ApplicationInfoFlags.of(0)).theme
            else @Suppress("DEPRECATION") pm.getApplicationInfo(pkg.packageName, 0).theme
        }.getOrDefault(0)
        return if (liveAppTheme != 0) liveAppTheme else pkg.appTheme
    }

    private fun activityInfoTheme(pm: PackageManager, component: ComponentName): Int = if (android.os.Build.VERSION.SDK_INT >= 33) {
        pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0)).theme
    } else {
        @Suppress("DEPRECATION") pm.getActivityInfo(component, 0).theme
    }

    private fun pinTypedFields(instance: Any, targetType: Class<*>, value: Any): Int {
        var count = 0
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (!targetType.isAssignableFrom(field.type)) return@forEach
                runCatching { field.isAccessible = true; field.set(instance, value); count++ }
            }
            current = current.superclass
        }
        return count
    }

    private fun setNamedField(instance: Any, name: String, value: Any?) {
        runCatching {
            val field = findField(instance.javaClass, name) ?: return
            field.isAccessible = true
            field.set(instance, value)
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
