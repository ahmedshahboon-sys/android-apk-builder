package com.shahboun.multi

import android.app.Application
import android.os.Build

/**
 * Exposes a stable guest main-process identity inside an isolated Shahboun clone process.
 *
 * Each :cloneN process is permanently owned by exactly one clone session. Large multi-process apps
 * cache Application.getProcessName()/ActivityThread.currentProcessName() during static init and on
 * background threads, so a temporary callback-only override is not sufficient. We therefore pin
 * ActivityThread's local bound-process name for the lifetime of the clone process while keeping the
 * real host process name cached for Shahboun's own routing/validation.
 */
object RuntimeGuestProcessIdentity {
    private val lock = Any()
    private val realHostProcessName: String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID

    @Volatile private var pinnedGuest: String? = null

    fun hostProcessName(): String = realHostProcessName

    fun pin(session: RuntimeSession) = synchronized(lock) {
        val guestName = session.runtimePackage.packageName
        val existing = pinnedGuest
        if (existing == guestName) return@synchronized
        require(existing == null) {
            "رفض تغيير هوية عملية clone من $existing إلى $guestName"
        }

        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            ?: error("ActivityThread غير متاح")
        val boundField = RuntimeCompatibility.findField(threadClass, "mBoundApplication")
            ?: error("ActivityThread.mBoundApplication غير متاح")
        boundField.isAccessible = true
        val bound = boundField.get(thread) ?: error("AppBindData غير متاح")
        val processField = RuntimeCompatibility.findField(bound.javaClass, "processName")
            ?: error("AppBindData.processName غير متاح")
        processField.isAccessible = true
        val old = runCatching { processField.get(bound) as? String }.getOrNull()
        processField.set(bound, guestName)
        pinnedGuest = guestName
        RuntimeDiagnostics.log(
            "IDENTITY",
            "guest process identity pinned ${session.runtimePackage.packageName}/${session.runtimePackage.slot} real=$realHostProcessName old=$old guest=$guestName"
        )
    }

    fun <T> withGuestMainProcess(session: RuntimeSession, block: () -> T): T {
        pin(session)
        return block()
    }
}
