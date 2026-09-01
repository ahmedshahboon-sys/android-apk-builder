package com.shahboun.multi

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Message
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Engine 2.0 pre-attach Activity virtualization.
 *
 * Android builds an Activity Context/Resources from ActivityInfo before Instrumentation.onCreate.
 * For clone launches we patch the transaction's ActivityInfo with the immutable guest APK paths and
 * theme while retaining the host package/token identity. This lets the framework construct the
 * Activity with guest-aware Resources instead of replacing caches after attach.
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
        override fun handleMessage(msg: Message): Boolean {
            runCatching { patchTransaction(msg.obj) }
                .onFailure { RuntimeDiagnostics.log("LAUNCH2", "transaction patch fallback: ${it.javaClass.simpleName}: ${it.message}") }
            return previous?.handleMessage(msg) ?: false
        }
    }

    private data class Descriptor(val packageName: String, val slot: Int, val activity: String)

    private fun patchTransaction(root: Any?) {
        root ?: return
        val descriptor = findDescriptor(root) ?: return
        val app = MultiApplication.current ?: return
        val pkg = runCatching { app.engine.runtimePackageFor(descriptor.packageName, descriptor.slot) }.getOrElse {
            RuntimeDiagnostics.log("LAUNCH2", "snapshot unavailable ${descriptor.packageName}/${descriptor.slot}: ${it.javaClass.simpleName}")
            return
        }
        val resolvedActivity = pkg.resolveActivity(descriptor.activity)
        val infos = findObjects(root, ActivityInfo::class.java)
        if (infos.isEmpty()) {
            RuntimeDiagnostics.log("LAUNCH2", "ActivityInfo not found ${pkg.packageName}/${pkg.slot}")
            return
        }

        var patched = 0
        infos.forEach { info ->
            val ai = info.applicationInfo ?: return@forEach
            // Keep host package/process/token identity. Only resource/code paths and theme are guest.
            ai.sourceDir = pkg.baseApk.absolutePath
            ai.publicSourceDir = pkg.baseApk.absolutePath
            val splitPaths = pkg.splitApks.map { it.absolutePath }.toTypedArray()
            ai.splitSourceDirs = splitPaths
            ai.splitPublicSourceDirs = splitPaths
            if (android.os.Build.VERSION.SDK_INT >= 26) ai.splitNames = pkg.splitNames.toTypedArray()
            ai.nativeLibraryDir = app.engine.runtimeSlotDir(pkg.packageName, pkg.slot).resolve("native").absolutePath
            ai.theme = pkg.appTheme

            val guestTheme = pkg.activityTheme(descriptor.activity).takeIf { it != 0 }
                ?: pkg.activityTheme(resolvedActivity).takeIf { it != 0 }
                ?: pkg.launchActivityTheme.takeIf { it != 0 }
                ?: pkg.appTheme
            if (guestTheme != 0) info.theme = guestTheme
            patched++
        }
        RuntimeDiagnostics.log(
            "LAUNCH2",
            "pre-attach ActivityInfo patched ${pkg.packageName}/${pkg.slot} requested=${descriptor.activity} resolved=$resolvedActivity infos=$patched apks=${pkg.allApks.size}"
        )
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
        if (value == null || depth > 6) return
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
