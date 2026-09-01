package com.shahboun.multi

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * Resolves the clone session for Binder/system calls, including worker threads that do not inherit
 * the guest ThreadLocal/class loader. Each :cloneN process has one immutable clone owner.
 */
object RuntimeSessionResolver {
    fun current(context: Context, args: Array<out Any?>? = null): RuntimeSession? {
        RuntimeExecutionScope.current()?.let { return it }

        RuntimeExecutionScope.processOwner()?.let { (packageName, slot) ->
            RuntimeRegistry.getOrNull(packageName, slot)?.let { return it }
            restore(packageName, slot)?.let { return it }
        }

        val processIndex = processIndex() ?: return null
        val hints = hintedPackages(args)
        val candidates = RuntimeProcessAllocator.snapshot(context)
            .filterValues { it == processIndex }
            .keys
            .mapNotNull(::parseIdentity)
            .filter { hints.isEmpty() || it.first in hints }

        if (candidates.size != 1) return null
        val (packageName, slot) = candidates.single()
        return RuntimeRegistry.getOrNull(packageName, slot) ?: restore(packageName, slot)
    }

    private fun restore(packageName: String, slot: Int): RuntimeSession? =
        runCatching { MultiApplication.current?.engine?.sessionFor(packageName, slot) }.getOrNull()

    private fun processIndex(): Int? {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else BuildConfig.APPLICATION_ID
        return name.substringAfter(":clone", "").toIntOrNull()
    }

    private fun hintedPackages(args: Array<out Any?>?): Set<String> = buildSet {
        args.orEmpty().forEach { value ->
            when (value) {
                is ComponentName -> add(value.packageName)
                is String -> if (value.contains('.')) add(value)
                is Iterable<*> -> value.forEach { nested -> if (nested is ComponentName) add(nested.packageName) }
                is Array<*> -> value.forEach { nested -> if (nested is ComponentName) add(nested.packageName) }
            }
        }
    }

    private fun parseIdentity(raw: String): Pair<String, Int>? {
        val split = raw.lastIndexOf('#')
        if (split <= 0 || split == raw.lastIndex) return null
        val packageName = raw.substring(0, split)
        val slot = raw.substring(split + 1).toIntOrNull() ?: return null
        return packageName to slot
    }
}
