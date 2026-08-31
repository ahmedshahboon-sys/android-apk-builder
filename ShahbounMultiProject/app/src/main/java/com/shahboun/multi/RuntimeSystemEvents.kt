package com.shahboun.multi

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build

/** Replays host-observable system events into manifest receivers owned by each clone snapshot. */
class RuntimeSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RuntimeSystemEvents.dispatch(context.applicationContext, intent)
    }
}

object RuntimeSystemEvents {
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun install(context: Context) {
        if (networkCallback != null) return
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
        val action = "android.net.conn.CONNECTIVITY_CHANGE"
        dispatch(context, Intent(action).putExtra("noConnectivity", !available))
    }

    fun dispatch(context: Context, original: Intent) {
        val app = context as? MultiApplication ?: MultiApplication.current ?: return
        val action = original.action ?: return
        val clones = CloneStore(context).list().filter { !it.frozen }
        var delivered = 0
        clones.forEach { clone ->
            val names = receiverNamesForAction(context, clone.packageName, original)
            if (names.isEmpty()) return@forEach
            val session = runCatching { app.engine.sessionFor(clone.packageName, clone.slot) }.getOrElse {
                RuntimeDiagnostics.log("SYSTEM", "session restore failed ${clone.packageName}/${clone.slot} action=$action: ${it.javaClass.simpleName}")
                return@forEach
            }
            val host = session.componentHost ?: return@forEach
            names.filter(session.runtimePackage::ownsReceiver).forEach { name ->
                val routed = Intent(original).setComponent(ComponentName(clone.packageName, name)).setPackage(clone.packageName)
                if (runCatching { host.dispatchExplicitReceiver(routed) }.getOrDefault(false)) delivered++
            }
        }
        RuntimeDiagnostics.log("SYSTEM", "broadcast $action delivered=$delivered clones=${clones.size}")
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
}
