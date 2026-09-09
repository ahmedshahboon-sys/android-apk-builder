package com.shahboun.multi

import android.content.AttributionSource
import android.content.Context

/**
 * Rewrites virtual package identity only at the boundary to real Android Binder services.
 *
 * Never derive the physical package from a guest-patched Context/LoadedApk. Runtime 3 deliberately
 * changes what guest code sees, so Context.packageName may legitimately be the logical guest name.
 * system_server, however, validates Binder calls against the APK that owns Process.myUid(): the
 * fixed BuildConfig.APPLICATION_ID. Mixing those two identities caused Android 16 SettingsProvider
 * and AppOps failures such as "Package ... does not belong to uid".
 */
internal object RuntimeBinderIdentitySanitizer {
    private val physicalPackage: String get() = BuildConfig.APPLICATION_ID

    fun sanitize(context: Context, session: RuntimeSession?, args: Array<out Any?>?): Array<Any?>? {
        if (args == null || session == null) return args?.let { source -> Array(source.size) { source[it] } }
        val guestPackage = session.runtimePackage.packageName
        var changed = false
        val out = Array<Any?>(args.size) { index ->
            val value = args[index]
            when {
                value is String && value == guestPackage -> {
                    changed = true
                    physicalPackage
                }
                value is AttributionSource && value.packageName == guestPackage -> {
                    changed = true
                    physicalAttribution(context, value)
                }
                else -> value
            }
        }
        if (changed) {
            RuntimeDiagnostics.log(
                "IDENTITY",
                "Binder identity sanitized ${guestPackage}/${session.runtimePackage.slot} -> $physicalPackage"
            )
        }
        return out
    }

    private fun physicalAttribution(context: Context, original: AttributionSource): AttributionSource {
        // applicationContext.attributionSource is captured from the installed host package. If an
        // OEM exposes a guest-patched source here, rebuild the minimal source with the physical UID.
        val host = context.applicationContext.attributionSource
        if (host.packageName == physicalPackage) return host
        return runCatching {
            AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName(physicalPackage)
                .setAttributionTag(original.attributionTag)
                .build()
        }.getOrElse { host }
    }
}
