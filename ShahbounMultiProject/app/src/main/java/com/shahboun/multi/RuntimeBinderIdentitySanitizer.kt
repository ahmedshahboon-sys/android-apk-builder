package com.shahboun.multi

import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo

/**
 * Central identity translation at Binder boundaries.
 *
 * Outbound calls must use the physical host identity because system_server validates the Linux UID.
 * Returned self-identity can be translated back to the logical guest package where that translation
 * is safe. Runtime-local virtualUid is deliberately NOT presented as a real Android/Linux UID.
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
                value is ComponentName && value.packageName == guestPackage -> {
                    changed = true
                    ComponentName(physicalPackage, value.className)
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
                "IDENTITY7",
                "Binder outbound identity sanitized ${guestPackage}/${session.runtimePackage.slot} -> $physicalPackage"
            )
        }
        return out
    }

    /** Translate safe returned self-identity back to the logical guest package. */
    fun restoreResult(session: RuntimeSession?, value: Any?): Any? {
        if (session == null || value == null) return value
        val guest = session.runtimePackage.packageName
        return when (value) {
            is String -> if (value == physicalPackage) guest else value
            is ComponentName -> if (value.packageName == physicalPackage) ComponentName(guest, value.className) else value
            is ApplicationInfo -> {
                if (value.packageName == physicalPackage) ApplicationInfo(value).apply { packageName = guest } else value
            }
            is PackageInfo -> {
                if (value.packageName == physicalPackage) PackageInfo(value).apply {
                    packageName = guest
                    applicationInfo = applicationInfo?.let { info -> ApplicationInfo(info).apply { packageName = guest } }
                } else value
            }
            is List<*> -> value.map { restoreResult(session, it) }
            is Array<*> -> Array<Any?>(value.size) { restoreResult(session, value[it]) }
            else -> value
        }
    }

    private fun physicalAttribution(context: Context, original: AttributionSource): AttributionSource {
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
