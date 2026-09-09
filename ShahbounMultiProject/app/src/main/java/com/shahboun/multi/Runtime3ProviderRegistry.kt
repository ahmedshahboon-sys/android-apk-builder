package com.shahboun.multi

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import java.lang.reflect.InvocationTargetException

/** Installs guest ContentProviders before Application.onCreate, matching Android bootstrap order. */
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
        // Framework-side identity must be backed by our installed host UID. Guest-facing Context
        // still exposes the logical package to provider code after attach.
        val frameworkAppInfo = RuntimeLoadedApkBridge.buildApplicationInfo(context, session)
        var installed = 0
        var optionalSkipped = 0
        val hardFailures = ArrayList<Throwable>()

        pkg.providers.forEach { snapshot ->
            val authority = snapshot.authority?.takeIf { it.isNotBlank() }
            if (authority == null) {
                optionalSkipped++
                RuntimeDiagnostics.log("PROVIDER7", "skip ${snapshot.name}: no authority clone=${pkg.packageName}/${pkg.slot}")
                return@forEach
            }

            if (runCatching { session.classLoader.loadClass(snapshot.name) }.isFailure) {
                optionalSkipped++
                RuntimeDiagnostics.log("PROVIDER7", "provider class absent from delivered splits clone=${pkg.packageName}/${pkg.slot} provider=${snapshot.name} authority=$authority")
                return@forEach
            }

            val component = ComponentName(pkg.packageName, snapshot.name)
            val original = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.packageManager.getProviderInfo(component, PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                } else {
                    @Suppress("DEPRECATION") context.packageManager.getProviderInfo(component, PackageManager.GET_META_DATA)
                }
            }.getOrNull()

            val info = (original?.let(::ProviderInfo) ?: ProviderInfo()).apply {
                name = snapshot.name
                packageName = pkg.packageName
                this.authority = authority
                exported = snapshot.exported
                grantUriPermissions = snapshot.grantUriPermissions
                applicationInfo = frameworkAppInfo
                processName = RuntimeGuestProcessIdentity.hostProcessName()
                enabled = true
            }

            runCatching {
                installMethod.invoke(thread, context, null, info, false, true, true)
                    ?: error("framework refused provider ${snapshot.name} ($authority)")
            }.onSuccess { holder ->
                installed++
                RuntimeDiagnostics.log("PROVIDER7", "installed ${pkg.packageName}/${pkg.slot} ${snapshot.name} authority=$authority holder=${holder.javaClass.name}")
            }.onFailure { error ->
                val root = unwrap(error)
                if (root is ClassNotFoundException || root.cause is ClassNotFoundException) {
                    optionalSkipped++
                    RuntimeDiagnostics.log("PROVIDER7", "provider unavailable ${snapshot.name} authority=$authority reason=ClassNotFoundException")
                } else {
                    hardFailures += root
                    RuntimeDiagnostics.log("PROVIDER7", "HARD FAIL clone=${pkg.packageName}/${pkg.slot} provider=${snapshot.name} authority=$authority root=${root.javaClass.name}: ${root.message.orEmpty().replace('\n',' ').take(320)}")
                    RuntimeIssueLedger.recordThrowable(root, pkg.packageName, pkg.slot)
                }
            }
        }

        RuntimeDiagnostics.log("PROVIDER7", "registry result ${pkg.packageName}/${pkg.slot} installed=$installed optionalSkipped=$optionalSkipped hardFailures=${hardFailures.size} total=${pkg.providers.size}")
        if (hardFailures.isNotEmpty()) {
            val first = hardFailures.first()
            val aggregate = IllegalStateException("Guest provider bootstrap failed (${hardFailures.size}) before Application.onCreate", first)
            hardFailures.drop(1).forEach(aggregate::addSuppressed)
            throw aggregate
        }
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
