package com.shahboun.multi

import android.content.Context
import android.os.Build
import android.webkit.WebView

internal object RuntimeCompatibilityMatrixV4 {
    data class Entry(val name: String, val state: String, val detail: String)

    private val serviceNames = linkedMapOf(
        "connectivity" to "connectivity",
        "wifi" to "wifi",
        "location" to "location",
        "telephony" to "phone",
        "subscriptions" to "telephony_subscription_service",
        "account" to "account",
        "audio" to "audio",
        "media-session" to "media_session",
        "media-router" to "media_router",
        "sensors" to "sensor",
        "camera" to "camera",
        "vibrator" to "vibrator",
        "vibrator-manager" to "vibrator_manager",
        "storage" to "storage",
        "downloads" to "download",
        "power" to "power",
        "device-policy" to "device_policy",
        "display" to "display",
        "shortcuts" to "shortcut",
        "launcher-apps" to "launcherapps",
        "usage-stats" to "usagestats",
        "bluetooth" to "bluetooth",
        "input-method" to "input_method",
        "autofill" to "autofill",
        "companion-device" to "companiondevice",
        "biometric" to "biometric",
        "role" to "role",
        "app-widget" to "appwidget",
        "clipboard" to Context.CLIPBOARD_SERVICE,
        "jobs" to Context.JOB_SCHEDULER_SERVICE,
        "alarms" to Context.ALARM_SERVICE,
        "notifications" to Context.NOTIFICATION_SERVICE
    )

    fun run(context: Context): List<Entry> {
        val result = ArrayList<Entry>()
        val sdkState = if (Build.VERSION.SDK_INT in 29..36) "FULL" else "PARTIAL"
        result += Entry("android", sdkState, "sdk=${Build.VERSION.SDK_INT} android=${Build.VERSION.RELEASE} ${Build.MANUFACTURER}/${Build.MODEL}")

        val nativeLoaded = RuntimeNativeRuntime.initialize()
        result += when {
            !nativeLoaded -> Entry("native", "FAIL", "shahboun_runtime unavailable")
            RuntimeNativeRuntime.hasSyscallInterception() -> Entry("native", "FULL", "path mapping + syscall interception")
            else -> Entry("native", "PARTIAL", "path mapping/reverse mapping active; guest native syscall interception not yet proven")
        }

        serviceNames.forEach { (label, name) ->
            val manager = runCatching { context.getSystemService(name) }.getOrNull()
            result += if (manager != null) Entry("service:$label", "PRESENT", manager.javaClass.name)
            else Entry("service:$label", "NOT TESTED", "manager unavailable on this device/profile")
        }

        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        result += if (webView != null) Entry("webview", "PRESENT", "${webView.packageName}@${webView.versionName}")
        else Entry("webview", "FAIL", "provider missing")

        val gms = runCatching { context.packageManager.getPackageInfo("com.google.android.gms", 0) }.getOrNull()
        result += if (gms != null) {
            val code = if (Build.VERSION.SDK_INT >= 28) gms.longVersionCode else @Suppress("DEPRECATION") gms.versionCode.toLong()
            Entry("gms", "PRESENT", "version=$code; live auth/push still requires guest validation")
        } else Entry("gms", "NOT TESTED", "Google Play services not installed")

        val regressions = RuntimeRegressionSuite.run(context)
        result += Entry(
            "regression-static",
            if (regressions.all { it.passed }) "FULL" else "FAIL",
            "pass=${regressions.count { it.passed }} fail=${regressions.count { !it.passed }}"
        )

        result.forEach { RuntimeDiagnostics.log("MATRIX5", "${it.state} ${it.name} ${it.detail}") }
        return result
    }

    fun summary(context: Context): String {
        val entries = run(context)
        val fail = entries.count { it.state == "FAIL" }
        val partial = entries.count { it.state == "PARTIAL" }
        val notTested = entries.count { it.state == "NOT TESTED" }
        val full = entries.size - fail - partial - notTested
        return "Runtime5 matrix full=$full partial=$partial fail=$fail notTested=$notTested total=${entries.size}"
    }
}
