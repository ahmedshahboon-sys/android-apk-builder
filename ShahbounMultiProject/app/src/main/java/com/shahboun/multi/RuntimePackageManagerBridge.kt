package com.shahboun.multi

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
        val handle = RuntimeCompatibility.findService(pm, listOf("IPackageManager", "PackageManagerService"), listOf("mPM"))
            ?: error("ApplicationPackageManager.mPM غير متاح")
        val field = handle.field
        val delegate = handle.delegate
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) { installed = true; return@runCatching }
        val interfaces = RuntimeCompatibility.collectInterfaces(delegate.javaClass)
        require(interfaces.isNotEmpty()) { "واجهة IPackageManager غير متاحة" }
        val proxy = Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(context.applicationContext, delegate))
        require(RuntimeCompatibility.write(field, pm, proxy)) { "تعذر تثبيت PackageManager proxy" }
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val staticField = RuntimeCompatibility.findField(activityThread, "sPackageManager") ?: return@runCatching
            staticField.isAccessible = true
            if (staticField.get(null) === delegate) staticField.set(null, proxy)
        }.onFailure { RuntimeDiagnostics.log("PM", "global package manager field unavailable: ${it.javaClass.simpleName}") }
        installed = true
        RuntimeDiagnostics.log("PM", "clone-aware PackageManager bridge installed field=${field.name}")
    }

    private class Handler(private val context: Context, private val delegate: Any) : InvocationHandler {
        private val componentStates = java.util.concurrent.ConcurrentHashMap<String, Int>()

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args)
            val session = RuntimeSessionResolver.current(context, args) ?: return invokeDelegate(method, args)
            val pkg = session.runtimePackage
            val values = args ?: emptyArray()
            val packageMentioned = values.any { it == pkg.packageName }
            val guestComponents = values.filterIsInstance<ComponentName>().filter { it.packageName == pkg.packageName }
            val componentMentioned = guestComponents.isNotEmpty()
            val uidMentioned = values.any { it is Int && it == Process.myUid() }

            return when (method.name) {
                "getApplicationInfo" -> if (packageMentioned) applicationInfo(session, method, args) else invokeDelegate(method, args)
                "getPackageInfo", "getPackageInfoVersioned" -> if (packageMentioned) packageInfo(session, method, args) else invokeDelegate(method, args)
                "getActivityInfo", "getServiceInfo", "getReceiverInfo", "getProviderInfo" -> {
                    val result = invokeDelegate(method, args)
                    if (componentMentioned) patchComponentInfo(session, result) else result
                }
                "resolveIntent", "resolveService" -> patchResolveInfo(session, invokeDelegate(method, args))
                "queryIntentActivities", "queryIntentServices", "queryIntentReceivers", "queryIntentContentProviders" -> patchResolveCollection(session, invokeDelegate(method, args))
                "getPackageUid" -> if (packageMentioned) Process.myUid() else invokeDelegate(method, args)
                "getPackagesForUid" -> if (uidMentioned) arrayOf(pkg.packageName) else invokeDelegate(method, args)
                "getNameForUid", "getNameForUidSdkSandbox" -> if (uidMentioned) pkg.packageName else invokeDelegate(method, args)
                "checkPermission" -> if (packageMentioned) checkGuestPermission(values) else invokeDelegate(method, args)
                "checkUidPermission" -> if (uidMentioned) checkGuestPermission(values) else invokeDelegate(method, args)
                "setComponentEnabledSetting" -> if (componentMentioned) setVirtualComponentState(session, guestComponents.first(), values) else invokeDelegate(method, args)
                "setComponentEnabledSettings" -> if (containsGuestEnabledSetting(values, pkg.packageName)) setVirtualComponentStates(session, values, pkg.packageName) else invokeDelegate(method, args)
                "getComponentEnabledSetting" -> if (componentMentioned) virtualComponentState(session, guestComponents.first()) else invokeDelegate(method, args)
                else -> invokeDelegate(method, args)
            }
        }

        private fun stateKey(session: RuntimeSession, component: ComponentName): String =
            "${session.runtimePackage.packageName}#${session.runtimePackage.slot}:${component.flattenToString()}"

        private fun setVirtualComponentState(session: RuntimeSession, component: ComponentName, args: Array<out Any?>): Any? {
            val state = args.drop(1).firstOrNull { it is Int } as? Int ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            componentStates[stateKey(session, component)] = state
            context.getSharedPreferences("shahboun_component_states", Context.MODE_PRIVATE).edit()
                .putInt(stateKey(session, component), state).apply()
            RuntimeDiagnostics.log("PM", "virtual component state ${component.flattenToShortString()}=$state clone=${session.runtimePackage.packageName}/${session.runtimePackage.slot}")
            return null
        }

        private fun virtualComponentState(session: RuntimeSession, component: ComponentName): Int {
            val key = stateKey(session, component)
            componentStates[key]?.let { return it }
            return context.getSharedPreferences("shahboun_component_states", Context.MODE_PRIVATE)
                .getInt(key, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                .also { componentStates[key] = it }
        }

        private fun containsGuestEnabledSetting(args: Array<out Any?>, guest: String): Boolean =
            args.any { value ->
                (value as? List<*>)?.any { setting -> enabledSettingComponent(setting)?.packageName == guest } == true
            }

        private fun setVirtualComponentStates(session: RuntimeSession, args: Array<out Any?>, guest: String): Any? {
            args.forEach { value ->
                (value as? List<*>)?.forEach { setting ->
                    val component = enabledSettingComponent(setting) ?: return@forEach
                    if (component.packageName != guest) return@forEach
                    val state = runCatching { setting!!.javaClass.getMethod("getEnabledState").invoke(setting) as Int }.getOrElse {
                        runCatching { setting!!.javaClass.getMethod("getNewState").invoke(setting) as Int }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                    }
                    componentStates[stateKey(session, component)] = state
                    context.getSharedPreferences("shahboun_component_states", Context.MODE_PRIVATE).edit()
                        .putInt(stateKey(session, component), state).apply()
                    RuntimeDiagnostics.log("PM", "virtual component state ${component.flattenToShortString()}=$state batch clone=${session.runtimePackage.packageName}/${session.runtimePackage.slot}")
                }
            }
            return null
        }

        private fun enabledSettingComponent(setting: Any?): ComponentName? {
            if (setting == null) return null
            return runCatching { setting.javaClass.getMethod("getComponentName").invoke(setting) as? ComponentName }.getOrNull()
                ?: runCatching { RuntimeCompatibility.findField(setting.javaClass, "mComponentName", "componentName")?.let { f -> f.isAccessible = true; f.get(setting) as? ComponentName } }.getOrNull()
        }

        private fun checkGuestPermission(args: Array<out Any?>): Int {
            val guest = RuntimeSessionResolver.current(context, args)?.runtimePackage?.packageName
            val permission = args.firstOrNull { it is String && it != guest } as? String ?: return PackageManager.PERMISSION_DENIED
            return context.checkSelfPermission(permission)
        }

        @Suppress("DEPRECATION")
        private fun packageInfo(session: RuntimeSession, method: Method, args: Array<out Any?>?): PackageInfo {
            val original = runCatching { invokeDelegate(method, args) as? PackageInfo }.getOrNull() ?: PackageInfo()
            original.packageName = session.runtimePackage.packageName
            original.applicationInfo = applicationInfoFromOriginal(session, original.applicationInfo)
            original.activities?.forEach { it.applicationInfo = original.applicationInfo }
            original.services?.forEach { it.applicationInfo = original.applicationInfo }
            original.receivers?.forEach { it.applicationInfo = original.applicationInfo }
            original.providers?.forEach { it.applicationInfo = original.applicationInfo }
            original.versionCode = session.runtimePackage.versionCode.toInt()
            runCatching { PackageInfo::class.java.getMethod("setLongVersionCode", Long::class.javaPrimitiveType).invoke(original, session.runtimePackage.versionCode) }
            return original
        }

        private fun applicationInfo(session: RuntimeSession, method: Method, args: Array<out Any?>?): ApplicationInfo {
            val original = runCatching { invokeDelegate(method, args) as? ApplicationInfo }.getOrNull()
            return applicationInfoFromOriginal(session, original)
        }

        private fun patchComponentInfo(session: RuntimeSession, result: Any?): Any? {
            val component = result as? ComponentInfo ?: return result
            if (component.packageName != session.runtimePackage.packageName) return result
            component.applicationInfo = applicationInfoFromOriginal(session, component.applicationInfo)
            return component
        }

        private fun patchResolveInfo(session: RuntimeSession, result: Any?): Any? { val resolve = result as? ResolveInfo ?: return result; patchResolveEntry(session, resolve); return resolve }
        private fun patchResolveCollection(session: RuntimeSession, result: Any?): Any? { when (result) { is List<*> -> result.filterIsInstance<ResolveInfo>().forEach { patchResolveEntry(session, it) }; is Array<*> -> result.filterIsInstance<ResolveInfo>().forEach { patchResolveEntry(session, it) } }; return result }
        private fun patchResolveEntry(session: RuntimeSession, resolve: ResolveInfo) {
            resolve.activityInfo?.takeIf { it.packageName == session.runtimePackage.packageName }?.let { it.applicationInfo = applicationInfoFromOriginal(session, it.applicationInfo) }
            resolve.serviceInfo?.takeIf { it.packageName == session.runtimePackage.packageName }?.let { it.applicationInfo = applicationInfoFromOriginal(session, it.applicationInfo) }
            resolve.providerInfo?.takeIf { it.packageName == session.runtimePackage.packageName }?.let { it.applicationInfo = applicationInfoFromOriginal(session, it.applicationInfo) }
        }

        private fun applicationInfoFromOriginal(session: RuntimeSession, original: ApplicationInfo?): ApplicationInfo {
            val pkg = session.runtimePackage
            val slotDir = (context as? MultiApplication)?.engine?.runtimeSlotDir(pkg.packageName, pkg.slot)
                ?: Runtime3ProcessMetadata.slotDir(context, pkg.packageName, pkg.slot)
            fun dir(name: String) = java.io.File(slotDir, name).apply { if (!exists()) mkdirs() }
            return (original?.let(::ApplicationInfo) ?: ApplicationInfo()).apply {
                packageName = pkg.packageName; className = pkg.applicationClass; uid = Process.myUid(); sourceDir = pkg.baseApk.absolutePath; publicSourceDir = pkg.baseApk.absolutePath
                splitSourceDirs = pkg.splitApks.map { it.absolutePath }.toTypedArray(); splitPublicSourceDirs = splitSourceDirs
                if (android.os.Build.VERSION.SDK_INT >= 26) splitNames = pkg.splitNames.toTypedArray()
                dataDir = dir("data").absolutePath; deviceProtectedDataDir = dir("device_data").absolutePath; nativeLibraryDir = dir("native").absolutePath
                theme = if (pkg.appTheme != 0) pkg.appTheme else theme; targetSdkVersion = pkg.targetSdk; if (android.os.Build.VERSION.SDK_INT >= 24) minSdkVersion = pkg.minSdk; flags = pkg.appFlags
            }
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? = try { method.invoke(delegate, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw (e.targetException ?: e) }
    }
}
