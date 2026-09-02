package com.shahboun.multi

import android.app.Activity
import android.app.Service
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.os.Bundle
import android.widget.TextView

/** Declared host Activity token target. Guest activities replace it through ShahbounInstrumentation. */
open class RuntimeStubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "تعذر تشغيل النسخة: طبقة Shahboun Runtime لم تستبدل شاشة التشغيل"
            textSize = 18f
            setPadding(40, 80, 40, 40)
        })
    }
}

class RuntimeStubActivity0 : RuntimeStubActivity()
class RuntimeStubActivity1 : RuntimeStubActivity()
class RuntimeStubActivity2 : RuntimeStubActivity()
class RuntimeStubActivity3 : RuntimeStubActivity()
class RuntimeStubActivity4 : RuntimeStubActivity()
class RuntimeStubActivity5 : RuntimeStubActivity()
class RuntimeStubActivity6 : RuntimeStubActivity()
class RuntimeStubActivity7 : RuntimeStubActivity()
class RuntimeStubActivity8 : RuntimeStubActivity()
class RuntimeStubActivity9 : RuntimeStubActivity()
class RuntimeStubActivity10 : RuntimeStubActivity()
class RuntimeStubActivity11 : RuntimeStubActivity()
class RuntimeStubActivity12 : RuntimeStubActivity()
class RuntimeStubActivity13 : RuntimeStubActivity()
class RuntimeStubActivity14 : RuntimeStubActivity()
class RuntimeStubActivity15 : RuntimeStubActivity()

/** Runtime 3 fixed-capacity pool. A slot is never shared by two clone identities. */
object RuntimeProcessPool {
    private val activityStubs: Array<Class<out Activity>> = arrayOf(
        RuntimeStubActivity0::class.java, RuntimeStubActivity1::class.java, RuntimeStubActivity2::class.java,
        RuntimeStubActivity3::class.java, RuntimeStubActivity4::class.java, RuntimeStubActivity5::class.java,
        RuntimeStubActivity6::class.java, RuntimeStubActivity7::class.java, RuntimeStubActivity8::class.java,
        RuntimeStubActivity9::class.java, RuntimeStubActivity10::class.java, RuntimeStubActivity11::class.java,
        RuntimeStubActivity12::class.java, RuntimeStubActivity13::class.java, RuntimeStubActivity14::class.java,
        RuntimeStubActivity15::class.java
    )
    private val serviceStubs: Array<Class<out Service>> = arrayOf(
        RuntimeStubService0::class.java, RuntimeStubService1::class.java, RuntimeStubService2::class.java,
        RuntimeStubService3::class.java, RuntimeStubService4::class.java, RuntimeStubService5::class.java,
        RuntimeStubService6::class.java, RuntimeStubService7::class.java, RuntimeStubService8::class.java,
        RuntimeStubService9::class.java, RuntimeStubService10::class.java, RuntimeStubService11::class.java,
        RuntimeStubService12::class.java, RuntimeStubService13::class.java, RuntimeStubService14::class.java,
        RuntimeStubService15::class.java
    )
    private val receiverStubs: Array<Class<out BroadcastReceiver>> = arrayOf(
        RuntimeStubReceiver0::class.java, RuntimeStubReceiver1::class.java, RuntimeStubReceiver2::class.java,
        RuntimeStubReceiver3::class.java, RuntimeStubReceiver4::class.java, RuntimeStubReceiver5::class.java,
        RuntimeStubReceiver6::class.java, RuntimeStubReceiver7::class.java, RuntimeStubReceiver8::class.java,
        RuntimeStubReceiver9::class.java, RuntimeStubReceiver10::class.java, RuntimeStubReceiver11::class.java,
        RuntimeStubReceiver12::class.java, RuntimeStubReceiver13::class.java, RuntimeStubReceiver14::class.java,
        RuntimeStubReceiver15::class.java
    )
    private val jobServiceStubs: Array<Class<out JobService>> = arrayOf(
        RuntimeJobService0::class.java, RuntimeJobService1::class.java, RuntimeJobService2::class.java,
        RuntimeJobService3::class.java, RuntimeJobService4::class.java, RuntimeJobService5::class.java,
        RuntimeJobService6::class.java, RuntimeJobService7::class.java, RuntimeJobService8::class.java,
        RuntimeJobService9::class.java, RuntimeJobService10::class.java, RuntimeJobService11::class.java,
        RuntimeJobService12::class.java, RuntimeJobService13::class.java, RuntimeJobService14::class.java,
        RuntimeJobService15::class.java
    )

    val size: Int get() = activityStubs.size

    fun allocateProcess(packageName: String, slot: Int): Int {
        val app = MultiApplication.current ?: error("Runtime 3 application not initialized")
        return RuntimeProcessAllocator.allocate(app, packageName, slot, size)
    }

    fun processIndex(packageName: String, slot: Int): Int {
        val app = MultiApplication.current ?: error("Runtime 3 application not initialized")
        return RuntimeProcessAllocator.lookup(app, packageName, slot, size)
            ?: RuntimeProcessAllocator.allocate(app, packageName, slot, size)
    }

    fun releaseProcess(packageName: String, slot: Int) {
        MultiApplication.current?.let { RuntimeProcessAllocator.release(it, packageName, slot) }
    }

    fun activityStub(packageName: String, slot: Int): Class<out Activity> = activityStubs[processIndex(packageName, slot)]
    fun serviceStub(packageName: String, slot: Int): Class<out Service> = serviceStubs[processIndex(packageName, slot)]
    fun receiverStub(packageName: String, slot: Int): Class<out BroadcastReceiver> = receiverStubs[processIndex(packageName, slot)]
    fun jobServiceStub(packageName: String, slot: Int): Class<out JobService> = jobServiceStubs[processIndex(packageName, slot)]
    fun isActivityStubName(name: String?): Boolean = name == RuntimeStubActivity::class.java.name || activityStubs.any { it.name == name }
}
