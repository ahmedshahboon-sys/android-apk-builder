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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.shahboun.numberlookup.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var repository: LookupRepositoryV2
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
        repository = LookupRepositoryV2(this)
        applySafeInsets()

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

    private fun applySafeInsets() {
        val initialLeft = b.root.paddingLeft
        val initialTop = b.root.paddingTop
        val initialRight = b.root.paddingRight
        val initialBottom = b.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(initialLeft + bars.left, initialTop + bars.top, initialRight + bars.right, initialBottom + bars.bottom)
            insets
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
                    b.sourceStatus.text = "الرقم غير صالح"
                    addStateCard("راجع رقم الهاتف", "استخدم صيغة ليبية مثل 091xxxxxxx أو +21891xxxxxxx.")
                    return@launch
                }

                val attempted = envelope.providerHealth.count { it.enabled && it.state != ProviderState.NEEDS_CONFIGURATION }
                val configuredExternal = envelope.providerHealth.any { it.id == "numbers_online" && it.state == ProviderState.READY }
                val found = envelope.results.any { it.found && !it.primaryName.isNullOrBlank() }
                b.sourceStatus.text = when {
                    envelope.fromCache -> "تم العثور على نتيجة محفوظة محليًا"
                    found -> "تم العثور على نتيجة"
                    byPhone && !configuredExternal -> "لم يُعثر على اسم. يمكنك إعداد Numbers Online من «مصادر البحث»."
                    attempted > 0 -> "اكتمل البحث في المصادر المتاحة"
                    else -> "لا توجد مصادر بحث مهيأة"
                }
                b.summary.text = if (envelope.results.isEmpty() || !found && byPhone) "" else "${envelope.results.size} نتيجة"

                if (envelope.results.isEmpty() || (byPhone && envelope.results.none { it.found })) {
                    addStateCard(
                        "لا توجد نتيجة",
                        if (!configuredExternal && byPhone) "فعّل مصدر Numbers Online من شاشة «مصادر البحث» ثم أعد المحاولة." else "لم يُعثر على اسم مرتبط بهذا الرقم في المصادر التي استجابت."
                    )
                } else if (byPhone) {
                    envelope.results.filter { it.found }.forEach(::addPhoneResult)
                } else {
                    envelope.results.forEach(::addNameResult)
                }
            } catch (_: CancellationException) {
                setLoading(false)
            } catch (_: Exception) {
                setLoading(false)
                b.sourceStatus.text = "حدث خطأ مؤقت"
                addStateCard("تعذر إكمال البحث", "تحقق من الإنترنت وحاول مرة أخرى.")
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        b.progress.visibility = if (loading) View.VISIBLE else View.GONE
        b.search.isEnabled = !loading
        b.query.isEnabled = !loading
    }

    private fun addPhoneResult(r: UnifiedLookupResult) {
        val name = r.primaryName ?: return
        val lines = buildList {
            add("رقم الهاتف: ${r.phoneNumber.ifBlank { r.normalizedNumber }}")
            r.carrier?.takeIf(String::isNotBlank)?.let { add("شركة الاتصالات: $it") }
            r.lineType?.takeIf(String::isNotBlank)?.let { add("نوع الخط: $it") }
            if (r.aliases.isNotEmpty()) add("أسماء أخرى: ${r.aliases.joinToString("، ")}")
        }
        addResultCard(name, lines.joinToString("\n"))
    }

    private fun addNameResult(r: UnifiedLookupResult) {
        addResultCard(r.primaryName ?: "اسم غير متاح", buildString {
            append("رقم الهاتف: ${r.phoneNumber.ifBlank { r.normalizedNumber }}")
            if (r.aliases.isNotEmpty()) append("\nاسم بديل: ${r.aliases.first()}")
        })
    }

    private fun addStateCard(title: String, message: String) = addResultCard(title, message)

    private fun addResultCard(title: String, body: String) {
        val card = MaterialCardView(this).apply {
            radius = 26f
            cardElevation = 1.5f
            strokeWidth = 1
            setContentPadding(28, 24, 28, 24)
            val content = TextView(this@MainActivity).apply {
                textDirection = View.TEXT_DIRECTION_RTL
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                text = "$title\n$body"
                textSize = 16f
                setLineSpacing(6f, 1.05f)
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
