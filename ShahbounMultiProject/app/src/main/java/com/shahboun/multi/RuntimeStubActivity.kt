package com.shahboun.multi

import android.app.Activity
import android.app.Service
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

/** Five fixed host processes. package+slot is mapped deterministically so copies of one app spread across the pool. */
object RuntimeProcessPool {
    private val activityStubs: Array<Class<out Activity>> = arrayOf(
        RuntimeStubActivity0::class.java,
        RuntimeStubActivity1::class.java,
        RuntimeStubActivity2::class.java,
        RuntimeStubActivity3::class.java,
        RuntimeStubActivity4::class.java
    )
    private val serviceStubs: Array<Class<out Service>> = arrayOf(
        RuntimeStubService0::class.java,
        RuntimeStubService1::class.java,
        RuntimeStubService2::class.java,
        RuntimeStubService3::class.java,
        RuntimeStubService4::class.java
    )

    fun processIndex(packageName: String, slot: Int): Int = Math.floorMod(31 * packageName.hashCode() + slot, activityStubs.size)
    fun activityStub(packageName: String, slot: Int): Class<out Activity> = activityStubs[processIndex(packageName, slot)]
    fun serviceStub(packageName: String, slot: Int): Class<out Service> = serviceStubs[processIndex(packageName, slot)]
    fun isActivityStubName(name: String?): Boolean = name == RuntimeStubActivity::class.java.name || activityStubs.any { it.name == name }
}
