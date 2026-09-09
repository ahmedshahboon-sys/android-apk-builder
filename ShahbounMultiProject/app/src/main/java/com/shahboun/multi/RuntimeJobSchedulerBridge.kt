package com.shahboun.multi

import android.app.Application
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

object RuntimeJobSchedulerBridge {
    @Volatile private var installed = false
    @Volatile private var facadeOnly = false
    private lateinit var appContext: Context

    fun install(context: Context): Result<Unit> = runCatching {
        if (installed) return@runCatching
        appContext = physicalContext(context)
        val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: error("JobScheduler غير متاح")
        val handle = RuntimeCompatibility.findService(
            scheduler,
            interfaceHints = listOf("IJobScheduler", "JobSchedulerService"),
            candidateNames = listOf("mBinder", "mService", "mScheduler", "mJobScheduler", "mBinderService")
        )
        if (handle == null) {
            activateFacade("hidden IJobScheduler binder unavailable")
            return@runCatching
        }
        val field = handle.field
        val delegate = handle.delegate
        if (Proxy.isProxyClass(delegate.javaClass) && Proxy.getInvocationHandler(delegate) is Handler) {
            installed = true
            facadeOnly = false
            return@runCatching
        }
        val interfaces = RuntimeCompatibility.collectInterfaces(delegate.javaClass)
        if (interfaces.isEmpty()) {
            activateFacade("hidden IJobScheduler interface unavailable")
            return@runCatching
        }
        val proxy = Proxy.newProxyInstance(interfaces.first().classLoader, interfaces, Handler(delegate))
        if (!RuntimeCompatibility.write(field, scheduler, proxy)) {
            activateFacade("hidden IJobScheduler proxy write blocked")
            return@runCatching
        }
        installed = true
        facadeOnly = false
        RuntimeDiagnostics.log("JOB", "Runtime3 JobScheduler bridge installed field=${field.name} owner=${field.declaringClass.name}")
    }

    fun usesPublicFacade(): Boolean = facadeOnly

    /**
     * Always construct the guest facade from the physical host Context.
     * A guest/wrapped Context can route JobScheduler back into RuntimeGuestContext.getSystemService(),
     * which recursively constructs another RuntimeGuestJobScheduler until StackOverflowError.
     */
    fun facadeFor(context: Context, session: RuntimeSession): JobScheduler =
        RuntimeGuestJobScheduler(physicalContext(context), session)

    private fun physicalContext(context: Context): Context {
        var current: Context = context
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        while (current is ContextWrapper && current !is Application && seen.add(current)) {
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        val application = current.applicationContext
        return if (application != null && application !is RuntimeGuestContext) application else current
    }

    private fun activateFacade(reason: String) {
        installed = true
        facadeOnly = true
        RuntimeDiagnostics.log("JOB", "public-api JobScheduler facade active reason=$reason")
    }

    fun lookup(hostJobId: Int): JobRecord? {
        if (!::appContext.isInitialized) return null
        return Runtime3JobStore.lookup(appContext, hostJobId)
    }

    fun saveRecord(record: JobRecord) {
        if (!::appContext.isInitialized) return
        Runtime3JobStore.save(appContext, record)
    }

    fun removeRecord(packageName: String, slot: Int, hostJobId: Int) {
        if (!::appContext.isInitialized) return
        Runtime3JobStore.remove(appContext, packageName, slot, hostJobId)
    }

    fun recordsFor(packageName: String, slot: Int, namespace: String? = null, allNamespaces: Boolean = true): List<JobRecord> {
        if (!::appContext.isInitialized) return emptyList()
        return if (allNamespaces) Runtime3JobStore.recordsFor(appContext, packageName, slot)
        else Runtime3JobStore.recordsFor(appContext, packageName, slot, namespace)
    }

    fun cancelClone(packageName: String, slot: Int): Int {
        if (!::appContext.isInitialized) return 0
        val records = recordsFor(packageName, slot)
        val scheduler = appContext.getSystemService(JobScheduler::class.java)
        records.forEach { record ->
            val target = if (Build.VERSION.SDK_INT >= 34 && record.namespace != null) {
                runCatching { scheduler?.forNamespace(record.namespace) }.getOrNull() ?: scheduler
            } else scheduler
            runCatching { target?.cancel(record.hostJobId) }
            removeRecord(packageName, slot, record.hostJobId)
        }
        return records.size
    }

    fun hostJobId(packageName: String, slot: Int, namespace: String?, guestJobId: Int): Int {
        val namespaceHash = namespace?.hashCode() ?: 0
        val hash = 31 * (31 * packageName.hashCode() + slot) + namespaceHash
        return ((hash and 0x3fff) shl 16) or (guestJobId and 0xffff)
    }

    data class JobRecord(
        val packageName: String,
        val slot: Int,
        val serviceName: String,
        val guestJobId: Int,
        val hostJobId: Int,
        val namespace: String? = null
    )

    private class Handler(private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val mutable = args?.copyOf() ?: emptyArray()
            return try {
                method.invoke(delegate, *mutable)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
