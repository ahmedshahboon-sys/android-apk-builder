package com.shahboun.multi

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.os.Parcel

/**
 * Public-API JobScheduler facade used by guest contexts when Android 14+ hides the
 * framework's cached IJobScheduler Binder. Jobs are rewritten to Shahboun host
 * stubs before they reach the system and translated back to guest identity on reads.
 */
class RuntimeGuestJobScheduler(
    private val hostContext: Context,
    private val session: RuntimeSession,
    private val delegate: JobScheduler = hostContext.getSystemService(JobScheduler::class.java)
        ?: error("JobScheduler غير متاح")
) : JobScheduler() {

    override fun schedule(job: JobInfo): Int {
        if (isRejectedGuestJob(job)) return RESULT_FAILURE
        val routed = route(job) ?: return delegate.schedule(job)
        return runCatching {
            val result = delegate.schedule(routed.hostJob)
            if (result == RESULT_SUCCESS) save(routed.record)
            RuntimeDiagnostics.log(
                "JOB",
                "facade schedule ${session.runtimePackage.packageName}/${session.runtimePackage.slot} guest=${job.id} host=${routed.record.hostJobId} result=$result"
            )
            result
        }.getOrElse {
            RuntimeDiagnostics.log("JOB", "facade schedule rejected ${session.runtimePackage.packageName}/${session.runtimePackage.slot} guest=${job.id}: ${it.javaClass.simpleName}: ${it.message}")
            RESULT_FAILURE
        }
    }

    override fun enqueue(job: JobInfo, work: JobWorkItem): Int {
        if (isRejectedGuestJob(job)) return RESULT_FAILURE
        val routed = route(job) ?: return delegate.enqueue(job, work)
        return runCatching {
            val result = delegate.enqueue(routed.hostJob, work)
            if (result == RESULT_SUCCESS) save(routed.record)
            RuntimeDiagnostics.log(
                "JOB",
                "facade enqueue ${session.runtimePackage.packageName}/${session.runtimePackage.slot} guest=${job.id} host=${routed.record.hostJobId} result=$result"
            )
            result
        }.getOrElse {
            RuntimeDiagnostics.log("JOB", "facade enqueue rejected ${session.runtimePackage.packageName}/${session.runtimePackage.slot} guest=${job.id}: ${it.javaClass.simpleName}: ${it.message}")
            RESULT_FAILURE
        }
    }

    override fun cancel(jobId: Int) {
        val hostId = hostJobId(jobId)
        runCatching { delegate.cancel(hostId) }
        remove(hostId)
        RuntimeDiagnostics.log(
            "JOB",
            "facade cancel ${session.runtimePackage.packageName}/${session.runtimePackage.slot} guest=$jobId host=$hostId"
        )
    }

    override fun cancelAll() {
        RuntimeJobSchedulerBridge.cancelClone(session.runtimePackage.packageName, session.runtimePackage.slot)
    }

    override fun getAllPendingJobs(): MutableList<JobInfo> {
        val pkg = session.runtimePackage
        return delegate.allPendingJobs.mapNotNull { hostJob ->
            val record = RuntimeJobSchedulerBridge.lookup(hostJob.id)
            if (record == null || record.packageName != pkg.packageName || record.slot != pkg.slot) null
            else restore(hostJob, record)
        }.toMutableList()
    }

    override fun getPendingJob(jobId: Int): JobInfo? {
        val hostId = hostJobId(jobId)
        val record = RuntimeJobSchedulerBridge.lookup(hostId) ?: return null
        if (record.packageName != session.runtimePackage.packageName || record.slot != session.runtimePackage.slot) return null
        return delegate.getPendingJob(hostId)?.let { restore(it, record) }
    }

    private data class Routed(val hostJob: JobInfo, val record: RuntimeJobSchedulerBridge.JobRecord)

    private fun isRejectedGuestJob(original: JobInfo): Boolean {
        val pkg = session.runtimePackage
        val service = original.service
        if (service.packageName != pkg.packageName || !pkg.ownsService(service.className)) return false
        val clazz = runCatching { session.classLoader.loadClass(service.className) }.getOrNull()
        val valid = clazz != null && JobService::class.java.isAssignableFrom(clazz)
        if (!valid) {
            RuntimeDiagnostics.log(
                "JOB",
                "guest job rejected ${pkg.packageName}/${pkg.slot} service=${service.className} guest=${original.id} reason=not-JobService"
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
            RuntimeJobSchedulerBridge.JobRecord(pkg.packageName, pkg.slot, service.className, original.id, hostId)
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
        var h = 17
        h = 31 * h + pkg.packageName.hashCode()
        h = 31 * h + pkg.slot
        h = 31 * h + guestId
        return h and 0x7fffffff
    }

    private fun save(record: RuntimeJobSchedulerBridge.JobRecord) {
        hostContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                "job.${record.hostJobId}",
                "${record.packageName}|${record.slot}|${record.serviceName}|${record.guestJobId}"
            )
            .apply()
    }

    private fun remove(hostId: Int) {
        hostContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("job.$hostId).apply()
    }

    companion object {
        private const val PREFS = "shahboun_runtime_jobs"
    }
}
