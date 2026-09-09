package com.shahboun.multi

import java.util.concurrent.ConcurrentHashMap

/** Prevents bridge/facade wrappers from re-entering the same logical service path on one thread. */
internal object RuntimeRecursionGuard {
    private val active = ThreadLocal.withInitial { LinkedHashSet<String>() }
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun <T> call(key: String, fallback: () -> T, block: () -> T): T {
        val set = active.get()
        if (!set.add(key)) {
            if (reported.add(key)) {
                RuntimeDiagnostics.log("GUARD5", "recursive runtime path blocked key=$key")
            }
            return fallback()
        }
        return try {
            block()
        } finally {
            set.remove(key)
            if (set.isEmpty()) active.remove()
        }
    }

    fun isActive(key: String): Boolean = active.get().contains(key)

    fun selfTest(): Boolean {
        var fallbackHit = false
        val result = call("self-test", fallback = { -1 }) {
            call("self-test", fallback = { fallbackHit = true; 7 }) { 99 }
        }
        return fallbackHit && result == 7 && !isActive("self-test")
    }
}
