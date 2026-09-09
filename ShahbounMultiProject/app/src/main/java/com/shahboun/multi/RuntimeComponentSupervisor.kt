package com.shahboun.multi

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Keeps one coherent clone identity across activities, services, receivers and providers in each process. */
internal object RuntimeComponentSupervisor {
    data class Snapshot(
        val packageName: String,
        val slot: Int,
        val process: String,
        val activities: Int,
        val services: Int,
        val receivers: Int,
        val providers: Int
    )

    private data class Counters(
        val activities: AtomicInteger = AtomicInteger(),
        val services: AtomicInteger = AtomicInteger(),
        val receivers: AtomicInteger = AtomicInteger(),
        val providers: AtomicInteger = AtomicInteger()
    )

    private val counters = ConcurrentHashMap<String, Counters>()

    fun activity(session: RuntimeSession) = touch(session, "activity") { it.activities.incrementAndGet() }
    fun service(session: RuntimeSession) = touch(session, "service") { it.services.incrementAndGet() }
    fun receiver(session: RuntimeSession) = touch(session, "receiver") { it.receivers.incrementAndGet() }
    fun providers(session: RuntimeSession, count: Int) = touch(session, "provider") { it.providers.addAndGet(count.coerceAtLeast(0)) }

    fun snapshot(session: RuntimeSession): Snapshot {
        assertOwner(session)
        val c = counters.getOrPut(key(session)) { Counters() }
        return Snapshot(
            session.runtimePackage.packageName,
            session.runtimePackage.slot,
            RuntimeGuestProcessIdentity.hostProcessName(),
            c.activities.get(), c.services.get(), c.receivers.get(), c.providers.get()
        )
    }

    fun release(session: RuntimeSession) {
        counters.remove(key(session))
        RuntimeNativeRuntime.unregister(session.runtimePackage.packageName, session.runtimePackage.slot)
    }

    private inline fun touch(session: RuntimeSession, type: String, block: (Counters) -> Int) {
        assertOwner(session)
        val value = block(counters.getOrPut(key(session)) { Counters() })
        RuntimeDiagnostics.log("COMP4", "$type ${session.runtimePackage.packageName}/${session.runtimePackage.slot} count=$value process=${RuntimeGuestProcessIdentity.hostProcessName()}")
    }

    private fun assertOwner(session: RuntimeSession) {
        RuntimeExecutionScope.bindProcessSession(session)
        val owner = RuntimeExecutionScope.processOwner()
        if (RuntimeGuestProcessIdentity.hostProcessName().contains(":clone") && owner != null) {
            check(owner.first == session.runtimePackage.packageName && owner.second == session.runtimePackage.slot) {
                "Runtime component identity collision owner=$owner incoming=${session.runtimePackage.packageName}/${session.runtimePackage.slot}"
            }
        }
    }

    private fun key(session: RuntimeSession): String = "${session.runtimePackage.packageName}#${session.runtimePackage.slot}"
}
