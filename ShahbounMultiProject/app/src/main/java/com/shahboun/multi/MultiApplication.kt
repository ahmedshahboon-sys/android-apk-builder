package com.shahboun.multi

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.job.JobScheduler
import android.os.Build
import android.os.Bundle
import android.webkit.WebView

class MultiApplication : Application() {
    lateinit var engine: ShahbounRuntime3Engine
        private set

    var runtimeBridgeReady: Boolean = false
        private set

    override fun onCreate() {
        RuntimePerformanceProbe.mark("app-start")
        super.onCreate()
        current = this

        runCatching { RuntimeDiagnostics.initialize(this) }
        runCatching { RuntimeDeepDiagnostics.initialize(this) }
        runCatching { RuntimeDiagnostics.installCrashHandler() }

        val processName = currentProcessName()
        safeStartup("APP") { RuntimeDiagnostics.log("APP", "MultiApplication onCreate process=$processName app=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} engine=${RuntimeEngineVersion.ENGINE_VERSION}") }
        safeStartup("COMPAT") { RuntimeCompatibility.logProfile() }
        safeStartup("NATIVE7") { RuntimeNativeRuntime.initialize() }
        safeStartup("WEBVIEW") { installWebViewIsolation() }
        safeStartup("WEB-GMS7") { RuntimeWebGmsCompatibility.prepareProcess(this, processName) }
        safeStartup("BARS") { SystemBarsFitter.install(this) }
        if (processName == packageName) safeStartup("DIAG-PROMPT") { installDiagnosticsPrompt() }
        if (processName == packageName) safeStartup("JOB-MIGRATE") { migrateLegacyJobRecords() }

        engine = ShahbounRuntime3Engine()
        val engineResult = engine.initialize(this)
        engineResult
            .onSuccess {
                safeStartup("ENGINE7") { RuntimeDiagnostics.log("ENGINE7", "initialized implementation=${engine.name} version=${RuntimeEngineVersion.ENGINE_VERSION}") }
                if (processName == packageName) safeStartup("LEGACY-CLEAN") { Runtime3LegacyCleaner.cleanup(this) }
            }
            .onFailure {
                safeStartup("ENGINE7") { RuntimeDiagnostics.log("ENGINE7", "initialize failed without killing host UI: ${it.stackTraceToString()}") }
            }

        if (processName == packageName && engineResult.isSuccess) safeStartup("UPDATE") { RuntimeUpdateCenter.checkAndNotify(this, engine) }

        safeStartup("BRIDGES") { RuntimeBridgeRegistry.install(this) }
        safeStartup("SYSTEM-EVENTS") { RuntimeSystemEvents.install(this) }
        safeStartup("GAP-AUDIT7") { RuntimeDiagnostics.log("GAP7", RuntimeGapAudit.render().replace('\n', ' ').take(7000)) }

        val launchBridgeReady = runCatching { RuntimeLaunchTransactionBridge.install() }
            .getOrElse { Result.failure(it) }
            .onFailure { safeStartup("LAUNCH7") { RuntimeDiagnostics.log("LAUNCH7", "pre-attach bridge fallback: ${it.stackTraceToString()}") } }
            .isSuccess

        val instrumentationReady = runCatching { RuntimeInstrumentationInstaller.install() }
            .getOrElse { Result.failure(it) }
            .onFailure {
                runtimeBridgeReady = false
                safeStartup("RUNTIME7") { RuntimeDiagnostics.log("RUNTIME7", "instrumentation bridge unavailable: ${it.stackTraceToString()}") }
            }
            .isSuccess

        runtimeBridgeReady = engineResult.isSuccess && instrumentationReady && RuntimeBridgeRegistry.isCoreReady() && launchBridgeReady
        RuntimePerformanceProbe.mark("runtime-ready")
        safeStartup("RUNTIME7") {
            RuntimeDiagnostics.log(
                "RUNTIME7",
                "startup completed hostAlive=true engine=${engineResult.isSuccess} instrumentation=$instrumentationReady core=${RuntimeBridgeRegistry.isCoreReady()} launch=$launchBridgeReady native=${RuntimeNativeRuntime.isLoaded()} ready=$runtimeBridgeReady app=${BuildConfig.VERSION_NAME} engineVersion=${RuntimeEngineVersion.ENGINE_VERSION}"
            )
        }
    }

    private fun installDiagnosticsPrompt() {
        if (!RuntimeDiagnostics.hasPersistentIssues()) return
        val prefs = getSharedPreferences("shahboun_diagnostics_prompt", MODE_PRIVATE)
        val versionKey = currentVersionCode().toString()
        if (prefs.getBoolean(versionKey, false)) return

        val callbacks = object : ActivityLifecycleCallbacks {
            private var handled = false
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (handled || activity !is MainActivity) return
                handled = true
                prefs.edit().putBoolean(versionKey, true).apply()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(activity)
                        .setTitle("تقارير تشخيص سابقة")
                        .setMessage("توجد مشاكل محفوظة من تجارب سابقة. هل تريد حذف التقارير وبدء جلسة تشخيص نظيفة؟")
                        .setPositiveButton("نعم، ابدأ من جديد") { _, _ -> RuntimeDiagnostics.clear() }
                        .setNegativeButton("لا، احتفظ بها", null)
                        .setCancelable(false)
                        .show()
                }
                unregisterActivityLifecycleCallbacks(this)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        registerActivityLifecycleCallbacks(callbacks)
    }

    private inline fun safeStartup(tag: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            runCatching { RuntimeDiagnostics.log("STARTUP", "$tag failed but host continues: ${error.javaClass.simpleName}: ${error.message}") }
        }
    }

    private fun migrateLegacyJobRecords() {
        val migrationPrefs = getSharedPreferences("shahboun_runtime_migrations", MODE_PRIVATE)
        val schema = 7
        if (migrationPrefs.getInt("job_runtime_schema", 0) >= schema) return
        runCatching { getSystemService(JobScheduler::class.java)?.cancelAll() }
            .onFailure { RuntimeDiagnostics.log("JOB", "legacy system job cleanup failed: ${it.javaClass.simpleName}: ${it.message}") }
        val cleared = getSharedPreferences("shahboun_runtime_jobs", MODE_PRIVATE).edit().clear().commit()
        migrationPrefs.edit().putInt("job_runtime_schema", schema).commit()
        RuntimeDiagnostics.log("JOB", "Runtime7 job migration complete recordsCleared=$cleared")
    }

    private fun installWebViewIsolation() {
        if (Build.VERSION.SDK_INT < 28) return
        val process = currentProcessName()
        val processIndex = Regex(":clone([0-9]+)$").find(process)?.groupValues?.getOrNull(1) ?: return
        runCatching { WebView.setDataDirectorySuffix("shahboun_process_$processIndex") }
            .onSuccess { RuntimeDiagnostics.log("WEBVIEW", "isolated data directory processIndex=$processIndex process=$process") }
            .onFailure { RuntimeDiagnostics.log("WEBVIEW", "data directory isolation failed processIndex=$processIndex: ${it.stackTraceToString()}") }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(0L)

    private fun currentProcessName(): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName

    fun requireRuntimeBridge() {
        check(runtimeBridgeReady) { "جسر Shahboun Runtime ${RuntimeEngineVersion.ENGINE_VERSION} غير متاح على هذا الجهاز. افتح «التشخيص» وانسخ السجل." }
    }

    companion object {
        @Volatile var current: MultiApplication? = null
            private set
    }
}
