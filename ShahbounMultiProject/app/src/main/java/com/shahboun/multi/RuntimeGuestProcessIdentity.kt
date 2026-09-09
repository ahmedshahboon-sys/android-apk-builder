package com.shahboun.multi

import android.app.Application
import android.os.Build

/**
 * Pins one immutable guest identity to one Runtime 3 clone process for its full lifetime.
 *
 * Android must keep the real process name (`com.shahboun.multi:cloneN`) as its permanent
 * bookkeeping/security identity. Some large apps however decide whether to run their main-process
 * bootstrap by reading ActivityThread's process name during Application.onCreate(). For that narrow
 * bootstrap window we expose a reversible guest alias and restore the physical name immediately.
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
        pinnedGuest = packageName
        session?.let { bindRuntime3Environment(it) }
        RuntimeDiagnostics.log(
            "IDENTITY",
            "Runtime3 guest identity pinned $packageName/$slot physical=$realHostProcessName mode=virtual-scoped-bootstrap-alias"
        )
    }

    private fun bindRuntime3Environment(session: RuntimeSession) {
        val app = MultiApplication.current ?: error("Runtime 3 application unavailable")
        val pkg = session.runtimePackage
        Runtime3ProcessEnvironment.activate(session, app.engine.runtimeSlotDir(pkg.packageName, pkg.slot))
    }

    fun <T> withGuestMainProcess(session: RuntimeSession, block: () -> T): T {
        pin(session)
        val pkg = session.runtimePackage.packageName
        val patch = patchProcessAlias(pkg)
        return try {
            RuntimeExecutionScope.withSession(session, block)
        } finally {
            patch?.restore()
        }
    }

    private data class AliasPatch(
        val bound: Any,
        val processField: java.lang.reflect.Field,
        val oldProcess: Any?,
        val appInfo: Any?,
        val appInfoProcessField: java.lang.reflect.Field?,
        val oldAppInfoProcess: Any?
    ) {
        fun restore() {
            runCatching { processField.set(bound, oldProcess) }
            appInfoProcessField?.let { field -> appInfo?.let { info -> runCatching { field.set(info, oldAppInfoProcess) } } }
            RuntimeDiagnostics.log("IDENTITY", "bootstrap process alias restored physical=${RuntimeGuestProcessIdentity.hostProcessName()}")
        }
    }

    private fun patchProcessAlias(packageName: String): AliasPatch? = runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            ?: return@runCatching null
        val boundField = RuntimeCompatibility.findField(threadClass, "mBoundApplication") ?: return@runCatching null
        val bound = boundField.get(thread) ?: return@runCatching null
        val processField = RuntimeCompatibility.findField(bound.javaClass, "processName") ?: return@runCatching null
        processField.isAccessible = true
        val oldProcess = processField.get(bound)
        processField.set(bound, packageName)

        val appInfoField = RuntimeCompatibility.findField(bound.javaClass, "appInfo")
        val appInfo = appInfoField?.let { runCatching { it.get(bound) }.getOrNull() }
        val appInfoProcessField = appInfo?.let { RuntimeCompatibility.findField(it.javaClass, "processName") }
        val oldAppInfoProcess = appInfoProcessField?.let { field -> appInfo?.let { info -> runCatching { field.get(info) }.getOrNull() } }
        appInfoProcessField?.let { field -> appInfo?.let { info -> runCatching { field.set(info, packageName) } } }

        RuntimeDiagnostics.log("IDENTITY", "bootstrap process alias active guest=$packageName physical=$realHostProcessName")
        AliasPatch(bound, processField, oldProcess, appInfo, appInfoProcessField, oldAppInfoProcess)
    }.onFailure {
        RuntimeDiagnostics.log("IDENTITY", "bootstrap process alias unavailable: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()
}
