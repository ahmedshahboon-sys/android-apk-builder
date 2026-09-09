package com.shahboun.multi

import android.content.Context
import android.os.Build
import android.webkit.WebView
import java.io.File

/** Runtime 4 compatibility policy for WebView/GMS-heavy guests. */
internal object RuntimeWebGmsCompatibility {
    private val googlePackages = setOf(
        "com.google.android.gms",
        "com.android.vending",
        "com.google.android.gsf"
    )

    fun prepareProcess(context: Context, processName: String) {
        if (Build.VERSION.SDK_INT >= 28 && processName.contains(":clone")) {
            val suffix = processName.substringAfterLast(':').replace(Regex("[^A-Za-z0-9_.-]"), "_")
            runCatching { WebView.setDataDirectorySuffix("shahboun_$suffix") }
                .onFailure { error ->
                    if (error.message?.contains("already", ignoreCase = true) != true) {
                        RuntimeDiagnostics.log("WEB4", "suffix setup failed $processName: ${error.javaClass.simpleName}: ${error.message}")
                    }
                }
        }
        val provider = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        RuntimeDiagnostics.log("WEB4", "provider=${provider?.packageName ?: "missing"} version=${provider?.versionName ?: "n/a"}")
        RuntimeDiagnostics.log("GMS4", "packages=${googlePackages.joinToString { "$it:${isInstalled(context, it)}" }}")
    }

    fun prepareCloneStorage(context: Context, session: RuntimeSession, slotDir: File) {
        val webRoot = File(slotDir, "webview").apply { mkdirs() }
        val serviceRoot = File(slotDir, "gms").apply { mkdirs() }
        RuntimeDiagnostics.log(
            "WEB4",
            "clone ${session.runtimePackage.packageName}/${session.runtimePackage.slot} web=${webRoot.absolutePath} gms=${serviceRoot.absolutePath} hostGms=${isInstalled(context, "com.google.android.gms")}" 
        )
    }

    fun shouldUsePhysicalIdentity(packageName: String): Boolean = packageName in googlePackages

    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
