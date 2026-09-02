package com.shahboun.multi

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Parcel

/**
 * Public-API JobScheduler facade used by guest contexts when Android hides the
 * framework's cached IJobScheduler Binder. Jobs are rewritten to Shahboun host
 * stubs before they reach the system and translated back to guest identity on reads.
 */
class RuntimeGuestJobScheduler(
    private val hostContext: Context,
    private val session: RuntimeSession,
    private val namespace: String? = null,
    private val delegate: JobScheduler = hostContext.getSystemService(JobScheduler::class.java)
        ?: error("JobScheduler غير متاح")
) : JobScheduler() {

    override fun forNamespace(rawNamespace: String): JobScheduler {
        if (Build.VERSION.SDK_INT < 34) return this
        val normalized = rawNamespace.trim()
        require(normalized.isNotEmpty()) { "JobScheduler namespace فارغ" }
        val namespacedDelegate = delegate.forNamespace(normalized)
        RuntimeDiagnostics.log(
            "JOB",
            "facade namespace ${session.runtimePackage.packageName}/${session.runtimePackage.slot} namespace=$normalized"
        )
        return RuntimeGuestJobScheduler(hostContext, session, normalized, namespacedDelegate)
    }

    override fun getNamespace(): String? = namespace

    override fun schedule(job: JobInfo): Int {
        if (isRejectedGuestJob(job)) return RESULT_FAILURE
        val routed = route(job) ?: return safeSchedule(job)
        return runCatching {
            val result = delegate.schedule(routed.hostJob)
            if (result == RESULT_SUCCESS) RuntimeJobSchedulerBridge.saveRecord(routed.record)
            RuntimeDiagnostics.log(
                "JOB",
                "facade schedule ${session.runtimePackage.packageName}/${session.runtimePackage.slot} namespace=${namespace ?: "default"} guest=${job.id} host=${routed.record.hostJobId} result=$result"
            )
            result
        }.getOrElse {
            RuntimeDiagnostics.log("JOB", "facade schedule rejected ${session.runtimePackage.packageName}/${session.runtimePackage.slot} namespace=${namespace ?: "default"} guest=${job.id}: ${it.javaClass.simpleName}: ${it.message}")
            RESULT_FAILURE
        }
    }

    override fun enqueue(job: JobInfo, work: JobWorkItem): Int {
        if (isRejectedGuestJob(job)) return RESULT_FAILURE
        val routed = route(job) ?: return safeEnqueue(job, work)
        return runCatching {
            val result = delegate.enqueue(routed.hostJob, work)
            if (result == RESULT_SUCCESS) RuntimeJobSchedulerBridge.saveRecord(routed.record)
            RuntimeDiagnostics.log(
                "JOB",
                "facade enqueue ${session.runtimePackage.packageName}/${session.runtimePackage.slot} namespace=${namespace ?: "default"} guest=${job.id} host=${routed.record.hostJobId} result=$result"
            )
            result
        }.getOrElse {
            RuntimeDiagnostics.log("JOB", "facade enqueue rejected ${session.runtimePackage.packageName}/${session.runtimePackage.slot} namespace=${namespace ?: "default"} guest=${job.id}: ${it.javaClass.simpleName}: ${it.message}")
            RESULT_FAILURE
        }
    }

    override fun cancel(jobId: Int) {
        val pkg = session.runtimePackage
        val hostId = hostJobId(jobId)
        runCatching { delegate.cancel(hostId) }
        RuntimeJobSchedulerBridge.removeRecord(pkg.packageName, pkg.slot, hostId)
        RuntimeDiagnostics.log(
            "JOB",
            "facade cancel ${pkg.packageName}/${pkg.slot} namespace=${namespace ?: "default"} guest=$jobId host=$hostId"
        )
    }

    override fun cancelAll() {
        val pkg = session.runtimePackage
        val records = RuntimeJobSchedulerBridge.recordsFor(pkg.packageName, pkg.slot, namespace, allNamespaces = false)
        records.forEach { record ->
            runCatching { delegate.cancel(record.hostJobId) }
            RuntimeJobSchedulerBridge.removeRecord(pkg.packageName, pkg.slot, record.hostJobId)
        }
        RuntimeDiagnostics.log(
            "JOB",
            "facade cancelAll ${pkg.packageName}/${pkg.slot} namespace=${namespace ?: "default"} count=${records.size}"
        )
    }

    override fun cancelInAllNamespaces() {
        RuntimeJobSchedulerBridge.cancelClone(session.runtimePackage.packageName, session.runtimePackage.slot)
    }

    override fun getAllPendingJobs(): MutableList<JobInfo> {
        val pkg = session.runtimePackage
        return runCatching { delegate.allPendingJobs }.getOrDefault(emptyList()).mapNotNull { hostJob ->
            val record = RuntimeJobSchedulerBridge.lookup(hostJob.id)
            if (record == null || record.packageName != pkg.packageName || record.slot != pkg.slot || record.namespace != namespace) null
            else restore(hostJob, record)
        }.toMutableList()
    }

    override fun getPendingJob(jobId: Int): JobInfo? {
        val hostId = hostJobId(jobId)
        val record = RuntimeJobSchedulerBridge.lookup(hostId) ?: return null
        val pkg = session.runtimePackage
        if (record.packageName != pkg.packageName || record.slot != pkg.slot || record.namespace != namespace) return null
        return runCatching { delegate.getPendingJob(hostId) }.getOrNull()?.let { restore(it, record) }
    }

    private data class Routed(val hostJob: JobInfo, val record: RuntimeJobSchedulerBridge.JobRecord)

    private fun safeSchedule(job: JobInfo): Int = runCatching { delegate.schedule(job) }.getOrElse {
        RuntimeDiagnostics.log("JOB", "delegate schedule failed namespace=${namespace ?: "default"}: ${it.javaClass.simpleName}: ${it.message}")
        RESULT_FAILURE
    }

    private fun safeEnqueue(job: JobInfo, work: JobWorkItem): Int = runCatching { delegate.enqueue(job, work) }.getOrElse {
        RuntimeDiagnostics.log("JOB", "delegate enqueue failed namespace=${namespace ?: "default"}: ${it.javaClass.simpleName}: ${it.message}")
        RESULT_FAILURE
    }

    private fun isRejectedGuestJob(original: JobInfo): Boolean {
        val pkg = session.runtimePackage
        val service = original.service
        if (service.packageName != pkg.packageName || !pkg.ownsService(service.className)) return false
        val clazz = runCatching { session.classLoader.loadClass(service.className) }.getOrNull()
        val valid = clazz != null && JobService::class.java.isAssignableFrom(clazz)
        if (!valid) {
            RuntimeDiagnostics.log(
                "JOB",
                "guest job rejected ${pkg.packageName}/${pkg.slot} namespace=${namespace ?: "default"} service=${service.className} guest=${original.id} reason=not-JobService"
            )
        }
        return !valid
    }

    private fun route(original: JobInfo): Routed? {
        val pkg = session.runtimePackage
        val service = original.service
        if (service.packageName != pkg.packageName || !pkg.ownsService(service.className)) return null
        val hostId = hostJobId(original.id)
        val hostService = ComponentName(
            BuildConfig.APPLICATION_ID,
            RuntimeProcessPool.jobServiceStub(pkg.packageName, pkg.slot).name
        )
        val patched = patchJob(original, hostId, hostService)
        return Routed(
            patched,
            RuntimeJobSchedulerBridge.JobRecord(
                pkg.packageName,
                pkg.slot,
                service.className,
                original.id,
                hostId,
                namespace
            )
        )
    }

    private fun restore(hostJob: JobInfo, record: RuntimeJobSchedulerBridge.JobRecord): JobInfo = patchJob(
        hostJob,
        record.guestJobId,
        ComponentName(record.packageName, record.serviceName)
    )

    private fun patchJob(source: JobInfo, id: Int, service: ComponentName): JobInfo {
        val parcel = Parcel.obtain()
        val clone = try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            JobInfo.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        val idField = RuntimeCompatibility.findField(JobInfo::class.java, "jobId", "mJobId")
            ?: error("JobInfo.jobId غير متاح")
        val serviceField = RuntimeCompatibility.findField(JobInfo::class.java, "service", "mService")
            ?: error("JobInfo.service غير متاح")
        idField.isAccessible = true
        serviceField.isAccessible = true
        idField.setInt(clone, id)
        serviceField.set(clone, service)
        return clone
    }

    private fun hostJobId(guestId: Int): Int {
        val pkg = session.runtimePackage
        return RuntimeJobSchedulerBridge.hostJobId(pkg.packageName, pkg.slot, namespace, guestId)
    }
}
