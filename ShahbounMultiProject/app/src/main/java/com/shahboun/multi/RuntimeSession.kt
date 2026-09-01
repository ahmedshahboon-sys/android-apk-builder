package com.shahboun.multi

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.TypedValue
import dalvik.system.DexClassLoader
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

class RuntimeSession(
    val runtimePackage: RuntimePackage,
    val classLoader: DexClassLoader,
    val resources: Resources,
    val resourcesLoader: ResourcesLoader?,
    private val loaderHostResources: Resources?,
    private val closeables: List<Closeable>
) : Closeable {
    @Volatile var guestApplication: Application? = null
        private set
    @Volatile var componentHost: RuntimeComponentHost? = null
        private set

    @Synchronized
    fun ensureGuestApplication(base: Context, slotDir: File): Application {
        guestApplication?.let { return it }
        val appClass = runtimePackage.applicationClass?.let { classLoader.loadClass(it) }
        val app = if (appClass != null) {
            require(Application::class.java.isAssignableFrom(appClass)) { "Application class غير صالح" }
            appClass.getDeclaredConstructor().newInstance() as Application
        } else Application()
        val guestContext = RuntimeGuestContext(base, this, slotDir)
        val attached = runCatching {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }
            attach.invoke(app, guestContext); true
        }.onFailure {
            RuntimeDiagnostics.log("RUNTIME", "guest Application.attach fallback ${runtimePackage.packageName}/${runtimePackage.slot}: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        if (!attached) {
            val baseField = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
            baseField.set(app, guestContext)
        }
        guestApplication = app
        RuntimeDiagnostics.log("RUNTIME", "guest Application attached ${runtimePackage.packageName}/${runtimePackage.slot} attached=$attached class=${app.javaClass.name}")
        val components = RuntimeComponentHost(base, this, slotDir)
        componentHost = components
        RuntimeDiagnostics.log("RUNTIME", "initializing guest providers ${runtimePackage.packageName}/${runtimePackage.slot}")
        RuntimeExecutionScope.withSession(this) { components.initializeProviders() }
        RuntimeDiagnostics.log("RUNTIME", "guest providers ready ${runtimePackage.packageName}/${runtimePackage.slot}")
        RuntimeDiagnostics.log("RUNTIME", "calling guest Application.onCreate ${runtimePackage.packageName}/${runtimePackage.slot}")
        RuntimeExecutionScope.withSession(this) { app.onCreate() }
        RuntimeDiagnostics.log("RUNTIME", "guest Application ready ${runtimePackage.packageName}/${runtimePackage.slot}")
        return app
    }

    fun attachLoaderTo(target: Resources): Boolean {
        val loader = resourcesLoader ?: return false
        return runCatching {
            target.addLoaders(loader)
            true
        }.onFailure {
            RuntimeDiagnostics.log("RES", "attach loader failed ${runtimePackage.packageName}/${runtimePackage.slot}: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    override fun close() {
        runCatching { componentHost?.close() }
        componentHost = null
        guestApplication = null
        val loader = resourcesLoader
        val host = loaderHostResources
        if (loader != null && host != null) runCatching { host.removeLoaders(loader) }
        closeables.asReversed().forEach { runCatching { it.close() } }
    }
}

private class GuestDexClassLoader(
    dexPath: String,
    optimizedDirectory: String?,
    private val guestNativeDir: File,
    nativeSearchPath: String,
    parent: ClassLoader
) : DexClassLoader(dexPath, optimizedDirectory, nativeSearchPath, parent) {
    private val parentFirstPrefixes = arrayOf(
        "java.", "javax.", "android.", "dalvik.", "sun.",
        "org.xml.", "org.w3c."
    )

    @Synchronized
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        findLoadedClass(name)?.let { return it }
        if (parentFirstPrefixes.any { name.startsWith(it) }) return super.loadClass(name, resolve)
        val loaded = runCatching { findClass(name) }.getOrNull() ?: super.loadClass(name, false)
        if (resolve) resolveClass(loaded)
        return loaded
    }

    override fun findLibrary(name: String): String? {
        val mapped = System.mapLibraryName(name)
        val direct = File(guestNativeDir, mapped)
        if (direct.isFile && direct.canRead()) {
            RuntimeDiagnostics.log("NATIVE", "resolved library name=$name file=${direct.name} size=${direct.length()}")
            return direct.absolutePath
        }
        val inherited = super.findLibrary(name)
        RuntimeDiagnostics.log(
            "NATIVE",
            "library lookup name=$name mapped=$mapped direct=${direct.exists()} inherited=${inherited ?: "missing"} dir=${guestNativeDir.absolutePath}"
        )
        return inherited
    }
}

class RuntimeSessionFactory(private val context: Context) {
    fun create(pkg: RuntimePackage, slotDir: File): RuntimeSession {
        val allApks = listOf(pkg.baseApk) + pkg.splitApks
        RuntimeCodeSecurity.prepareApks(allApks)
        val codeCache = File(slotDir, "code_cache").apply { require(exists() || mkdirs()) }
        val nativeDir = File(slotDir, "native").apply { if (exists()) deleteRecursively(); require(mkdirs()) }
        val nativeResult = NativeLibraryExtractor.extract(allApks, nativeDir)
        RuntimeDiagnostics.log(
            "NATIVE",
            "extract package=${pkg.packageName} abi=${nativeResult.abi ?: "none"} libraries=${nativeResult.files.size} " +
                "names=${nativeResult.files.joinToString { it.name }}"
        )
        RuntimeDiagnostics.log("DEX", "loading package=${pkg.packageName} slot=${pkg.slot} apks=${allApks.size} " + allApks.joinToString { "${it.name}:r=${it.canRead()}:w=${it.canWrite()}:size=${it.length()}" })

        val codeApks = allApks.filter(::containsDexCode)
        require(codeApks.isNotEmpty()) { "لا توجد ملفات DEX قابلة للتشغيل داخل حزمة التطبيق" }
        val dexPath = codeApks.joinToString(File.pathSeparator) { it.absolutePath }
        RuntimeDiagnostics.log("DEX", "code-bearing apks package=${pkg.packageName} count=${codeApks.size}/${allApks.size} names=${codeApks.joinToString { it.name }}")

        val hostLoader = context.classLoader
        val platformParent = hostLoader.parent ?: ClassLoader.getSystemClassLoader().parent ?: ClassLoader.getSystemClassLoader()
        val apkNativePaths = nativeResult.abi?.let { abi -> allApks.map { "${it.absolutePath}!/lib/$abi" } }.orEmpty()
        val nativeSearchPath = (listOf(nativeDir.absolutePath) + apkNativePaths).distinct().joinToString(File.pathSeparator)
        val loader = GuestDexClassLoader(dexPath, codeCache.absolutePath, nativeDir, nativeSearchPath, platformParent)
        RuntimeDiagnostics.log("NATIVE", "search-path package=${pkg.packageName} abi=${nativeResult.abi ?: "none"} entries=${1 + apkNativePaths.size}")
        RuntimeDiagnostics.log("DEX", "guest-first isolated classloader enabled package=${pkg.packageName} slot=${pkg.slot} parent=${platformParent.javaClass.name}")

        val effectivePkg = resolveLauncherTarget(pkg, loader)
        val closeables = mutableListOf<Closeable>()
        val splitPaths = effectivePkg.splitApks.map { it.absolutePath }.toTypedArray()
        val archiveInfo = ApplicationInfo().apply {
            packageName = effectivePkg.packageName
            sourceDir = effectivePkg.baseApk.absolutePath
            publicSourceDir = effectivePkg.baseApk.absolutePath
            splitSourceDirs = splitPaths
            splitPublicSourceDirs = splitPaths
            if (Build.VERSION.SDK_INT >= 26) splitNames = effectivePkg.splitNames.toTypedArray()
            dataDir = File(slotDir, "data").absolutePath
            nativeLibraryDir = nativeDir.absolutePath
            targetSdkVersion = effectivePkg.targetSdk
            if (Build.VERSION.SDK_INT >= 24) minSdkVersion = effectivePkg.minSdk
            flags = effectivePkg.appFlags
            theme = effectivePkg.appTheme
        }
        val resources = context.packageManager.getResourcesForApplication(archiveInfo)
        RuntimeDiagnostics.log(
            "RES",
            "archive resource graph attached package=${effectivePkg.packageName} apks=${allApks.size} splitNames=${effectivePkg.splitNames.joinToString()} assets=${resources.assets}"
        )

        var runtimeResourcesLoader: ResourcesLoader? = null
        var loaderHostResources: Resources? = null
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val resLoader = ResourcesLoader()
                allApks.forEach { apk ->
                    val provider = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        ResourcesProvider.loadFromApk(pfd)
                    }
                    resLoader.addProvider(provider)
                    closeables += provider
                }
                val hostResources = context.applicationContext.resources
                hostResources.addLoaders(resLoader)
                runtimeResourcesLoader = resLoader
                loaderHostResources = hostResources
                RuntimeDiagnostics.log("RES", "application loader installed ${effectivePkg.packageName}/${effectivePkg.slot} providers=${allApks.size} process=${Application.getProcessName()}")
            }.onFailure {
                RuntimeDiagnostics.log("RES", "application loader fallback ${effectivePkg.packageName}/${effectivePkg.slot}: ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        if (effectivePkg.packageName == "com.whatsapp") {
            val probe = 0x7f0e1351
            runCatching {
                val value = TypedValue()
                resources.getValue(probe, value, true)
                RuntimeDiagnostics.log("RES", "resource probe ok package=${effectivePkg.packageName} id=0x${probe.toString(16)} type=${value.type} data=0x${value.data.toString(16)}")
            }.onFailure {
                RuntimeDiagnostics.log("RES", "resource probe missing package=${effectivePkg.packageName} id=0x${probe.toString(16)} ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        val launcher = loader.loadClass(effectivePkg.launchActivity)
        require(android.app.Activity::class.java.isAssignableFrom(launcher)) { "شاشة تشغيل التطبيق ليست Activity صالحة" }
        effectivePkg.applicationClass?.let { name -> require(Application::class.java.isAssignableFrom(loader.loadClass(name))) { "Application class غير صالح" } }
        return RuntimeSession(effectivePkg, loader, resources, runtimeResourcesLoader, loaderHostResources, closeables)
    }

    private fun resolveLauncherTarget(pkg: RuntimePackage, loader: ClassLoader): RuntimePackage {
        if (runCatching { loader.loadClass(pkg.launchActivity) }.isSuccess) return pkg
        val resolved = runCatching {
            val pm = context.packageManager
            val launcher = pm.getLaunchIntentForPackage(pkg.packageName)?.component ?: return@runCatching null
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getActivityInfo(launcher, PackageManager.ComponentInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getActivityInfo(launcher, 0)
            }
            info.targetActivity?.takeIf { it.isNotBlank() } ?: info.name?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!resolved.isNullOrBlank() && runCatching { loader.loadClass(resolved) }.isSuccess) {
            RuntimeDiagnostics.log("DEX", "launcher alias resolved ${pkg.packageName}: ${pkg.launchActivity} -> $resolved")
            return pkg.copy(launchActivity = resolved)
        }
        return pkg
    }

    private fun containsDexCode(apk: File): Boolean = runCatching {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().any { entry ->
                !entry.isDirectory && entry.name.matches(Regex("classes(\\d*)?\\.dex"))
            }
        }
    }.getOrDefault(false)
}

private object RuntimeCodeSecurity {
    fun prepareApks(apks: List<File>) {
        require(apks.isNotEmpty()) { "لا توجد ملفات APK للتشغيل" }
        apks.forEach { apk ->
            require(apk.isFile && apk.length() > 0) { "ملف APK غير صالح: ${apk.name}" }
            runCatching { Os.chmod(apk.absolutePath, 0b100100100) }.recoverCatching { require(apk.setReadOnly()) { "تعذر حماية ملف APK: ${apk.name}" } }.getOrThrow()
            require(apk.canRead()) { "ملف APK غير قابل للقراءة: ${apk.name}" }
            require(!apk.canWrite()) { "ملف APK ما زال قابلاً للكتابة: ${apk.name}" }
        }
    }
}

private object NativeLibraryExtractor {
    data class Result(val abi: String?, val files: List<File>)

    fun extract(apks: List<File>, targetDir: File): Result {
        val supported = Build.SUPPORTED_ABIS.toList()
        val abi = supported.firstOrNull { candidate -> apks.any { containsAbi(it, candidate) } }
            ?: return Result(null, emptyList())
        val extracted = LinkedHashMap<String, File>()
        apks.forEach { apk -> extractAbi(apk, abi, targetDir, extracted) }
        extracted.values.forEach { file ->
            require(file.isFile && file.length() > 0 && file.canRead()) { "مكتبة Native غير صالحة: ${file.name}" }
            runCatching { Os.chmod(file.absolutePath, 0b101101101) }
        }
        return Result(abi, extracted.values.toList())
    }

    private fun containsAbi(apk: File, abi: String): Boolean = ZipFile(apk).use { zip ->
        zip.entries().asSequence().any { !it.isDirectory && it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
    }

    private fun extractAbi(apk: File, abi: String, targetDir: File, extracted: MutableMap<String, File>) {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }.forEach { entry ->
                val fileName = entry.name.substringAfterLast('/')
                require(fileName.isNotBlank() && !fileName.contains("..")) { "اسم مكتبة Native غير صالح" }
                val out = File(targetDir, fileName)
                val temp = File(targetDir, ".$fileName.tmp")
                zip.getInputStream(entry).use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } }
                require(temp.isFile && temp.length() > 0) { "فشل استخراج مكتبة Native" }
                if (out.exists()) out.delete()
                require(temp.renameTo(out)) { "فشل تثبيت مكتبة Native: $fileName" }
                extracted[fileName] = out
            }
        }
    }
}

object RuntimeRegistry {
    private val sessions = ConcurrentHashMap<String, RuntimeSession>()
    private fun key(packageName: String, slot: Int) = "$packageName#$slot"
    fun put(session: RuntimeSession) { sessions.put(key(session.runtimePackage.packageName, session.runtimePackage.slot), session)?.close() }
    fun getOrNull(packageName: String, slot: Int): RuntimeSession? = sessions[key(packageName, slot)]
    fun get(packageName: String, slot: Int): RuntimeSession = getOrNull(packageName, slot) ?: error("جلسة التشغيل غير موجودة")
    fun remove(packageName: String, slot: Int) { sessions.remove(key(packageName, slot))?.close() }
    fun clear() { sessions.values.forEach { it.close() }; sessions.clear() }
    fun sessionForClassLoader(loader: ClassLoader?): RuntimeSession? = loader?.let { candidate -> sessions.values.firstOrNull { it.classLoader === candidate } }
}
