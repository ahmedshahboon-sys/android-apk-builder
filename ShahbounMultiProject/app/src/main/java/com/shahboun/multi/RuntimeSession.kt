package com.shahboun.multi

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.os.Build
import android.system.Os
import dalvik.system.DexClassLoader
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

class RuntimeSession(
    val runtimePackage: RuntimePackage,
    val classLoader: DexClassLoader,
    val resources: Resources,
    val resourcesLoader: ResourcesLoader?,
    private val loaderHostResources: Resources?,
    private val closeables: List<Closeable>
) : Closeable {
    enum class BootstrapState { NEW, PREPARING, ATTACHED, PROVIDERS_READY, APPLICATION_READY, RUNNING, FAILED, CLOSED }

    @Volatile var guestApplication: Application? = null
        private set
    @Volatile private var attachedApplication: Application? = null
    @Volatile var bootstrapState: BootstrapState = BootstrapState.NEW
        private set
    @Volatile var bootstrapFailure: Throwable? = null
        private set
    @Volatile var componentHost: RuntimeComponentHost? = null
        private set

    /**
     * During Application.attach/attachBaseContext Android libraries are already allowed to ask the
     * supplied Context for applicationContext. Publish the pending guest Application before attach
     * so that call never falls back to RuntimeGuestContext (which is not an Application).
     */
    fun applicationForContext(): Application? = guestApplication ?: attachedApplication
    fun isApplicationReady(): Boolean = bootstrapState == BootstrapState.RUNNING && guestApplication != null

    @Synchronized
    fun ensureGuestApplication(base: Context, slotDir: File): Application {
        guestApplication?.let { return it }
        check(bootstrapState != BootstrapState.CLOSED) { "RuntimeSession مغلقة" }
        if (bootstrapState == BootstrapState.FAILED) throw IllegalStateException("Guest bootstrap فشل سابقًا", bootstrapFailure)
        check(bootstrapState == BootstrapState.NEW) { "Guest bootstrap re-entry state=$bootstrapState" }
        bootstrapState = BootstrapState.PREPARING

        val appClass = runtimePackage.applicationClass?.let { classLoader.loadClass(it) }
        val app = if (appClass != null) {
            require(Application::class.java.isAssignableFrom(appClass)) { "Application class غير صالح" }
            appClass.getDeclaredConstructor().newInstance() as Application
        } else Application()

        // This assignment deliberately precedes RuntimeGuestContext/Application.attach. Several
        // modern libraries call context.applicationContext from attachBaseContext and require an
        // actual Application instance at that point.
        attachedApplication = app

        try {
            val guestContext = RuntimeGuestContext(base, this, slotDir)
            val attached = runCatching {
                val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }
                attach.invoke(app, guestContext)
                true
            }.onFailure {
                RuntimeDiagnostics.log(
                    "RUNTIME",
                    "guest Application.attach fallback ${runtimePackage.packageName}/${runtimePackage.slot}: ${it.javaClass.simpleName}: ${it.message}"
                )
            }.getOrDefault(false)
            if (!attached) {
                val baseField = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
                baseField.set(app, guestContext)
            }

            bootstrapState = BootstrapState.ATTACHED
            check(applicationForContext() === app) { "Guest Application publication lost during attach" }
            RuntimeProcessApplicationBridge.bind(this)
            RuntimeDiagnostics.log(
                "RUNTIME",
                "guest Application attached ${runtimePackage.packageName}/${runtimePackage.slot} attached=$attached class=${app.javaClass.name}"
            )

            val components = RuntimeComponentHost(base, this, slotDir)
            componentHost = components
            RuntimeDiagnostics.log("RUNTIME", "initializing guest providers ${runtimePackage.packageName}/${runtimePackage.slot}")
            RuntimeExecutionScope.withSession(this) { components.initializeProviders() }
            bootstrapState = BootstrapState.PROVIDERS_READY
            RuntimeDiagnostics.log("RUNTIME", "guest providers ready ${runtimePackage.packageName}/${runtimePackage.slot}")

            RuntimeDiagnostics.log("RUNTIME", "calling guest Application.onCreate ${runtimePackage.packageName}/${runtimePackage.slot}")
            RuntimeGuestProcessIdentity.withGuestMainProcess(this) { app.onCreate() }
            RuntimeInstrumentationInstaller.reassert("guest-app:${runtimePackage.packageName}/${runtimePackage.slot}").getOrElse { throw it }

            guestApplication = app
            RuntimeProcessApplicationBridge.bind(this)
            bootstrapState = BootstrapState.APPLICATION_READY
            bootstrapState = BootstrapState.RUNNING
            RuntimeDiagnostics.log("RUNTIME", "guest Application ready ${runtimePackage.packageName}/${runtimePackage.slot} state=$bootstrapState")
            return app
        } catch (error: Throwable) {
            guestApplication = null
            attachedApplication = null
            bootstrapFailure = error
            bootstrapState = BootstrapState.FAILED
            RuntimeDiagnostics.log("RUNTIME", "guest bootstrap failed ${runtimePackage.packageName}/${runtimePackage.slot}: ${error.stackTraceToString()}")
            throw error
        }
    }

    /**
     * Build 51 used both PackageManager.getResourcesForApplication(splitSourceDirs) and a second
     * ResourcesLoader containing those exact APKs. On Android 16 this can create duplicate package
     * tables and resource-ID mismatches. Runtime 7.1.1 uses one authoritative archive graph only.
     */
    fun attachLoaderTo(target: Resources): Boolean {
        val loader = resourcesLoader ?: return false
        return runCatching {
            target.addLoaders(loader)
            true
        }.onFailure {
            RuntimeDiagnostics.log(
                "RES",
                "attach loader failed ${runtimePackage.packageName}/${runtimePackage.slot}: ${it.javaClass.simpleName}: ${it.message}"
            )
        }.getOrDefault(false)
    }

    override fun close() {
        bootstrapState = BootstrapState.CLOSED
        runCatching { componentHost?.close() }
        componentHost = null
        guestApplication = null
        attachedApplication = null
        bootstrapFailure = null
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
        "java.", "javax.", "android.", "dalvik.", "sun.", "org.xml.", "org.w3c."
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
        val credentialDir = File(slotDir, "data").apply { require(exists() || mkdirs()) }
        val codeCache = File(credentialDir, "code_cache").apply { require(exists() || mkdirs()) }
        val nativeDir = File(slotDir, "native").apply {
            if (exists()) deleteRecursively()
            require(mkdirs())
        }
        val nativeResult = NativeLibraryExtractor.extract(allApks, nativeDir)
        RuntimeDiagnostics.log(
            "NATIVE",
            "extract package=${pkg.packageName} abi=${nativeResult.abi ?: "none"} libraries=${nativeResult.files.size} names=${nativeResult.files.joinToString { it.name }}"
        )
        RuntimeDiagnostics.log(
            "DEX",
            "loading package=${pkg.packageName} slot=${pkg.slot} apks=${allApks.size} " +
                allApks.joinToString { "${it.name}:r=${it.canRead()}:w=${it.canWrite()}:size=${it.length()}" }
        )

        val codeApks = allApks.filter(::containsDexCode)
        require(codeApks.isNotEmpty()) { "لا توجد ملفات DEX قابلة للتشغيل داخل حزمة التطبيق" }
        val dexPath = codeApks.joinToString(File.pathSeparator) { it.absolutePath }
        RuntimeDiagnostics.log(
            "DEX",
            "code-bearing apks package=${pkg.packageName} count=${codeApks.size}/${allApks.size} names=${codeApks.joinToString { it.name }}"
        )

        val hostLoader = context.classLoader
        val platformParent = hostLoader.parent ?: ClassLoader.getSystemClassLoader().parent ?: ClassLoader.getSystemClassLoader()
        val apkNativePaths = nativeResult.abi?.let { abi ->
            allApks.map { "${it.absolutePath}!/lib/$abi" }
        }.orEmpty()
        val nativeSearchPath = (listOf(nativeDir.absolutePath) + apkNativePaths)
            .distinct()
            .joinToString(File.pathSeparator)
        val loader = GuestDexClassLoader(
            dexPath,
            codeCache.absolutePath,
            nativeDir,
            nativeSearchPath,
            platformParent
        )
        RuntimeDiagnostics.log(
            "NATIVE",
            "search-path package=${pkg.packageName} abi=${nativeResult.abi ?: "none"} entries=${1 + apkNativePaths.size}"
        )
        RuntimeDiagnostics.log(
            "DEX",
            "guest-first isolated classloader enabled package=${pkg.packageName} slot=${pkg.slot} parent=${platformParent.javaClass.name}"
        )

        val effectivePkg = resolveLauncherTarget(pkg, loader)
        val splitPaths = effectivePkg.splitApks.map { it.absolutePath }.toTypedArray()
        val deviceDir = File(slotDir, "device_data").apply { require(exists() || mkdirs()) }
        val archiveInfo = ApplicationInfo().apply {
            packageName = effectivePkg.packageName
            sourceDir = effectivePkg.baseApk.absolutePath
            publicSourceDir = effectivePkg.baseApk.absolutePath
            splitSourceDirs = splitPaths
            splitPublicSourceDirs = splitPaths
            if (Build.VERSION.SDK_INT >= 26) splitNames = effectivePkg.splitNames.toTypedArray()
            dataDir = credentialDir.absolutePath
            if (Build.VERSION.SDK_INT >= 24) credentialProtectedDataDir = credentialDir.absolutePath
            deviceProtectedDataDir = deviceDir.absolutePath
            nativeLibraryDir = nativeDir.absolutePath
            targetSdkVersion = effectivePkg.targetSdk
            if (Build.VERSION.SDK_INT >= 24) minSdkVersion = effectivePkg.minSdk
            flags = effectivePkg.appFlags or ApplicationInfo.FLAG_HAS_CODE
            theme = effectivePkg.appTheme
        }

        // One resource graph only. getResourcesForApplication already consumes base + splitSourceDirs.
        // Re-adding the same APKs via ResourcesLoader changes package-table precedence on Android 16.
        val resources = context.packageManager.getResourcesForApplication(archiveInfo)
        RuntimeDiagnostics.log(
            "RES",
            "authoritative archive resource graph package=${effectivePkg.packageName} base=${effectivePkg.baseApk.name} splits=${effectivePkg.splitApks.size} splitNames=${effectivePkg.splitNames.joinToString()} assets=${resources.assets} duplicateLoader=false"
        )

        val launcher = loader.loadClass(effectivePkg.launchActivity)
        require(android.app.Activity::class.java.isAssignableFrom(launcher)) {
            "شاشة تشغيل التطبيق ليست Activity صالحة"
        }
        effectivePkg.applicationClass?.let { name ->
            require(Application::class.java.isAssignableFrom(loader.loadClass(name))) {
                "Application class غير صالح"
            }
        }
        return RuntimeSession(
            effectivePkg,
            loader,
            resources,
            null,
            null,
            emptyList()
        )
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
            RuntimeDiagnostics.log(
                "DEX",
                "launcher alias resolved ${pkg.packageName}: ${pkg.launchActivity} -> $resolved"
            )
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
            runCatching { Os.chmod(apk.absolutePath, 0b100100100) }
                .recoverCatching {
                    require(apk.setReadOnly()) { "تعذر حماية ملف APK: ${apk.name}" }
                }.getOrThrow()
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
        val extracted = LinkedHashMap<String, Pair<File, String>>()
        if (abi != null) {
            apks.forEach { apk ->
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence()
                        .filter {
                            !it.isDirectory &&
                                it.name.startsWith("lib/$abi/") &&
                                it.name.endsWith(".so")
                        }
                        .forEach { entry ->
                            val fileName = entry.name.substringAfterLast('/')
                            require(fileName.matches(Regex("[A-Za-z0-9._+-]+\\.so"))) {
                                "Unsafe native library name: $fileName"
                            }
                            val temp = File(
                                targetDir,
                                ".$fileName.${android.os.Process.myPid()}.tmp"
                            )
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(temp).use(input::copyTo)
                            }
                            val digest = sha256(temp)
                            val existing = extracted[fileName]
                            if (existing != null) {
                                temp.delete()
                                require(existing.second == digest) {
                                    "Conflicting native library $fileName across APK splits; refusing nondeterministic overwrite"
                                }
                                return@forEach
                            }
                            val out = File(targetDir, fileName)
                            require(temp.renameTo(out)) {
                                "Unable to commit native library $fileName"
                            }
                            runCatching { Os.chmod(out.absolutePath, 0b101101101) }
                            require(out.canonicalFile.parentFile == targetDir.canonicalFile) {
                                "Native library escaped clone directory"
                            }
                            extracted[fileName] = out to digest
                        }
                }
            }
        }
        return Result(abi, extracted.values.map { it.first })
    }

    private fun containsAbi(apk: File, abi: String): Boolean = runCatching {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().any {
                !it.isDirectory &&
                    it.name.startsWith("lib/$abi/") &&
                    it.name.endsWith(".so")
            }
        }
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
