package com.shahboun.multi

import android.app.Application
import android.os.Build
import android.webkit.WebView

class MultiApplication : Application() {
    lateinit var engine: ShahbounCloneEngine
        private set

    var runtimeBridgeReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        current = this
        RuntimeDiagnostics.initialize(this)
        RuntimeDiagnostics.installCrashHandler()
        val processName = currentProcessName()
        RuntimeDiagnostics.log("APP", "MultiApplication onCreate process=$processName")
        installWebViewIsolation()
        SystemBarsFitter.install(this)

        engine = ShahbounCloneEngine()
        engine.initialize(this)
            .onSuccess { RuntimeDiagnostics.log("ENGINE", "initialized: ${engine.name}") }
            .onFailure { RuntimeDiagnostics.log("ENGINE", "initialize failed: ${it.stackTraceToString()}") }
            .getOrThrow()

        if (processName == packageName) RuntimeUpdateCenter.checkAndNotify(this, engine)

        RuntimePackageManagerBridge.install(this)
            .onSuccess { RuntimeDiagnostics.log("PM", "package manager bridge ready") }
            .onFailure { RuntimeDiagnostics.log("PM", "package manager bridge fallback: ${it.stackTraceToString()}") }

        RuntimeNotificationBridge.install(this)
            .onSuccess { RuntimeDiagnostics.log("NOTIFY", "notification bridge ready") }
            .onFailure { RuntimeDiagnostics.log("NOTIFY", "notification bridge fallback: ${it.stackTraceToString()}") }

        RuntimePendingIntentBridge.install(this)
            .onSuccess { RuntimeDiagnostics.log("PENDING", "PendingIntent bridge ready") }
            .onFailure { RuntimeDiagnostics.log("PENDING", "PendingIntent bridge fallback: ${it.stackTraceToString()}") }

        RuntimeAlarmBridge.install(this)
            .onSuccess { RuntimeDiagnostics.log("ALARM", "AlarmManager bridge ready") }
            .onFailure { RuntimeDiagnostics.log("ALARM", "AlarmManager bridge fallback: ${it.stackTraceToString()}") }

        RuntimeJobSchedulerBridge.install(this)
            .onSuccess { RuntimeDiagnostics.log("JOB", "JobScheduler bridge ready") }
            .onFailure { RuntimeDiagnostics.log("JOB", "JobScheduler bridge fallback: ${it.stackTraceToString()}") }

        RuntimeClipboardBridge.install(this)
            .onSuccess { RuntimeDiagnostics.log("CLIP", "clipboard bridge ready") }
            .onFailure { RuntimeDiagnostics.log("CLIP", "clipboard bridge fallback: ${it.stackTraceToString()}") }

        RuntimeIdentityServiceBridge.install(this)
            .onSuccess { RuntimeDiagnostics.log("IDENTITY", "system identity compatibility ready") }
            .onFailure { RuntimeDiagnostics.log("IDENTITY", "system identity compatibility fallback: ${it.stackTraceToString()}") }

        RuntimeSystemEvents.install(this)

        RuntimeInstrumentationInstaller.install()
            .onSuccess {
                runtimeBridgeReady = true
                RuntimeDiagnostics.log("RUNTIME", "instrumentation bridge installed")
            }
            .onFailure {
                runtimeBridgeReady = false
                RuntimeDiagnostics.log("RUNTIME", "instrumentation bridge unavailable: ${it.stackTraceToString()}")
            }
    }

    private fun installWebViewIsolation() {
        if (Build.VERSION.SDK_INT < 28) return
        val process = currentProcessName()
        val processIndex = Regex(":clone([0-9])$").find(process)?.groupValues?.getOrNull(1) ?: return
        runCatching { WebView.setDataDirectorySuffix("shahboun_process_$processIndex") }
            .onSuccess { RuntimeDiagnostics.log("WEBVIEW", "isolated data directory processIndex=$processIndex process=$process") }
            .onFailure { RuntimeDiagnostics.log("WEBVIEW", "data directory isolation failed processIndex=$processIndex: ${it.stackTraceToString()}") }
    }

    private fun currentProcessName(): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName

    fun requireRuntimeBridge() {
        check(runtimeBridgeReady) {
            "جسر تشغيل النسخ غير متاح على هذا الجهاز. افتح «التشخيص» وانسخ السجل."
        }
    }

    companion object {
        @Volatile var current: MultiApplication? = null
            private set
    }
}
