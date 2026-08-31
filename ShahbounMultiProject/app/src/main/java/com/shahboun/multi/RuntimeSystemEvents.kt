package com.shahboun.multi

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build

/** Replays host-observable system events into the process assigned to each clone. */
class RuntimeSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RuntimeSystemEvents.dispatch(context.applicationContext, intent)
    }
}

object RuntimeSystemEvents {
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun install(context: Context) {
        if (networkCallback != null) return
        if (currentProcessName() != BuildConfig.APPLICATION_ID) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = dispatchNetwork(context, true)
            override fun onLost(network: Network) = dispatchNetwork(context, false)
        }
        runCatching { cm.registerDefaultNetworkCallback(callback); networkCallback = callback }
            .onSuccess { RuntimeDiagnostics.log("SYSTEM", "network callback installed") }
            .onFailure { RuntimeDiagnostics.log("SYSTEM", "network callback unavailable: ${it.javaClass.simpleName}") }
    }

    private fun dispatchNetwork(context: Context, available: Boolean) {
        dispatch(context, Intent("android.net.conn.CONNECTIVITY_CHANGE").putExtra("noConnectivity", !available))
    }

    fun dispatch(context: Context, original: Intent) {
        if (currentProcessName() != BuildConfig.APPLICATION_ID) return
        val action = original.action ?: return
        val clones = CloneStore(context).list().filter { !it.frozen }
        var routed = 0
        clones.forEach { clone ->
            val names = receiverNamesForAction(context, clone.packageName, original)
            if (names.isEmpty()) return@forEach
            val stub = RuntimeProcessPool.receiverStub(clone.packageName, clone.slot)
            names.forEach { receiverName ->
                val wrapper = Intent(original).apply {
                    component = ComponentName(BuildConfig.APPLICATION_ID, stub.name)
                    `package` = BuildConfig.APPLICATION_ID
                    putExtra(EXTRA_RUNTIME_PACKAGE, clone.packageName)
                    putExtra(EXTRA_RUNTIME_SLOT, clone.slot)
                    putExtra(EXTRA_RUNTIME_RECEIVER, receiverName)
                    putExtra(EXTRA_RUNTIME_ORIGINAL_RECEIVER_INTENT, Intent(original).apply {
                        component = ComponentName(clone.packageName, receiverName)
                        `package` = clone.packageName
                    })
                }
                if (runCatching { context.sendBroadcast(wrapper); true }.getOrDefault(false)) routed++
            }
        }
        RuntimeDiagnostics.log("SYSTEM", "broadcast $action routed=$routed clones=${clones.size} host=${currentProcessName()}")
    }

    private fun receiverNamesForAction(context: Context, packageName: String, original: Intent): Set<String> = runCatching {
        val probe = Intent(original).setPackage(packageName).setComponent(null)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryBroadcastReceivers(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
        } else {
            @Suppress("DEPRECATION") context.packageManager.queryBroadcastReceivers(probe, PackageManager.MATCH_DISABLED_COMPONENTS)
        }
        resolved.mapNotNull { it.activityInfo?.takeIf { info -> info.packageName == packageName }?.name }.toSet()
    }.getOrDefault(emptySet())

    private fun currentProcessName(): String = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else BuildConfig.APPLICATION_ID
}
