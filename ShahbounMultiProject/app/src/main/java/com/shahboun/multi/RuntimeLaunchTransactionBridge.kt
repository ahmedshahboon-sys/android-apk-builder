package com.shahboun.multi

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Message
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Engine 2.0 pre-attach Activity virtualization.
 *
 * The Android system validates and schedules a declared Shahboun stub. Once the transaction reaches
 * the assigned clone process, this bridge swaps the local launch record to guest Intent/ActivityInfo
 * and binds a guest LoadedApk before ActivityThread creates ContextImpl or asks Instrumentation for
 * the Activity. The system token/process identity remains the declared host stub identity.
 */
object RuntimeLaunchTransactionBridge {
    @Volatile private var installed = false

    fun install(): Result<Unit> = runCatching {
        if (installed) return@runCatching
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            ?: error("ActivityThread غير متاح")
        val handlerField = RuntimeCompatibility.findField(threadClass, "mH") ?: error("ActivityThread.mH غير متاح")
        handlerField.isAccessible = true
        val handler = handlerField.get(thread) as? Handler ?: error("ActivityThread handler غير متاح")
        val callbackField = RuntimeCompatibility.findField(Handler::class.java, "mCallback") ?: error("Handler.mCallback غير متاح")
        callbackField.isAccessible = true
        val previous = callbackField.get(handler) as? Handler.Callback
        if (previous is Callback) {
            installed = true
            return@runCatching
        }
        callbackField.set(handler, Callback(previous))
        installed = true
        RuntimeDiagnostics.log("LAUNCH2", "pre-attach transaction bridge installed sdk=${RuntimeCompatibility.profile.sdk}")
    }

    private class Callback(private val previous: Handler.Callback?) : Handler.Callback {
        private val handling = AtomicBoolean(false)

        override fun handleMessage(msg: Message): Boolean {
            if (!handling.compareAndSet(false, true)) return previous?.handleMessage(msg) ?: false
            return try {
                runCatching { patchTransaction(msg.obj) }
                    .onFailure { RuntimeDiagnostics.log("LAUNCH2", "transaction patch fallback: ${it.stackTraceToString()}") }
                previous?.handleMessage(msg) ?: false
            } finally {
                handling.set(false)
            }
        }
    }

    private data class Descriptor(val packageName: String, val slot: Int, val activity: String)

    private fun patchTransaction(root: Any?) {
        root ?: return
        val descriptor = findDescriptor(root) ?: return
        val app = MultiApplication.current ?: return
        val expectedProcess = "${BuildConfig.APPLICATION_ID}:clone${RuntimeProcessPool.processIndex(descriptor.packageName, descriptor.slot)}"
        val actualProcess = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else BuildConfig.APPLICATION_ID
        if (actualProcess != expectedProcess) {
            RuntimeDiagnostics.log("LAUNCH2", "rejected wrong process ${descriptor.packageName}/${descriptor.slot} actual=$actualProcess expected=$expectedProcess")
            return
        }

        // Bind guest code/resources/Application into Android's own LoadedApk cache before the
        // transaction executor reaches createBaseContextForActivity().
        val session = app.engine.sessionFor(descriptor.packageName, descriptor.slot)
        RuntimeLoadedApkBridge.bind(app, session).getOrThrow()
        val pkg = session.runtimePackage
        val resolvedActivity = pkg.resolveActivity(descriptor.activity)
        val guestInfo = RuntimeLoadedApkBridge.activityInfo(app, session, descriptor.activity)

        val infos = findObjects(root, ActivityInfo::class.java)
        require(infos.isNotEmpty()) { "Launch transaction لا يحتوي ActivityInfo" }
        infos.forEach { info -> patchActivityInfo(info, guestInfo) }

        val intents = findObjects(root, Intent::class.java)
        var routedIntents = 0
        intents.forEach { intent ->
            if (intent.getStringExtra(EXTRA_RUNTIME_PACKAGE) == descriptor.packageName &&
                intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1) == descriptor.slot) {
                intent.component = ComponentName(pkg.packageName, resolvedActivity)
                intent.`package` = pkg.packageName
                // Keep runtime descriptor extras until callActivityOnCreate binds the guest context;
                // RuntimeGuestContext then restores the original public Intent for guest code.
                intent.putExtra(EXTRA_RUNTIME_PACKAGE, pkg.packageName)
                intent.putExtra(EXTRA_RUNTIME_SLOT, pkg.slot)
                intent.putExtra(EXTRA_RUNTIME_ACTIVITY, descriptor.activity)
                routedIntents++
            }
        }

        RuntimeDiagnostics.log(
            "LAUNCH2",
            "guest launch bound ${pkg.packageName}/${pkg.slot} requested=${descriptor.activity} resolved=$resolvedActivity infos=${infos.size} intents=$routedIntents process=$actualProcess"
        )
    }

    private fun patchActivityInfo(target: ActivityInfo, source: ActivityInfo) {
        target.name = source.name
        target.packageName = source.packageName
        target.processName = source.processName
        target.applicationInfo = source.applicationInfo
        target.theme = source.theme
        target.targetActivity = source.targetActivity
        target.exported = source.exported
        target.permission = source.permission
        target.taskAffinity = source.taskAffinity
        target.launchMode = source.launchMode
        target.documentLaunchMode = source.documentLaunchMode
        target.flags = source.flags
        target.screenOrientation = source.screenOrientation
        target.configChanges = source.configChanges
        target.softInputMode = source.softInputMode
        target.uiOptions = source.uiOptions
        target.parentActivityName = source.parentActivityName
        if (android.os.Build.VERSION.SDK_INT >= 26) target.colorMode = source.colorMode
    }

    private fun findDescriptor(root: Any): Descriptor? {
        val intents = findObjects(root, Intent::class.java)
        intents.forEach { intent ->
            val packageName = intent.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return@forEach
            val slot = intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
            val activity = intent.getStringExtra(EXTRA_RUNTIME_ACTIVITY) ?: return@forEach
            if (slot >= 0) return Descriptor(packageName, slot, activity)
        }
        return null
    }

    private fun <T : Any> findObjects(root: Any, target: Class<T>): List<T> {
        val found = ArrayList<T>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        walk(root, target, found, visited, 0)
        return found
    }

    private fun <T : Any> walk(value: Any?, target: Class<T>, out: MutableList<T>, visited: MutableSet<Any>, depth: Int) {
        if (value == null || depth > 7) return
        if (target.isInstance(value)) {
            @Suppress("UNCHECKED_CAST") out += value as T
            return
        }
        if (!visited.add(value)) return
        when (value) {
            is Iterable<*> -> { value.forEach { walk(it, target, out, visited, depth + 1) }; return }
            is Array<*> -> { value.forEach { walk(it, target, out, visited, depth + 1) }; return }
        }
        val name = value.javaClass.name
        if (!(name.startsWith("android.app.servertransaction.") || name.startsWith("android.app.ClientTransaction") || name.startsWith("android.app.ActivityThread"))) return
        RuntimeCompatibility.allFields(value.javaClass).forEach { field ->
            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) return@forEach
            val child = runCatching { field.get(value) }.getOrNull()
            walk(child, target, out, visited, depth + 1)
        }
    }
}
