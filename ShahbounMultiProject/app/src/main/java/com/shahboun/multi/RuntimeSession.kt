package com.shahboun.multi

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
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
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java).apply {
                isAccessible = true
            }
            attach.invoke(app, guestContext)
            true
        }.onFailure {
            RuntimeDiagnostics.log("RUNTIME", "guest Application.attach fallback ${runtimePackage.packageName}/${runtimePackage.slot}: ${it.javaClass.simpleName}")
        }.getOrDefault(false)

        if (!attached) {
            val baseField = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
            baseField.set(app, guestContext)
        }

        guestApplication = app
        RuntimeDiagnostics.log(
            "RUNTIME",
            "guest Application attached ${runtimePackage.packageName}/${runtimePackage.slot} attached=$attached class=${app.javaClass.name}"
        )

        val components = RuntimeComponentHost(base, this, slotDir)
        componentHost = components
        RuntimeDiagnostics.log("RUNTIME", "initializing guest providers ${runtimePackage.packageName}/${runtimePackage.slot}")
        components.initializeProviders()
        RuntimeDiagnostics.log("RUNTIME", "guest providers ready ${runtimePackage.packageName}/${runtimePackage.slot}")

        RuntimeDiagnostics.log("RUNTIME", "calling guest Application.onCreate ${runtimePackage.packageName}/${runtimePackage.slot}")
        app.onCreate()
        RuntimeDiagnostics.log("RUNTIME", "guest Application ready ${runtimePackage.packageName}/${runtimePackage.slot}")
        return app
    }

    override fun close() {
        runCatching { componentHost?.close() }
        componentHost = null
        guestApplication = null
        closeables.asReversed().forEach { runCatching { it.close() } }
    }
}

/**
 * Guest APKs may bundle their own AndroidX/Google/Kotlin libraries. A normal
 * DexClassLoader is parent-first, which can accidentally bind guest code to the
 * host app's dependency versions. That caused WhatsApp to resolve
 * AppLocalesMetadataHolderService from Shahboun Multi instead of from its APK.
 *
 * Framework + bridge classes remain parent-first. Everything else is guest-first.
 */
private class GuestDexClassLoader(
    dexPath: String,
    optimizedDirectory: String?,
    librarySearchPath: String?,
    parent: ClassLoader
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {

    private val parentFirstPrefixes = arrayOf(
        "java.",
        "javax.",
        "android.",
        "dalvik.",
        "sun.",
        "org.apache.",
        "org.xml.",
        "org.w3c.",
        "com.android.",
        "com.shahboun.multi."
    )

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }

            if (parentFirstPrefixes.any { name.startsWith(it) }) {
                return super.loadClass(name, resolve)
            }

            val loaded = runCatching { findClass(name) }.getOrNull()
                ?: super.loadClass(name, false)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}

/** Creates code/resource/native loaders using only Shahboun runtime code. */
class RuntimeSessionFactory(private val context: Context) {
    fun create(pkg: RuntimePackage, slotDir: File): RuntimeSession {
        val allApks = listOf(pkg.baseApk) + pkg.splitApks
        RuntimeCodeSecurity.prepareApks(allApks)

        val codeCache = File(slotDir, "code_cache").apply { require(exists() || mkdirs()) }
        val nativeDir = File(slotDir, "native").apply {
            if (exists()) deleteRecursively()
            require(mkdirs())
        }
        NativeLibraryExtractor.extract(allApks, nativeDir)

        RuntimeDiagnostics.log(
            "DEX",
            "loading package=${pkg.packageName} slot=${pkg.slot} apks=${allApks.size} " +
                allApks.joinToString { "${it.name}:r=${it.canRead()}:w=${it.canWrite()}:size=${it.length()}" }
        )

        val loader = GuestDexClassLoader(
            pkg.dexPath,
            codeCache.absolutePath,
            nativeDir.absolutePath,
            context.classLoader
        )
        RuntimeDiagnostics.log("DEX", "guest-first classloader enabled package=${pkg.packageName} slot=${pkg.slot}")

        val closeables = mutableListOf<Closeable>()
        val resources = if (Build.VERSION.SDK_INT >= 30) {
            val base = Resources(context.resources.assets, context.resources.displayMetrics, context.resources.configuration)
            val resourcesLoader = ResourcesLoader()
            allApks.forEach { apk ->
                ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val provider = ResourcesProvider.loadFromApk(pfd)
                    resourcesLoader.addProvider(provider)
                    closeables += provider
                }
            }
            base.addLoaders(resourcesLoader)
            base
        } else {
            context.packageManager.getResourcesForApplication(pkg.packageName)
        }

        val launcher = loader.loadClass(pkg.launchActivity)
        require(android.app.Activity::class.java.isAssignableFrom(launcher)) {
            "شاشة تشغيل التطبيق ليست Activity صالحة"
        }
        pkg.applicationClass?.let { name ->
            require(Application::class.java.isAssignableFrom(loader.loadClass(name))) { "Application class غير صالح" }
        }
        return RuntimeSession(pkg, loader, resources, closeables)
    }
}

/** Android 14+ rejects dynamically loaded dex/apk files that remain writable. */
private object RuntimeCodeSecurity {
    fun prepareApks(apks: List<File>) {
        require(apks.isNotEmpty()) { "لا توجد ملفات APK للتشغيل" }
        apks.forEach { apk ->
            require(apk.isFile && apk.length() > 0) { "ملف APK غير صالح: ${apk.name}" }
            runCatching { Os.chmod(apk.absolutePath, 0b100100100) }
                .recoverCatching {
                    require(apk.setReadOnly()) { "تعذر حماية ملف APK: ${apk.name}" }
                }
                .getOrThrow()
            require(apk.canRead()) { "ملف APK غير قابل للقراءة: ${apk.name}" }
            require(!apk.canWrite()) { "ملف APK ما زال قابلاً للكتابة: ${apk.name}" }
        }
    }
}

private object NativeLibraryExtractor {
    fun extract(apks: List<File>, targetDir: File) {
        val supported = Build.SUPPORTED_ABIS.toList()
        var selectedAbi: String? = null
        for (abi in supported) {
            if (apks.any { containsAbi(it, abi) }) {
                selectedAbi = abi
                break
            }
        }
        val abi = selectedAbi ?: return
        apks.forEach { apk -> extractAbi(apk, abi, targetDir) }
    }

    private fun containsAbi(apk: File, abi: String): Boolean = ZipFile(apk).use { zip ->
        zip.entries().asSequence().any { !it.isDirectory && it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
    }

    private fun extractAbi(apk: File, abi: String, targetDir: File) {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
                .forEach { entry ->
                    val fileName = entry.name.substringAfterLast('/')
                    require(fileName.isNotBlank() && !fileName.contains("..")) { "اسم مكتبة Native غير صالح" }
                    val out = File(targetDir, fileName)
                    zip.getInputStream(entry).use { input -> FileOutputStream(out).use { output -> input.copyTo(output) } }
                    require(out.isFile && out.length() > 0) { "فشل استخراج مكتبة Native" }
                }
        }
    }
}

object RuntimeRegistry {
    private val sessions = ConcurrentHashMap<String, RuntimeSession>()
    private fun key(packageName: String, slot: Int) = "$packageName#$slot"

    fun put(session: RuntimeSession) {
        sessions.put(key(session.runtimePackage.packageName, session.runtimePackage.slot), session)?.close()
    }

    fun get(packageName: String, slot: Int): RuntimeSession =
        sessions[key(packageName, slot)] ?: error("جلسة التشغيل غير موجودة")

    fun remove(packageName: String, slot: Int) {
        sessions.remove(key(packageName, slot))?.close()
    }

    fun clear() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
