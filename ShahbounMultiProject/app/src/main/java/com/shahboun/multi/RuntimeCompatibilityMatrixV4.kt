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
            !nativeLoaded -> Entry("native:path", "FAIL", "shahboun_runtime unavailable")
            else -> Entry("native:path", "PARTIAL", "path-map-v4 + reverse + relative logical mapping active; live JNI/native .so calls still require validation")
        }
        result += Entry(
            "native:syscall-interception",
            if (RuntimeNativeRuntime.hasSyscallInterception()) "FULL" else "UNSUPPORTED",
            if (RuntimeNativeRuntime.hasSyscallInterception()) "interception proven" else "no libc/syscall hook installed; deliberate stability boundary on Android 16"
        )
        result += Entry(
            "native:linker-namespace",
            if (RuntimeNativeRuntime.hasLinkerNamespaceIsolation()) "FULL" else "PARTIAL",
            if (RuntimeNativeRuntime.hasLinkerNamespaceIsolation()) "isolated linker namespace proven" else "clone-specific extraction/search path exists; kernel/linker namespace isolation not claimed"
        )

        serviceNames.forEach { (label, name) ->
            val manager = runCatching { context.getSystemService(name) }.getOrNull()
            if (manager == null) {
                result += Entry("service:$label", "NOT TESTED", "manager unavailable on this device/profile")
            } else {
                val capability = RuntimeSystemServiceVirtualizer.stateFor(name)
                result += if (capability == null) {
                    Entry("service:$label", "NOT TESTED", "manager present (${manager.javaClass.name}); guest Binder path not exercised")
                } else {
                    Entry("service:$label", capability.state.name, capability.detail)
                }
            }
        }

        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        result += if (webView != null) Entry("webview", "PARTIAL", "${webView.packageName}@${webView.versionName}; provider present, clone login/media/OAuth require live validation")
        else Entry("webview", "FAIL", "provider missing")

        val gms = runCatching { context.packageManager.getPackageInfo("com.google.android.gms", 0) }.getOrNull()
        result += if (gms != null) {
            val code = if (Build.VERSION.SDK_INT >= 28) gms.longVersionCode else @Suppress("DEPRECATION") gms.versionCode.toLong()
            Entry("gms", "PARTIAL", "version=$code; auth/push/account callbacks require live guest validation")
        } else Entry("gms", "NOT TESTED", "Google Play services not installed")

        val regressions = RuntimeRegressionSuite.run(context)
        result += Entry(
            "regression-static",
            if (regressions.all { it.passed }) "FULL" else "FAIL",
            "pass=${regressions.count { it.passed }} fail=${regressions.count { !it.passed }}"
        )

        result.forEach { RuntimeDiagnostics.log("MATRIX7", "${it.state} ${it.name} ${it.detail}") }
        return result
    }

    fun summary(context: Context): String {
        val entries = run(context)
        val counts = entries.groupingBy { it.state }.eachCount().toSortedMap()
        val blocked = entries.any { it.state == "FAIL" }
        val state = if (blocked) "BLOCKED" else if (entries.all { it.state == "FULL" }) "READY" else "PARTIAL"
        return "Runtime7 matrix state=$state " + counts.entries.joinToString(" ") { "${it.key.lowercase()}=${it.value}" } + " total=${entries.size}"
    }
}
