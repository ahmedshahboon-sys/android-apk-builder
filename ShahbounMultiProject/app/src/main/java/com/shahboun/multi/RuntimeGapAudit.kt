package com.shahboun.multi

/**
 * Honest, code-level capability ledger. This does not turn structural presence into FULL support.
 * Live-only capabilities remain LIVE_VALIDATION_REQUIRED until a real guest exercise records proof.
 */
internal object RuntimeGapAudit {
    enum class State { FULL, PARTIAL, FALLBACK, PASSTHROUGH, NOT_TESTED, UNSUPPORTED, FAIL, LIVE_VALIDATION_REQUIRED }
    data class Entry(val area: String, val state: State, val evidence: String)

    fun current(): List<Entry> = listOf(
        Entry("Native path mapping", State.FULL, "PATH_MAP_V4 absolute/reverse/containment helpers"),
        Entry("Native relative/openat policy", State.PARTIAL, "dir-base resolution and containment exist; libc interception is not installed"),
        Entry("Native libc interception", State.UNSUPPORTED, "no stable Android 16 interposition is claimed; avoids linker/libc hooks"),
        Entry("Linker namespace virtualization", State.UNSUPPORTED, "per-clone extraction/search path exists; platform linker namespace is not virtualized"),
        Entry("Filesystem Java/Context", State.FULL, "clone-scoped data/files/cache/db/shared_prefs/no_backup/device/external buckets"),
        Entry("Filesystem symlink safety", State.PARTIAL, "realpath containment helpers exist; third-party direct libc calls remain outside interception"),
        Entry("Virtual identity", State.FULL, "one RuntimeVirtualIdentity model carries guest/clone/virtual/host/process/session fields"),
        Entry("Process identity", State.PARTIAL, "framework-visible aliases are virtualized where safe; kernel pid/uid/proc remain physical"),
        Entry("Binder identity", State.PARTIAL, "sanitizers/proxies exist; full argument/result/callback matrix is not live-proven"),
        Entry("Context", State.PARTIAL, "guest package/resources/storage/services are wrapped; system_server AppOps identity stays physical by design"),
        Entry("LoadedApk", State.PARTIAL, "SDK-aware patch bridge exists; hidden API reflection cannot be guaranteed on every OEM"),
        Entry("Application lifecycle", State.PARTIAL, "single-session bootstrap guards exist; heavy-app order still needs live validation"),
        Entry("ClassLoader/DEX/splits", State.PARTIAL, "guest-first loader and split paths exist; dynamic-feature stress remains live-required"),
        Entry("Resources", State.PARTIAL, "archive graph/loaders and activity fixes exist; full configuration matrix needs device validation"),
        Entry("Activities/Services/Receivers/Providers", State.PARTIAL, "runtime component host exists; edge-case Android/OEM matrix not fully exercised"),
        Entry("PackageManager", State.PARTIAL, "guest self/package/component virtualization exists; complete API matrix not live-proven"),
        Entry("System services", State.PARTIAL, "central virtualizer/identity bridges exist; unsupported managers use honest passthrough"),
        Entry("Jobs", State.PARTIAL, "clone/namespace job ids and recursion guard exist; persisted callback/restart requires live validation"),
        Entry("Alarms/PendingIntent/Notifications", State.PARTIAL, "clone namespaces and routing bridges exist; process-death delivery needs live validation"),
        Entry("WebView", State.LIVE_VALIDATION_REQUIRED, "clone storage/data-directory preparation exists; cookies/OAuth/renderer separation must be exercised"),
        Entry("GMS/Firebase/Accounts", State.LIVE_VALIDATION_REQUIRED, "compatibility preparation exists; sign-in/FCM callbacks require real services"),
        Entry("Network/Wi-Fi/Location/Telephony", State.LIVE_VALIDATION_REQUIRED, "compatibility identity only; device/OEM permissions and callbacks require live tests"),
        Entry("Camera/Microphone/Media", State.LIVE_VALIDATION_REQUIRED, "real hardware capture cannot be established by CI"),
        Entry("SAF/URI/external intents", State.PARTIAL, "routing/content bridges exist; persistable grants and external return ownership need live validation"),
        Entry("Crash isolation", State.PARTIAL, "clone processes isolate fatal failures; complete restart/session recovery is not proven for all components"),
        Entry("Security", State.PARTIAL, "path/session/routing checks exist; full spoof/replay/URI fuzz matrix remains incomplete"),
        Entry("Performance/leaks", State.NOT_TESTED, "requires device PSS/heap/startup/leak measurement"),
        Entry("Samsung SM-G990E Android 16 heavy apps", State.LIVE_VALIDATION_REQUIRED, "CI cannot substitute for real WhatsApp/Facebook/Instagram/TikTok runs")
    )

    fun render(): String = buildString {
        appendLine("--- RUNTIME7 GAP AUDIT ---")
        current().forEach { appendLine("${it.area}: ${it.state} — ${it.evidence}") }
    }
}
