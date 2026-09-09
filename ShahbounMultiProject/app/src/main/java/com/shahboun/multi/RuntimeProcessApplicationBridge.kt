package com.shahboun.multi

import android.app.Application

/**
 * Makes framework static application lookups resolve to the guest Application inside a dedicated
 * clone process. This runs after Application.attach() and before guest Application.onCreate().
 */
internal object RuntimeProcessApplicationBridge {
    fun bind(session: RuntimeSession): Boolean {
        val guest = session.applicationForContext() ?: return false
        if (!RuntimeGuestProcessIdentity.hostProcessName().startsWith("${BuildConfig.APPLICATION_ID}:clone")) return false
        return runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
                ?: error("ActivityThread unavailable")

            val initialField = RuntimeCompatibility.findField(threadClass, "mInitialApplication")
                ?: error("ActivityThread.mInitialApplication unavailable")
            initialField.isAccessible = true
            initialField.set(thread, guest)

            RuntimeCompatibility.findField(threadClass, "mAllApplications")?.let { field ->
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val applications = field.get(thread) as? MutableList<Application>
                if (applications != null && applications.none { it === guest }) applications.add(guest)
            }

            val current = threadClass.getDeclaredMethod("currentApplication").apply { isAccessible = true }.invoke(null)
            check(current === guest) { "ActivityThread.currentApplication still points to host" }
            RuntimeDiagnostics.log(
                "APP7",
                "framework currentApplication bound ${session.runtimePackage.packageName}/${session.runtimePackage.slot} class=${guest.javaClass.name}"
            )
            true
        }.onFailure {
            RuntimeDiagnostics.log(
                "APP7",
                "framework currentApplication bind failed ${session.runtimePackage.packageName}/${session.runtimePackage.slot}: ${it.javaClass.simpleName}: ${it.message}"
            )
        }.getOrDefault(false)
    }
}
