package com.shahboun.multi

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

object RuntimeUpdateCenter {
    private const val CHANNEL_ID = "clone_updates"
    private const val NOTIFICATION_ID = 86031

    fun checkAndNotify(context: Context, engine: ShahbounCloneEngine) {
        val appContext = context.applicationContext
        Thread {
            val outdated = runCatching {
                CloneStore(appContext).list().filter { engine.needsUpdate(it.packageName, it.slot) }
            }.getOrElse {
                RuntimeDiagnostics.log("UPDATE", "update check failed: ${it.stackTraceToString()}")
                emptyList()
            }
            val nm = appContext.getSystemService(NotificationManager::class.java) ?: return@Thread
            if (outdated.isEmpty()) {
                nm.cancel(NOTIFICATION_ID)
                RuntimeDiagnostics.log("UPDATE", "all clone snapshots current")
                return@Thread
            }
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "تحديثات النسخ", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val intent = Intent(appContext, CloneUpdatesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(appContext, 86031, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = if (outdated.size == 1) "نسخة واحدة تحتاج تحديث" else "${outdated.size} نسخ تحتاج تحديث"
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("مكرّر التطبيقات")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$text من التطبيقات الأصلية المثبتة. بيانات النسخ ستبقى محفوظة."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            runCatching { nm.notify(NOTIFICATION_ID, notification) }
                .onFailure { RuntimeDiagnostics.log("UPDATE", "notification failed: ${it.stackTraceToString()}") }
        }.start()
    }
}

class CloneUpdatesActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val app get() = application as MultiApplication
    private val engine get() = app.engine
    private lateinit var content: LinearLayout
    private lateinit var updateAllButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "تحديثات النسخ"
        buildUi()
        refresh()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(24), dp(18), dp(24))
            setBackgroundColor(Color.rgb(13, 11, 18))
        }
        root.addView(TextView(this).apply {
            text = "تحديثات النسخ"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.END
        })
        root.addView(TextView(this).apply {
            text = "نحدّث ملفات التطبيق فقط ونحتفظ ببيانات كل نسخة. إذا فشل أي تحديث يرجع Snapshot القديم تلقائيًا."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.END
            setPadding(0, dp(8), 0, dp(16))
        })
        updateAllButton = Button(this).apply {
            text = "تحديث كل النسخ المتاحة"
            setOnClickListener { updateAll() }
        }
        root.addView(updateAllButton, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(14) })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        CairoFontManager.prepare(this) { runOnUiThread { CairoFontManager.applyTo(root, this) } }
    }

    private fun refresh() {
        updateAllButton.isEnabled = false
        executor.execute {
            val all = CloneStore(this).list()
            val outdated = all.filter { engine.needsUpdate(it.packageName, it.slot) }
            runOnUiThread {
                content.removeAllViews()
                updateAllButton.isEnabled = outdated.isNotEmpty()
                updateAllButton.text = if (outdated.isEmpty()) "كل النسخ محدّثة" else "تحديث الكل (${outdated.size})"
                if (outdated.isEmpty()) {
                    content.addView(label("ما فيش تحديثات مطلوبة حاليًا.", 16f, Color.LTGRAY))
                } else {
                    outdated.forEach { clone ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(14), dp(12), dp(14), dp(12))
                            setBackgroundColor(Color.rgb(31, 27, 42))
                        }
                        row.addView(label(clone.customName, 17f, Color.WHITE))
                        row.addView(label("${clone.packageName} • نسخة ${clone.slot + 1}", 12f, Color.LTGRAY))
                        row.addView(Button(this).apply {
                            text = "تحديث هذه النسخة"
                            setOnClickListener { updateOne(clone, this) }
                        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
                        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                    }
                }
                if (CairoFontManager.isReady(this)) CairoFontManager.applyTo(content, this)
            }
        }
    }

    private fun label(textValue: String, size: Float, color: Int) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        gravity = Gravity.END
    }

    private fun updateOne(clone: CloneProfile, button: Button) {
        button.isEnabled = false
        button.text = "جاري التحديث…"
        executor.execute {
            val result = engine.updateClone(clone.packageName, clone.slot)
            runOnUiThread {
                result.onSuccess { Toast.makeText(this, "تم تحديث ${clone.customName}", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, "فشل تحديث ${clone.customName}: ${it.message}", Toast.LENGTH_LONG).show() }
                refresh()
            }
        }
    }

    private fun updateAll() {
        updateAllButton.isEnabled = false
        updateAllButton.text = "جاري تحديث النسخ…"
        executor.execute {
            val outdated = CloneStore(this).list().filter { engine.needsUpdate(it.packageName, it.slot) }
            var success = 0
            val failures = mutableListOf<String>()
            outdated.forEach { clone ->
                engine.updateClone(clone.packageName, clone.slot)
                    .onSuccess { success++ }
                    .onFailure { failures += "${clone.customName}: ${it.message ?: "خطأ"}" }
            }
            RuntimeDiagnostics.log("UPDATE", "update-all complete success=$success failed=${failures.size}")
            runOnUiThread {
                val message = buildString {
                    append("تم تحديث $success نسخة")
                    if (failures.isNotEmpty()) append("، وفشل ${failures.size}:\n${failures.joinToString("\n")}")
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }
}
