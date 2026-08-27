package com.shahboun.numberlookup

import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shahboun.numberlookup.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private val rows = mutableListOf<Row>()

    data class Row(
        val id: Int,
        val name: EditText,
        val enabled: CheckBox,
        val url: EditText,
        val phone: EditText,
        val namePath: EditText,
        val method: EditText,
        val param: EditText,
        val token: EditText
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets()
        val store = ConfigStore(this)
        store.load().forEach { original ->
            val c = if (original.id == 4 && original.bearerToken.isBlank()) {
                original.copy(
                    name = "Numbers Online",
                    baseUrl = "https://numbers.online",
                    phonePath = "/api/v1/lookup/{e164}",
                    namePath = "",
                    method = "GET",
                    queryParam = ""
                )
            } else original
            addRow(c)
        }
        b.save.setOnClickListener {
            rows.forEach { r ->
                store.save(
                    SourceConfig(
                        r.id,
                        r.name.text.toString(),
                        r.enabled.isChecked,
                        r.url.text.toString(),
                        r.phone.text.toString(),
                        r.namePath.text.toString(),
                        r.method.text.toString(),
                        r.param.text.toString(),
                        r.token.text.toString()
                    )
                )
            }
            Toast.makeText(this, "تم حفظ المصادر", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun applyInsets() {
        val l = b.root.paddingLeft
        val t = b.root.paddingTop
        val r = b.root.paddingRight
        val bot = b.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, bot + bars.bottom)
            insets
        }
    }

    private fun addRow(c: SourceConfig) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 18, 0, 18)
        }
        val title = TextView(this).apply {
            text = if (c.id == 4) "المصدر 4 — Numbers Online" else "المصدر ${c.id}"
            textSize = 19f
        }
        fun edit(h: String, v: String, secret: Boolean = false) = EditText(this).apply {
            hint = h
            setText(v)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val name = edit("اسم المصدر", c.name)
        val enabled = CheckBox(this).apply {
            text = "مفعّل"
            isChecked = if (c.id == 4 && c.bearerToken.isNotBlank()) true else c.enabled
        }
        val url = edit("Base URL (HTTPS)", c.baseUrl)
        val phone = edit("مسار البحث بالرقم", c.phonePath)
        val namePath = edit("مسار البحث بالاسم", c.namePath)
        val method = edit("GET أو POST", c.method)
        val param = edit("اسم حقل البحث", c.queryParam)
        val token = edit(if (c.id == 4) "مفتاح Numbers Online" else "Bearer Token اختياري", c.bearerToken, true)
        listOf(title, name, enabled, url, phone, namePath, method, param, token).forEach { box.addView(it) }
        b.container.addView(box, b.container.childCount - 1)
        rows += Row(c.id, name, enabled, url, phone, namePath, method, param, token)
    }
}
