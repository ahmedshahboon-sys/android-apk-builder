package com.shahboun.multi

import android.app.Application
import android.os.Build

/** Pins one immutable guest identity to one Runtime 3 clone process for its full lifetime. */
object RuntimeGuestProcessIdentity {
    private val lock = Any()
    private val realHostProcessName: String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID

    @Volatile private var pinnedGuest: String? = null

    fun hostProcessName(): String = realHostProcessName

    fun pin(session: RuntimeSession) = pinPackage(session.runtimePackage.packageName, session.runtimePackage.slot, session)

    fun pinPackage(packageName: String, slot: Int) = pinPackage(packageName, slot, null)

    private fun pinPackage(packageName: String, slot: Int, session: RuntimeSession?) = synchronized(lock) {
        val existing = pinnedGuest
        if (existing == packageName) {
            session?.let { bindRuntime3Environment(it) }
            return@synchronized
        }
        require(existing == null) { "رفض تغيير هوية عملية clone من $existing إلى $packageName" }

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

        session?.let { bindRuntime3Environment(it) }

        RuntimeDiagnostics.log(
            "IDENTITY",
            "Runtime3 guest process identity pinned $packageName/$slot real=$realHostProcessName old=$old guest=$packageName"
        )
    }

    private fun bindRuntime3Environment(session: RuntimeSession) {
        val app = MultiApplication.current ?: error("Runtime 3 application unavailable")
        val pkg = session.runtimePackage
        Runtime3ProcessEnvironment.activate(session, app.engine.runtimeSlotDir(pkg.packageName, pkg.slot))
    }

    fun <T> withGuestMainProcess(session: RuntimeSession, block: () -> T): T {
        pin(session)
        return block()
    }
}
