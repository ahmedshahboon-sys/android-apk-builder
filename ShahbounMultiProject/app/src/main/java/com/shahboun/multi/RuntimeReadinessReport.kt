package com.shahboun.multi

/** Builds an evidence-based readiness verdict from the runtime log. */
object RuntimeReadinessReport {
    private data class Check(val name: String, val state: String, val detail: String = "")

    fun render(log: String): String {
        if (log.isBlank()) return "--- READINESS ---\nStatus: NOT TESTED\nلا توجد تجربة تشغيل في السجل.\n"

        val checks = ArrayList<Check>()
        val crashBlock = latestCrashBlock(log)
        val fatal = crashBlock.isNotBlank()

        checks += status("Process isolation", log.contains("MultiApplication onCreate process=${BuildConfig.APPLICATION_ID}:clone"), "لم تبدأ عملية clone منفصلة")
        checks += status("Guest DEX", log.contains("guest-first isolated classloader enabled"), "لم يثبت تحميل كود الضيف")
        checks += resourceCheck(log, crashBlock)
        checks += status("Guest application", log.contains("guest Application ready"), "Application.onCreate لم يكتمل")
        checks += status("Providers", log.contains("guest providers ready"), "تهيئة Providers لم تكتمل")
        checks += contentResolverCheck(log, crashBlock)
        checks += status("LoadedApk", log.contains("[LOADEDAPK] bound"), "Virtual LoadedApk لم يثبت")
        checks += status("Activity launch", log.contains("[RUNTIME] activity bound"), "لم يتم إنشاء Activity ضيف")
        checks += internalRouting(log, crashBlock)
        checks += packageManagerCheck(log, crashBlock)
        checks += bridge(log, "Notifications", "notifications")
        checks += bridge(log, "PendingIntent / ActivityManager", "activity-manager-pending-intent")
        checks += bridge(log, "AlarmManager", "alarm")
        checks += jobCheck(log, crashBlock)
        checks += bridge(log, "Clipboard", "clipboard")
        checks += identityCheck(log)
        checks += accountCheck(log)
        checks += nativeCheck(log)
        checks += serviceVirtualizationCheck(log)
        checks += regressionCheck(log)

        checks += runtimeFeature(log, "Background services", listOf("[SERVICE] created ", "[SERVICE] created guest", "[SERVICE] start"))
        checks += runtimeFeature(log, "Broadcast receivers", listOf("[RECEIVER]", "broadcast routed="))
        checks += runtimeFeature(log, "Notifications delivery", listOf("[NOTIFY] routed", "[NOTIFY] notify"))
        checks += runtimeFeature(log, "Camera / microphone", listOf("camera opened", "CameraDevice", "AudioRecord", "MediaRecorder"))
        checks += webViewCheck(log)
        checks += gmsCheck(log)
        checks += cloneIsolationCheck(log)

        val blocker = classifyBlocker(crashBlock)
        val hardFail = checks.any { it.state == "FAIL" }
        val degraded = checks.any { it.state == "FALLBACK" || it.state == "PARTIAL" || it.state == "NOT TESTED" }
        val verdict = when {
            fatal || hardFail -> "BLOCKED"
            degraded -> "PARTIAL"
            else -> "READY"
        }

        return buildString {
            appendLine("--- READINESS ---")
            appendLine("Status: $verdict")
            if (blocker.isNotBlank()) appendLine("Current blocker: $blocker")
            checks.forEach { c ->
                append("${symbol(c.state)} ${c.name}: ${c.state}")
                if (c.detail.isNotBlank()) append(" — ${c.detail}")
                appendLine()
            }
            appendLine()
            appendLine("Categories:")
            appendLine("Structural: ${category(checks, listOf("Guest DEX", "Guest resources", "LoadedApk", "PackageManager", "Native filesystem"))}")
            appendLine("Live launch: ${category(checks, listOf("Process isolation", "Guest application", "Providers", "Activity launch", "Internal activity routing"))}")
            appendLine("Components/background: ${category(checks, listOf("Background services", "Broadcast receivers", "JobScheduler", "AlarmManager"))}")
            appendLine("Media/Web: ${category(checks, listOf("Camera / microphone", "WebView"))}")
            appendLine("GMS: ${category(checks, listOf("GMS / Firebase"))}")
            appendLine("Clone isolation: ${category(checks, listOf("Clone isolation", "Native filesystem", "PendingIntent / ActivityManager", "Notifications"))}")
            if (verdict != "READY") appendLine("Result: النسخة ليست جاهزة للاعتماد النهائي حتى تختفي FAIL/FALLBACK/PARTIAL وتُختبر عناصر NOT TESTED فعليًا.")
            else appendLine("Result: كل بوابات الجاهزية المسجلة اجتازت اختبارات تشغيل فعلية في هذه الجلسة.")
        }
    }

    private fun category(checks: List<Check>, names: List<String>): String {
        val selected = checks.filter { it.name in names }
        if (selected.isEmpty() || selected.any { it.state == "FAIL" }) return "BLOCKED"
        if (selected.any { it.state == "FALLBACK" || it.state == "PARTIAL" || it.state == "NOT TESTED" }) return "PARTIAL"
        return "READY"
    }

    private fun status(name: String, ok: Boolean, failDetail: String): Check = if (ok) Check(name, "OK") else Check(name, "NOT TESTED", failDetail)

    private fun nativeCheck(log: String): Check = when {
        log.contains("[NATIVE5] load failed") -> Check("Native filesystem", "FAIL", "native runtime failed to load")
        log.contains("Shahboun native runtime ready path-map-v2") || log.contains("[NATIVE5] registered root") -> Check("Native filesystem", "PARTIAL", "path/reverse mapping active; native syscall interception is not implemented/proven")
        log.contains("[NATIVE4] Shahboun native runtime ready") -> Check("Native filesystem", "PARTIAL", "legacy path helper only")
        else -> Check("Native filesystem", "NOT TESTED")
    }

    private fun serviceVirtualizationCheck(log: String): Check {
        val lines = log.lineSequence().filter { it.contains("[SERVICE5]") }.toList()
        if (lines.isEmpty()) return Check("System service virtualization", "NOT TESTED")
        if (lines.any { it.contains(" failed ") || it.contains(" failed") }) return Check("System service virtualization", "FAIL", "one or more manager binder proxies failed")
        if (lines.any { it.contains("passthrough") }) return Check("System service virtualization", "PARTIAL", "some managers use passthrough")
        return Check("System service virtualization", "OK", "observed managers used binder identity proxies")
    }

    private fun regressionCheck(log: String): Check {
        val fail = log.lineSequence().any { it.contains("[REGRESSION5] FAIL") }
        val pass = log.lineSequence().count { it.contains("[REGRESSION5] PASS") }
        return when {
            fail -> Check("Static regressions", "FAIL", "regression gate failed")
            pass > 0 -> Check("Static regressions", "OK", "$pass checks passed")
            else -> Check("Static regressions", "NOT TESTED")
        }
    }

    private fun resourceCheck(log: String, crash: String): Check {
        val failed = crash.contains("Resources\$NotFoundException")
        val probed = log.contains("activity probe ok") && log.contains("base probe ok") && log.contains("inflater probe ok")
        val prepared = log.contains("[RES] activity graph prepared") && log.contains("loaderActivity=true") && log.contains("loaderBase=true")
        return when {
            failed -> Check("Guest resources", "FAIL", "Resources.NotFoundException")
            probed -> Check("Guest resources", "OK", "resource probes passed")
            prepared -> Check("Guest resources", "PARTIAL", "loader prepared; full resource behavior not exercised")
            else -> Check("Guest resources", "NOT TESTED", "لم يثبت تجهيز موارد Activity/base")
        }
    }

    private fun internalRouting(log: String, crash: String): Check = when {
        crash.contains("ActivityNotFoundException") -> Check("Internal activity routing", "FAIL", "ActivityNotFoundException")
        log.contains("[ROUTE] activity") -> Check("Internal activity routing", "OK")
        else -> Check("Internal activity routing", "NOT TESTED")
    }

    private fun packageManagerCheck(log: String, crash: String): Check {
        val failure = crash.contains("setComponentEnabledSetting") || (crash.contains("Attempt to change component state") && crash.contains("PackageManager"))
        return when {
            failure -> Check("PackageManager", "FAIL", "guest component state mutation escaped to Android system")
            log.contains("[PM] virtual component state") -> Check("PackageManager", "OK", "virtual component state active")
            else -> bridge(log, "PackageManager", "package-manager")
        }
    }

    private fun contentResolverCheck(log: String, crash: String): Check {
        val unsupported = crash.contains("UnsupportedOperationException") && (crash.contains("ContentResolver") || crash.contains("acquireUnstableProvider") || crash.contains("ContentProviderClient"))
        return when {
            unsupported -> Check("ContentResolver / Provider clients", "FAIL", "provider-client acquisition غير مدعوم")
            log.contains("[CONTENT] resolver mode=system-client-safe") -> Check("ContentResolver / Provider clients", "OK", "framework resolver")
            log.contains("[CONTENT] resolver mode=legacy-multiplexer") -> Check("ContentResolver / Provider clients", "PARTIAL", "legacy resolver")
            else -> Check("ContentResolver / Provider clients", "NOT TESTED")
        }
    }

    private fun bridge(log: String, name: String, key: String): Check {
        val ready = log.contains("[BRIDGE] $key=ready")
        val fallbackLine = log.lineSequence().lastOrNull { it.contains("[BRIDGE] $key=fallback") }
        return when {
            fallbackLine != null -> Check(name, "FALLBACK", fallbackLine.substringAfter("fallback ").take(180))
            ready -> Check(name, "PARTIAL", "bridge installed; feature delivery still requires live exercise")
            else -> Check(name, "NOT TESTED")
        }
    }

    private fun jobCheck(log: String, crash: String): Check {
        if (crash.contains("StackOverflowError") && crash.contains("JobScheduler")) return Check("JobScheduler", "FAIL", "recursive JobScheduler lookup")
        val scheduled = log.contains("[JOB] schedule ") || log.contains("created guest JobService")
        val facade = log.contains("public-api JobScheduler facade active")
        return when {
            scheduled -> Check("JobScheduler", "OK", "clone-scoped job route exercised")
            facade -> Check("JobScheduler", "PARTIAL", "public API facade installed; no live job callback observed")
            else -> bridge(log, "JobScheduler", "jobs")
        }
    }

    private fun identityCheck(log: String): Check {
        val crash = latestCrashBlock(log)
        val security = crash.contains("Package ") && crash.contains("does not belong to")
        return when {
            security -> Check("Framework identity", "FAIL", "package/UID mismatch")
            log.contains("Binder identity sanitized") -> Check("Framework identity", "OK", "binder boundary sanitization exercised")
            log.contains("[BRIDGE] identity=ready") -> Check("Framework identity", "PARTIAL", "bridge installed; full identity matrix not exercised")
            else -> Check("Framework identity", "NOT TESTED")
        }
    }

    private fun accountCheck(log: String): Check = when {
        log.contains("ACCOUNT identity proxy installed") -> Check("AccountManager", "OK")
        log.contains("ACCOUNT public-api passthrough active") || log.contains("ACCOUNT public-api service") -> Check("AccountManager", "PARTIAL", "public API passthrough")
        log.contains("ACCOUNT binder service unavailable") || log.contains("ACCOUNT manager unavailable") -> Check("AccountManager", "FALLBACK", "AccountManager binder غير متاح")
        else -> Check("AccountManager", "NOT TESTED")
    }

    private fun webViewCheck(log: String): Check = when {
        log.contains("data directory isolation failed") || log.contains("suffix setup failed") -> Check("WebView", "FAIL", "WebView data directory isolation failed")
        log.contains("[WEBVIEW] isolated data directory") && log.contains("[WEB4] clone") -> Check("WebView", "PARTIAL", "process/storage isolation prepared; cookies/OAuth/renderer not live-tested")
        else -> Check("WebView", "NOT TESTED")
    }

    private fun gmsCheck(log: String): Check = when {
        log.contains("[GMS4]") -> Check("GMS / Firebase", "PARTIAL", "package presence/storage prepared; login/FCM callbacks not live-tested")
        else -> Check("GMS / Firebase", "NOT TESTED")
    }

    private fun cloneIsolationCheck(log: String): Check = when {
        log.contains("[REGRESSION5] PASS native-path-isolation") -> Check("Clone isolation", "PARTIAL", "static two-slot filesystem mapping passed; two live app instances still required")
        else -> Check("Clone isolation", "NOT TESTED")
    }

    private fun runtimeFeature(log: String, name: String, markers: List<String>): Check =
        if (markers.any(log::contains)) Check(name, "OK") else Check(name, "NOT TESTED")

    private fun latestCrashBlock(log: String): String {
        val idx = log.lastIndexOf("[CRASH]")
        if (idx < 0) return ""
        return log.substring(idx).take(7000)
    }

    private fun classifyBlocker(crash: String): String = when {
        crash.isBlank() -> ""
        crash.contains("StackOverflowError") && crash.contains("JobScheduler") -> "JobScheduler recursion"
        crash.contains("IgSessionManager not initialized") -> "Guest initialization order / Instagram session bootstrap"
        crash.contains("Application did not provide its own FbAppType") -> "Guest initialization identity / Facebook AppShell"
        crash.contains("ActivityNotFoundException") -> "Internal Activity Routing"
        crash.contains("Resources\$NotFoundException") -> "Guest Resources"
        crash.contains("does not belong to") -> "Framework package/UID identity"
        crash.contains("UnsatisfiedLinkError") -> "Guest Native Libraries / JNI"
        crash.contains("ClassNotFoundException") -> "DEX / component resolution"
        crash.contains("SecurityException") -> "Android framework identity / permission"
        else -> crash.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim().orEmpty().ifBlank { "Runtime crash" }
    }

    private fun symbol(state: String): String = when (state) {
        "OK", "READY" -> "✓"
        "FAIL", "BLOCKED" -> "✕"
        "FALLBACK" -> "!"
        "PARTIAL" -> "~"
        else -> "·"
    }
}
