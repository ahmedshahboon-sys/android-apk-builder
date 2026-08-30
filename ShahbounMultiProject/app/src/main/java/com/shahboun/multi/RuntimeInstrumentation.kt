package com.shahboun.multi

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle

internal const val EXTRA_RUNTIME_PACKAGE = "shahboun.runtime.package"
internal const val EXTRA_RUNTIME_SLOT = "shahboun.runtime.slot"
internal const val EXTRA_RUNTIME_ACTIVITY = "shahboun.runtime.activity"

/**
 * Own bridge between Android's Activity launch path and a Shahboun RuntimeSession.
 * No third-party hook or binary is used.
 */
class ShahbounInstrumentation(private val base: Instrumentation) : Instrumentation() {
    override fun newActivity(cl: ClassLoader?, className: String?, intent: Intent?): Activity {
        if (className == RuntimeStubActivity::class.java.name && intent != null) {
            val packageName = intent.getStringExtra(EXTRA_RUNTIME_PACKAGE)
            val slot = intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
            val guestActivity = intent.getStringExtra(EXTRA_RUNTIME_ACTIVITY)
            if (!packageName.isNullOrBlank() && slot >= 0 && !guestActivity.isNullOrBlank()) {
                val session = RuntimeRegistry.get(packageName, slot)
                return base.newActivity(session.classLoader, guestActivity, intent)
            }
        }
        return base.newActivity(cl, className, intent)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        RuntimeGuestContext.attachIfNeeded(activity)
        base.callActivityOnCreate(activity, icicle)
    }

    override fun callActivityOnStart(activity: Activity) = base.callActivityOnStart(activity)
    override fun callActivityOnResume(activity: Activity) = base.callActivityOnResume(activity)
    override fun callActivityOnPause(activity: Activity) = base.callActivityOnPause(activity)
    override fun callActivityOnStop(activity: Activity) = base.callActivityOnStop(activity)
    override fun callActivityOnDestroy(activity: Activity) = base.callActivityOnDestroy(activity)
}

object RuntimeInstrumentationInstaller {
    @Volatile private var installed = false

    fun install(): Result<Unit> = runCatching {
        if (installed) return@runCatching
        val activityThread = Class.forName("android.app.ActivityThread")
        val currentMethod = activityThread.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }
        val current = currentMethod.invoke(null) ?: error("ActivityThread غير متاح")
        val field = activityThread.getDeclaredField("mInstrumentation").apply { isAccessible = true }
        val existing = field.get(current) as? Instrumentation ?: error("Instrumentation غير متاح")
        if (existing !is ShahbounInstrumentation) field.set(current, ShahbounInstrumentation(existing))
        installed = true
    }
}
