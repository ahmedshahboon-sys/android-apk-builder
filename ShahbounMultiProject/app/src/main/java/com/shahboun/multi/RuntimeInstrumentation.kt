package com.shahboun.multi

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import java.util.Collections
import java.util.WeakHashMap

internal const val EXTRA_RUNTIME_PACKAGE = "shahboun.runtime.package"
internal const val EXTRA_RUNTIME_SLOT = "shahboun.runtime.slot"
internal const val EXTRA_RUNTIME_ACTIVITY = "shahboun.runtime.activity"

internal object RuntimeActivityBindings {
    private data class Key(val packageName: String, val slot: Int)
    private val bindings = Collections.synchronizedMap(WeakHashMap<Activity, Key>())
    fun bind(activity: Activity, packageName: String, slot: Int) { bindings[activity] = Key(packageName, slot); RuntimeDiagnostics.log("RUNTIME", "activity bound $packageName/$slot activity=${activity.javaClass.name}") }
    fun sessionFor(activity: Activity): RuntimeSession? { val key = bindings[activity] ?: return null; return RuntimeRegistry.getOrNull(key.packageName, key.slot) ?: runCatching { MultiApplication.current?.engine?.sessionFor(key.packageName, key.slot) }.getOrNull() }
    fun unbind(activity: Activity) { bindings.remove(activity) }

    fun finishClone(packageName: String, slot: Int): Int {
        val targets = synchronized(bindings) { bindings.entries.filter { it.value.packageName == packageName && it.value.slot == slot }.map { it.key } }
        targets.forEach { activity ->
            runCatching { activity.runOnUiThread { if (!activity.isFinishing) activity.finishAndRemoveTask() } }
        }
        RuntimeDiagnostics.log("RUNTIME", "finish activities $packageName/$slot count=${targets.size}")
        return targets.size
    }
}

class ShahbounInstrumentation(private val base: Instrumentation) : Instrumentation() {
    override fun newActivity(cl: ClassLoader?, className: String?, intent: Intent?): Activity {
        if (intent != null) {
            val packageName = intent.getStringExtra(EXTRA_RUNTIME_PACKAGE)
            val slot = intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
            val requested = intent.getStringExtra(EXTRA_RUNTIME_ACTIVITY)
            if (!packageName.isNullOrBlank() && slot >= 0 && !requested.isNullOrBlank()) {
                val app = MultiApplication.current ?: error("Shahboun application runtime غير متاح")
                val session = app.engine.sessionFor(packageName, slot)
                require(session.runtimePackage.ownsActivity(requested)) { "Activity غير مسجلة في Snapshot النسخة" }
                val resolved = session.runtimePackage.resolveActivity(requested)
                val incoming = className.orEmpty()
                require(RuntimeProcessPool.isActivityStubName(incoming) || incoming == resolved || incoming == requested) {
                    "Launch Activity غير متوقعة: incoming=$incoming expected=$resolved"
                }
                RuntimeDiagnostics.log(
                    "RUNTIME",
                    "newActivity incoming=$incoming requested=$requested resolved=$resolved package=$packageName/$slot loader=${session.classLoader.javaClass.simpleName}"
                )
                return RuntimeExecutionScope.withSession(session) {
                    base.newActivity(session.classLoader, resolved, intent)
                }
            }
        }
        return base.newActivity(cl, className, intent)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        val frameworkSession = RuntimeFrameworkActivityBinder.bind(activity)
        if (frameworkSession == null) RuntimeGuestContext.attachIfNeeded(activity)
        RuntimeActivityResourceFix.prepare(activity)
        scoped(activity) { base.callActivityOnCreate(activity, icicle) }
        RuntimeFrameworkActivityBinder.restorePublicIntent(activity)
    }
    override fun callActivityOnStart(activity: Activity) = scoped(activity) { base.callActivityOnStart(activity) }
    override fun callActivityOnResume(activity: Activity) = scoped(activity) { base.callActivityOnResume(activity) }
    override fun callActivityOnPause(activity: Activity) = scoped(activity) { base.callActivityOnPause(activity) }
    override fun callActivityOnStop(activity: Activity) = scoped(activity) { base.callActivityOnStop(activity) }
    override fun callActivityOnDestroy(activity: Activity) { val session = RuntimeActivityBindings.sessionFor(activity); try { if (session != null) RuntimeExecutionScope.withSession(session) { base.callActivityOnDestroy(activity) } else base.callActivityOnDestroy(activity) } finally { RuntimeActivityBindings.unbind(activity) } }

    @Suppress("unused")
    fun execStartActivity(who: Context?, contextThread: IBinder?, token: IBinder?, target: Activity?, intent: Intent, requestCode: Int, options: Bundle?): ActivityResult? {
        val session = target?.let(RuntimeActivityBindings::sessionFor)
        val routed = if (target != null) routeForGuest(target, intent) else intent
        RuntimeDiagnostics.log("RUNTIME", "execStartActivity target=${target?.javaClass?.name ?: "null"} token=${if (token == null) "null" else "present"} routed=${routed.component?.flattenToShortString() ?: routed.action}")
        return if (session != null) RuntimeExecutionScope.withSession(session) { HiddenInstrumentationDispatch.execStartActivity(base, who, contextThread, token, target, routed, requestCode, options) }
        else HiddenInstrumentationDispatch.execStartActivity(base, who, contextThread, token, target, routed, requestCode, options)
    }

    private fun <T> scoped(activity: Activity, block: () -> T): T { val session = RuntimeActivityBindings.sessionFor(activity); return if (session != null) RuntimeExecutionScope.withSession(session, block) else block() }
    private fun routeForGuest(activity: Activity, intent: Intent): Intent { val session = RuntimeActivityBindings.sessionFor(activity) ?: return intent; return RuntimeIntentRouter.wrap(activity, session, intent) }
}

private object HiddenInstrumentationDispatch {
    private val method by lazy { Instrumentation::class.java.getDeclaredMethod("execStartActivity", Context::class.java, IBinder::class.java, IBinder::class.java, Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java).apply { isAccessible = true } }
    fun execStartActivity(instrumentation: Instrumentation, who: Context?, contextThread: IBinder?, token: IBinder?, target: Activity?, intent: Intent, requestCode: Int, options: Bundle?): Instrumentation.ActivityResult? {
        @Suppress("UNCHECKED_CAST") return method.invoke(instrumentation, who, contextThread, token, target, intent, requestCode, options) as? Instrumentation.ActivityResult
    }
}

object RuntimeInstrumentationInstaller {
    @Volatile private var installed = false
    fun install(): Result<Unit> = runCatching {
        if (installed) return@runCatching
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null) ?: error("ActivityThread غير متاح")
        val field = RuntimeCompatibility.findField(activityThread, "mInstrumentation") ?: error("Instrumentation غير متاح")
        field.isAccessible = true
        val existing = field.get(current) as? Instrumentation ?: error("Instrumentation غير متاح")
        if (existing !is ShahbounInstrumentation) field.set(current, ShahbounInstrumentation(existing))
        installed = true
    }
}
