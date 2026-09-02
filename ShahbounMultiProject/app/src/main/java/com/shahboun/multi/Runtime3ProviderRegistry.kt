package com.shahboun.multi

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build

/**
 * Installs guest ContentProviders into ActivityThread's local provider registry.
 *
 * This keeps Android's real ApplicationContentResolver and ContentProviderClient path intact while
 * avoiding publication of guest authorities to system_server. The provider is local to the assigned
 * clone process and is instantiated with the guest class loader / LoadedApk already bound by Runtime 3.
 */
object Runtime3ProviderRegistry {
    fun install(context: Context, session: RuntimeSession): Result<Int> = runCatching {
        check(Build.VERSION.SDK_INT >= 29) { "framework provider registry requires Android 10+" }
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            ?: error("ActivityThread unavailable")

        val installMethod = threadClass.declaredMethods.firstOrNull { method ->
            method.name == "installProvider" &&
                method.parameterTypes.size == 6 &&
                Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                ProviderInfo::class.java.isAssignableFrom(method.parameterTypes[2]) &&
                method.parameterTypes.sliceArray(3..5).all { it == Boolean::class.javaPrimitiveType }
        }?.apply { isAccessible = true }
            ?: error("ActivityThread.installProvider(Context, holder, ProviderInfo, boolean, boolean, boolean) unavailable")

        val pkg = session.runtimePackage
        val guestAppInfo = context.applicationInfo
        var installed = 0
        pkg.providers.forEach { snapshot ->
            val component = ComponentName(pkg.packageName, snapshot.name)
            val original = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.packageManager.getProviderInfo(
                        component,
                        PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getProviderInfo(component, PackageManager.GET_META_DATA)
                }
            }.getOrNull()

            val info = (original?.let(::ProviderInfo) ?: ProviderInfo()).apply {
                name = snapshot.name
                packageName = pkg.packageName
                authority = snapshot.authority?.takeIf { it.isNotBlank() } ?: authority
                exported = snapshot.exported
                grantUriPermissions = snapshot.grantUriPermissions
                applicationInfo = guestAppInfo
                processName = pkg.packageName
                enabled = true
            }
            val authority = info.authority?.takeIf { it.isNotBlank() }
            if (authority == null) {
                RuntimeDiagnostics.log("PROVIDER3", "skip ${snapshot.name}: no authority")
                return@forEach
            }

            val holder = installMethod.invoke(
                thread,
                context,
                null,
                info,
                false, // noisy
                true,  // noReleaseNeeded: local provider lives for clone process lifetime
                true   // stable
            ) ?: error("framework refused provider ${snapshot.name} ($authority)")
            installed++
            RuntimeDiagnostics.log(
                "PROVIDER3",
                "framework-local installed ${pkg.packageName}/${pkg.slot} ${snapshot.name} authority=$authority metadata=${info.metaData?.size() ?: 0} holder=${holder.javaClass.name}"
            )
        }
        installed
    }
}
