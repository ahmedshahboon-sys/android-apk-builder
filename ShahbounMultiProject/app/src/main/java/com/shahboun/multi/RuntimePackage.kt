package com.shahboun.multi

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

data class RuntimeProviderInfo(
    val name: String,
    val authority: String?,
    val exported: Boolean,
    val grantUriPermissions: Boolean
)

data class RuntimeComponentInfo(
    val name: String,
    val exported: Boolean,
    val theme: Int = 0
)

data class RuntimePackage(
    val packageName: String,
    val slot: Int,
    val baseApk: File,
    val splitApks: List<File>,
    val splitNames: List<String>,
    val launchActivity: String,
    val applicationClass: String?,
    val versionCode: Long,
    val sha256: String,
    val splitSha256: List<String>,
    val appTheme: Int,
    val launchActivityTheme: Int,
    val targetSdk: Int,
    val minSdk: Int,
    val appFlags: Int,
    val providers: List<RuntimeProviderInfo>,
    val activities: List<RuntimeComponentInfo>,
    val services: List<RuntimeComponentInfo>,
    val receivers: List<RuntimeComponentInfo>
) {
    val dexPath: String get() = (listOf(baseApk) + splitApks).joinToString(File.pathSeparator) { it.absolutePath }
    fun ownsActivity(name: String): Boolean = name == launchActivity || activities.any { it.name == name }
    fun ownsService(name: String): Boolean = services.any { it.name == name }
    fun ownsReceiver(name: String): Boolean = receivers.any { it.name == name }
    fun activityTheme(name: String): Int = activities.firstOrNull { it.name == name }?.theme ?: 0
}

class RuntimePackageInstaller(private val context: Context) {
    fun snapshot(packageName: String, slot: Int, slotDir: File): RuntimePackage {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        val launch = pm.getLaunchIntentForPackage(packageName) ?: error("التطبيق لا يملك شاشة تشغيل رئيسية")
        val launchActivity = launch.component?.className ?: error("تعذر تحديد شاشة تشغيل التطبيق")
        val applicationClass = appInfo.className?.takeIf { it.isNotBlank() }
        val activityInfo = runCatching { pm.getActivityInfo(launch.component!!, 0) }.getOrNull()
        val launchActivityTheme = activityInfo?.theme ?: 0

        val apkDir = File(slotDir, "apk").apply {
            if (exists()) deleteRecursively()
            require(mkdirs()) { "تعذر إنشاء مجلد APK للنسخة" }
        }
        val base = File(apkDir, "base.apk")
        copyVerified(File(appInfo.sourceDir), base)

        val originalSplitPaths = appInfo.splitSourceDirs.orEmpty()
        val originalSplitNames = appInfo.splitNames.orEmpty()
        val normalizedSplitNames = originalSplitPaths.indices.map { index ->
            originalSplitNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "split_$index"
        }
        val splits = originalSplitPaths.mapIndexed { index, source ->
            File(apkDir, "split-$index.apk").also { copyVerified(File(source), it) }
        }

        val packageFlags = PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
        val pkg = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(packageFlags.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(packageName, packageFlags)
        }
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else {
            @Suppress("DEPRECATION") pkg.versionCode.toLong()
        }
        val providers = pkg.providers.orEmpty().mapNotNull { info ->
            val name = info.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RuntimeProviderInfo(name, info.authority, info.exported, info.grantUriPermissions)
        }
        val activities = pkg.activities.orEmpty().mapNotNull { info ->
            val name = info.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RuntimeComponentInfo(name, info.exported, info.theme)
        }
        val services = pkg.services.orEmpty().mapNotNull { info ->
            val name = info.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RuntimeComponentInfo(name, info.exported)
        }
        val receivers = pkg.receivers.orEmpty().mapNotNull { info ->
            val name = info.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RuntimeComponentInfo(name, info.exported)
        }

        val digest = sha256(base)
        val splitDigests = splits.map(::sha256)
        File(slotDir, "runtime.meta").writeText(buildString {
            appendLine("format=9")
            appendLine("package=$packageName")
            appendLine("slot=$slot")
            appendLine("launchActivity=$launchActivity")
            appendLine("applicationClass=${applicationClass.orEmpty()}")
            appendLine("versionCode=$versionCode")
            appendLine("sha256=$digest")
            appendLine("splitCount=${splits.size}")
            splitDigests.forEachIndexed { index, hash -> appendLine("splitSha256.$index=$hash") }
            normalizedSplitNames.forEachIndexed { index, name -> appendLine("splitName.$index=$name") }
            appendLine("appTheme=${appInfo.theme}")
            appendLine("launchActivityTheme=$launchActivityTheme")
            appendLine("targetSdk=${appInfo.targetSdkVersion}")
            appendLine("minSdk=${if (android.os.Build.VERSION.SDK_INT >= 24) appInfo.minSdkVersion else 1}")
            appendLine("appFlags=${appInfo.flags}")
            writeProviders(providers)
            writeComponents("activity", activities)
            writeComponents("service", services)
            writeComponents("receiver", receivers)
        })

        return RuntimePackage(
            packageName, slot, base, splits, normalizedSplitNames, launchActivity, applicationClass,
            versionCode, digest, splitDigests, appInfo.theme, launchActivityTheme,
            appInfo.targetSdkVersion, if (android.os.Build.VERSION.SDK_INT >= 24) appInfo.minSdkVersion else 1,
            appInfo.flags, providers, activities, services, receivers
        )
    }

    fun read(packageName: String, slot: Int, slotDir: File): RuntimePackage {
        val meta = File(slotDir, "runtime.meta")
        require(meta.isFile) { "بيانات تشغيل النسخة غير موجودة" }
        val values = meta.readLines().mapNotNull { line ->
            val p = line.indexOf('=')
            if (p <= 0) null else line.substring(0, p) to line.substring(p + 1)
        }.toMap()
        require(values["package"] == packageName && values["slot"] == slot.toString()) { "بيانات النسخة لا تطابق الطلب" }
        val apkDir = File(slotDir, "apk")
        val base = File(apkDir, "base.apk")
        require(base.isFile) { "APK النسخة مفقود" }
        val splits = apkDir.listFiles().orEmpty().filter { it.name.startsWith("split-") && it.extension == "apk" }.sortedBy { it.name.substringAfter("split-").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
        val expected = values["sha256"] ?: error("بصمة APK مفقودة")
        require(sha256(base) == expected) { "فشل تحقق سلامة APK الخاص بالنسخة" }
        val expectedSplitCount = values["splitCount"]?.toIntOrNull() ?: 0
        require(splits.size == expectedSplitCount) { "عدد ملفات Split APK لا يطابق بيانات النسخة" }
        val splitDigests = splits.mapIndexed { index, split ->
            val expectedSplit = values["splitSha256.$index"]
            val actual = sha256(split)
            if (expectedSplit != null) require(actual == expectedSplit) { "فشل تحقق سلامة Split APK رقم ${index + 1}" }
            actual
        }
        val splitNames = (0 until expectedSplitCount).map { index -> values["splitName.$index"] ?: "split_$index" }
        val providers = readProviders(values)
        val activities = readComponents(values, "activity")
        val services = readComponents(values, "service")
        val receivers = readComponents(values, "receiver")
        return RuntimePackage(
            packageName = packageName,
            slot = slot,
            baseApk = base,
            splitApks = splits,
            splitNames = splitNames,
            launchActivity = values["launchActivity"] ?: error("شاشة التشغيل غير مسجلة"),
            applicationClass = values["applicationClass"]?.takeIf { it.isNotBlank() },
            versionCode = values["versionCode"]?.toLongOrNull() ?: 0L,
            sha256 = expected,
            splitSha256 = splitDigests,
            appTheme = values["appTheme"]?.toIntOrNull() ?: 0,
            launchActivityTheme = values["launchActivityTheme"]?.toIntOrNull() ?: 0,
            targetSdk = values["targetSdk"]?.toIntOrNull() ?: android.os.Build.VERSION.SDK_INT,
            minSdk = values["minSdk"]?.toIntOrNull() ?: 1,
            appFlags = values["appFlags"]?.toIntOrNull() ?: 0,
            providers = providers,
            activities = activities,
            services = services,
            receivers = receivers
        )
    }

    private fun StringBuilder.writeProviders(items: List<RuntimeProviderInfo>) {
        appendLine("providerCount=${items.size}")
        items.forEachIndexed { index, provider ->
            appendLine("provider.$index.name=${provider.name}")
            appendLine("provider.$index.authority=${provider.authority.orEmpty()}")
            appendLine("provider.$index.exported=${provider.exported}")
            appendLine("provider.$index.grantUriPermissions=${provider.grantUriPermissions}")
        }
    }

    private fun StringBuilder.writeComponents(prefix: String, items: List<RuntimeComponentInfo>) {
        appendLine("${prefix}Count=${items.size}")
        items.forEachIndexed { index, component ->
            appendLine("$prefix.$index.name=${component.name}")
            appendLine("$prefix.$index.exported=${component.exported}")
            if (prefix == "activity") appendLine("$prefix.$index.theme=${component.theme}")
        }
    }

    private fun readProviders(values: Map<String, String>): List<RuntimeProviderInfo> {
        val count = values["providerCount"]?.toIntOrNull() ?: 0
        return (0 until count).mapNotNull { index ->
            val name = values["provider.$index.name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RuntimeProviderInfo(
                name = name,
                authority = values["provider.$index.authority"]?.takeIf { it.isNotBlank() },
                exported = values["provider.$index.exported"].toBoolean(),
                grantUriPermissions = values["provider.$index.grantUriPermissions"].toBoolean()
            )
        }
    }

    private fun readComponents(values: Map<String, String>, prefix: String): List<RuntimeComponentInfo> {
        val count = values["${prefix}Count"]?.toIntOrNull() ?: 0
        return (0 until count).mapNotNull { index ->
            val name = values["$prefix.$index.name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RuntimeComponentInfo(
                name = name,
                exported = values["$prefix.$index.exported"].toBoolean(),
                theme = if (prefix == "activity") values["$prefix.$index.theme"]?.toIntOrNull() ?: 0 else 0
            )
        }
    }

    private fun copyVerified(source: File, target: File) {
        require(source.isFile) { "ملف APK المصدر غير موجود" }
        FileInputStream(source).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
        require(target.length() == source.length() && target.length() > 0) { "فشل نسخ APK" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
