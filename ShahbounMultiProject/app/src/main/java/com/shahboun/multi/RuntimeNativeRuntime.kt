package com.shahboun.multi

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Shahboun-owned JNI boundary for clone path policy and native runtime health. */
internal object RuntimeNativeRuntime {
    private val attempted = AtomicBoolean(false)
    @Volatile private var loaded = false
    private val registeredRoots = ConcurrentHashMap<String, String>()

    enum class Capability { UNAVAILABLE, PATH_MAPPING }
    private fun key(packageName: String, slot: Int) = "$packageName#$slot"

    fun initialize(): Boolean {
        if (attempted.compareAndSet(false, true)) {
            loaded = runCatching { System.loadLibrary("shahboun_runtime"); true }
                .onFailure { RuntimeDiagnostics.log("NATIVE7", "load failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            if (loaded) RuntimeDiagnostics.log("NATIVE7", "Shahboun native runtime ready path-map-v4 syscall-intercept=false")
        }
        return loaded
    }

    fun register(packageName: String, slot: Int, root: File): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { return false }
        if (!canonicalRoot.isAbsolute || packageName.isBlank() || slot < 0) return false
        registeredRoots[key(packageName, slot)] = canonicalRoot.path
        if (!initialize()) return false
        return runCatching { nativeRegisterRoot(packageName, slot, canonicalRoot.path) }
            .onSuccess { ok -> if (ok) RuntimeDiagnostics.log("NATIVE7", "registered root $packageName/$slot root=${canonicalRoot.path}") }
            .onFailure { RuntimeDiagnostics.log("NATIVE7", "register failed $packageName/$slot: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    fun unregister(packageName: String, slot: Int) {
        registeredRoots.remove(key(packageName, slot))
        if (!loaded) return
        runCatching { nativeUnregisterRoot(packageName, slot) }
    }

    fun map(packageName: String, slot: Int, path: File): File = File(map(packageName, slot, path.absolutePath))
    fun map(packageName: String, slot: Int, path: String): String {
        if (!initialize()) return path
        val mapped = runCatching { nativeMapGuestPath(packageName, slot, path) }.getOrNull()
        return mapped?.takeIf { it.isNotBlank() } ?: path
    }

    fun mapAt(packageName: String, slot: Int, logicalDirectory: String, path: String): String? {
        if (!initialize()) return null
        return runCatching { nativeMapGuestPathAt(packageName, slot, logicalDirectory, path) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun reverseMap(packageName: String, slot: Int, path: File): File = File(reverseMap(packageName, slot, path.absolutePath))
    fun reverseMap(packageName: String, slot: Int, path: String): String {
        if (!initialize()) return path
        val mapped = runCatching { nativeReverseMapGuestPath(packageName, slot, path) }.getOrNull()
        return mapped?.takeIf { it.isNotBlank() } ?: path
    }

    fun describe(packageName: String, slot: Int): String {
        if (!initialize()) return "UNAVAILABLE"
        return runCatching { nativeDescribePolicy(packageName, slot) }.getOrDefault("UNKNOWN")
    }

    fun capability(): Capability = if (initialize()) Capability.PATH_MAPPING else Capability.UNAVAILABLE
    fun hasSyscallInterception(): Boolean = false
    fun hasLinkerNamespaceIsolation(): Boolean = false
    fun isSafe(path: File): Boolean = !initialize() || runCatching { nativeIsSafePath(path.absolutePath) }.getOrDefault(false)

    /** Exact canonical JVM root is authoritative; native disagreement is repaired and diagnosed. */
    fun isWithinRoot(packageName: String, slot: Int, path: File): Boolean {
        val rootPath = registeredRoots[key(packageName, slot)] ?: return false
        val root = runCatching { File(rootPath).canonicalFile }.getOrElse { return false }
        val candidate = runCatching { path.canonicalFile }.getOrElse { return false }
        if (!isCanonicalChild(root, candidate)) return false
        if (!initialize()) return true
        if (runCatching { nativeIsWithinRoot(packageName, slot, candidate.path) }.getOrDefault(false)) return true

        val repaired = runCatching { nativeRegisterRoot(packageName, slot, root.path) }.getOrDefault(false)
        val retryAccepted = repaired && runCatching { nativeIsWithinRoot(packageName, slot, candidate.path) }.getOrDefault(false)
        if (!retryAccepted) RuntimeDiagnostics.log(
            "NATIVE7",
            "containment disagreement $packageName/$slot root=${root.path} path=${candidate.path} repaired=$repaired; JVM canonical policy retained"
        )
        return true
    }

    private fun isCanonicalChild(root: File, candidate: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar)
        val candidatePath = candidate.path
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    fun isLoaded(): Boolean = loaded

    @JvmStatic private external fun nativeRegisterRoot(packageName: String, slot: Int, rootPath: String): Boolean
    @JvmStatic private external fun nativeUnregisterRoot(packageName: String, slot: Int)
    @JvmStatic private external fun nativeMapGuestPath(packageName: String, slot: Int, path: String): String
    @JvmStatic private external fun nativeMapGuestPathAt(packageName: String, slot: Int, dirLogicalPath: String, path: String): String
    @JvmStatic private external fun nativeReverseMapGuestPath(packageName: String, slot: Int, path: String): String
    @JvmStatic private external fun nativeDescribePolicy(packageName: String, slot: Int): String
    @JvmStatic private external fun nativeIsSafePath(path: String): Boolean
    @JvmStatic private external fun nativeIsWithinRoot(packageName: String, slot: Int, path: String): Boolean
}
