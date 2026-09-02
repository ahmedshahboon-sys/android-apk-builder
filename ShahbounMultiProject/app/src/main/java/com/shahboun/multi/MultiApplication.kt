package com.shahboun.multi

import android.app.Application
import android.app.job.JobScheduler
import android.os.Build
import android.webkit.WebView

class MultiApplication : Application() {
    lateinit var engine: ShahbounRuntime3Engine
        private set

    var runtimeBridgeReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        current = this

        // Nothing outside the core engine is allowed to kill the host UI during Application startup.
        runCatching { RuntimeDiagnostics.initialize(this) }
        runCatching { RuntimeDeepDiagnostics.initialize(this) }
        runCatching { RuntimeDiagnostics.installCrashHandler() }

        val processName = currentProcessName()
        safeStartup("APP") { RuntimeDiagnostics.log("APP", "MultiApplication onCreate process=$processName") }
        safeStartup("COMPAT") { RuntimeCompatibility.logProfile() }
        safeStartup("WEBVIEW") { installWebViewIsolation() }
        safeStartup("BARS") { SystemBarsFitter.install(this) }

        if (processName == packageName) safeStartup("JOB-MIGRATE") { migrateLegacyJobRecords() }

        // Core engine initialization is intentionally tiny (private Runtime 3 storage + factories).
        // Keep the object available even if a recovery/bootstrap path hits malformed legacy state.
        engine = ShahbounRuntime3Engine()
        val engineResult = engine.initialize(this)
        engineResult
            .onSuccess {
                safeStartup("ENGINE3") { RuntimeDiagnostics.log("ENGINE3", "initialized: ${engine.name}") }
                if (processName == packageName) safeStartup("LEGACY-CLEAN") { Runtime3LegacyCleaner.cleanup(this) }
            }
            .onFailure {
                safeStartup("ENGINE3") { RuntimeDiagnostics.log("ENGINE3", "initialize failed without killing host UI: ${it.stackTraceToString()}") }
            }

        if (processName == packageName && engineResult.isSuccess) {
            safeStartup("UPDATE") { RuntimeUpdateCenter.checkAndNotify(this, engine) }
        }

        safeStartup("BRIDGES") { RuntimeBridgeRegistry.install(this) }
        safeStartup("SYSTEM-EVENTS") { RuntimeSystemEvents.install(this) }

        val launchBridgeReady = runCatching { RuntimeLaunchTransactionBridge.install() }
            .getOrElse { Result.failure(it) }
            .onFailure { safeStartup("LAUNCH2") { RuntimeDiagnostics.log("LAUNCH2", "pre-attach bridge fallback: ${it.stackTraceToString()}") } }
            .isSuccess

        val instrumentationReady = runCatching { RuntimeInstrumentationInstaller.install() }
            .getOrElse { Result.failure(it) }
            .onFailure {
                runtimeBridgeReady = false
                safeStartup("RUNTIME") { RuntimeDiagnostics.log("RUNTIME", "instrumentation bridge unavailable: ${it.stackTraceToString()}") }
            }
            .isSuccess

        runtimeBridgeReady = engineResult.isSuccess && instrumentationReady && RuntimeBridgeRegistry.isCoreReady() && launchBridgeReady
        safeStartup("RUNTIME") {
            RuntimeDiagnostics.log(
                "RUNTIME",
                "startup completed hostAlive=true engine=${engineResult.isSuccess} instrumentation=$instrumentationReady core=${RuntimeBridgeRegistry.isCoreReady()} launch2=$launchBridgeReady ready=$runtimeBridgeReady"
            )
        }
    }

    private inline fun safeStartup(tag: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            runCatching { RuntimeDiagnostics.log("STARTUP", "$tag failed but host continues: ${error.javaClass.simpleName}: ${error.message}") }
        }
    }

    private fun migrateLegacyJobRecords() {
        val migrationPrefs = getSharedPreferences("shahboun_runtime_migrations", MODE_PRIVATE)
        val schema = 3
        if (migrationPrefs.getInt("job_runtime_schema", 0) >= schema) return
        runCatching { getSystemService(JobScheduler::class.java)?.cancelAll() }
            .onFailure { RuntimeDiagnostics.log("JOB", "legacy system job cleanup failed: ${it.javaClass.simpleName}: ${it.message}") }
        val cleared = getSharedPreferences("shahboun_runtime_jobs", MODE_PRIVATE).edit().clear().commit()
        migrationPrefs.edit().putInt("job_runtime_schema", schema).commit()
        RuntimeDiagnostics.log("JOB", "Runtime 3 job migration complete recordsCleared=$cleared")
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
        check(runtimeBridgeReady) { "جسر Runtime 3 غير متاح على هذا الجهاز. افتح «التشخيص» وانسخ السجل." }
    }

    companion object {
        @Volatile var current: MultiApplication? = null
            private set
    }
}
