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
        RuntimeCompatibility.logProfile()
        installWebViewIsolation()
        SystemBarsFitter.install(this)

        engine = ShahbounCloneEngine()
        engine.initialize(this)
            .onSuccess { RuntimeDiagnostics.log("ENGINE", "initialized: ${engine.name}") }
            .onFailure { RuntimeDiagnostics.log("ENGINE", "initialize failed: ${it.stackTraceToString()}") }
            .getOrThrow()

        if (processName == packageName) RuntimeUpdateCenter.checkAndNotify(this, engine)

        // Engine 2.0: all framework/system bridges are installed and diagnosed through one
        // compatibility-aware registry instead of independent hard-coded reflection paths.
        RuntimeBridgeRegistry.install(this)
        RuntimeSystemEvents.install(this)

        // Must be installed before Instrumentation: this patches launch ActivityInfo/resource paths
        // before Activity.attach(), while Instrumentation handles guest class instantiation/lifecycle.
        val launchBridgeReady = RuntimeLaunchTransactionBridge.install()
            .onFailure { RuntimeDiagnostics.log("LAUNCH2", "pre-attach bridge fallback: ${it.stackTraceToString()}") }
            .isSuccess

        RuntimeInstrumentationInstaller.install()
            .onSuccess {
                runtimeBridgeReady = RuntimeBridgeRegistry.isCoreReady() && launchBridgeReady
                RuntimeDiagnostics.log("RUNTIME", "instrumentation bridge installed coreReady=$runtimeBridgeReady launch2=$launchBridgeReady")
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
