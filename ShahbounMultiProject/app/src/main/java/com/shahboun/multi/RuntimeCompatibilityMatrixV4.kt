package com.shahboun.multi

import android.content.Context
import android.os.Build
import android.webkit.WebView

internal object RuntimeCompatibilityMatrixV4 {
    data class Entry(val name: String, val ok: Boolean, val detail: String)

    private val serviceNames = linkedMapOf(
        "connectivity" to Context.CONNECTIVITY_SERVICE,
        "wifi" to Context.WIFI_SERVICE,
        "location" to Context.LOCATION_SERVICE,
        "telephony" to Context.TELEPHONY_SERVICE,
        "account" to Context.ACCOUNT_SERVICE,
        "audio" to Context.AUDIO_SERVICE,
        "sensors" to Context.SENSOR_SERVICE,
        "camera" to Context.CAMERA_SERVICE,
        "vibrator" to Context.VIBRATOR_SERVICE,
        "storage" to Context.STORAGE_SERVICE,
        "clipboard" to Context.CLIPBOARD_SERVICE,
        "jobs" to Context.JOB_SCHEDULER_SERVICE,
        "alarms" to Context.ALARM_SERVICE,
        "notifications" to Context.NOTIFICATION_SERVICE
    )

    fun run(context: Context): List<Entry> {
        val result = ArrayList<Entry>()
        result += Entry("android", Build.VERSION.SDK_INT in 29..36, "sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER}/${Build.MODEL}")
        result += Entry("native", RuntimeNativeRuntime.initialize(), "shahboun_runtime=${RuntimeNativeRuntime.isLoaded()}")
        serviceNames.forEach { (label, name) ->
            val manager = runCatching { context.getSystemService(name) }.getOrNull()
            result += Entry("service:$label", manager != null, manager?.javaClass?.name ?: "missing")
        }
        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        result += Entry("webview", webView != null, webView?.let { "${it.packageName}@${it.versionName}" } ?: "provider missing")
        val gms = runCatching { context.packageManager.getPackageInfo("com.google.android.gms", 0) }.getOrNull()
        result += Entry("gms", gms != null, gms?.let { "present version=${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()}" } ?: "not installed")
        result.forEach { RuntimeDiagnostics.log("MATRIX4", "${if (it.ok) "PASS" else "FAIL"} ${it.name} ${it.detail}") }
        return result
    }

    fun summary(context: Context): String {
        val entries = run(context)
        val pass = entries.count { it.ok }
        val fail = entries.size - pass
        return "Runtime4 matrix pass=$pass fail=$fail total=${entries.size}"
    }
}
