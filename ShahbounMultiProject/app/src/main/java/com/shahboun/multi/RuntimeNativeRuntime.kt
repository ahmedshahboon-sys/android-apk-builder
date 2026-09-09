package com.shahboun.multi

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Shahboun-owned JNI boundary for clone path policy and native runtime health. */
internal object RuntimeNativeRuntime {
    private val attempted = AtomicBoolean(false)
    @Volatile private var loaded = false

    fun initialize(): Boolean {
        if (attempted.compareAndSet(false, true)) {
            loaded = runCatching { System.loadLibrary("shahboun_runtime"); true }
                .onFailure { RuntimeDiagnostics.log("NATIVE4", "load failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            if (loaded) RuntimeDiagnostics.log("NATIVE4", "Shahboun native runtime ready")
        }
        return loaded
    }

    fun register(packageName: String, slot: Int, root: File): Boolean {
        if (!initialize()) return false
        return runCatching { nativeRegisterRoot(packageName, slot, root.canonicalPath) }
            .onFailure { RuntimeDiagnostics.log("NATIVE4", "register failed $packageName/$slot: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    fun unregister(packageName: String, slot: Int) {
        if (!loaded) return
        runCatching { nativeUnregisterRoot(packageName, slot) }
    }

    fun map(packageName: String, slot: Int, path: File): File {
        if (!initialize()) return path
        val mapped = runCatching { nativeMapGuestPath(packageName, slot, path.absolutePath) }.getOrNull()
        return if (mapped.isNullOrBlank()) path else File(mapped)
    }

    fun isSafe(path: File): Boolean = !initialize() || runCatching { nativeIsSafePath(path.absolutePath) }.getOrDefault(false)
    fun isLoaded(): Boolean = loaded

    @JvmStatic private external fun nativeRegisterRoot(packageName: String, slot: Int, rootPath: String): Boolean
    @JvmStatic private external fun nativeUnregisterRoot(packageName: String, slot: Int)
    @JvmStatic private external fun nativeMapGuestPath(packageName: String, slot: Int, path: String): String
    @JvmStatic private external fun nativeIsSafePath(path: String): Boolean
}
