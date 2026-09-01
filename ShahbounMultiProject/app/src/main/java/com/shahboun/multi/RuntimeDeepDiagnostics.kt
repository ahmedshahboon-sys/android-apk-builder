package com.shahboun.multi

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Debug
import android.os.Process

object RuntimeDeepDiagnostics {
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        RuntimeDiagnostics.log("DEEP", "diagnostics probes initialized process=${processName()}")
        captureStartupSnapshot()
    }

    fun captureStartupSnapshot() {
        if (!::appContext.isInitialized) return
        captureMemory("startup")
        captureAudio("startup")
        capturePermissions("startup")
        captureHistoricalExitReasons()
    }

    fun captureEmergencySnapshot(reason: String) {
        if (!::appContext.isInitialized) return
        runCatching { captureMemory("emergency:$reason") }
        runCatching { captureAudio("emergency:$reason") }
        runCatching { capturePermissions("emergency:$reason") }
    }

    fun captureCallCheckpoint(stage: String) {
        if (!::appContext.isInitialized) return
        captureMemory("call:$stage")
        captureAudio("call:$stage")
        capturePermissions("call:$stage")
        RuntimeDiagnostics.log("CALL", "checkpoint=$stage process=${processName()} pid=${Process.myPid()}")
    }

    fun renderReport(): String {
        if (!::appContext.isInitialized) return ""
        val exit = latestExitSummary()
        val audio = audioSummary()
        val perms = permissionSummary()
        val memory = memorySummary()
        return buildString {
            appendLine("--- DEEP DIAGNOSTICS ---")
            appendLine("Last process exit: $exit")
            appendLine("Audio / call stack: $audio")
            appendLine("Permissions / AppOps: $perms")
            appendLine("Memory: $memory")
        }
    }

    private fun captureHistoricalExitReasons() {
        if (Build.VERSION.SDK_INT < 30) return
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return
        runCatching { am.getHistoricalProcessExitReasons(null, 0, 12) }
            .onSuccess { list ->
                list.filter { it.processName?.startsWith(appContext.packageName) == true }
                    .take(8)
                    .forEach { info ->
                        RuntimeDiagnostics.log(
                            "EXIT",
                            "process=${info.processName} pid=${info.pid} reason=${info.reason} reasonName=${reasonName(info.reason)} status=${info.status} importance=${info.importance} pssKb=${info.pss} rssKb=${info.rss} timestamp=${info.timestamp} description=${info.description.orEmpty().take(240)}"
                        )
                    }
            }
            .onFailure { RuntimeDiagnostics.log("EXIT", "history unavailable ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun latestExitSummary(): String {
        if (Build.VERSION.SDK_INT < 30) return "unsupported on this Android version"
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return "ActivityManager unavailable"
        val info = runCatching {
            am.getHistoricalProcessExitReasons(null, 0, 20)
                .firstOrNull { it.processName?.startsWith(appContext.packageName + ":clone") == true }
        }.getOrNull() ?: return "no recorded clone exit yet"
        return "${info.processName} / ${reasonName(info.reason)} / status=${info.status} / pss=${info.pss}KB / rss=${info.rss}KB${info.description?.let { " / ${it.take(120)}" } ?: ""}"
    }

    @Suppress("DEPRECATION")
    private fun captureAudio(stage: String) {
        val audio = appContext.getSystemService(AudioManager::class.java) ?: return
        val devices = if (Build.VERSION.SDK_INT >= 23) {
            runCatching { audio.getDevices(AudioManager.GET_DEVICES_ALL).joinToString(",") { "${it.type}:${it.productName}" } }.getOrDefault("unknown")
        } else "unsupported"
        RuntimeDiagnostics.log(
            "AUDIO",
            "stage=$stage mode=${audio.mode} music=${audio.isMusicActive} micMute=${audio.isMicrophoneMute} speaker=${audio.isSpeakerphoneOn} bluetoothSco=${audio.isBluetoothScoOn} devices=${devices.take(500)}"
        )
    }

    @Suppress("DEPRECATION")
    private fun audioSummary(): String {
        val audio = appContext.getSystemService(AudioManager::class.java) ?: return "AudioManager unavailable"
        val devices = if (Build.VERSION.SDK_INT >= 23) runCatching {
            audio.getDevices(AudioManager.GET_DEVICES_ALL).joinToString(",") { it.type.toString() }
        }.getOrDefault("?") else "?"
        return "mode=${audio.mode}, micMute=${audio.isMicrophoneMute}, speaker=${audio.isSpeakerphoneOn}, sco=${audio.isBluetoothScoOn}, devices=$devices"
    }

    private fun capturePermissions(stage: String) {
        val permissions = linkedMapOf(
            "camera" to Manifest.permission.CAMERA,
            "record_audio" to Manifest.permission.RECORD_AUDIO,
            "bluetooth_connect" to if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH,
            "notifications" to if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else "android.permission.POST_NOTIFICATIONS"
        )
        val appOps = appContext.getSystemService(AppOpsManager::class.java)
        permissions.forEach { (name, permission) ->
            val granted = appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            val op = runCatching { AppOpsManager.permissionToOp(permission) }.getOrNull()
            val mode = if (op != null && appOps != null) runCatching {
                appOps.unsafeCheckOpNoThrow(op, Process.myUid(), appContext.packageName)
            }.getOrNull() else null
            RuntimeDiagnostics.log("PERM", "stage=$stage name=$name permission=$permission granted=$granted appOp=${op ?: "none"} mode=${mode ?: "n/a"}")
        }
    }

    private fun permissionSummary(): String {
        fun state(permission: String): String = if (appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
        return "camera=${state(Manifest.permission.CAMERA)}, mic=${state(Manifest.permission.RECORD_AUDIO)}, bluetooth=${state(if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH)}"
    }

    private fun captureMemory(stage: String) {
        val rt = Runtime.getRuntime()
        val javaUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024
        val javaMaxKb = rt.maxMemory() / 1024
        val nativeKb = Debug.getNativeHeapAllocatedSize() / 1024
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        RuntimeDiagnostics.log("MEM", "stage=$stage javaUsedKb=$javaUsedKb javaMaxKb=$javaMaxKb nativeKb=$nativeKb pssKb=${mi.totalPss} privateDirtyKb=${mi.totalPrivateDirty}")
    }

    private fun memorySummary(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val max = rt.maxMemory() / (1024 * 1024)
        return "java=${used}MB/${max}MB, native=${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)}MB"
    }

    private fun processName(): String = runCatching { Application.getProcessName() }.getOrDefault("unknown")

    private fun reasonName(reason: Int): String = if (Build.VERSION.SDK_INT < 30) reason.toString() else when (reason) {
        android.app.ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> reason.toString()
    }
}
