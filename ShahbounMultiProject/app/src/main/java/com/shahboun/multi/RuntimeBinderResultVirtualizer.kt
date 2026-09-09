package com.shahboun.multi

import android.content.AttributionSource
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Conservative return-side Binder boundary.
 *
 * Arbitrarily replacing the host package in results is unsafe: many services legitimately return
 * host/system identities. PackageManager has dedicated semantic result patching. For generic system
 * services this layer detects identity-bearing results and records that they remain PARTIAL until a
 * service-specific semantic transformer exists. This is intentionally safer than a global rewrite.
 */
internal object RuntimeBinderResultVirtualizer {
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun restore(session: RuntimeSession?, result: Any?): Any? {
        if (session == null || result == null) return result
        when (result) {
            is ApplicationInfo, is PackageInfo, is ComponentName, is AttributionSource -> report(session, result.javaClass.simpleName)
            is Array<*> -> if (result.any(::identityBearing)) report(session, "Array")
            is List<*> -> if (result.any(::identityBearing)) report(session, "List")
        }
        return result
    }

    private fun identityBearing(value: Any?): Boolean = value is ApplicationInfo || value is PackageInfo || value is ComponentName || value is AttributionSource

    private fun report(session: RuntimeSession, kind: String) {
        val identity = RuntimeVirtualIdentityRegistry.forSession(session)
        val key = "${identity.key}:$kind"
        if (reported.add(key)) {
            RuntimeDiagnostics.log(
                "BINDER7",
                "return identity object kind=$kind guest=${identity.guestPackage}/${identity.cloneId} genericRewrite=false serviceSpecificRequired=true"
            )
        }
    }
}
