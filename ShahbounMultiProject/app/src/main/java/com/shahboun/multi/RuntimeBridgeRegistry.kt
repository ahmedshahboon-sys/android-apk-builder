package com.shahboun.multi

import android.content.Context

/** Installs runtime bridges through one compatibility-aware pipeline and records capability state. */
object RuntimeBridgeRegistry {
    data class BridgeState(val name: String, val ready: Boolean, val detail: String?)

    @Volatile private var latest: List<BridgeState> = emptyList()

    fun install(context: Context): List<BridgeState> {
        val states = ArrayList<BridgeState>()
        fun installOne(name: String, block: () -> Result<Unit>) {
            val result = runCatching { block() }.getOrElse { Result.failure(it) }
            val error = result.exceptionOrNull()
            states += BridgeState(name, error == null, error?.let { "${it.javaClass.simpleName}: ${it.message}" })
            RuntimeDiagnostics.log("BRIDGE", "$name=${if (error == null) "ready" else "fallback"}${error?.let { " ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()}")
        }

        installOne("package-manager") { RuntimePackageManagerBridge.install(context) }
        installOne("notifications") { RuntimeNotificationBridge.install(context) }
        installOne("activity-manager-pending-intent") { RuntimePendingIntentBridge.install(context) }
        installOne("alarm") { RuntimeAlarmBridge.install(context) }
        installOne("jobs") { RuntimeJobSchedulerBridge.install(context) }
        installOne("clipboard") { RuntimeClipboardBridge.install(context) }
        installOne("identity") { RuntimeIdentityServiceBridge.install(context) }

        latest = states.toList()
        val ready = states.count { it.ready }
        RuntimeDiagnostics.log("BRIDGE", "capability matrix ready=$ready/${states.size} failed=${states.filterNot { it.ready }.joinToString { it.name }}")
        return latest
    }

    fun snapshot(): List<BridgeState> = latest
    fun isCoreReady(): Boolean {
        val required = setOf("package-manager", "activity-manager-pending-intent", "identity")
        return latest.filter { it.name in required }.all { it.ready }
    }
}
