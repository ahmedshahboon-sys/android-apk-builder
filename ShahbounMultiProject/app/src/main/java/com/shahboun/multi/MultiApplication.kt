package com.shahboun.multi

import android.app.Application

class MultiApplication : Application() {
    lateinit var engine: ShahbounCloneEngine
        private set

    var runtimeBridgeReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        RuntimeDiagnostics.initialize(this)
        RuntimeDiagnostics.installCrashHandler()
        RuntimeDiagnostics.log("APP", "MultiApplication onCreate")
        SystemBarsFitter.install(this)

        engine = ShahbounCloneEngine()
        engine.initialize(this)
            .onSuccess { RuntimeDiagnostics.log("ENGINE", "initialized: ${engine.name}") }
            .onFailure { RuntimeDiagnostics.log("ENGINE", "initialize failed: ${it.stackTraceToString()}") }
            .getOrThrow()

        RuntimePackageManagerBridge.install(this)
            .onSuccess { RuntimeDiagnostics.log("PM", "package manager bridge ready") }
            .onFailure { RuntimeDiagnostics.log("PM", "package manager bridge fallback: ${it.stackTraceToString()}") }

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

    fun requireRuntimeBridge() {
        check(runtimeBridgeReady) {
            "جسر تشغيل النسخ غير متاح على هذا الجهاز. افتح «التشخيص» وانسخ السجل."
        }
    }
}
