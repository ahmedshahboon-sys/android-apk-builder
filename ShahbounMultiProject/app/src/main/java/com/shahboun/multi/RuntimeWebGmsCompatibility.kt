package com.shahboun.multi

import android.content.Context
import android.os.Build
import android.webkit.WebView
import java.io.File

/** Stable WebView/GMS preparation. It never bypasses Play Integrity or platform attestation. */
internal object RuntimeWebGmsCompatibility {
    private val googlePackages = setOf("com.google.android.gms", "com.android.vending", "com.google.android.gsf")

    fun prepareProcess(context: Context, processName: String) {
        if (Build.VERSION.SDK_INT >= 28 && processName.contains(":clone")) {
            val suffix = processName.substringAfterLast(':').replace(Regex("[^A-Za-z0-9_.-]"), "_")
            runCatching { WebView.setDataDirectorySuffix("shahboun_$suffix") }
                .onSuccess { RuntimeDiagnostics.log("WEB7", "dataDirectorySuffix=shahboun_$suffix process=$processName") }
                .onFailure { error ->
                    if (error.message?.contains("already", ignoreCase = true) != true) {
                        RuntimeDiagnostics.log("WEB7", "suffix setup failed $processName: ${error.javaClass.simpleName}: ${error.message}")
                    }
                }
        }
        val provider = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        RuntimeDiagnostics.log("WEB7", "provider=${provider?.packageName ?: "missing"} version=${provider?.versionName ?: "n/a"}")
        RuntimeDiagnostics.log("GMS7", "packages=${googlePackages.joinToString { "$it:${isInstalled(context, it)}" }} integrityBypass=false")
    }

    fun prepareCloneStorage(context: Context, session: RuntimeSession, slotDir: File) {
        val dataRoot = File(slotDir, "data").apply { mkdirs() }
        val webRoot = File(dataRoot, "app_webview").apply { mkdirs() }
        val webCache = File(dataRoot, "cache/WebView").apply { mkdirs() }
        val firebaseRoot = File(dataRoot, "firebase").apply { mkdirs() }
        val serviceRoot = File(slotDir, "gms").apply { mkdirs() }
        listOf(webRoot, webCache, firebaseRoot, serviceRoot).forEach { dir ->
            check(RuntimePathPolicy.isContained(slotDir, dir)) { "Web/GMS storage escaped clone root" }
        }
        RuntimeDiagnostics.log(
            "WEB7",
            "clone ${session.runtimePackage.packageName}/${session.runtimePackage.slot} app_webview=${webRoot.absolutePath} cache=${webCache.absolutePath} firebase=${firebaseRoot.absolutePath} gms=${serviceRoot.absolutePath} hostGms=${isInstalled(context, "com.google.android.gms")}"
        )
    }

    fun shouldUsePhysicalIdentity(packageName: String): Boolean = packageName in googlePackages

    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
