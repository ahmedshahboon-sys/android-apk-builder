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
        checks += resourceCheck(log, crashBlock)
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

        checks += packageManagerCheck(log, crashBlock)
        checks += bridge(log, "Notifications", "notifications")
        checks += bridge(log, "PendingIntent / ActivityManager", "activity-manager-pending-intent")
        checks += bridge(log, "AlarmManager", "alarm")
        checks += bridge(log, "JobScheduler", "jobs", "public-api JobScheduler facade active")
        checks += bridge(log, "Clipboard", "clipboard", "public-api clipboard compatibility active")
        checks += identityCheck(log)
        checks += accountCheck(log)

        checks += runtimeFeature(log, "Background services", listOf("[SERVICE] created ", "[SERVICE] created guest", "[SERVICE] start"))
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

    private fun resourceCheck(log: String, crash: String): Check {
        val failed = crash.contains("Resources\$NotFoundException")
        val probed = hasAllResourceProbes(log)
        val prepared = log.contains("[RES] activity graph prepared") && log.contains("loaderActivity=true") && log.contains("loaderBase=true")
        return when {
            failed -> Check("Guest resources", "FAIL", "Resources.NotFoundException")
            probed -> Check("Guest resources", "OK", "resource probes passed")
            prepared -> Check("Guest resources", "OK", "activity/base resource loaders prepared")
            else -> Check("Guest resources", "NOT TESTED", "لم يثبت تجهيز موارد Activity/base")
        }
    }

    private fun packageManagerCheck(log: String, crash: String): Check {
        val componentStateFailure = crash.contains("setComponentEnabledSetting") ||
            (crash.contains("Attempt to change component state") && crash.contains("PackageManager"))
        return when {
            componentStateFailure -> Check("PackageManager", "FAIL", "guest component state mutation escaped to Android system")
            log.contains("[PM] virtual component state") -> Check("PackageManager", "OK", "virtual component state active")
            else -> bridge(log, "PackageManager", "package-manager")
        }
    }

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

    private fun bridge(log: String, name: String, key: String, compatibilityMarker: String? = null): Check {
        val ready = log.contains("[BRIDGE] $key=ready")
        val compatible = compatibilityMarker?.let(log::contains) == true
        val fallbackLine = log.lineSequence().lastOrNull { it.contains("[BRIDGE] $key=fallback") }
        return when {
            ready && compatible -> Check(name, "OK", "Android public API compatibility")
            ready -> Check(name, "OK")
            compatible -> Check(name, "OK", "Android public API compatibility")
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
        log.contains("ACCOUNT public-api passthrough active") || log.contains("ACCOUNT public-api service") -> Check("AccountManager", "OK", "Android public API compatibility")
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
        crash.contains("RuntimeGuestContext.unregisterReceiver") && crash.contains("NullPointerException") -> "BroadcastReceiver lifecycle / unregisterReceiver(null)"
        crash.contains("setComponentEnabledSetting") || crash.contains("Attempt to change component state") -> "Virtual PackageManager / component state"
        crash.contains("UnsupportedOperationException") && (crash.contains("ContentResolver") || crash.contains("acquireUnstableProvider")) -> "ContentResolver / ContentProvider client acquisition"
        crash.contains("ActivityNotFoundException") -> "Internal Activity Routing"
        crash.contains("Resources\$NotFoundException") -> "Guest Resources"
        crash.contains("does not belong to") -> "Framework package/UID identity"
        crash.contains("UnsatisfiedLinkError") -> "Guest Native Libraries / JNI"
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
