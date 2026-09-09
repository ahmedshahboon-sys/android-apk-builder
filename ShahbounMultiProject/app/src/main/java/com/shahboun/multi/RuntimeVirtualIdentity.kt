package com.shahboun.multi

import android.os.Process

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
}

internal object RuntimeVirtualIdentityRegistry {
    fun forSession(session: RuntimeSession): RuntimeVirtualIdentity {
        val pkg = session.runtimePackage
        return RuntimeVirtualIdentity(
            guestPackage = pkg.packageName,
            cloneId = pkg.slot,
            virtualUid = virtualUid(pkg.packageName, pkg.slot),
            virtualUserId = pkg.slot,
            virtualProcessName = pkg.packageName,
            hostUid = Process.myUid(),
            hostPackage = BuildConfig.APPLICATION_ID,
            physicalProcess = RuntimeGuestProcessIdentity.hostProcessName(),
            sessionId = "${pkg.packageName}#${pkg.slot}:${pkg.versionCode}:${pkg.sha256.take(12)}"
        )
    }

    /** Deterministic Runtime-local UID. It is never passed to Android as a real Linux UID. */
    internal fun virtualUid(packageName: String, cloneId: Int): Int {
        val hash = (31 * packageName.hashCode() + cloneId).ushr(1)
        return 200_000 + (hash % 700_000)
    }
}
