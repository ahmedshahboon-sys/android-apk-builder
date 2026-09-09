package com.shahboun.multi

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import java.lang.reflect.InvocationTargetException

/**
 * Installs guest ContentProviders into ActivityThread's local provider registry.
 *
 * Android 16 can reject individual reflective installProvider calls even when the method exists.
 * A rejected provider must not abort the entire clone launch: record the real underlying cause,
 * skip only that provider, and let the guest continue so the failing authority can be diagnosed
 * independently from Activity launch/resources.
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
        var skipped = 0
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
                RuntimeDiagnostics.log("PROVIDER3", "skip ${snapshot.name}: no authority clone=${pkg.packageName}/${pkg.slot}")
                return@forEach
            }

            val attempt = runCatching {
                installMethod.invoke(
                    thread,
                    context,
                    null,
                    info,
                    false,
                    true,
                    true
                ) ?: error("framework refused provider ${snapshot.name} ($authority)")
            }

            attempt.onSuccess { holder ->
                installed++
                RuntimeDiagnostics.log(
                    "PROVIDER3",
                    "framework-local installed ${pkg.packageName}/${pkg.slot} ${snapshot.name} authority=$authority metadata=${info.metaData?.size() ?: 0} holder=${holder.javaClass.name}"
                )
            }.onFailure { error ->
                skipped++
                val root = unwrap(error)
                RuntimeDiagnostics.log(
                    "PROVIDER3",
                    "install rejected clone=${pkg.packageName}/${pkg.slot} provider=${snapshot.name} authority=$authority root=${root.javaClass.name}: ${root.message.orEmpty().replace('\n',' ').take(260)}"
                )
                RuntimeIssueLedger.recordThrowable(root, pkg.packageName, pkg.slot)
            }
        }
        RuntimeDiagnostics.log("PROVIDER3", "registry result ${pkg.packageName}/${pkg.slot} installed=$installed skipped=$skipped total=${pkg.providers.size}")
        installed
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        val seen = HashSet<Throwable>()
        while (seen.add(current)) {
            val next = when (current) {
                is InvocationTargetException -> current.targetException ?: current.cause
                else -> current.cause
            } ?: break
            if (next === current) break
            current = next
        }
        return current
    }
}
