package com.shahboun.multi

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Shahboun-owned JNI boundary for clone path policy and native runtime health. */
internal object RuntimeNativeRuntime {
    private val attempted = AtomicBoolean(false)
    @Volatile private var loaded = false

    enum class Capability { UNAVAILABLE, PATH_MAPPING, PATH_MAPPING_RELATIVE_SAFE }

    fun initialize(): Boolean {
        if (attempted.compareAndSet(false, true)) {
            loaded = runCatching { System.loadLibrary("shahboun_runtime"); true }
                .onFailure { RuntimeDiagnostics.log("NATIVE7", "load failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            if (loaded) RuntimeDiagnostics.log("NATIVE7", "Shahboun native runtime ready path-map-v4 syscall-intercept=false linker-namespace=false")
        }
        return loaded
    }

    fun register(packageName: String, slot: Int, root: File): Boolean {
        if (!initialize()) return false
        return runCatching { nativeRegisterRoot(packageName, slot, root.canonicalPath) }
            .onSuccess { ok -> if (ok) RuntimeDiagnostics.log("NATIVE7", "registered root $packageName/$slot root=${root.canonicalPath}") }
            .onFailure { RuntimeDiagnostics.log("NATIVE7", "register failed $packageName/$slot: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    fun unregister(packageName: String, slot: Int) {
        if (!loaded) return
        runCatching { nativeUnregisterRoot(packageName, slot) }
    }

    fun map(packageName: String, slot: Int, path: File): File = File(map(packageName, slot, path.absolutePath))

    /**
     * Maps a guest absolute path. A blank native answer means policy denial and is never silently
     * converted back to the original guest path; doing so would re-open traversal escapes.
     */
    fun map(packageName: String, slot: Int, path: String): String {
        if (!initialize()) return path
        val mapped = runCatching { nativeMapGuestPath(packageName, slot, path) }
            .getOrElse {
                RuntimeDiagnostics.log("NATIVE7", "map failed $packageName/$slot path=${path.take(240)}: ${it.javaClass.simpleName}: ${it.message}")
                return path
            }
        if (mapped.isBlank()) throw SecurityException("Shahboun native path policy rejected guest path")
        return mapped
    }

    /** Resolve openat-style relative paths against a physical directory already owned by the clone. */
    fun resolveRelative(packageName: String, slot: Int, physicalDir: File, relativePath: String): File {
        require(!relativePath.startsWith('/')) { "resolveRelative requires a relative path" }
        if (!initialize()) throw IllegalStateException("Native runtime unavailable")
        val mapped = nativeResolveRelativePath(packageName, slot, physicalDir.canonicalPath, relativePath)
        if (mapped.isBlank()) throw SecurityException("Relative clone path escaped registered root")
        return File(mapped)
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

    fun capability(): Capability = if (initialize()) Capability.PATH_MAPPING_RELATIVE_SAFE else Capability.UNAVAILABLE

    /** Deliberately false until third-party libc calls are intercepted and proven on Android 16. */
    fun hasSyscallInterception(): Boolean = false

    /** Deliberately false: Android linker namespace virtualization is not claimed. */
    fun hasLinkerNamespaceVirtualization(): Boolean = false

    /** General lexical absolute-path check; it is not an authorization decision. */
    fun isSafe(path: File): Boolean = !initialize() || runCatching { nativeIsSafePath(path.absolutePath) }.getOrDefault(false)

    /** Authorization check for paths that must stay lexically inside a registered clone root. */
    fun isWithinRoot(packageName: String, slot: Int, path: File): Boolean {
        if (!initialize()) return false
        return runCatching { nativeIsWithinRoot(packageName, slot, path.absolutePath) }.getOrDefault(false)
    }

    /** Symlink-aware validation for an existing path. */
    fun isExistingPathContained(packageName: String, slot: Int, path: File): Boolean {
        if (!initialize()) return false
        return runCatching { nativeExistingPathContained(packageName, slot, path.absolutePath) }.getOrDefault(false)
    }

    /** Symlink-aware validation for a not-yet-existing target using the canonical parent directory. */
    fun isCreateTargetContained(packageName: String, slot: Int, path: File): Boolean {
        if (!initialize()) return false
        return runCatching { nativeCreateTargetContained(packageName, slot, path.absolutePath) }.getOrDefault(false)
    }

    fun isLoaded(): Boolean = loaded

    @JvmStatic private external fun nativeRegisterRoot(packageName: String, slot: Int, rootPath: String): Boolean
    @JvmStatic private external fun nativeUnregisterRoot(packageName: String, slot: Int)
    @JvmStatic private external fun nativeMapGuestPath(packageName: String, slot: Int, path: String): String
    @JvmStatic private external fun nativeReverseMapGuestPath(packageName: String, slot: Int, path: String): String
    @JvmStatic private external fun nativeResolveRelativePath(packageName: String, slot: Int, dirBase: String, path: String): String
    @JvmStatic private external fun nativeDescribePolicy(packageName: String, slot: Int): String
    @JvmStatic private external fun nativeIsSafePath(path: String): Boolean
    @JvmStatic private external fun nativeIsWithinRoot(packageName: String, slot: Int, path: String): Boolean
    @JvmStatic private external fun nativeExistingPathContained(packageName: String, slot: Int, path: String): Boolean
    @JvmStatic private external fun nativeCreateTargetContained(packageName: String, slot: Int, path: String): Boolean
}
