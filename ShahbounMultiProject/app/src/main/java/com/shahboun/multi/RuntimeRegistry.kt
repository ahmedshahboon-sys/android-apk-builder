package com.shahboun.multi

import java.util.concurrent.ConcurrentHashMap

/** Process-local registry for active Runtime 3 guest sessions. */
object RuntimeRegistry {
    private val sessions = ConcurrentHashMap<String, RuntimeSession>()

    private fun key(packageName: String, slot: Int): String = "$packageName#$slot"

    fun put(session: RuntimeSession) {
        sessions.put(key(session.runtimePackage.packageName, session.runtimePackage.slot), session)?.close()
    }

    fun getOrNull(packageName: String, slot: Int): RuntimeSession? = sessions[key(packageName, slot)]

    fun get(packageName: String, slot: Int): RuntimeSession =
        getOrNull(packageName, slot) ?: error("جلسة التشغيل غير موجودة")

    fun remove(packageName: String, slot: Int) {
        sessions.remove(key(packageName, slot))?.close()
    }

    fun clear() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    fun sessionForClassLoader(loader: ClassLoader?): RuntimeSession? =
        loader?.let { candidate -> sessions.values.firstOrNull { it.classLoader === candidate } }
}
