package com.shahboun.multi

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Binds a clone RuntimeSession into Android's own ActivityThread/LoadedApk model before launch.
 */
object RuntimeLoadedApkBridge {
    private val bound = ConcurrentHashMap<String, Any>()

    data class Binding(val loadedApk: Any, val applicationInfo: ApplicationInfo)

    fun bind(context: Context, session: RuntimeSession): Result<Binding> = runCatching {
        val pkg = session.runtimePackage
        val key = key(pkg.packageName, pkg.slot)
        val appInfo = buildApplicationInfo(context, session)
        val existing = bound[key]
        if (existing != null) {
            patchLoadedApk(existing, session, appInfo)
            return@runCatching Binding(existing, appInfo)
        }

        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            ?: error("ActivityThread غير متاح")
        val loadedApk = obtainLoadedApk(thread, appInfo)
        patchLoadedApk(loadedApk, session, appInfo)
        registerPackage(thread, pkg.packageName, loadedApk)
        bound[key] = loadedApk
        RuntimeDiagnostics.log(
            "LOADEDAPK",
            "bound ${pkg.packageName}/${pkg.slot} classLoader=${session.classLoader.javaClass.simpleName} resources=${System.identityHashCode(session.resources)} process=${currentProcessName()}"
        )
        Binding(loadedApk, appInfo)
    }

    fun release(session: RuntimeSession) {
        val pkg = session.runtimePackage
        val loadedApk = bound.remove(key(pkg.packageName, pkg.slot)) ?: return
        RuntimeCompatibility.findField(loadedApk.javaClass, "mApplication")?.let {
            RuntimeCompatibility.write(it, loadedApk, null)
        }
        RuntimeDiagnostics.log("LOADEDAPK", "released ${pkg.packageName}/${pkg.slot} process=${currentProcessName()}")
    }

    fun activityInfo(context: Context, session: RuntimeSession, requested: String): ActivityInfo {
        val pkg = session.runtimePackage
        val resolved = pkg.resolveActivity(requested)
        val original = runCatching {
            val component = ComponentName(pkg.packageName, resolved)
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getActivityInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
            }
        }.getOrNull()
        val binding = bind(context, session).getOrThrow()
        return (original?.let(::ActivityInfo) ?: ActivityInfo()).apply {
            name = resolved
            packageName = pkg.packageName
            processName = currentProcessName()
            applicationInfo = binding.applicationInfo
            val t = pkg.activityTheme(requested).takeIf { it != 0 }
                ?: pkg.activityTheme(resolved).takeIf { it != 0 }
                ?: pkg.launchActivityTheme.takeIf { it != 0 }
                ?: pkg.appTheme
            if (t != 0) theme = t
            exported = pkg.activities.firstOrNull {
                it.name == requested || it.name == resolved || it.targetActivity == resolved
            }?.exported ?: exported
            targetActivity = null
        }
    }

    fun buildApplicationInfo(context: Context, session: RuntimeSession): ApplicationInfo {
        val pkg = session.runtimePackage
        val original = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getApplicationInfo(
                    pkg.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(pkg.packageName, PackageManager.GET_META_DATA)
            }
        }.getOrNull()
        val slotDir = (context.applicationContext as? MultiApplication)?.engine?.runtimeSlotDir(pkg.packageName, pkg.slot)
            ?: MultiApplication.current?.engine?.runtimeSlotDir(pkg.packageName, pkg.slot)
            ?: File(context.filesDir, "clone_engine")
        fun dir(name: String) = File(slotDir, name).apply { if (!exists()) mkdirs() }
        return (original?.let(::ApplicationInfo) ?: ApplicationInfo()).apply {
            packageName = pkg.packageName
            className = pkg.applicationClass
            uid = Process.myUid()
            sourceDir = pkg.baseApk.absolutePath
            publicSourceDir = pkg.baseApk.absolutePath
            splitSourceDirs = pkg.splitApks.map { it.absolutePath }.toTypedArray()
            splitPublicSourceDirs = splitSourceDirs
            if (Build.VERSION.SDK_INT >= 26) splitNames = pkg.splitNames.toTypedArray()
            dataDir = dir("data").absolutePath
            deviceProtectedDataDir = dir("device_data").absolutePath
            nativeLibraryDir = dir("native").absolutePath
            processName = currentProcessName()
            theme = pkg.appTheme
            targetSdkVersion = pkg.targetSdk
            if (Build.VERSION.SDK_INT >= 24) minSdkVersion = pkg.minSdk
            flags = pkg.appFlags or ApplicationInfo.FLAG_HAS_CODE
        }
    }

    private fun obtainLoadedApk(activityThread: Any, appInfo: ApplicationInfo): Any {
        val methods = activityThread.javaClass.methods.toList() + activityThread.javaClass.declaredMethods.toList()
        val candidates = methods.distinctBy { signature(it) }.filter {
            it.name == "getPackageInfoNoCheck" && it.parameterTypes.firstOrNull() == ApplicationInfo::class.java
        }.sortedBy { it.parameterCount }
        for (method in candidates) {
            val args = buildArgs(activityThread, method, appInfo) ?: continue
            val result = runCatching {
                method.isAccessible = true
                method.invoke(activityThread, *args)
            }.getOrNull()
            if (result != null) return result
        }
        error("ActivityThread.getPackageInfoNoCheck غير متاح")
    }

    private fun buildArgs(activityThread: Any, method: Method, appInfo: ApplicationInfo): Array<Any?>? {
        val out = arrayOfNulls<Any?>(method.parameterCount)
        method.parameterTypes.forEachIndexed { index, type ->
            out[index] = when {
                index == 0 && type == ApplicationInfo::class.java -> appInfo
                type.name.contains("CompatibilityInfo") -> compatibilityInfo(activityThread, type)
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                !type.isPrimitive -> null
                else -> return null
            }
        }
        return out
    }

    private fun compatibilityInfo(activityThread: Any, expected: Class<*>): Any? {
        RuntimeCompatibility.allFields(activityThread.javaClass).forEach { field ->
            if (expected.isAssignableFrom(field.type)) {
                runCatching { field.get(activityThread) }.getOrNull()?.let { return it }
            }
        }
        return runCatching {
            val field = expected.getDeclaredField("DEFAULT_COMPATIBILITY_INFO").apply { isAccessible = true }
            field.get(null)
        }.getOrNull()
    }

    private fun patchLoadedApk(loadedApk: Any, session: RuntimeSession, appInfo: ApplicationInfo) {
        val pkg = session.runtimePackage
        val setter = (loadedApk.javaClass.declaredMethods.toList() + loadedApk.javaClass.methods.toList())
            .firstOrNull {
                it.name == "setApplicationInfo" &&
                    it.parameterTypes.contentEquals(arrayOf(ApplicationInfo::class.java))
            }
        val appInfoApplied = runCatching {
            requireNotNull(setter) { "LoadedApk.setApplicationInfo غير متاح" }
            setter.isAccessible = true
            setter.invoke(loadedApk, appInfo)
            true
        }.onFailure {
            RuntimeDiagnostics.log(
                "LOADEDAPK",
                "setApplicationInfo fallback ${pkg.packageName}/${pkg.slot}: ${it.javaClass.simpleName}: ${it.message}"
            )
        }.getOrDefault(false)

        if (!appInfoApplied) {
            writeField(loadedApk, session, arrayOf("mApplicationInfo"), appInfo)
            writeField(loadedApk, session, arrayOf("mPackageName"), appInfo.packageName)
            writeField(loadedApk, session, arrayOf("mAppDir"), appInfo.sourceDir)
            writeField(loadedApk, session, arrayOf("mResDir"), appInfo.publicSourceDir ?: appInfo.sourceDir)
            writeField(loadedApk, session, arrayOf("mSplitNames"), appInfo.splitNames)
            writeField(loadedApk, session, arrayOf("mSplitAppDirs"), appInfo.splitSourceDirs)
            writeField(
                loadedApk,
                session,
                arrayOf("mSplitResDirs"),
                appInfo.splitPublicSourceDirs ?: appInfo.splitSourceDirs
            )
            writeField(loadedApk, session, arrayOf("mDataDir"), appInfo.dataDir)
            writeField(loadedApk, session, arrayOf("mDataDirFile"), appInfo.dataDir?.let { File(it) })
            writeField(
                loadedApk,
                session,
                arrayOf("mDeviceProtectedDataDirFile"),
                appInfo.deviceProtectedDataDir?.let { File(it) }
            )
            // ApplicationInfo does not expose credentialProtectedDataDir in all compile SDK surfaces.
            // Our clone credential-protected storage is the regular isolated dataDir.
            writeField(
                loadedApk,
                session,
                arrayOf("mCredentialProtectedDataDirFile"),
                appInfo.dataDir?.let { File(it) }
            )
            writeField(loadedApk, session, arrayOf("mLibDir"), appInfo.nativeLibraryDir)
        }

        writeField(loadedApk, session, arrayOf("mClassLoader"), session.classLoader)
        writeField(loadedApk, session, arrayOf("mResources"), session.resources)
        writeField(loadedApk, session, arrayOf("mApplication"), session.guestApplication)
        writeField(loadedApk, session, arrayOf("mSecurityViolation"), false)

        RuntimeDiagnostics.log(
            "LOADEDAPK",
            "patched ${pkg.packageName}/${pkg.slot} appInfoPath=${if (appInfoApplied) "framework" else "field-fallback"} data=${appInfo.dataDir}"
        )
    }

    private fun writeField(
        loadedApk: Any,
        session: RuntimeSession,
        names: Array<out String>,
        value: Any?
    ) {
        RuntimeCompatibility.findField(loadedApk.javaClass, *names)?.let { field ->
            if (!RuntimeCompatibility.write(field, loadedApk, value)) {
                RuntimeDiagnostics.log(
                    "LOADEDAPK",
                    "field write failed ${field.name} ${session.runtimePackage.packageName}/${session.runtimePackage.slot}"
                )
            }
        }
    }

    private fun registerPackage(activityThread: Any, packageName: String, loadedApk: Any) {
        val packagesField = RuntimeCompatibility.findField(activityThread.javaClass, "mPackages") ?: return
        val packages = runCatching {
            @Suppress("UNCHECKED_CAST")
            packagesField.get(activityThread) as? MutableMap<Any?, Any?>
        }.getOrNull() ?: return
        synchronized(packages) {
            packages[packageName] = WeakReference(loadedApk)
        }
    }

    private fun key(packageName: String, slot: Int) = "$packageName#$slot"
    private fun signature(method: Method) = method.name + method.parameterTypes.joinToString(
        prefix = "(",
        postfix = ")"
    ) { it.name }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID
}
