package com.shahboun.multi

/**
 * Temporarily exposes the guest's declared main-process name while guest startup code runs.
 *
 * Some applications (notably large multi-process apps) gate their dependency/session bootstrap on
 * ActivityThread.currentProcessName()/Application.getProcessName(). Our Linux process must stay a
 * declared Shahboun :cloneN process for Android, but guest code should observe its own process name
 * while providers and Application.onCreate initialize.
 */
object RuntimeGuestProcessIdentity {
    private val lock = Any()

    fun <T> withGuestMainProcess(session: RuntimeSession, block: () -> T): T = synchronized(lock) {
        val guestName = session.runtimePackage.packageName
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            ?: return@synchronized block()
        val boundField = RuntimeCompatibility.findField(threadClass, "mBoundApplication")
            ?: return@synchronized block()
        boundField.isAccessible = true
        val bound = boundField.get(thread) ?: return@synchronized block()
        val processField = RuntimeCompatibility.findField(bound.javaClass, "processName")
            ?: return@synchronized block()
        processField.isAccessible = true
        val old = runCatching { processField.get(bound) as? String }.getOrNull()
        val changed = runCatching {
            processField.set(bound, guestName)
            RuntimeDiagnostics.log("IDENTITY", "guest process scope enter ${session.runtimePackage.packageName}/${session.runtimePackage.slot} old=$old guest=$guestName")
            true
        }.getOrDefault(false)
        try {
            block()
        } finally {
            if (changed) {
                runCatching { processField.set(bound, old) }
                RuntimeDiagnostics.log("IDENTITY", "guest process scope exit ${session.runtimePackage.packageName}/${session.runtimePackage.slot} restored=$old")
            }
        }
    }
}
