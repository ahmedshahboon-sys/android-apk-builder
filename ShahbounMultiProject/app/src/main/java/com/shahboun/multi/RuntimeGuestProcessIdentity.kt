package com.shahboun.multi

import android.app.Application
import android.os.Build

/**
 * Exposes a stable guest main-process identity inside an isolated Shahboun clone process.
 *
 * Each :cloneN process is permanently owned by exactly one clone session. Large multi-process apps
 * cache Application.getProcessName()/ActivityThread.currentProcessName() during class initialization,
 * attachBaseContext and background bootstrap. A callback-only override is therefore too late. The
 * identity is pinned before RuntimeSession/Application creation and stays pinned for the process life.
 */
object RuntimeGuestProcessIdentity {
    private val lock = Any()
    private val realHostProcessName: String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID

    @Volatile private var pinnedGuest: String? = null

    fun hostProcessName(): String = realHostProcessName

    fun pin(session: RuntimeSession) = pinPackage(session.runtimePackage.packageName, session.runtimePackage.slot)

    fun pinPackage(packageName: String, slot: Int) = synchronized(lock) {
        val existing = pinnedGuest
        if (existing == packageName) return@synchronized
        require(existing == null) {
            "رفض تغيير هوية عملية clone من $existing إلى $packageName"
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
        processField.set(bound, packageName)
        pinnedGuest = packageName
        RuntimeDiagnostics.log(
            "IDENTITY",
            "guest process identity pinned $packageName/$slot real=$realHostProcessName old=$old guest=$packageName"
        )
    }

    fun <T> withGuestMainProcess(session: RuntimeSession, block: () -> T): T {
        pin(session)
        return block()
    }
}
