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
    private lateinit var output: TextView
    @Volatile private var lastCompactReport: String = ""
    @Volatile private var lastFullAudit: String = ""
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
            text = "التشخيص الذكي"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = CairoFontManager.typeface(this@DebugActivity, 700)
            gravity = Gravity.END
            includeFontPadding = false
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(titleView, LinearLayout.LayoutParams(-1, -2))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row1.addView(button("فحص الكل") { auditAll() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        row1.addView(button("تحديث") { showCompact() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4),0,dp(4),0) })
        row1.addView(button("نسخ المختصر") { copyCompact() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        root.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row2.addView(button("مشاركة") { shareCompact() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        row2.addView(button("تفاصيل") { toggleDetails() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(4),0,dp(4),0) })
        row2.addView(button("مسح") { RuntimeDiagnostics.clear(); auditAll() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
        root.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

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

        val horizontal = HorizontalScrollView(this).apply { isFillViewport = true; addView(output, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val vertical = ScrollView(this).apply { isFillViewport = true; addView(horizontal, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        root.addView(vertical, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        CairoFontManager.prepare(this) { runOnUiThread { CairoFontManager.applyTo(root, this) } }

        // Automatic structural preflight for every clone as soon as diagnostics opens.
        auditAll()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; setTextColor(Color.WHITE); typeface = CairoFontManager.typeface(this@DebugActivity, 500)
        setBackgroundColor(Color.rgb(255, 122, 0)); minWidth = 0; minHeight = 0; setPadding(dp(3), 0, dp(3), 0); setOnClickListener { action() }
    }

    private fun auditAll() {
        val app = application as? MultiApplication ?: return
        val clones = CloneStore(this).list()
        if (clones.isEmpty()) {
            lastCompactReport = RuntimeDiagnostics.compactSnapshot() + "\n\nلا توجد نسخ للفحص."
            lastFullAudit = RuntimeDiagnostics.snapshot()
            showCompact()
            return
        }
        output.text = "جاري فحص ${clones.size} نسخة تلقائيًا…"
        Thread {
            val reports = clones.map { clone ->
                clone to runCatching { RuntimeCompatibilityAudit.run(this@DebugActivity, app.engine, clone.packageName, clone.slot) }
            }
            val compact = buildString {
                appendLine("=== SHAHBOUN AUTO DIAG ===")
                appendLine(RuntimeDiagnostics.compactSnapshot())
                appendLine()
                appendLine("Apps: ${clones.size}")
                reports.forEach { (clone, result) ->
                    result.fold(
                        onSuccess = { report -> appendCompactClone(clone, report) },
                        onFailure = { error ->
                            appendLine("✕ ${clone.packageName} #${clone.slot + 1} • AUDIT-CRASH")
                            appendLine("  ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
                        }
                    )
                }
                appendLine()
                appendLine("LIVE: الأخطاء التي لا تظهر إلا أثناء تشغيل كود التطبيق تحتاج تشغيل النسخة مرة واحدة؛ بعدها تظهر هنا مختصرة تلقائيًا.")
            }.trimEnd()
            val full = buildString {
                appendLine("=== SHAHBOUN DEEP COMPATIBILITY AUDIT ===")
                reports.forEach { (clone, result) ->
                    appendLine(result.fold(onSuccess = { it.render() }, onFailure = { "✕ ${clone.customName}: ${it.stackTraceToString()}" }))
                }
                appendLine()
                append(RuntimeDiagnostics.snapshot())
            }
            RuntimeDiagnostics.log("AUDIT", "compact auto audit completed clones=${clones.size}")
            lastCompactReport = compact
            lastFullAudit = full
            runOnUiThread { if (!isFinishing) { fullMode = false; output.text = compact } }
        }.start()
    }

    private fun StringBuilder.appendCompactClone(clone: CloneProfile, report: CompatibilityReport) {
        val actionable = report.checks.filter {
            it.status == CompatibilityCheck.Status.FAIL ||
                (it.status == CompatibilityCheck.Status.WARN && it.name != "Runtime live test")
        }
        val failures = actionable.filter { it.status == CompatibilityCheck.Status.FAIL }
        val warnings = actionable.filter { it.status == CompatibilityCheck.Status.WARN }
        val marker = when {
            failures.isNotEmpty() -> "✕"
            warnings.isNotEmpty() -> "!"
            else -> "✓"
        }
        val state = when {
            failures.isNotEmpty() -> "FAIL"
            warnings.isNotEmpty() -> "WARN"
            else -> "PRECHECK-OK"
        }
        appendLine("$marker ${clone.packageName} #${clone.slot + 1} • $state")
        actionable.take(3).forEach { issue ->
            val code = issueCode(issue)
            appendLine("  $code: ${issue.detail.replace('\n', ' ').take(150)}")
        }
        if (actionable.size > 3) appendLine("  +${actionable.size - 3} مشاكل إضافية")
    }

    private fun issueCode(issue: CompatibilityCheck): String = when {
        issue.name.contains("Snapshot", true) -> "APK"
        issue.name.contains("DEX", true) -> "DEX"
        issue.name.contains("Resources", true) -> "RES"
        issue.name.contains("Manifest", true) -> "MANIFEST"
        issue.name.contains("Launcher", true) -> "LAUNCHER"
        issue.name.contains("Process", true) -> "PROCESS"
        issue.name.contains("صلاحيات", true) -> "PERMISSION"
        issue.name.contains("Bridge", true) -> "BRIDGE"
        issue.name.contains("تحديث", true) -> "UPDATE"
        issue.name.contains("مساحة", true) -> "STORAGE"
        else -> "CHECK"
    }

    private fun showCompact() {
        fullMode = false
        output.text = if (lastCompactReport.isBlank()) RuntimeDiagnostics.compactSnapshot() else lastCompactReport
    }

    private fun toggleDetails() {
        fullMode = !fullMode
        output.text = if (fullMode) {
            if (lastFullAudit.isBlank()) RuntimeDiagnostics.snapshot() else lastFullAudit
        } else {
            if (lastCompactReport.isBlank()) RuntimeDiagnostics.compactSnapshot() else lastCompactReport
        }
    }

    private fun copyCompact() {
        val text = if (lastCompactReport.isBlank()) RuntimeDiagnostics.compactSnapshot() else lastCompactReport
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Shahboun Auto Diag", text))
        Toast.makeText(this, "تم نسخ التشخيص المختصر", Toast.LENGTH_SHORT).show()
    }

    private fun shareCompact() {
        val text = if (lastCompactReport.isBlank()) RuntimeDiagnostics.compactSnapshot() else lastCompactReport
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Shahboun Auto Diag")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "مشاركة التشخيص المختصر"))
    }
}
