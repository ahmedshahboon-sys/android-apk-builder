package com.shahboun.multi

import android.app.Application
import android.os.Build

/**
 * Pins one immutable guest identity to one Runtime 3 clone process for its full lifetime.
 *
 * Android keeps the real Linux/process-manager identity (`com.shahboun.multi:cloneN`) for
 * security and bookkeeping. Inside the already-dedicated clone process we keep ActivityThread's
 * public-facing process metadata on the guest package for the entire guest lifetime. Large apps
 * frequently perform main-process checks after Application.onCreate (during Activities, providers,
 * jobs and background initialization), so a temporary bootstrap-only alias is insufficient.
 */
object RuntimeGuestProcessIdentity {
    private val lock = Any()
    private val realHostProcessName: String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID

    @Volatile private var pinnedGuest: String? = null
    @Volatile private var persistentAliasGuest: String? = null

    fun hostProcessName(): String = realHostProcessName

    fun pin(session: RuntimeSession) = pinPackage(session.runtimePackage.packageName, session.runtimePackage.slot, session)

    fun pinPackage(packageName: String, slot: Int) = pinPackage(packageName, slot, null)

    private fun pinPackage(packageName: String, slot: Int, session: RuntimeSession?) = synchronized(lock) {
        val existing = pinnedGuest
        if (existing == packageName) {
            session?.let { bindRuntime3Environment(it) }
            ensurePersistentProcessAlias(packageName)
            return@synchronized
        }
        require(existing == null) { "رفض تغيير هوية عملية clone من $existing إلى $packageName" }
        pinnedGuest = packageName
        session?.let { bindRuntime3Environment(it) }
        ensurePersistentProcessAlias(packageName)
        RuntimeDiagnostics.log(
            "IDENTITY",
            "Runtime3 guest identity pinned $packageName/$slot physical=$realHostProcessName mode=virtual-persistent-process-alias"
        )
    }

    private fun bindRuntime3Environment(session: RuntimeSession) {
        val app = MultiApplication.current ?: error("Runtime 3 application unavailable")
        val pkg = session.runtimePackage
        Runtime3ProcessEnvironment.activate(session, app.engine.runtimeSlotDir(pkg.packageName, pkg.slot))
    }

    /**
     * The process is permanently assigned to one clone slot, therefore the guest-facing alias may
     * remain active until the process dies. We intentionally do not mutate the kernel process name,
     * packageName or Android's system-server bookkeeping.
     */
    fun <T> withGuestMainProcess(session: RuntimeSession, block: () -> T): T {
        pin(session)
        ensurePersistentProcessAlias(session.runtimePackage.packageName)
        return RuntimeExecutionScope.withSession(session, block)
    }

    fun ensureGuestAlias(session: RuntimeSession) {
        pin(session)
        ensurePersistentProcessAlias(session.runtimePackage.packageName)
    }

    private fun ensurePersistentProcessAlias(packageName: String) = synchronized(lock) {
        if (!isDedicatedCloneProcess()) return@synchronized
        if (persistentAliasGuest == packageName) return@synchronized
        val existing = persistentAliasGuest
        require(existing == null || existing == packageName) { "رفض تبديل process alias من $existing إلى $packageName" }

        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
                ?: error("ActivityThread غير متاح")
            val boundField = RuntimeCompatibility.findField(threadClass, "mBoundApplication")
                ?: error("ActivityThread.mBoundApplication غير متاح")
            val bound = boundField.get(thread) ?: error("AppBindData غير متاح")

            RuntimeCompatibility.findField(bound.javaClass, "processName")?.let { field ->
                field.isAccessible = true
                field.set(bound, packageName)
            } ?: error("AppBindData.processName غير متاح")

            RuntimeCompatibility.findField(bound.javaClass, "appInfo")?.let { field ->
                field.isAccessible = true
                val appInfo = field.get(bound)
                if (appInfo != null) {
                    RuntimeCompatibility.findField(appInfo.javaClass, "processName")?.let { processField ->
                        processField.isAccessible = true
                        processField.set(appInfo, packageName)
                    }
                }
            }

            persistentAliasGuest = packageName
            RuntimeDiagnostics.log(
                "IDENTITY",
                "persistent guest process alias active guest=$packageName physical=$realHostProcessName packageIdentityUntouched=true"
            )
        }.onFailure {
            RuntimeDiagnostics.log("IDENTITY", "persistent guest process alias unavailable: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun isDedicatedCloneProcess(): Boolean =
        realHostProcessName.startsWith("${BuildConfig.APPLICATION_ID}:clone")
}
