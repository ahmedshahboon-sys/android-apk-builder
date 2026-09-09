package com.shahboun.multi

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** Lightweight runtime metrics; measurements are diagnostic evidence, not a performance guarantee. */
internal object RuntimePerformanceProbe {
    private val marks = ConcurrentHashMap<String, Long>()

    fun mark(name: String) {
        marks[name] = SystemClock.elapsedRealtime()
    }

    fun duration(from: String, to: String): Long? {
        val a = marks[from] ?: return null
        val b = marks[to] ?: return null
        return (b - a).takeIf { it >= 0 }
    }

    fun render(): String {
        val info = Debug.MemoryInfo()
        runCatching { Debug.getMemoryInfo(info) }
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val nativeUsed = Debug.getNativeHeapAllocatedSize()
        val threads = Thread.getAllStackTraces().size
        return buildString {
            appendLine("--- PERFORMANCE ---")
            appendLine("pid=${Process.myPid()} pssKb=${info.totalPss} javaHeapKb=${javaUsed / 1024} nativeHeapKb=${nativeUsed / 1024} threads=$threads")
            duration("app-start", "runtime-ready")?.let { appendLine("startupMs=$it") }
            duration("guest-bootstrap-start", "guest-running")?.let { appendLine("lastGuestBootstrapMs=$it") }
            appendLine("Evidence: LIVE PROCESS SAMPLE; leak conclusions require repeated real-device runs")
        }
    }
}
