package com.shahboun.multi

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

data class RuntimePackage(
    val packageName: String,
    val slot: Int,
    val baseApk: File,
    val splitApks: List<File>,
    val launchActivity: String,
    val applicationClass: String?,
    val versionCode: Long,
    val sha256: String,
    val splitSha256: List<String>
) {
    val dexPath: String get() = (listOf(baseApk) + splitApks).joinToString(File.pathSeparator) { it.absolutePath }
}

/** Copies the installed package into Shahboun-owned private storage before execution. */
class RuntimePackageInstaller(private val context: Context) {
    fun snapshot(packageName: String, slot: Int, slotDir: File): RuntimePackage {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        val launch = pm.getLaunchIntentForPackage(packageName)
            ?: error("التطبيق لا يملك شاشة تشغيل رئيسية")
        val launchActivity = launch.component?.className
            ?: error("تعذر تحديد شاشة تشغيل التطبيق")
        val applicationClass = appInfo.className?.takeIf { it.isNotBlank() }

        val apkDir = File(slotDir, "apk").apply {
            if (exists()) deleteRecursively()
            require(mkdirs()) { "تعذر إنشاء مجلد APK للنسخة" }
        }

        val base = File(apkDir, "base.apk")
        copyVerified(File(appInfo.sourceDir), base)

        val splits = appInfo.splitSourceDirs.orEmpty().mapIndexed { index, source ->
            File(apkDir, "split-$index.apk").also { copyVerified(File(source), it) }
        }

        val pkg = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(packageName, 0)
        }
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else {
            @Suppress("DEPRECATION") pkg.versionCode.toLong()
        }

        val digest = sha256(base)
        val splitDigests = splits.map(::sha256)
        File(slotDir, "runtime.meta").writeText(
            buildString {
                appendLine("format=4")
                appendLine("package=$packageName")
                appendLine("slot=$slot")
                appendLine("launchActivity=$launchActivity")
                appendLine("applicationClass=${applicationClass.orEmpty()}")
                appendLine("versionCode=$versionCode")
                appendLine("sha256=$digest")
                appendLine("splitCount=${splits.size}")
                splitDigests.forEachIndexed { index, hash -> appendLine("splitSha256.$index=$hash") }
            }
        )

        return RuntimePackage(packageName, slot, base, splits, launchActivity, applicationClass, versionCode, digest, splitDigests)
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
        val splits = apkDir.listFiles().orEmpty().filter { it.name.startsWith("split-") && it.extension == "apk" }.sortedBy { it.name }
        val expected = values["sha256"] ?: error("بصمة APK مفقودة")
        require(sha256(base) == expected) { "فشل تحقق سلامة APK الخاص بالنسخة" }

        val expectedSplitCount = values["splitCount"]?.toIntOrNull() ?: 0
        require(splits.size == expectedSplitCount) { "عدد ملفات Split APK لا يطابق بيانات النسخة" }
        val splitDigests = splits.mapIndexed { index, split ->
            val expectedSplit = values["splitSha256.$index"]
            if (expectedSplit == null) {
                // Compatibility with clones created by format=3. Calculate now, but require all
                // newly-created clones to persist and verify split hashes through format=4.
                sha256(split)
            } else {
                val actual = sha256(split)
                require(actual == expectedSplit) { "فشل تحقق سلامة Split APK رقم ${index + 1}" }
                actual
            }
        }

        return RuntimePackage(
            packageName = packageName,
            slot = slot,
            baseApk = base,
            splitApks = splits,
            launchActivity = values["launchActivity"] ?: error("شاشة التشغيل غير مسجلة"),
            applicationClass = values["applicationClass"]?.takeIf { it.isNotBlank() },
            versionCode = values["versionCode"]?.toLongOrNull() ?: 0L,
            sha256 = expected,
            splitSha256 = splitDigests
        )
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
