package com.shahboun.multi

import android.content.Context
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import dalvik.system.DexClassLoader
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RuntimeSession(
    val runtimePackage: RuntimePackage,
    val classLoader: DexClassLoader,
    val resources: Resources,
    private val closeables: List<Closeable>
) : Closeable {
    override fun close() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }
}

/**
 * Creates code/resource loaders without importing any third-party virtualization engine.
 * Guest code is loaded only from the clone-private APK snapshot.
 */
class RuntimeSessionFactory(private val context: Context) {
    fun create(pkg: RuntimePackage, slotDir: File): RuntimeSession {
        val codeCache = File(slotDir, "code_cache").apply { require(exists() || mkdirs()) }
        val nativeDir = File(slotDir, "native").apply { require(exists() || mkdirs()) }
        val loader = DexClassLoader(
            pkg.dexPath,
            codeCache.absolutePath,
            nativeDir.absolutePath,
            context.classLoader
        )

        val closeables = mutableListOf<Closeable>()
        val resources = if (Build.VERSION.SDK_INT >= 30) {
            val base = Resources(context.resources.assets, context.resources.displayMetrics, context.resources.configuration)
            val resourcesLoader = ResourcesLoader()
            val allApks = listOf(pkg.baseApk) + pkg.splitApks
            allApks.forEach { apk ->
                val pfd = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd.use {
                    val provider = ResourcesProvider.loadFromApk(it)
                    resourcesLoader.addProvider(provider)
                    closeables += provider
                }
            }
            base.addLoaders(resourcesLoader)
            base
        } else {
            // Android 10 has no public ResourcesProvider API. Use the installed package's
            // resources only; code still comes from the verified private APK snapshot.
            context.packageManager.getResourcesForApplication(pkg.packageName)
        }

        // Fail early if the recorded launcher class cannot be loaded from our snapshot.
        val launcher = loader.loadClass(pkg.launchActivity)
        require(android.app.Activity::class.java.isAssignableFrom(launcher)) {
            "شاشة تشغيل التطبيق ليست Activity صالحة"
        }
        return RuntimeSession(pkg, loader, resources, closeables)
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
