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
        appContext = context.applicationContext
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: error("JobScheduler غير متاح")
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

    fun facadeFor(context: Context, session: RuntimeSession): JobScheduler = RuntimeGuestJobScheduler(context.applicationContext, session)

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
        return if (allNamespaces) {
            Runtime3JobStore.recordsFor(appContext, packageName, slot)
        } else {
            Runtime3JobStore.recordsFor(appContext, packageName, slot, namespace)
        }
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
                .onFailure { RuntimeDiagnostics.log("JOB", "cancelClone host=${record.hostJobId} failed: ${it.javaClass.simpleName}") }
            removeRecord(record.packageName, record.slot, record.hostJobId)
        }
        Runtime3JobStore.clear(appContext, packageName, slot)
        RuntimeDiagnostics.log("JOB", "cancelClone $packageName/$slot count=${records.size}")
        return records.size
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
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return invokeDelegate(method, args)
            val session = RuntimeExecutionScope.current() ?: return invokeDelegate(method, args)
            return when (method.name) {
                "schedule", "enqueue" -> routeSchedule(session, method, args)
                "cancel" -> routeCancel(session, method, args)
                "cancelAll", "cancelAllInNamespace" -> routeCancelAll(session, method, args)
                "getPendingJob" -> routeGetPendingJob(session, method, args)
                else -> invokeDelegate(method, args)
            }
        }

        private fun routeSchedule(session: RuntimeSession, method: Method, args: Array<out Any?>?): Any? {
            val source = args ?: emptyArray()
            val index = source.indexOfFirst { it is JobInfo }
            if (index < 0) return invokeDelegate(method, args)
            val original = source[index] as JobInfo
            val service = original.service
            val pkg = session.runtimePackage
            if (service.packageName != pkg.packageName || !pkg.ownsService(service.className)) return invokeDelegate(method, args)
            val namespace = namespaceFrom(method, source)
            val hostId = hostJobId(pkg.packageName, pkg.slot, namespace, original.id)
            val hostService = ComponentName(BuildConfig.APPLICATION_ID, RuntimeProcessPool.jobServiceStub(pkg.packageName, pkg.slot).name)
            val routed = cloneAndPatchJob(original, hostId, hostService).getOrElse {
                RuntimeDiagnostics.log("JOB", "JobInfo patch failed ${pkg.packageName}/${pkg.slot} id=${original.id}: ${it.stackTraceToString()}")
                return invokeDelegate(method, args)
            }
            saveRecord(JobRecord(pkg.packageName, pkg.slot, service.className, original.id, hostId, namespace))
            val mutable = Array<Any?>(source.size) { source[it] }
            mutable[index] = routed
            RuntimeDiagnostics.log("JOB", "schedule ${pkg.packageName}/${pkg.slot} ns=${namespace ?: "default"} ${service.className} guest=${original.id} host=$hostId")
            return invokeDelegate(method, mutable)
        }

        private fun routeCancel(session: RuntimeSession, method: Method, args: Array<out Any?>?): Any? {
            val source = args ?: return invokeDelegate(method, args)
            val intIndex = method.parameterTypes.indexOfLast { it == Int::class.javaPrimitiveType }
            if (intIndex < 0 || source.getOrNull(intIndex) !is Int) return invokeDelegate(method, args)
            val guestId = source[intIndex] as Int
            val namespace = namespaceFrom(method, source)
            val pkg = session.runtimePackage
            val hostId = hostJobId(pkg.packageName, pkg.slot, namespace, guestId)
            val mutable = Array<Any?>(source.size) { source[it] }
            mutable[intIndex] = hostId
            removeRecord(pkg.packageName, pkg.slot, hostId)
            RuntimeDiagnostics.log("JOB", "cancel ${pkg.packageName}/${pkg.slot} ns=${namespace ?: "default"} guest=$guestId host=$hostId")
            return invokeDelegate(method, mutable)
        }

        private fun routeCancelAll(session: RuntimeSession, method: Method, args: Array<out Any?>?): Any? {
            val pkg = session.runtimePackage
            val namespace = if (method.name == "cancelAllInNamespace") namespaceFrom(method, args ?: emptyArray()) else null
            val allNamespaces = method.name == "cancelAll"
            val records = recordsFor(pkg.packageName, pkg.slot, namespace, allNamespaces)
            val cancelMethod = delegate.javaClass.methods.firstOrNull {
                it.name == "cancel" && it.parameterTypes.lastOrNull() == Int::class.javaPrimitiveType
            } ?: return nullFor(method.returnType)
            records.forEach { record ->
                runCatching {
                    val callArgs = when (cancelMethod.parameterCount) {
                        1 -> arrayOf<Any?>(record.hostJobId)
                        2 -> arrayOf<Any?>(record.namespace, record.hostJobId)
                        else -> return@runCatching
                    }
                    cancelMethod.invoke(delegate, *callArgs)
                }
                removeRecord(record.packageName, record.slot, record.hostJobId)
            }
            RuntimeDiagnostics.log("JOB", "${method.name} ${pkg.packageName}/${pkg.slot} ns=${namespace ?: if (allNamespaces) "ALL" else "default"} count=${records.size}")
            return nullFor(method.returnType)
        }

        private fun routeGetPendingJob(session: RuntimeSession, method: Method, args: Array<out Any?>?): Any? {
            val source = args ?: return invokeDelegate(method, args)
            val intIndex = method.parameterTypes.indexOfLast { it == Int::class.javaPrimitiveType }
            if (intIndex < 0 || source.getOrNull(intIndex) !is Int) return invokeDelegate(method, args)
            val guestId = source[intIndex] as Int
            val namespace = namespaceFrom(method, source)
            val pkg = session.runtimePackage
            val mutable = Array<Any?>(source.size) { source[it] }
            mutable[intIndex] = hostJobId(pkg.packageName, pkg.slot, namespace, guestId)
            return invokeDelegate(method, mutable)
        }

        private fun namespaceFrom(method: Method, args: Array<out Any?>): String? {
            if (Build.VERSION.SDK_INT < 34) return null
            val index = method.parameterTypes.indices.firstOrNull { it < args.size && method.parameterTypes[it] == String::class.java }
                ?: return null
            return args[index] as? String
        }

        private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? = try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) { throw (e.targetException ?: e) }
    }

    private fun cloneAndPatchJob(original: JobInfo, hostId: Int, hostService: ComponentName): Result<JobInfo> = runCatching {
        val parcel = android.os.Parcel.obtain()
        val clone = try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            JobInfo.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        val idField = RuntimeCompatibility.findField(JobInfo::class.java, "jobId", "mJobId") ?: error("JobInfo.jobId غير متاح")
        val serviceField = RuntimeCompatibility.findField(JobInfo::class.java, "service", "mService") ?: error("JobInfo.service غير متاح")
        idField.isAccessible = true
        serviceField.isAccessible = true
        idField.setInt(clone, hostId)
        serviceField.set(clone, hostService)
        clone
    }

    fun hostJobId(packageName: String, slot: Int, namespace: String?, guestId: Int): Int {
        var h = 17
        h = 31 * h + packageName.hashCode()
        h = 31 * h + slot
        h = 31 * h + (namespace?.hashCode() ?: 0)
        h = 31 * h + guestId
        return h and 0x7fffffff
    }

    private fun nullFor(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }
}

open class RuntimeJobService : JobService() {
    private data class Guest(val service: JobService, val session: RuntimeSession)
    private val running = ConcurrentHashMap<Int, Guest>()

    override fun onStartJob(params: JobParameters): Boolean {
        val record = RuntimeJobSchedulerBridge.lookup(params.jobId) ?: return false
        val app = applicationContext as? MultiApplication ?: MultiApplication.current ?: return false
        val session = runCatching { app.engine.sessionFor(record.packageName, record.slot) }.getOrElse {
            RuntimeDiagnostics.log("JOB", "session restore failed host=${params.jobId}: ${it.stackTraceToString()}")
            return false
        }
        if (!session.runtimePackage.ownsService(record.serviceName)) return false
        val guest = running.getOrPut(params.jobId) { createGuest(session, record.serviceName, record.packageName, record.slot) }
        return runCatching { RuntimeExecutionScope.withSession(session) { guest.service.onStartJob(params) } }.getOrDefault(false)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        RuntimeJobSchedulerBridge.lookup(params.jobId) ?: return false
        val guest = running[params.jobId] ?: return false
        return runCatching { RuntimeExecutionScope.withSession(guest.session) { guest.service.onStopJob(params) } }.getOrDefault(false)
    }

    override fun onDestroy() {
        running.values.forEach { guest -> runCatching { RuntimeExecutionScope.withSession(guest.session) { guest.service.onDestroy() } } }
        running.clear()
        super.onDestroy()
    }

    private fun createGuest(session: RuntimeSession, serviceName: String, packageName: String, slot: Int): Guest = RuntimeExecutionScope.withSession(session) {
        val clazz = session.classLoader.loadClass(serviceName)
        require(JobService::class.java.isAssignableFrom(clazz)) { "JobService class غير صالح: $serviceName" }
        val service = clazz.getDeclaredConstructor().newInstance() as JobService
        val app = applicationContext as MultiApplication
        val guestContext = RuntimeGuestContext(baseContext, session, app.engine.runtimeSlotDir(packageName, slot))
        attachServiceFields(service, guestContext, session, serviceName)
        service.onCreate()
        service.onBind(Intent())
        RuntimeDiagnostics.log("JOB", "created guest JobService $packageName/$slot $serviceName process=${if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName}")
        Guest(service, session)
    }

    private fun attachServiceFields(service: Service, guestContext: Context, session: RuntimeSession, serviceName: String) {
        ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(service, guestContext)
        val serviceClass = Service::class.java
        fun field(name: String) = serviceClass.getDeclaredField(name).apply { isAccessible = true }
        field("mApplication").set(service, session.guestApplication ?: application)
        field("mClassName").set(service, serviceName)
        listOf("mThread", "mToken", "mActivityManager", "mStartCompatibility").forEach { name ->
            runCatching { val f = field(name); f.set(service, f.get(this)) }
        }
    }
}

class RuntimeJobService0 : RuntimeJobService()
class RuntimeJobService1 : RuntimeJobService()
class RuntimeJobService2 : RuntimeJobService()
class RuntimeJobService3 : RuntimeJobService()
class RuntimeJobService4 : RuntimeJobService()
