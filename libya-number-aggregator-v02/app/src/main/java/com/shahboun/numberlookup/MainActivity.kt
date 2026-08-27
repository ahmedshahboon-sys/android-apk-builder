package com.shahboun.numberlookup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var pendingSearch = false

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (pendingSearch) {
            pendingSearch = false
            if (granted) runSearchInternal() else {
                b.sourceStatus.text = "صلاحية جهات الاتصال مرفوضة. سيستمر البحث فقط في المصادر الخارجية المفعلة."
                runSearchInternal()
            }
        }
    }

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingSearch = true
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        runSearchInternal()
    }

    private fun runSearchInternal() {
        val q = b.query.text.toString().trim()
        val byPhone = b.byPhone.isChecked
        val configs = ConfigStore(this).load().filter { it.enabled && it.baseUrl.isNotBlank() }
        val canReadContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        b.progress.visibility = View.VISIBLE
        b.search.isEnabled = false
        b.results.removeAllViews()
        val totalSources = configs.size + if (canReadContacts) 1 else 0
        b.sourceStatus.text = if (totalSources > 0) "جارٍ البحث في $totalSources مصدر..." else "لا توجد مصادر متاحة."

        lifecycleScope.launch {
            val local = if (canReadContacts) {
                withContext(Dispatchers.IO) { ContactLookup(this@MainActivity).search(q, byPhone) }
            } else emptyList()

            val outcomes = configs.map { c ->
                async(Dispatchers.IO) { client.search(c, q, byPhone) }
            }.awaitAll()

            val all = local + outcomes.flatMap { it.results }
            val merged = withContext(Dispatchers.Default) { ResultMerger.merge(all) }
            b.progress.visibility = View.GONE
            b.search.isEnabled = true

            val statusLines = mutableListOf<String>()
            if (canReadContacts) statusLines += "✓ جهات اتصال الهاتف: ${local.size} نتيجة — محلي فقط، بدون رفع"
            statusLines += outcomes.map { o ->
                val mark = if (o.ok) "✓" else "✗"
                "$mark ${o.source}: ${o.message}${if (o.results.isNotEmpty()) " — ${o.results.size} نتيجة" else ""}"
            }
            if (statusLines.isEmpty()) statusLines += "لا توجد مصادر مفعلة."
            b.sourceStatus.text = statusLines.joinToString("\n")

            val responded = outcomes.count { it.ok } + if (canReadContacts) 1 else 0
            b.summary.text = "${all.size} نتيجة خام • ${merged.size} بعد الدمج • $responded/$totalSources مصادر استجابت"

            if (merged.isEmpty()) {
                addResultText(if (canReadContacts) "لم يتم العثور على الاسم في جهات اتصال هاتفك أو المصادر المفعلة." else "لا توجد نتائج مطابقة من المصادر التي استجابت.")
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
