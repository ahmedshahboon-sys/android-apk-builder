package com.shahboun.multi

import android.app.Application
import android.os.Build

/**
 * Tracks which clone owns execution. Thread scope is precise during callbacks; isolated clone
 * processes also keep a process-level identity so async Handlers/listeners do not fall back to host identity.
 */
object RuntimeExecutionScope {
    private val local = object : InheritableThreadLocal<RuntimeSession?>() {}
    @Volatile private var processSession: RuntimeSession? = null

    fun current(): RuntimeSession? = local.get()
        ?: RuntimeRegistry.sessionForClassLoader(Thread.currentThread().contextClassLoader)
        ?: processSession

    fun bindProcessSession(session: RuntimeSession) {
        if (!isCloneProcess()) return
        val existing = processSession
        if (existing == null || (existing.runtimePackage.packageName == session.runtimePackage.packageName && existing.runtimePackage.slot == session.runtimePackage.slot)) {
            processSession = session
            RuntimeDiagnostics.log("RUNTIME", "process identity bound ${session.runtimePackage.packageName}/${session.runtimePackage.slot} process=${processName()}")
        } else {
            RuntimeDiagnostics.log("RUNTIME", "process identity switched ${existing.runtimePackage.packageName}/${existing.runtimePackage.slot} -> ${session.runtimePackage.packageName}/${session.runtimePackage.slot} process=${processName()}")
            processSession = session
        }
    }

    fun clearProcessSession(session: RuntimeSession) {
        if (processSession === session) processSession = null
    }

    fun <T> withSession(session: RuntimeSession, block: () -> T): T {
        bindProcessSession(session)
        val previous = local.get()
        val thread = Thread.currentThread()
        val previousLoader = thread.contextClassLoader
        local.set(session)
        thread.contextClassLoader = session.classLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previousLoader
            if (previous == null) local.remove() else local.set(previous)
        }
    }

    private fun isCloneProcess(): Boolean = processName().contains(":clone")
    private fun processName(): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID
}
