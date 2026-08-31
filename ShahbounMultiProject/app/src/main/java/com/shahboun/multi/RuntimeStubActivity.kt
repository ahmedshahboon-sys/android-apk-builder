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

/** Fixed process selector. Slots 0..4 are isolated; higher slots safely use the shared fallback. */
object RuntimeProcessPool {
    private val activityStubs: Array<Class<out Activity>> = arrayOf(
        RuntimeStubActivity0::class.java,
        RuntimeStubActivity1::class.java,
        RuntimeStubActivity2::class.java,
        RuntimeStubActivity3::class.java,
        RuntimeStubActivity4::class.java
    )

    fun activityStub(slot: Int): Class<out Activity> = activityStubs.getOrNull(slot) ?: RuntimeStubActivity::class.java
    fun serviceStub(slot: Int): Class<out Service> = when (slot) {
        0 -> RuntimeStubService0::class.java
        1 -> RuntimeStubService1::class.java
        2 -> RuntimeStubService2::class.java
        3 -> RuntimeStubService3::class.java
        4 -> RuntimeStubService4::class.java
        else -> RuntimeStubService::class.java
    }
    fun isActivityStubName(name: String?): Boolean = name == RuntimeStubActivity::class.java.name || activityStubs.any { it.name == name }
}
