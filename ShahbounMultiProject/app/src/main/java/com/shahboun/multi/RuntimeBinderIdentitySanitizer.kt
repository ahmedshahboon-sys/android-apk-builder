package com.shahboun.multi

import android.content.AttributionSource
import android.content.Context

/**
 * Rewrites virtual package identity only at the boundary to real Android Binder services.
 *
 * Guest code must continue to see its own package name, but Android system_server validates every
 * calling package/AttributionSource against the physical UID.  A cloned package is not installed
 * under Process.myUid(), so sending it across Binder causes Android 16 SecurityException errors
 * such as "Package ... does not belong to uid" and "Given calling package ... does not match".
 */
internal object RuntimeBinderIdentitySanitizer {
    fun sanitize(context: Context, session: RuntimeSession?, args: Array<out Any?>?): Array<Any?>? {
        if (args == null || session == null) return args?.let { source -> Array(source.size) { source[it] } }
        val guestPackage = session.runtimePackage.packageName
        val hostPackage = context.packageName
        var changed = false
        val out = Array<Any?>(args.size) { index ->
            val value = args[index]
            when {
                value is String && value == guestPackage -> {
                    changed = true
                    hostPackage
                }
                value is AttributionSource && value.packageName == guestPackage -> {
                    changed = true
                    context.attributionSource
                }
                else -> value
            }
        }
        if (changed) {
            RuntimeDiagnostics.log(
                "IDENTITY",
                "Binder identity sanitized ${guestPackage}/${session.runtimePackage.slot} -> $hostPackage"
            )
        }
        return out
    }
}
