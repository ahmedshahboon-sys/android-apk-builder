package com.shahboun.multi

import android.app.Application
import android.os.Build

/**
 * Pins one immutable guest+clone identity to one dedicated clone process for its full lifetime.
 * Android keeps the real Linux/system_server identity; only guest-facing framework metadata is
 * aliased. The immutable clone id matters because two slots of the same package must never share a
 * process just because their package names match.
 */
object RuntimeGuestProcessIdentity {
    private val lock = Any()
    private val realHostProcessName: String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID

    @Volatile private var pinnedIdentity: String? = null
    @Volatile private var persistentAliasIdentity: String? = null

    fun hostProcessName(): String = realHostProcessName

    fun pin(session: RuntimeSession) = pinPackage(session.runtimePackage.packageName, session.runtimePackage.slot, session)

    fun pinPackage(packageName: String, slot: Int) = pinPackage(packageName, slot, null)

    private fun pinPackage(packageName: String, slot: Int, session: RuntimeSession?) = synchronized(lock) {
        val incoming = identityKey(packageName, slot)
        val existing = pinnedIdentity
        if (existing == incoming) {
            session?.let { bindRuntime3Environment(it) }
            ensurePersistentProcessAlias(packageName, slot)
            return@synchronized
        }
        require(existing == null) { "رفض تغيير هوية عملية clone من $existing إلى $incoming" }
        pinnedIdentity = incoming
        session?.let { bindRuntime3Environment(it) }
        ensurePersistentProcessAlias(packageName, slot)
        RuntimeDiagnostics.log(
            "IDENTITY",
            "guest identity pinned $incoming physical=$realHostProcessName mode=virtual-persistent-process-alias"
        )
    }

    private fun bindRuntime3Environment(session: RuntimeSession) {
        val app = MultiApplication.current ?: error("Runtime application unavailable")
        val pkg = session.runtimePackage
        Runtime3ProcessEnvironment.activate(session, app.engine.runtimeSlotDir(pkg.packageName, pkg.slot))
    }

    /** Compatibility helper: no execution-scope recursion. */
    fun <T> withGuestMainProcess(session: RuntimeSession, block: () -> T): T {
        pin(session)
        ensurePersistentProcessAlias(session.runtimePackage.packageName, session.runtimePackage.slot)
        return block()
    }

    fun ensureGuestAlias(session: RuntimeSession) {
        pin(session)
        ensurePersistentProcessAlias(session.runtimePackage.packageName, session.runtimePackage.slot)
    }

    fun pinnedKey(): String? = pinnedIdentity

    private fun ensurePersistentProcessAlias(packageName: String, slot: Int) = synchronized(lock) {
        if (!isDedicatedCloneProcess()) return@synchronized
        val incoming = identityKey(packageName, slot)
        if (persistentAliasIdentity == incoming) return@synchronized
        val existing = persistentAliasIdentity
        require(existing == null || existing == incoming) { "رفض تبديل process alias من $existing إلى $incoming" }

        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
                ?: error("ActivityThread غير متاح")
            val boundField = RuntimeCompatibility.findField(threadClass, "mBoundApplication")
                ?: error("ActivityThread.mBoundApplication غير متاح")
            boundField.isAccessible = true
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

            persistentAliasIdentity = incoming
            RuntimeDiagnostics.log(
                "IDENTITY",
                "persistent guest process alias active guest=$incoming physical=$realHostProcessName kernelIdentityUntouched=true"
            )
        }.onFailure {
            RuntimeDiagnostics.log("IDENTITY", "persistent guest process alias unavailable: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun identityKey(packageName: String, slot: Int): String = "$packageName#$slot"

    private fun isDedicatedCloneProcess(): Boolean =
        realHostProcessName.startsWith("${BuildConfig.APPLICATION_ID}:clone")
}
