package com.shahboun.multi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DebugActivity : AppCompatActivity() {
    private val arabic by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "تشخيص Shahboun Multi"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("تحديث") { refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(button("نسخ") { copy() }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(button("مشاركة") { share() }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(button("مسح") { RuntimeDiagnostics.clear(); refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        output = TextView(this).apply {
            typeface = arabic
            textSize = 13f
            setTextIsSelectable(true)
            gravity = Gravity.START
            setPadding(8, 16, 8, 16)
        }
        val scroll = ScrollView(this).apply { addView(output) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        typeface = arabic
        setOnClickListener { action() }
    }

    private fun refresh() { output.text = RuntimeDiagnostics.snapshot() }

    private fun copy() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Shahboun Multi Debug", RuntimeDiagnostics.snapshot()))
        Toast.makeText(this, "تم نسخ سجل التشخيص", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Shahboun Multi Debug")
            putExtra(Intent.EXTRA_TEXT, RuntimeDiagnostics.snapshot())
        }
        startActivity(Intent.createChooser(intent, "مشاركة سجل التشخيص"))
    }
}
