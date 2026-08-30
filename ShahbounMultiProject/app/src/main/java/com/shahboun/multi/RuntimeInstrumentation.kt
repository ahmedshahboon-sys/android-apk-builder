package com.shahboun.multi

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

internal const val EXTRA_RUNTIME_PACKAGE = "shahboun.runtime.package"
internal const val EXTRA_RUNTIME_SLOT = "shahboun.runtime.slot"
internal const val EXTRA_RUNTIME_ACTIVITY = "shahboun.runtime.activity"

/** Own bridge between Android's Activity launch path and a Shahboun RuntimeSession. */
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

    /**
     * Android keeps Instrumentation.execStartActivity out of the public SDK stubs.
     * The JVM signature is unchanged by Kotlin nullability. Android 16 can pass a
     * null activity token here when ContextImpl starts the first runtime activity,
     * so platform-owned arguments must remain nullable on our bridge boundary.
     */
    @Suppress("unused")
    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val routed = if (target != null) routeForGuest(target, intent) else intent
        RuntimeDiagnostics.log(
            "RUNTIME",
            "execStartActivity target=${target?.javaClass?.name ?: "null"} token=${if (token == null) "null" else "present"}"
        )
        return HiddenInstrumentationDispatch.execStartActivity(
            base, who, contextThread, token, target, routed, requestCode, options
        )
    }

    private fun routeForGuest(activity: Activity, intent: Intent): Intent {
        val current = activity.intent ?: return intent
        val packageName = current.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return intent
        val slot = current.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
        if (slot < 0) return intent
        val session = runCatching { RuntimeRegistry.get(packageName, slot) }.getOrNull() ?: return intent
        return RuntimeIntentRouter.wrap(activity, session, intent)
    }
}

private object HiddenInstrumentationDispatch {
    private val method by lazy {
        Instrumentation::class.java.getDeclaredMethod(
            "execStartActivity",
            Context::class.java,
            IBinder::class.java,
            IBinder::class.java,
            Activity::class.java,
            Intent::class.java,
            Int::class.javaPrimitiveType,
            Bundle::class.java
        ).apply { isAccessible = true }
    }

    fun execStartActivity(
        instrumentation: Instrumentation,
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): Instrumentation.ActivityResult? {
        @Suppress("UNCHECKED_CAST")
        return method.invoke(
            instrumentation, who, contextThread, token, target, intent, requestCode, options
        ) as? Instrumentation.ActivityResult
    }
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
