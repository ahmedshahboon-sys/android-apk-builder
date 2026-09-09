package com.shahboun.multi

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Shahboun-owned JNI boundary for clone path policy and native runtime health. */
internal object RuntimeNativeRuntime {
    private val attempted = AtomicBoolean(false)
    @Volatile private var loaded = false

    enum class Capability { UNAVAILABLE, PATH_MAPPING }

    fun initialize(): Boolean {
        if (attempted.compareAndSet(false, true)) {
            loaded = runCatching { System.loadLibrary("shahboun_runtime"); true }
                .onFailure { RuntimeDiagnostics.log("NATIVE5", "load failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            if (loaded) RuntimeDiagnostics.log("NATIVE5", "Shahboun native runtime ready path-map-v2")
        }
        return loaded
    }

    fun register(packageName: String, slot: Int, root: File): Boolean {
        if (!initialize()) return false
        return runCatching { nativeRegisterRoot(packageName, slot, root.canonicalPath) }
            .onSuccess { ok -> if (ok) RuntimeDiagnostics.log("NATIVE5", "registered root $packageName/$slot root=${root.canonicalPath}") }
            .onFailure { RuntimeDiagnostics.log("NATIVE5", "register failed $packageName/$slot: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    fun unregister(packageName: String, slot: Int) {
        if (!loaded) return
        runCatching { nativeUnregisterRoot(packageName, slot) }
    }

    fun map(packageName: String, slot: Int, path: File): File = File(map(packageName, slot, path.absolutePath))

    fun map(packageName: String, slot: Int, path: String): String {
        if (!initialize()) return path
        val mapped = runCatching { nativeMapGuestPath(packageName, slot, path) }.getOrNull()
        return mapped?.takeIf { it.isNotBlank() } ?: path
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
    fun isSafe(path: File): Boolean = !initialize() || runCatching { nativeIsSafePath(path.absolutePath) }.getOrDefault(false)
    fun isLoaded(): Boolean = loaded

    @JvmStatic private external fun nativeRegisterRoot(packageName: String, slot: Int, rootPath: String): Boolean
    @JvmStatic private external fun nativeUnregisterRoot(packageName: String, slot: Int)
    @JvmStatic private external fun nativeMapGuestPath(packageName: String, slot: Int, path: String): String
    @JvmStatic private external fun nativeReverseMapGuestPath(packageName: String, slot: Int, path: String): String
    @JvmStatic private external fun nativeDescribePolicy(packageName: String, slot: Int): String
    @JvmStatic private external fun nativeIsSafePath(path: String): Boolean
}
