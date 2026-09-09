package com.shahboun.multi

/** Static test plan/readiness matrix. CI evidence and live evidence are deliberately separate. */
internal object RuntimeCompatibilityMatrixV7 {
    enum class Evidence { CODE_COMPLETE, CI_TESTED, EMULATOR_TESTED, REAL_DEVICE_TESTED, LIVE_APP_TESTED, LIVE_VALIDATION_REQUIRED }
    data class Target(val packageName: String, val label: String, val areas: List<String>, val evidence: Evidence)

    val heavyApps = listOf(
        Target("com.whatsapp", "WhatsApp", heavyAreas(), Evidence.LIVE_VALIDATION_REQUIRED),
        Target("com.whatsapp.w4b", "WhatsApp Business", heavyAreas(), Evidence.LIVE_VALIDATION_REQUIRED),
        Target("com.facebook.katana", "Facebook", heavyAreas(), Evidence.LIVE_VALIDATION_REQUIRED),
        Target("com.facebook.orca", "Messenger", heavyAreas(), Evidence.LIVE_VALIDATION_REQUIRED),
        Target("com.instagram.android", "Instagram", heavyAreas(), Evidence.LIVE_VALIDATION_REQUIRED),
        Target("com.zhiliaoapp.musically", "TikTok", heavyAreas() + listOf("split-stress"), Evidence.LIVE_VALIDATION_REQUIRED)
    )

    val androidProfiles = listOf(
        "Android 12 / API 31",
        "Android 13 / API 33",
        "Android 14 / API 34",
        "Android 15 / API 35",
        "Android 16 / API 36 primary"
    )

    val oemProfiles = listOf(
        "Samsung One UI — primary live target SM-G990E",
        "Xiaomi/HyperOS — live validation required",
        "Pixel/AOSP — live validation required"
    )

    fun summary(): String = buildString {
        appendLine("Heavy apps: ${heavyApps.joinToString { "${it.label}=${it.evidence}" }}")
        appendLine("Android: ${androidProfiles.joinToString()}")
        appendLine("OEM: ${oemProfiles.joinToString()}")
    }

    private fun heavyAreas() = listOf(
        "snapshot", "DEX", "resources", "Application bootstrap", "providers", "launcher", "internal Activities",
        "background", "WebView", "camera", "microphone", "gallery", "notifications", "deep links", "jobs",
        "restart", "process death", "second clone"
    )
}
