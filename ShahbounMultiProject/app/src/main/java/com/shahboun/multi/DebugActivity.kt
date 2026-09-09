package com.shahboun.multi

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class DebugActivity : Activity() {
    companion object {
        // Keep enough headroom for the PART header/footer while reducing dozens of tiny messages.
        private const val COPY_PART_TARGET = 30_000
    }

    private lateinit var output: TextView
    private lateinit var partsBox: LinearLayout
    private lateinit var partsTitle: TextView
    @Volatile private var lastCompactReport: String = ""
    @Volatile private var lastFullAudit: String = ""
    @Volatile private var copyParts: List<String> = emptyList()
    private var fullMode = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 11, 18)
        window.navigationBarColor = Color.rgb(13, 11, 18)
        title = "تشخيص مكرّر التطبيقات"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(13, 11, 18))
        }

        val titleView = TextView(this).apply {
            text = "المشاكل المسجلة"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = CairoFontManager.typeface(this@DebugActivity, 700)
            gravity = Gravity.END
            includeFontPadding = false
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(titleView, LinearLayout.LayoutParams(-1, -2))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row1.addView(button("فحص تلقائي") { auditAll() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        row1.addView(button("تحديث") { showCompact() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        row1.addView(button("جهّز الأجزاء") { prepareCopyParts() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        root.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row2.addView(button("مشاركة") { shareCurrent() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        row2.addView(button("السجل الكامل") { toggleDetails() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        row2.addView(button("مسح التقارير") { RuntimeDiagnostics.clear(); auditAll() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
        root.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        partsTitle = TextView(this).apply {
            text = "أجزاء التقرير: ستظهر بعد اكتمال الفحص"
            textSize = 12f
            setTextColor(Color.rgb(194, 188, 205))
            typeface = CairoFontManager.typeface(this@DebugActivity, 500)
            gravity = Gravity.END
            includeFontPadding = false
            setPadding(0, dp(3), 0, dp(5))
        }
        root.addView(partsTitle, LinearLayout.LayoutParams(-1, -2))

        partsBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val partsScroll = HorizontalScrollView(this).apply {
            isFillViewport = false
            addView(partsBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(partsScroll, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })

        output = TextView(this).apply {
            typeface = CairoFontManager.typeface(this@DebugActivity, 400)
            textSize = 12f
            setTextColor(Color.rgb(235, 231, 240))
            setTextIsSelectable(true)
            gravity = Gravity.START or Gravity.TOP
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(24, 21, 32))
            includeFontPadding = false
        }

        val horizontal = HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(output, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val vertical = ScrollView(this).apply {
            isFillViewport = true
            addView(horizontal, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(vertical, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        CairoFontManager.prepare(this) { runOnUiThread { CairoFontManager.applyTo(root, this) } }
        auditAll()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.WHITE)
        typeface = CairoFontManager.typeface(this@DebugActivity, 500)
        setBackgroundColor(Color.rgb(255, 122, 0))
        minWidth = 0
        minHeight = 0
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { action() }
    }

    private fun auditAll() {
        val app = application as? MultiApplication ?: return
        val clones = CloneStore(this).list()
        clearPartButtons()
        output.text = if (clones.isEmpty()) "لا توجد نسخ للفحص." else "جاري فحص ${clones.size} نسخة تلقائيًا…"
        Thread {
            val reports = clones.map { clone ->
                clone to runCatching { RuntimeCompatibilityAudit.run(this@DebugActivity, app.engine, clone.packageName, clone.slot) }
            }
            val structuralIssues = reports.flatMap { (clone, result) ->
                result.fold(
                    onSuccess = { report -> report.checks.filter { it.status == CompatibilityCheck.Status.FAIL || (it.status == CompatibilityCheck.Status.WARN && it.name != "Runtime live test") }.map { issue -> Triple(clone, issueCode(issue), issue.detail.replace('\n', ' ').take(360)) } },
                    onFailure = { error -> listOf(Triple(clone, "AUDIT-CRASH", "${error.javaClass.name}: ${error.message.orEmpty().take(360)}")) }
                )
            }
            val compact = buildString {
                append(RuntimeDiagnostics.compactSnapshot())
                if (structuralIssues.isNotEmpty()) {
                    appendLine(); appendLine(); appendLine("PRECHECK ISSUES: ${structuralIssues.size}")
                    structuralIssues.forEachIndexed { index, (clone, code, detail) ->
                        appendLine("${index + 1}) ${clone.packageName} #${clone.slot + 1}"); appendLine(code); appendLine(detail)
                        if (index != structuralIssues.lastIndex) appendLine()
                    }
                }
            }.trimEnd()
            val full = buildString {
                appendLine("=== SHAHBOUN DEEP COMPATIBILITY AUDIT ==="); appendLine("Session: ${RuntimeDiagnostics.currentSessionGroup()}"); appendLine()
                reports.forEach { (clone, result) ->
                    appendLine(result.fold(onSuccess = { it.render() }, onFailure = { "✕ ${clone.customName} (${clone.packageName} #${clone.slot + 1})\n${it.stackTraceToString()}" })); appendLine()
                }
                appendLine("=== LIVE RUNTIME DIAGNOSTICS ==="); append(RuntimeDiagnostics.snapshot())
            }.trimEnd()
            RuntimeDiagnostics.log("AUDIT", "segmented deep audit completed clones=${clones.size} structuralIssues=${structuralIssues.size}")
            lastCompactReport = compact; lastFullAudit = full; copyParts = buildTransferParts(full)
            runOnUiThread { if (!isFinishing) { fullMode = false; output.text = compact; renderPartButtons() } }
        }.start()
    }

    private fun issueCode(issue: CompatibilityCheck): String = when {
        issue.name.contains("Snapshot", true) -> "APK"; issue.name.contains("DEX", true) -> "DEX"; issue.name.contains("Resources", true) -> "RES"
        issue.name.contains("Manifest", true) -> "MANIFEST"; issue.name.contains("Launcher", true) -> "LAUNCHER"; issue.name.contains("Process", true) -> "PROCESS"
        issue.name.contains("صلاحيات", true) -> "PERMISSION"; issue.name.contains("Bridge", true) -> "BRIDGE"; issue.name.contains("تحديث", true) -> "UPDATE"
        issue.name.contains("مساحة", true) -> "STORAGE"; else -> "CHECK"
    }

    private fun showCompact() { fullMode = false; lastCompactReport = RuntimeDiagnostics.compactSnapshot(); output.text = lastCompactReport }
    private fun toggleDetails() { fullMode = !fullMode; output.text = if (fullMode) (lastFullAudit.ifBlank { RuntimeDiagnostics.snapshot() }) else (lastCompactReport.ifBlank { RuntimeDiagnostics.compactSnapshot() }) }
    private fun prepareCopyParts() { val source = lastFullAudit.ifBlank { RuntimeDiagnostics.snapshot() }; copyParts = buildTransferParts(source); renderPartButtons(); Toast.makeText(this, "تم تجهيز ${copyParts.size} جزء للنسخ • حتى 30,000 حرف للجزء", Toast.LENGTH_SHORT).show() }

    private fun buildTransferParts(report: String): List<String> {
        if (report.isBlank()) return listOf("لا توجد بيانات تشخيص بعد.")
        val rawParts = splitOnLineBoundaries(report, COPY_PART_TARGET)
        return rawParts.mapIndexed { index, body ->
            val part = index + 1; val total = rawParts.size
            buildString {
                appendLine("=== SHAHBOUN REPORT • PART $part/$total ==="); appendLine("Session: ${RuntimeDiagnostics.currentSessionGroup()}"); appendLine(); append(body.trimEnd()); appendLine(); appendLine()
                if (part < total) append("ممنوع بدء التعديل أو الإصلاح الآن. هذا الجزء $part من $total فقط. رد فقط: تمام، أنا في الانتظار لباقي التقارير.")
                else append("هذا هو الجزء الأخير ($part/$total). ما هي المشاكل التي استنتجتها من جميع الأجزاء؟ حلّل فقط ولا تبدأ الإصلاح حتى أقول لك: ابدأ في الإصلاح.")
            }
        }
    }

    private fun splitOnLineBoundaries(text: String, target: Int): List<String> {
        val parts = mutableListOf<String>(); val current = StringBuilder()
        text.lineSequence().forEach { line ->
            val addition = line.length + 1
            if (current.isNotEmpty() && current.length + addition > target) { parts += current.toString().trimEnd(); current.setLength(0) }
            if (addition > target) {
                var remaining = line
                while (remaining.length > target) {
                    val cut = safeSentenceCut(remaining, target)
                    if (current.isNotEmpty()) { parts += current.toString().trimEnd(); current.setLength(0) }
                    parts += remaining.substring(0, cut).trimEnd(); remaining = remaining.substring(cut).trimStart()
                }
                if (remaining.isNotEmpty()) current.appendLine(remaining)
            } else current.appendLine(line)
        }
        if (current.isNotEmpty()) parts += current.toString().trimEnd()
        return parts.ifEmpty { listOf(text) }
    }

    private fun safeSentenceCut(value: String, limit: Int): Int {
        val floor = (limit * 0.72f).toInt()
        for (i in minOf(limit, value.length - 1) downTo floor) if (value[i] == ' ' || value[i] == '.' || value[i] == ':' || value[i] == ';' || value[i] == '،') return i + 1
        return minOf(limit, value.length)
    }

    private fun clearPartButtons() { copyParts = emptyList(); if (::partsBox.isInitialized) partsBox.removeAllViews(); if (::partsTitle.isInitialized) partsTitle.text = "أجزاء التقرير: جاري التجهيز…" }
    private fun renderPartButtons() {
        partsBox.removeAllViews()
        if (copyParts.isEmpty()) { partsTitle.text = "أجزاء التقرير: لا توجد أجزاء جاهزة"; return }
        partsTitle.text = "أجزاء التقرير: ${copyParts.size} • حتى 30,000 حرف • انسخهم بالترتيب"
        copyParts.forEachIndexed { index, text -> partsBox.addView(button("نسخ ${index + 1}/${copyParts.size}") { copyPart(index, text) }, LinearLayout.LayoutParams(dp(112), dp(42)).apply { setMargins(dp(4), 0, dp(4), 0) }) }
    }
    private fun copyPart(index: Int, text: String) { val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clipboard.setPrimaryClip(ClipData.newPlainText("Shahboun Report ${index + 1}/${copyParts.size}", text)); Toast.makeText(this, "تم نسخ الجزء ${index + 1} من ${copyParts.size}", Toast.LENGTH_SHORT).show() }
    private fun shareCurrent() { val text = lastFullAudit.ifBlank { RuntimeDiagnostics.snapshot() }; startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Shahboun Deep Diagnostics"); putExtra(Intent.EXTRA_TEXT, text) }, "مشاركة تقرير التشخيص")) }
}
