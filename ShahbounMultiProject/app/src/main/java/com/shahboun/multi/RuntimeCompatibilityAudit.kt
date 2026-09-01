package com.shahboun.multi

import android.app.Application
import android.content.Context
import android.os.Build
import java.util.zip.ZipFile

data class CompatibilityCheck(val name: String, val status: Status, val detail: String) {
    enum class Status { PASS, WARN, FAIL }
}

data class CompatibilityReport(val packageName: String, val slot: Int, val checks: List<CompatibilityCheck>) {
    val failed get() = checks.count { it.status == CompatibilityCheck.Status.FAIL }
    val warnings get() = checks.count { it.status == CompatibilityCheck.Status.WARN }
    val passed get() = checks.count { it.status == CompatibilityCheck.Status.PASS }
    fun render(): String = buildString {
        appendLine("اختبار التوافق: $packageName / نسخة ${slot + 1}")
        appendLine("نجاح: $passed   تحذير: $warnings   فشل: $failed")
        appendLine()
        checks.forEach { c ->
            val marker = when (c.status) {
                CompatibilityCheck.Status.PASS -> "✓"
                CompatibilityCheck.Status.WARN -> "!"
                CompatibilityCheck.Status.FAIL -> "✕"
            }
            appendLine("$marker ${c.name}: ${c.detail}")
        }
    }
}

/** Engine 2.0 audit: host process never loads guest code. Runtime checks are reported separately. */
object RuntimeCompatibilityAudit {
    fun run(context: Context, engine: ShahbounCloneEngine, packageName: String, slot: Int): CompatibilityReport {
        val out = mutableListOf<CompatibilityCheck>()
        fun check(name: String, block: () -> String) {
            runCatching { block() }
                .onSuccess { out += CompatibilityCheck(name, CompatibilityCheck.Status.PASS, it) }
                .onFailure { out += CompatibilityCheck(name, CompatibilityCheck.Status.FAIL, it.message ?: it.javaClass.simpleName) }
        }

        val pkg = runCatching { engine.runtimePackageFor(packageName, slot) }.getOrElse {
            out += CompatibilityCheck("Snapshot", CompatibilityCheck.Status.FAIL, it.message ?: it.javaClass.simpleName)
            return finish(packageName, slot, out)
        }

        check("Snapshot وسلامة APK") {
            require(pkg.baseApk.isFile && pkg.baseApk.length() > 0) { "base.apk مفقود" }
            require(pkg.splitApks.all { it.isFile && it.length() > 0 }) { "split APK مفقود" }
            "base + ${pkg.splitApks.size} split • format=${pkg.snapshotFormat}"
        }

        check("DEX archives") {
            val codeApks = pkg.allApks.filter(::containsDex)
            require(codeApks.isNotEmpty()) { "لا يوجد classes.dex" }
            "${codeApks.size}/${pkg.allApks.size} APK تحتوي DEX"
        }

        check("Resources archives") {
            pkg.allApks.forEach { apk -> ZipFile(apk).use { zip -> require(zip.getEntry("resources.arsc") != null || apk != pkg.baseApk) { "resources.arsc مفقود من base" } } }
            "${pkg.allApks.size} APK resource paths جاهزة للـpre-attach"
        }

        check("Manifest components") {
            val count = pkg.activities.size + pkg.services.size + pkg.receivers.size + pkg.providers.size
            require(count > 0) { "لا توجد مكونات محفوظة" }
            "Activities ${pkg.activities.size} • Services ${pkg.services.size} • Receivers ${pkg.receivers.size} • Providers ${pkg.providers.size}"
        }

        check("Launcher resolution") {
            require(pkg.launchActivity.isNotBlank()) { "Launcher مفقود" }
            "requested=${pkg.launchAlias ?: pkg.launchActivity} target=${pkg.launchActivity}"
        }

        check("Process allocation") {
            val index = RuntimeProcessAllocator.migrateIfNeeded(context, packageName, slot, RuntimeProcessPool.size)
            val mappings = RuntimeProcessAllocator.snapshot(context)
            val duplicate = mappings.entries.firstOrNull { it.key != "$packageName#$slot" && it.value == index }
            require(duplicate == null) { "process collision مع ${duplicate?.key}" }
            ":clone$index • allocation ثابت وفريد"
        }

        check("صلاحيات المضيف") {
            val requested = RuntimePermissionBroker.requestedByGuest(context, packageName)
            val missing = RuntimePermissionBroker.missingForGuest(context, packageName)
            require(missing.isEmpty()) { "ناقص ${missing.size}: ${missing.take(5).joinToString()}" }
            "${requested.size} صلاحية حساسة مغطاة"
        }

        val bridgeStates = RuntimeBridgeRegistry.snapshot()
        if (bridgeStates.isEmpty()) {
            out += CompatibilityCheck("System bridges", CompatibilityCheck.Status.WARN, "لم تُسجل capability matrix بعد")
        } else {
            bridgeStates.forEach { state ->
                out += CompatibilityCheck(
                    "Bridge/${state.name}",
                    if (state.ready) CompatibilityCheck.Status.PASS else CompatibilityCheck.Status.WARN,
                    if (state.ready) "جاهز" else (state.detail ?: "fallback")
                )
            }
        }

        val p = RuntimeCompatibility.profile
        out += CompatibilityCheck(
            "Android/OEM profile",
            CompatibilityCheck.Status.PASS,
            "SDK ${p.sdk} • ${p.manufacturer} ${p.model} • loader=${p.resourcesLoaderPreferred}"
        )

        val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else context.packageName
        out += CompatibilityCheck(
            "Runtime live test",
            CompatibilityCheck.Status.WARN,
            if (processName.contains(":clone")) "نفّذ الاختبار الحي من process النسخة" else "Host audit فقط؛ التشغيل الحي يُثبت من سجل :cloneN"
        )

        val updated = engine.needsUpdate(packageName, slot)
        out += CompatibilityCheck(
            "تحديث Snapshot",
            if (updated) CompatibilityCheck.Status.WARN else CompatibilityCheck.Status.PASS,
            if (updated) "التطبيق الأصلي أحدث؛ حدّث النسخة" else "محدث"
        )

        check("مساحة النسخة") {
            val bytes = engine.cloneSizeBytes(packageName, slot)
            require(bytes > 0) { "حجم النسخة صفر" }
            "$bytes bytes معزولة"
        }

        return finish(packageName, slot, out)
    }

    private fun containsDex(file: java.io.File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { !it.isDirectory && it.name.matches(Regex("classes(\\d*)?\\.dex")) }
        }
    }.getOrDefault(false)

    private fun finish(packageName: String, slot: Int, checks: List<CompatibilityCheck>): CompatibilityReport {
        val report = CompatibilityReport(packageName, slot, checks)
        RuntimeDiagnostics.log("AUDIT", report.render().replace("\n", " | "))
        return report
    }
}
