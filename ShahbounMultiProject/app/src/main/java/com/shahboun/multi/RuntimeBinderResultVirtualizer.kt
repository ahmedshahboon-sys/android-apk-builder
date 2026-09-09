package com.shahboun.multi

import android.app.ActivityManager
import android.content.AttributionSource
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import java.util.concurrent.ConcurrentHashMap

/** Conservative return-side Binder boundary with service-specific transformations only. */
internal object RuntimeBinderResultVirtualizer {
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun restore(session: RuntimeSession?, result: Any?): Any? = restore(session, null, result)

    fun restore(session: RuntimeSession?, methodName: String?, result: Any?): Any? {
        if (session == null || result == null) return result
        val identity = RuntimeVirtualIdentityRegistry.forSession(session)
        if (methodName == "getRunningAppProcesses" || methodName == "getRunningAppProcessesWithInfo") {
            patchRunningProcesses(identity, result)
            return result
        }
        when (result) {
            is ApplicationInfo, is PackageInfo, is ComponentName, is AttributionSource -> report(identity, methodName, result.javaClass.simpleName)
            is Array<*> -> if (result.any(::identityBearing)) report(identity, methodName, "Array")
            is List<*> -> if (result.any(::identityBearing)) report(identity, methodName, "List")
        }
        return result
    }

    private fun patchRunningProcesses(identity: RuntimeVirtualIdentity, value: Any?) {
        val list = when (value) {
            is List<*> -> value
            is Array<*> -> value.asList()
            else -> parceledList(value)
        } ?: return
        var changed = 0
        list.filterIsInstance<ActivityManager.RunningAppProcessInfo>().forEach { info ->
            val physical = info.processName.orEmpty()
            if (physical == identity.physicalProcess || physical == BuildConfig.APPLICATION_ID || physical.startsWith("${BuildConfig.APPLICATION_ID}:clone")) {
                info.processName = identity.virtualProcessName
                info.pkgList = arrayOf(identity.guestPackage)
                changed++
            }
        }
        if (changed > 0) RuntimeDiagnostics.log("PROCESS7", "virtualized running-process results count=$changed guest=${identity.guestPackage}/${identity.cloneId} process=${identity.virtualProcessName}")
    }

    private fun parceledList(value: Any?): List<*>? {
        value ?: return null
        if (!value.javaClass.name.contains("ParceledListSlice")) return null
        return runCatching { value.javaClass.methods.firstOrNull { it.name == "getList" && it.parameterCount == 0 }?.invoke(value) as? List<*> }.getOrNull()
    }

    private fun identityBearing(value: Any?): Boolean = value is ApplicationInfo || value is PackageInfo || value is ComponentName || value is AttributionSource

    private fun report(identity: RuntimeVirtualIdentity, methodName: String?, kind: String) {
        val key = "${identity.key}:${methodName ?: "unknown"}:$kind"
        if (reported.add(key)) RuntimeDiagnostics.log(
            "BINDER7",
            "return identity object method=${methodName ?: "unknown"} kind=$kind guest=${identity.guestPackage}/${identity.cloneId} genericRewrite=false serviceSpecificRequired=true"
        )
    }
}
