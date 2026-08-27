package com.shahboun.numberlookup

import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        val store = ConfigStore(this)
        store.load().forEach { addRow(it) }
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

    private fun addRow(c: SourceConfig) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 20)
        }
        val title = TextView(this).apply {
            text = "المصدر ${c.id}"
            textSize = 20f
        }
        fun edit(h: String, v: String, secret: Boolean = false) = EditText(this).apply {
            hint = h
            setText(v)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val name = edit("اسم المصدر", c.name)
        val enabled = CheckBox(this).apply { text = "مفعّل"; isChecked = c.enabled }
        val url = edit("Base URL (HTTPS)", c.baseUrl)
        val phone = edit("مسار البحث بالرقم", c.phonePath)
        val namePath = edit("مسار البحث بالاسم", c.namePath)
        val method = edit("GET أو POST", c.method)
        val param = edit("اسم حقل البحث", c.queryParam)
        val token = edit("Bearer Token اختياري", c.bearerToken, true)
        listOf(title, name, enabled, url, phone, namePath, method, param, token).forEach { box.addView(it) }
        b.container.addView(box, b.container.childCount - 1)
        rows += Row(c.id, name, enabled, url, phone, namePath, method, param, token)
    }
}
