package com.shahboun.numberlookup

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class SourcesActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFFF7F8FA.toInt())
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(24, 18, 24, 36)
        }
        root.addView(list)
        setContentView(root)
        applyInsets(root)
        render()
    }

    private fun applyInsets(root: View) {
        val l = root.paddingLeft
        val t = root.paddingTop
        val r = root.paddingRight
        val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) render()
    }

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "مصادر البحث"
            textSize = 27f
            gravity = Gravity.START
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        list.addView(TextView(this).apply {
            text = "حالة مختصرة للمصادر. «مُعدّ» تعني أن إعداداته موجودة، وليس ضمانًا أن كل رقم له اسم."
            textSize = 14f
            setTextColor(0xFF626871.toInt())
            setPadding(0, 6, 0, 16)
        })

        lifecycleScope.launch {
            LookupRepositoryV2(this@SourcesActivity).health().forEach(::addProviderCard)

            list.addView(MaterialButton(this@SourcesActivity).apply {
                text = "إعداد المصادر والمفتاح"
                setOnClickListener { startActivity(Intent(this@SourcesActivity, SettingsActivity::class.java)) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 10, 0, 10)
                }
            })

            list.addView(TextView(this@SourcesActivity).apply {
                text = "لتفعيل Numbers Online: افتح الإعدادات، ثم المصدر 4، والصق المفتاح في خانة Bearer Token. الـProvider يستخدم العنوان الرسمي تلقائيًا ولا يعرض المفتاح هنا."
                textSize = 13f
                setTextColor(0xFF6B7179.toInt())
                setPadding(4, 8, 4, 16)
            })
        }
    }

    private fun addProviderCard(h: ProviderHealth) {
        val stateArabic = when (h.state) {
            ProviderState.READY -> "مُعدّ"
            ProviderState.NEEDS_CONFIGURATION -> "يحتاج إعداد"
            ProviderState.DISABLED -> "متوقف"
            ProviderState.RATE_LIMITED -> "حد الطلبات"
            ProviderState.ERROR -> "غير متاح مؤقتًا"
        }
        val card = MaterialCardView(this).apply {
            radius = 20f
            cardElevation = 1f
            strokeWidth = 1
            setContentPadding(20, 16, 20, 16)
            addView(TextView(this@SourcesActivity).apply {
                textDirection = View.TEXT_DIRECTION_RTL
                text = buildString {
                    append(h.displayName)
                    append("\n")
                    append(stateArabic)
                    if (h.lastMessage.isNotBlank() && h.lastMessage != h.state.name) append(" • ${h.lastMessage}")
                    h.lastResponseTimeMs?.let { append(" • ${it}ms") }
                }
                textSize = 14.5f
                setLineSpacing(3f, 1.03f)
            })
        }
        card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, 10)
        }
        list.addView(card)
    }
}
