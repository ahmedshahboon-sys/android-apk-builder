package com.shahboun.numberlookup

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class SourcesActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "مصادر البحث"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(20, 20, 20, 32)
        }
        root.addView(list)
        setContentView(root)
        render()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) render()
    }

    private fun render() {
        list.removeAllViews()
        val title = TextView(this).apply {
            text = "مصادر البحث"
            textSize = 27f
            gravity = Gravity.START
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        list.addView(title)
        val subtitle = TextView(this).apply {
            text = "كل مصدر مستقل ويمكن تعطيله أو إضافة مصدر جديد لاحقًا بدون تغيير شاشة البحث. مفاتيح API لا تُعرض هنا."
            textSize = 14f
            setPadding(0, 8, 0, 18)
        }
        list.addView(subtitle)

        lifecycleScope.launch {
            val health = LookupRepository(this@SourcesActivity).health()
            health.forEach(::addProviderCard)

            val legacy = MaterialButton(this@SourcesActivity).apply {
                text = "إعداد المصادر الحالية 1–5"
                setOnClickListener { startActivity(Intent(this@SourcesActivity, SettingsActivity::class.java)) }
            }
            list.addView(legacy)

            val note = TextView(this@SourcesActivity).apply {
                text = "CallerKit وTrestle وAbstract تبقى بحالة «يحتاج إعداد» حتى تُدخل بيانات الاعتماد الرسمية في secure configuration. لا توجد أي مفاتيح حقيقية داخل المستودع."
                textSize = 13f
                setPadding(0, 18, 0, 0)
            }
            list.addView(note)
        }
    }

    private fun addProviderCard(h: ProviderHealth) {
        val stateArabic = when (h.state) {
            ProviderState.READY -> "متصل/جاهز"
            ProviderState.NEEDS_CONFIGURATION -> "يحتاج إعداد"
            ProviderState.DISABLED -> "متوقف"
            ProviderState.RATE_LIMITED -> "حد الطلبات"
            ProviderState.ERROR -> "خطأ مؤقت"
        }
        val card = MaterialCardView(this).apply {
            radius = 22f
            cardElevation = 2f
            setContentPadding(22, 20, 22, 20)
            val textView = TextView(this@SourcesActivity).apply {
                textDirection = View.TEXT_DIRECTION_RTL
                text = buildString {
                    append(h.displayName)
                    append("\n")
                    append("الحالة: $stateArabic")
                    append("\nالأولوية: ${h.priority}")
                    append("\nمفعّل: ${if (h.enabled) "نعم" else "لا"}")
                    h.lastResponseTimeMs?.let { append("\nآخر زمن استجابة: ${it}ms") }
                    if (h.lastMessage.isNotBlank()) append("\n${h.lastMessage}")
                    if (h.successCount > 0) append("\nنجاحات مسجلة: ${h.successCount}")
                }
                textSize = 15f
                setLineSpacing(3f, 1.05f)
            }
            addView(textView)
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, 14)
        card.layoutParams = lp
        list.addView(card)
    }
}
