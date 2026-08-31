package com.shahboun.multi

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Clone-aware PackageManager bridge. */
object RuntimePackageManagerBridge {
    @Volatile private var installed = false

    fun install(context: Context): Result<Unit> = runCatching {
        if (installed) return@runCatching
        val pm = context.packageManager
        val pmField = findField(pm.javaClass, "mPM") ?: error("ApplicationPackageManager.mPM غير متاح")
        pmField.isAccessible = true
        val delegate = pmField.get(pm) ?: error("IPackageManager غير متاح")
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) {
            installed = true
            return@runCatching
        }
        val interfaces = collectInterfaces(delegate.javaClass)
        require(interfaces.isNotEmpty()) { "واجهة IPackageManager غير متاحة" }
        val proxy = Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(context.applicationContext, delegate))
        pmField.set(pm, proxy)
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val staticField = activityThread.getDeclaredField("sPackageManager").apply { isAccessible = true }
            if (staticField.get(null) === delegate) staticField.set(null, proxy)
        }.onFailure { RuntimeDiagnostics.log("PM", "global package manager field unavailable: ${it.javaClass.simpleName}") }
        installed = true
        RuntimeDiagnostics.log("PM", "clone-aware PackageManager bridge installed")
    }

    private class Handler(private val context: Context, private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args)
            val session = RuntimeExecutionScope.current() ?: return invokeDelegate(method, args)
            val pkg = session.runtimePackage
            val values = args ?: emptyArray()
            val packageMentioned = values.any { it == pkg.packageName }
            val uidMentioned = values.any { it is Int && it == Process.myUid() }

            return when (method.name) {
                "getApplicationInfo" -> if (packageMentioned) applicationInfo(session, method, args) else invokeDelegate(method, args)
                "getPackageInfo" -> if (packageMentioned) packageInfo(session, method, args) else invokeDelegate(method, args)
                "getPackageUid" -> if (packageMentioned) Process.myUid() else invokeDelegate(method, args)
                "getPackagesForUid" -> if (uidMentioned) arrayOf(pkg.packageName) else invokeDelegate(method, args)
                "getNameForUid", "getNameForUidSdkSandbox" -> if (uidMentioned) pkg.packageName else invokeDelegate(method, args)
                "checkPermission" -> if (packageMentioned) checkGuestPermission(values) else invokeDelegate(method, args)
                "checkUidPermission" -> if (uidMentioned) checkGuestPermission(values) else invokeDelegate(method, args)
                else -> invokeDelegate(method, args)
            }
        }

        private fun checkGuestPermission(args: Array<out Any?>): Int {
            val permission = args.firstOrNull { it is String && it != RuntimeExecutionScope.current()?.runtimePackage?.packageName } as? String
                ?: return PackageManager.PERMISSION_DENIED
            return context.checkSelfPermission(permission)
        }

        private fun packageInfo(session: RuntimeSession, method: Method, args: Array<out Any?>?): PackageInfo {
            val original = runCatching { invokeDelegate(method, args) as? PackageInfo }.getOrNull() ?: PackageInfo()
            original.packageName = session.runtimePackage.packageName
            original.applicationInfo = applicationInfoFromOriginal(session, original.applicationInfo)
            @Suppress("DEPRECATION") original.versionCode = session.runtimePackage.versionCode.toInt()
            runCatching {
                PackageInfo::class.java.getMethod("setLongVersionCode", Long::class.javaPrimitiveType).invoke(original, session.runtimePackage.versionCode)
            }
            return original
        }

        private fun applicationInfo(session: RuntimeSession, method: Method, args: Array<out Any?>?): ApplicationInfo {
            val original = runCatching { invokeDelegate(method, args) as? ApplicationInfo }.getOrNull()
            return applicationInfoFromOriginal(session, original)
        }

        private fun applicationInfoFromOriginal(session: RuntimeSession, original: ApplicationInfo?): ApplicationInfo {
            val pkg = session.runtimePackage
            val slotDir = (context as? MultiApplication)?.engine?.runtimeSlotDir(pkg.packageName, pkg.slot)
                ?: java.io.File(context.filesDir, "clone_engine")
            fun dir(name: String) = java.io.File(slotDir, name).apply { if (!exists()) mkdirs() }
            return (original?.let(::ApplicationInfo) ?: ApplicationInfo()).apply {
                packageName = pkg.packageName
                className = pkg.applicationClass
                uid = Process.myUid()
                sourceDir = pkg.baseApk.absolutePath
                publicSourceDir = pkg.baseApk.absolutePath
                splitSourceDirs = pkg.splitApks.map { it.absolutePath }.toTypedArray()
                splitPublicSourceDirs = splitSourceDirs
                if (android.os.Build.VERSION.SDK_INT >= 26) splitNames = pkg.splitNames.toTypedArray()
                dataDir = dir("data").absolutePath
                deviceProtectedDataDir = dir("device_data").absolutePath
                nativeLibraryDir = dir("native").absolutePath
                theme = if (pkg.appTheme != 0) pkg.appTheme else theme
                targetSdkVersion = pkg.targetSdk
                if (android.os.Build.VERSION.SDK_INT >= 24) minSdkVersion = pkg.minSdk
                flags = pkg.appFlags
            }
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? = try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
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

    private fun collectInterfaces(type: Class<*>): Array<Class<*>> {
        val all = LinkedHashSet<Class<*>>()
        var current: Class<*>? = type
        while (current != null) {
            all.addAll(current.interfaces)
            current = current.superclass
        }
        return all.toTypedArray()
    }
}
