package com.shahboun.multi

import android.app.Application
import android.os.Build

/**
 * Pins one immutable guest identity to one Runtime 3 clone process for its full lifetime.
 *
 * Runtime 3 deliberately keeps Android's real process name (`com.shahboun.multi:cloneN`) stable.
 * Guest identity is virtualized through LoadedApk/Context/PackageManager/AppOps and the runtime
 * execution scope. Mutating ActivityThread.mBoundApplication.processName made later framework
 * validation observe the guest package instead of the physical clone slot and caused deterministic
 * process-mismatch crashes on Android 16.
 */
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

        // Do NOT rewrite ActivityThread/AppBindData.processName. The physical process identity is
        // the security boundary that maps this process to exactly one Runtime 3 slot. Rewriting it
        // breaks subsequent slot validation and can confuse framework process bookkeeping.
        pinnedGuest = packageName
        session?.let { bindRuntime3Environment(it) }

        RuntimeDiagnostics.log(
            "IDENTITY",
            "Runtime3 guest identity pinned $packageName/$slot physical=$realHostProcessName mode=virtual-no-process-rename"
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
