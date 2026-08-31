package com.shahboun.multi

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

internal const val EXTRA_RUNTIME_RECEIVER = "shahboun.runtime.receiver"
internal const val EXTRA_RUNTIME_ORIGINAL_RECEIVER_INTENT = "shahboun.runtime.original_receiver_intent"

/** Host-declared receiver used by clone PendingIntents and alarms. */
class RuntimeStubReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_RUNTIME_PACKAGE) ?: return
        val slot = intent.getIntExtra(EXTRA_RUNTIME_SLOT, -1)
        val receiverName = intent.getStringExtra(EXTRA_RUNTIME_RECEIVER) ?: return
        if (slot < 0) return

        val app = context.applicationContext as? MultiApplication ?: MultiApplication.current ?: return
        val session = runCatching { app.engine.sessionFor(packageName, slot) }.getOrElse {
            RuntimeDiagnostics.log("RECEIVER", "restore failed $packageName/$slot: ${it.stackTraceToString()}")
            return
        }
        if (!session.runtimePackage.ownsReceiver(receiverName)) {
            RuntimeDiagnostics.log("RECEIVER", "rejected restored receiver $packageName/$slot $receiverName")
            return
        }

        val original = readOriginal(intent)
            ?: Intent().setComponent(ComponentName(packageName, receiverName))
        original.component = ComponentName(packageName, receiverName)
        RuntimeExecutionScope.withSession(session) {
            session.componentHost?.dispatchExplicitReceiver(original)
        }
    }

    @Suppress("DEPRECATION")
    private fun readOriginal(wrapper: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        wrapper.getParcelableExtra(EXTRA_RUNTIME_ORIGINAL_RECEIVER_INTENT, Intent::class.java)
    } else wrapper.getParcelableExtra(EXTRA_RUNTIME_ORIGINAL_RECEIVER_INTENT)
}
