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

        engine = ShahbounCloneEngine()
        engine.initialize(this)
            .onSuccess { RuntimeDiagnostics.log("ENGINE", "initialized: ${engine.name}") }
            .onFailure { RuntimeDiagnostics.log("ENGINE", "initialize failed: ${it.stackTraceToString()}") }
            .getOrThrow()

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
}
