package com.shahboun.numberlookup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shahboun.numberlookup.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val client = LookupClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.search.setOnClickListener { runSearch() }
    }

    private fun runSearch() {
        val q = b.query.text.toString().trim()
        if (q.isBlank()) {
            b.query.error = "اكتب رقمًا أو اسمًا"
            return
        }
        val configs = ConfigStore(this).load().filter { it.enabled && it.baseUrl.isNotBlank() }
        if (configs.isEmpty()) {
            b.summary.text = "لا توجد مصادر مفعلة. افتح إعدادات المصادر وفعّل API لديك حق استخدامه."
            b.sourceStatus.text = "المصادر المرجعية المستخرجة موجودة في الإعدادات لكنها معطلة افتراضيًا."
            b.results.removeAllViews()
            return
        }
        b.progress.visibility = View.VISIBLE
        b.search.isEnabled = false
        b.results.removeAllViews()
        b.sourceStatus.text = "جارٍ الاتصال بـ ${configs.size} مصدر..."
        lifecycleScope.launch {
            val outcomes = configs.map { c ->
                async(Dispatchers.IO) { client.search(c, q, b.byPhone.isChecked) }
            }.awaitAll()
            val all = outcomes.flatMap { it.results }
            val merged = withContext(Dispatchers.Default) { ResultMerger.merge(all) }
            b.progress.visibility = View.GONE
            b.search.isEnabled = true
            b.sourceStatus.text = outcomes.joinToString("\n") { o ->
                val mark = if (o.ok) "✓" else "✗"
                "$mark ${o.source}: ${o.message}${if (o.results.isNotEmpty()) " — ${o.results.size} نتيجة" else ""}"
            }
            b.summary.text = "${all.size} نتيجة خام • ${merged.size} بعد الدمج • ${outcomes.count { it.ok }}/${configs.size} مصادر استجابت"
            if (merged.isEmpty()) {
                addResultText("لا توجد نتائج مطابقة من المصادر التي استجابت.")
            } else {
                merged.forEach { r ->
                    addResultText(buildString {
                        append("الرقم: ${r.number}\n")
                        append("الاسم: ${r.names.ifEmpty { listOf("—") }.joinToString(" / ")}\n")
                        append("المصادر: ${r.sources.joinToString("، ")}\n")
                        append("درجة الاتفاق: ${r.confidence}%")
                    })
                }
            }
        }
    }

    private fun addResultText(value: String) {
        val tv = TextView(this).apply {
            textSize = 17f
            setPadding(16, 20, 16, 20)
            text = value
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }
        b.results.addView(tv)
    }
}
