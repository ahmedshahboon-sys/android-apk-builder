package com.shahboun.multi

import android.content.AttributionSource
import android.content.Context

/**
 * Rewrites virtual package identity only at the boundary to real Android Binder services.
 * RuntimeVirtualIdentity is the only source of guest/host identity here.
 */
internal object RuntimeBinderIdentitySanitizer {
    fun sanitize(context: Context, session: RuntimeSession?, args: Array<out Any?>?): Array<Any?>? {
        if (args == null || session == null) return args?.let { source -> Array(source.size) { source[it] } }
        val identity = RuntimeVirtualIdentityRegistry.forSession(session)
        var changed = false
        val out = Array<Any?>(args.size) { index ->
            val transformed = sanitizeValue(context, identity, args[index])
            if (transformed !== args[index] || transformed != args[index]) changed = true
            transformed
        }
        if (changed) {
            RuntimeDiagnostics.log("IDENTITY", "Binder identity sanitized ${identity.guestPackage}/${identity.cloneId} -> ${identity.hostPackage}")
        }
        return out
    }

    private fun sanitizeValue(context: Context, identity: RuntimeVirtualIdentity, value: Any?): Any? = when {
        value is String && value == identity.guestPackage -> identity.hostPackage
        value is AttributionSource && value.packageName == identity.guestPackage -> physicalAttribution(context, identity, value)
        value is Array<*> -> Array<Any?>(value.size) { i -> sanitizeValue(context, identity, value[i]) }
        value is List<*> -> value.map { sanitizeValue(context, identity, it) }
        else -> value
    }

    private fun physicalAttribution(context: Context, identity: RuntimeVirtualIdentity, original: AttributionSource): AttributionSource {
        val host = context.applicationContext.attributionSource
        if (host.packageName == identity.hostPackage && host.uid == identity.hostUid) return host
        return runCatching {
            AttributionSource.Builder(identity.hostUid)
                .setPackageName(identity.hostPackage)
                .setAttributionTag(original.attributionTag)
                .build()
        }.getOrElse { host }
    }
}
