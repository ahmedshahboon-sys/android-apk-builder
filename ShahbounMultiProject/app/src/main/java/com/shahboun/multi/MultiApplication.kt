package com.shahboun.multi

import android.app.Application
import android.app.job.JobScheduler
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
        RuntimeDeepDiagnostics.initialize(this)
        RuntimeDiagnostics.installCrashHandler()
        val processName = currentProcessName()
        RuntimeDiagnostics.log("APP", "MultiApplication onCreate process=$processName")
        RuntimeCompatibility.logProfile()
        installWebViewIsolation()
        SystemBarsFitter.install(this)

        if (processName == packageName) migrateLegacyJobRecords()

        engine = ShahbounCloneEngine()
        engine.initialize(this)
            .onSuccess { RuntimeDiagnostics.log("ENGINE", "initialized: ${engine.name}") }
            .onFailure { RuntimeDiagnostics.log("ENGINE", "initialize failed: ${it.stackTraceToString()}") }
            .getOrThrow()

        if (processName == packageName) RuntimeUpdateCenter.checkAndNotify(this, engine)

        RuntimeBridgeRegistry.install(this)
        RuntimeSystemEvents.install(this)

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

    private fun migrateLegacyJobRecords() {
        val migrationPrefs = getSharedPreferences("shahboun_runtime_migrations", MODE_PRIVATE)
        val migratedCode = migrationPrefs.getInt("job_runtime_schema", 0)
        if (migratedCode >= BuildConfig.VERSION_CODE) return
        runCatching { getSystemService(JobScheduler::class.java)?.cancelAll() }
            .onFailure { RuntimeDiagnostics.log("JOB", "legacy system job cleanup failed: ${it.javaClass.simpleName}: ${it.message}") }
        val cleared = getSharedPreferences("shahboun_runtime_jobs", MODE_PRIVATE).edit().clear().commit()
        migrationPrefs.edit().putInt("job_runtime_schema", BuildConfig.VERSION_CODE).commit()
        RuntimeDiagnostics.log("JOB", "legacy job migration complete version=${BuildConfig.VERSION_CODE} recordsCleared=$cleared")
    }

    private fun installWebViewIsolation() {
        if (Build.VERSION.SDK_INT < 28) return
        val process = currentProcessName()
        val processIndex = Regex(":clone([0-9]+)$").find(process)?.groupValues?.getOrNull(1) ?: return
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
