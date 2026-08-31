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
            text = "التشخيص"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = CairoFontManager.typeface(this@DebugActivity, 700)
            gravity = Gravity.END
            includeFontPadding = false
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(titleView, LinearLayout.LayoutParams(-1, -2))

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        buttonRow.addView(button("تحديث") { refresh() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        buttonRow.addView(button("اختبار") { auditAll() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4),0,dp(4),0) })
        buttonRow.addView(button("نسخ") { copy() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        buttonRow.addView(button("مشاركة") { share() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        buttonRow.addView(button("مسح") { RuntimeDiagnostics.clear(); refresh() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        root.addView(buttonRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

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
        refresh()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; setTextColor(Color.WHITE); typeface = CairoFontManager.typeface(this@DebugActivity, 500)
        setBackgroundColor(Color.rgb(255, 122, 0)); minWidth = 0; minHeight = 0; setPadding(dp(4), 0, dp(4), 0); setOnClickListener { action() }
    }

    private fun auditAll() {
        val app = application as? MultiApplication ?: return
        val clones = CloneStore(this).list()
        if (clones.isEmpty()) { Toast.makeText(this,"لا توجد نسخ للاختبار",Toast.LENGTH_SHORT).show(); return }
        val report = buildString {
            appendLine("=== SHAHBOUN DEEP COMPATIBILITY AUDIT ===")
            clones.forEach { clone ->
                val result = runCatching { RuntimeCompatibilityAudit.run(this@DebugActivity, app.engine, clone.packageName, clone.slot) }
                appendLine(result.fold(onSuccess={it.render()},onFailure={"✕ ${clone.customName}: ${it.stackTraceToString()}"}))
            }
        }
        RuntimeDiagnostics.log("AUDIT","completed clones=${clones.size}")
        output.text = report + "\n\n" + RuntimeDiagnostics.snapshot()
    }

    private fun refresh() { output.text = RuntimeDiagnostics.snapshot() }
    private fun copy() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("مكرّر التطبيقات Debug", output.text?.toString().orEmpty()))
        Toast.makeText(this, "تم نسخ سجل التشخيص", Toast.LENGTH_SHORT).show()
    }
    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "مكرّر التطبيقات Debug"); putExtra(Intent.EXTRA_TEXT, output.text?.toString().orEmpty()) }
        startActivity(Intent.createChooser(intent, "مشاركة سجل التشخيص"))
    }
}
