package com.shahboun.multi

import android.os.Process
import java.util.Collections
import java.util.WeakHashMap

/**
 * One canonical description of the identity owned by a clone session.
 *
 * virtualUid/virtualUserId are Runtime-local identifiers only; Android system_server continues to
 * see the real host UID. Keeping both identities explicit prevents bridges from accidentally mixing
 * a logical guest package with the physical host UID.
 */
data class RuntimeVirtualIdentity(
    val guestPackage: String,
    val cloneId: Int,
    val virtualUid: Int,
    val virtualUserId: Int,
    val virtualProcessName: String,
    val hostUid: Int,
    val hostPackage: String,
    val physicalProcess: String,
    val sessionId: String
) {
    val key: String get() = "$guestPackage#$cloneId"
    val processKey: String get() = "$key@$virtualProcessName"
}

/** Canonical identity registry. Bridges must consume this object instead of inventing package/uid values. */
internal object RuntimeVirtualIdentityRegistry {
    private val identities = Collections.synchronizedMap(WeakHashMap<RuntimeSession, MutableMap<String, RuntimeVirtualIdentity>>())

    fun forSession(session: RuntimeSession): RuntimeVirtualIdentity =
        forSession(session, session.runtimePackage.packageName)

    fun forSession(session: RuntimeSession, virtualProcessName: String): RuntimeVirtualIdentity {
        val pkg = session.runtimePackage
        val normalizedProcess = normalizeProcessName(pkg.packageName, virtualProcessName)
        synchronized(identities) {
            val byProcess = identities.getOrPut(session) { linkedMapOf() }
            return byProcess.getOrPut(normalizedProcess) {
                RuntimeVirtualIdentity(
                    guestPackage = pkg.packageName,
                    cloneId = pkg.slot,
                    virtualUid = virtualUid(pkg.packageName, pkg.slot),
                    virtualUserId = pkg.slot,
                    virtualProcessName = normalizedProcess,
                    hostUid = Process.myUid(),
                    hostPackage = BuildConfig.APPLICATION_ID,
                    physicalProcess = RuntimeGuestProcessIdentity.hostProcessName(),
                    sessionId = sessionId(pkg)
                )
            }
        }
    }

    fun release(session: RuntimeSession) {
        synchronized(identities) { identities.remove(session) }
    }

    internal fun sessionId(session: RuntimeSession): String = sessionId(session.runtimePackage)

    /** Stable route/session identity derived only from the immutable clone snapshot. */
    internal fun sessionId(pkg: RuntimePackage): String =
        "${pkg.packageName}#${pkg.slot}:${pkg.versionCode}:${pkg.sha256.take(12)}"

    internal fun normalizeProcessName(packageName: String, requested: String?): String {
        val value = requested?.trim().orEmpty()
        if (value.isBlank()) return packageName
        return when {
            value == packageName -> packageName
            value.startsWith(":") -> packageName + value
            value.startsWith("$packageName:") -> value
            else -> value
        }
    }

    /** Deterministic Runtime-local UID. It is never passed to Android as a real Linux UID. */
    internal fun virtualUid(packageName: String, cloneId: Int): Int {
        val hash = (31 * packageName.hashCode() + cloneId).ushr(1)
        return 200_000 + (hash % 700_000)
    }
}
