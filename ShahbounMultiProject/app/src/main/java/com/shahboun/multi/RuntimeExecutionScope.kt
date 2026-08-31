package com.shahboun.multi

/**
 * Tracks which clone owns the current callback/thread. System bridges use this instead
 * of guessing from package names, which is essential when several slots share one package.
 */
object RuntimeExecutionScope {
    private val local = object : InheritableThreadLocal<RuntimeSession?>() {}

    fun current(): RuntimeSession? = local.get() ?: RuntimeRegistry.sessionForClassLoader(Thread.currentThread().contextClassLoader)

    fun <T> withSession(session: RuntimeSession, block: () -> T): T {
        val previous = current()
        val thread = Thread.currentThread()
        val previousLoader = thread.contextClassLoader
        local.set(session)
        thread.contextClassLoader = session.classLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previousLoader
            if (previous == null) local.remove() else local.set(previous)
        }
    }
}
