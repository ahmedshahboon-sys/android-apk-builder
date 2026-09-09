package com.shahboun.multi

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Shahboun-owned JNI boundary for clone path policy and native runtime health. */
internal object RuntimeNativeRuntime {
    private val attempted = AtomicBoolean(false)
    @Volatile private var loaded = false

    /*
     * The JVM registry is the authoritative copy of the exact canonical clone root that this
     * process registered. Native containment remains a defence-in-depth check. Keeping this copy
     * prevents a stale/missing JNI root record from rejecting a path that is provably inside the
     * same clone root, while still refusing traversal and symlink escapes.
     */
    private val registeredRoots = ConcurrentHashMap<String, String>()

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
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse {
            RuntimeDiagnostics.log("NATIVE7", "root canonicalization failed $packageName/$slot: ${it.javaClass.simpleName}: ${it.message}")
            return false
        }
        if (!canonicalRoot.isAbsolute) return false
        val key = key(packageName, slot)
        registeredRoots[key] = canonicalRoot.path
        if (!initialize()) return false
        return runCatching { nativeRegisterRoot(packageName, slot, canonicalRoot.path) }
            .onSuccess { ok ->
                if (ok) RuntimeDiagnostics.log("NATIVE7", "registered root $packageName/$slot root=${canonicalRoot.path}")
                else RuntimeDiagnostics.log("NATIVE7", "native rejected root registration $packageName/$slot root=${canonicalRoot.path}")
            }
            .onFailure { RuntimeDiagnostics.log("NATIVE7", "register failed $packageName/$slot: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    fun unregister(packageName: String, slot: Int) {
        registeredRoots.remove(key(packageName, slot))
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
        check(isWithinRoot(packageName, slot, physicalDir)) { "Relative base escaped registered clone root" }
        if (!initialize()) throw IllegalStateException("Native runtime unavailable")
        val mapped = nativeResolveRelativePath(packageName, slot, physicalDir.canonicalPath, relativePath)
        if (mapped.isBlank()) throw SecurityException("Relative clone path escaped registered root")
        val result = File(mapped)
        if (!isWithinRoot(packageName, slot, result)) throw SecurityException("Relative clone path escaped registered root")
        return result
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

    /**
     * Authorization check for paths inside a registered clone root. Canonical JVM containment is
     * mandatory. Native containment is checked too; if its in-process registry is stale, the exact
     * canonical root is re-registered once. A native false-negative after that is diagnosed, while
     * the already-proven canonical JVM containment remains authoritative.
     */
    fun isWithinRoot(packageName: String, slot: Int, path: File): Boolean {
        val rootPath = registeredRoots[key(packageName, slot)] ?: return false
        val canonicalPath = runCatching { path.canonicalFile.path }.getOrNull() ?: return false
        if (!pathContained(canonicalPath, rootPath)) return false
        if (!initialize()) return true

        val nativeOk = runCatching { nativeIsWithinRoot(packageName, slot, canonicalPath) }.getOrDefault(false)
        if (nativeOk) return true

        val repaired = runCatching {
            nativeRegisterRoot(packageName, slot, rootPath) && nativeIsWithinRoot(packageName, slot, canonicalPath)
        }.getOrDefault(false)
        if (!repaired) {
            RuntimeDiagnostics.log(
                "NATIVE7",
                "containment registry disagreement $packageName/$slot root=$rootPath path=$canonicalPath jvm=true native=false"
            )
        } else {
            RuntimeDiagnostics.log("NATIVE7", "containment registry repaired $packageName/$slot root=$rootPath")
        }
        return true
    }

    /** Symlink-aware validation for an existing path. */
    fun isExistingPathContained(packageName: String, slot: Int, path: File): Boolean {
        if (!isWithinRoot(packageName, slot, path)) return false
        if (!initialize()) return true
        return runCatching { nativeExistingPathContained(packageName, slot, path.absolutePath) }.getOrDefault(true)
    }

    /** Symlink-aware validation for a not-yet-existing target using the canonical parent directory. */
    fun isCreateTargetContained(packageName: String, slot: Int, path: File): Boolean {
        val parent = path.parentFile ?: return false
        if (!isWithinRoot(packageName, slot, parent)) return false
        if (!initialize()) return true
        return runCatching { nativeCreateTargetContained(packageName, slot, path.absolutePath) }.getOrDefault(true)
    }

    fun isLoaded(): Boolean = loaded

    private fun key(packageName: String, slot: Int): String = "$packageName#$slot"

    private fun pathContained(path: String, root: String): Boolean {
        if (path == root) return true
        if (!path.startsWith(root)) return false
        return root == "/" || (path.length > root.length && path[root.length] == File.separatorChar)
    }

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
