package com.shahboun.multi

/** Builds a concise readiness verdict from the runtime log without running guest code. */
object RuntimeReadinessReport {
    private data class Check(val name: String, val state: String, val detail: String = "")

    fun render(log: String): String {
        if (log.isBlank()) return "--- READINESS ---\nStatus: NOT TESTED\nلا توجد تجربة تشغيل في السجل.\n"

        val checks = ArrayList<Check>()
        val crashBlock = latestCrashBlock(log)
        val fatal = crashBlock.isNotBlank()

        checks += status("Process isolation", log.contains("MultiApplication onCreate process=${BuildConfig.APPLICATION_ID}:clone"), "لم تبدأ عملية clone منفصلة")
        checks += status("Guest DEX", log.contains("guest-first isolated classloader enabled"), "لم يثبت تحميل كود الضيف")
        checks += status("Guest resources", hasAllResourceProbes(log), "موارد Activity/base/inflater لم تجتز الفحص")
        checks += status("Guest application", log.contains("guest Application ready"), "Application.onCreate لم يكتمل")
        checks += status("Providers", log.contains("guest providers ready"), "تهيئة Providers لم تكتمل")
        checks += contentResolverCheck(log, crashBlock)
        checks += status("LoadedApk", log.contains("[LOADEDAPK] bound"), "Virtual LoadedApk لم يثبت")
        checks += status("Activity launch", log.contains("[RUNTIME] activity bound"), "لم يتم إنشاء Activity ضيف")

        val routed = log.contains("[ROUTE] activity")
        val activityNotFound = crashBlock.contains("ActivityNotFoundException")
        checks += when {
            routed && !activityNotFound -> Check("Internal activity routing", "OK")
            activityNotFound -> Check("Internal activity routing", "FAIL", "ActivityNotFoundException")
            else -> Check("Internal activity routing", "NOT TESTED", "لم يحدث انتقال داخلي بعد")
        }

        checks += bridge(log, "PackageManager", "package-manager")
        checks += bridge(log, "Notifications", "notifications")
        checks += bridge(log, "PendingIntent / ActivityManager", "activity-manager-pending-intent")
        checks += bridge(log, "AlarmManager", "alarm")
        checks += bridge(log, "JobScheduler", "jobs")
        checks += bridge(log, "Clipboard", "clipboard")
        checks += identityCheck(log)
        checks += accountCheck(log)

        checks += runtimeFeature(log, "Background services", listOf("[SERVICE] created guest", "[SERVICE] start"))
        checks += runtimeFeature(log, "Broadcast receivers", listOf("[RECEIVER]", "broadcast routed="))
        checks += runtimeFeature(log, "Notifications delivery", listOf("[NOTIFY] routed", "[NOTIFY] notify"))
        checks += runtimeFeature(log, "Camera / microphone", listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"), permissionOnly = true)
        checks += runtimeFeature(log, "WebView", listOf("[WEBVIEW] isolated data directory"))

        val blocker = classifyBlocker(crashBlock)
        val hardFail = checks.any { it.state == "FAIL" }
        val fallback = checks.any { it.state == "FALLBACK" }
        val untested = checks.any { it.state == "NOT TESTED" }
        val verdict = when {
            fatal || hardFail -> "BLOCKED"
            fallback || untested -> "PARTIAL"
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
            if (verdict != "READY") appendLine("Result: النسخة ليست جاهزة للاعتماد النهائي حتى تختفي FAIL/FALLBACK وتُختبر العناصر المطلوبة.")
            else appendLine("Result: اختبارات التشغيل المسجلة اجتازت بوابة الجاهزية.")
        }
    }

    private fun status(name: String, ok: Boolean, failDetail: String): Check = if (ok) Check(name, "OK") else Check(name, "NOT TESTED", failDetail)

    private fun contentResolverCheck(log: String, crash: String): Check {
        val unsupported = crash.contains("UnsupportedOperationException") &&
            (crash.contains("ContentResolver") || crash.contains("acquireUnstableProvider") || crash.contains("ContentProviderClient"))
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
            ready -> Check(name, "OK")
            fallbackLine != null -> Check(name, "FALLBACK", fallbackLine.substringAfter("fallback ").take(180))
            else -> Check(name, "NOT TESTED")
        }
    }

    private fun identityCheck(log: String): Check {
        val crash = latestCrashBlock(log)
        val security = crash.contains("Package ") && crash.contains("does not belong to")
        return when {
            security -> Check("Framework identity", "FAIL", "package/UID mismatch")
            log.contains("[BRIDGE] identity=ready") -> Check("Framework identity", "OK")
            else -> Check("Framework identity", "NOT TESTED")
        }
    }

    private fun accountCheck(log: String): Check = when {
        log.contains("ACCOUNT identity proxy installed") -> Check("AccountManager", "OK")
        log.contains("ACCOUNT binder service unavailable") -> Check("AccountManager", "FALLBACK", "IAccountManager binder غير متاح")
        log.contains("ACCOUNT manager unavailable") -> Check("AccountManager", "FALLBACK", "AccountManager غير متاح")
        else -> Check("AccountManager", "NOT TESTED")
    }

    private fun runtimeFeature(log: String, name: String, markers: List<String>, permissionOnly: Boolean = false): Check {
        if (permissionOnly) return if (markers.any(log::contains)) Check(name, "PARTIAL", "الصلاحية ظهرت؛ الاستخدام الفعلي لم يُثبت") else Check(name, "NOT TESTED")
        return if (markers.any(log::contains)) Check(name, "OK") else Check(name, "NOT TESTED")
    }

    private fun hasAllResourceProbes(log: String): Boolean = log.contains("activity probe ok") && log.contains("base probe ok") && log.contains("inflater probe ok")

    private fun latestCrashBlock(log: String): String {
        val idx = log.lastIndexOf("[CRASH]")
        if (idx < 0) return ""
        return log.substring(idx).take(7000)
    }

    private fun classifyBlocker(crash: String): String = when {
        crash.isBlank() -> ""
        crash.contains("UnsupportedOperationException") && (crash.contains("ContentResolver") || crash.contains("acquireUnstableProvider")) -> "ContentResolver / ContentProvider client acquisition"
        crash.contains("ActivityNotFoundException") -> "Internal Activity Routing"
        crash.contains("Resources\$NotFoundException") -> "Guest Resources"
        crash.contains("does not belong to") -> "Framework package/UID identity"
        crash.contains("ClassNotFoundException") -> "DEX / component resolution"
        crash.contains("Theme.AppCompat") -> "Activity theme virtualization"
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
