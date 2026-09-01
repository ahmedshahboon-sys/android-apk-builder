package com.shahboun.multi

import android.app.Application
import android.os.Build

/**
 * Tracks which clone owns execution. Thread scope is precise during callbacks; isolated clone
 * processes keep one immutable process-level owner so async Handlers/listeners never inherit a
 * different clone identity. A process must never host two distinct clone identities concurrently.
 */
object RuntimeExecutionScope {
    private val local = object : InheritableThreadLocal<RuntimeSession?>() {}
    @Volatile private var processSession: RuntimeSession? = null

    fun current(): RuntimeSession? = local.get()
        ?: RuntimeRegistry.sessionForClassLoader(Thread.currentThread().contextClassLoader)
        ?: processSession

    fun bindProcessSession(session: RuntimeSession) {
        if (!isCloneProcess()) return
        synchronized(this) {
            val existing = processSession
            if (existing == null) {
                processSession = session
                RuntimeDiagnostics.log("RUNTIME", "process identity bound ${identity(session)} process=${processName()}")
                return
            }
            if (sameIdentity(existing, session)) return

            RuntimeDiagnostics.log(
                "RUNTIME",
                "PROCESS COLLISION rejected existing=${identity(existing)} incoming=${identity(session)} process=${processName()}"
            )
            error(
                "رفض خلط نسختين داخل نفس عملية التشغيل: process=${processName()} " +
                    "existing=${identity(existing)} incoming=${identity(session)}"
            )
        }
    }

    fun clearProcessSession(session: RuntimeSession) {
        synchronized(this) {
            if (processSession === session || processSession?.let { sameIdentity(it, session) } == true) {
                RuntimeDiagnostics.log("RUNTIME", "process identity released ${identity(session)} process=${processName()}")
                processSession = null
            }
        }
    }

    fun processOwner(): Pair<String, Int>? = processSession?.let { it.runtimePackage.packageName to it.runtimePackage.slot }

    fun <T> withSession(session: RuntimeSession, block: () -> T): T {
        bindProcessSession(session)
        val previous = local.get()
        val thread = Thread.currentThread()
        val previousLoader = thread.contextClassLoader
        local.set(session)
        thread.contextClassLoader = session.classLoader
        return try {
            RuntimeGuestProcessIdentity.withGuestMainProcess(session) { block() }
        } finally {
            thread.contextClassLoader = previousLoader
            if (previous == null) local.remove() else local.set(previous)
        }
    }

    private fun sameIdentity(a: RuntimeSession, b: RuntimeSession): Boolean =
        a.runtimePackage.packageName == b.runtimePackage.packageName && a.runtimePackage.slot == b.runtimePackage.slot

    private fun identity(session: RuntimeSession): String =
        "${session.runtimePackage.packageName}/${session.runtimePackage.slot}"

    private fun isCloneProcess(): Boolean = processName().contains(":clone")
    private fun processName(): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID
}
