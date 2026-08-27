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
import com.google.android.material.card.MaterialCardView
import com.shahboun.numberlookup.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var repository: LookupRepository
    private var searchJob: Job? = null
    private var pendingSearch = false

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingSearch) {
            pendingSearch = false
            runSearchInternal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        repository = LookupRepository(this)
        b.settings.setOnClickListener { startActivity(Intent(this, SourcesActivity::class.java)) }
        b.search.setOnClickListener { runSearch() }
        b.forceRefresh.setOnClickListener {
            val q = b.query.text.toString().trim()
            if (b.byPhone.isChecked && q.isNotBlank()) {
                repository.forceRefresh(q)
                runSearchInternal(forceRefresh = true)
            }
        }
        b.searchType.setOnCheckedChangeListener { _, id ->
            b.query.hint = if (id == b.byPhone.id) "أدخل رقم الهاتف" else "أدخل الاسم"
            b.query.inputType = if (id == b.byPhone.id) android.text.InputType.TYPE_CLASS_PHONE else android.text.InputType.TYPE_CLASS_TEXT
            b.forceRefresh.visibility = if (id == b.byPhone.id) View.VISIBLE else View.GONE
        }
    }

    override fun onStop() {
        super.onStop()
        searchJob?.cancel()
    }

    private fun runSearch() {
        val q = b.query.text.toString().trim()
        if (q.isBlank()) {
            b.query.error = if (b.byPhone.isChecked) "أدخل رقم الهاتف" else "أدخل الاسم"
            return
        }
        if (b.byPhone.isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingSearch = true
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        runSearchInternal()
    }

    private fun runSearchInternal(forceRefresh: Boolean = false) {
        val q = b.query.text.toString().trim()
        val byPhone = b.byPhone.isChecked
        searchJob?.cancel()
        setLoading(true)
        b.results.removeAllViews()
        b.summary.text = ""
        b.sourceStatus.text = "جاري البحث في مصادر البيانات…"

        searchJob = lifecycleScope.launch {
            try {
                val envelope = if (byPhone) repository.lookupByPhone(q, forceRefresh) else repository.searchByName(q)
                setLoading(false)
                if (byPhone && envelope.normalizedPhone?.isValidFormat == false) {
                    b.sourceStatus.text = "الرقم غير صالح. استخدم رقمًا ليبيًا مثل 091xxxxxxx أو +21891xxxxxxx."
                    addStateCard("رقم غير صالح", "راجع الرقم وحاول مرة أخرى.")
                    return@launch
                }
                val status = envelope.providerHealth.joinToString("\n") { h ->
                    val mark = when (h.state) {
                        ProviderState.READY -> "✓"
                        ProviderState.NEEDS_CONFIGURATION -> "⚙"
                        ProviderState.DISABLED -> "○"
                        ProviderState.RATE_LIMITED -> "⏱"
                        ProviderState.ERROR -> "!"
                    }
                    "$mark ${h.displayName}: ${h.lastMessage.ifBlank { h.state.name }}${h.lastResponseTimeMs?.let { " • ${it}ms" } ?: ""}"
                }
                b.sourceStatus.text = if (envelope.fromCache) "✓ نتيجة من الذاكرة المحلية\n$status" else status.ifBlank { "لا توجد مصادر متاحة." }
                b.summary.text = if (envelope.results.isEmpty()) "لا توجد نتيجة" else "${envelope.results.size} نتيجة موحّدة"

                if (envelope.results.isEmpty()) {
                    addStateCard("لا توجد نتيجة", "تم فحص المصادر المتاحة ولم يُعثر على نتيجة مطابقة.")
                } else if (byPhone) {
                    envelope.results.forEach(::addPhoneResult)
                } else {
                    envelope.results.forEach(::addNameResult)
                }
            } catch (_: CancellationException) {
                setLoading(false)
            } catch (_: Exception) {
                setLoading(false)
                b.sourceStatus.text = "حدث خطأ مؤقت أثناء البحث. جرّب مرة أخرى."
                addStateCard("تعذر إكمال البحث", "قد يكون الإنترنت غير متاح أو أحد المصادر لا يستجيب.")
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        b.progress.visibility = if (loading) View.VISIBLE else View.GONE
        b.search.isEnabled = !loading
        b.query.isEnabled = !loading
    }

    private fun addPhoneResult(r: UnifiedLookupResult) {
        val name = r.primaryName ?: "لم يُعثر على اسم"
        val confidenceText = r.confidence?.let { "${(it * 100).toInt().coerceIn(0, 100)}%" }
        val lines = buildList {
            add("الرقم: ${r.phoneNumber.ifBlank { r.normalizedNumber }}")
            r.carrier?.takeIf(String::isNotBlank)?.let { add("شركة الاتصالات: $it") }
            r.lineType?.takeIf(String::isNotBlank)?.let { add("نوع الخط: $it") }
            r.isValid?.let { add("حالة الرقم: ${if (it) "صالح" else "غير صالح"}") }
            confidenceText?.let { add("درجة الترجيح: $it") }
            if (r.aliases.isNotEmpty()) add("أسماء أخرى: ${r.aliases.joinToString("، ")}")
            if (r.sourcesMatched.isNotEmpty()) add("المصادر المطابقة: ${r.sourcesMatched.joinToString("، ")}")
        }
        addResultCard(name, lines.joinToString("\n"))
    }

    private fun addNameResult(r: UnifiedLookupResult) {
        addResultCard(r.primaryName ?: "اسم غير متاح", buildString {
            append("الرقم: ${r.phoneNumber.ifBlank { r.normalizedNumber }}")
            if (r.aliases.isNotEmpty()) append("\nاسم بديل: ${r.aliases.first()}")
        })
    }

    private fun addStateCard(title: String, message: String) = addResultCard(title, message)

    private fun addResultCard(title: String, body: String) {
        val card = MaterialCardView(this).apply {
            radius = 22f
            cardElevation = 3f
            setContentPadding(24, 22, 24, 22)
            val content = TextView(this@MainActivity).apply {
                textDirection = View.TEXT_DIRECTION_RTL
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                text = "$title\n\n$body"
                textSize = 16f
                setLineSpacing(4f, 1.05f)
            }
            addView(content)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
            layoutParams = lp
        }
        b.results.addView(card)
    }
}
