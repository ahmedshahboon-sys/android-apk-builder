package com.shahboun.optimizer

import android.app.*
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var scoreText: TextView
    private lateinit var scoreSub: TextView
    private lateinit var ramValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var tempValue: TextView
    private lateinit var storageValue: TextView
    private lateinit var statusText: TextView
    private lateinit var scheduleStatus: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var chargingSwitch: Switch
    private lateinit var intervalSpinner: Spinner
    private lateinit var ramSpinner: Spinner
    private lateinit var tempSpinner: Spinner
    private val prefs by lazy { getSharedPreferences("optimizer_settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() { refreshMetrics(); refreshScheduleStatus(); handler.postDelayed(this, 5000) }
    }

    companion object { const val JOB_ID = 41061 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(245,247,250)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildUi()
        loadSettings()
        refreshMetrics()
        refreshScheduleStatus()
    }

    override fun onResume() { super.onResume(); handler.removeCallbacks(refreshTask); handler.post(refreshTask) }
    override fun onPause() { handler.removeCallbacks(refreshTask); super.onPause() }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(245,247,250)) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(20), dp(18), dp(32))
        }
        scroll.addView(root)

        addText("SHAHBOUN OPTIMIZER", 13f, true, Color.rgb(28,104,255), Gravity.CENTER)
        addText("صحة جهازك", 28f, true, Color.rgb(24,28,35), Gravity.CENTER, top=8)
        addText("مراقبة ذكية • تحسين قابل للجدولة", 14f, false, Color.rgb(112,119,130), Gravity.CENTER, top=4)

        val healthCard = card(22).apply { setPadding(dp(22),dp(24),dp(22),dp(22)) }
        scoreText = text("--", 52f, true, Color.rgb(24,28,35), Gravity.CENTER)
        scoreSub = text("جاري تحليل حالة الجهاز", 14f, false, Color.rgb(105,112,122), Gravity.CENTER)
        healthCard.addView(scoreText)
        healthCard.addView(text("/ 100", 14f, true, Color.rgb(28,104,255), Gravity.CENTER))
        healthCard.addView(scoreSub, lp(top=8))
        root.addView(healthCard, lp(top=22))

        val boost = Button(this).apply {
            text = "تحسين الآن"
            textSize = 17f
            setTextColor(Color.WHITE)
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.rgb(28,104,255), 18)
            setPadding(dp(12),dp(14),dp(12),dp(14))
            setOnClickListener { runSmartBoost() }
        }
        root.addView(boost, lp(top=14, height=dp(58)))
        statusText = text("الفحص الحي يعمل أثناء فتح التطبيق", 12f, false, Color.rgb(112,119,130), Gravity.CENTER)
        root.addView(statusText, lp(top=8))

        addSectionTitle("المؤشرات الحية")
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; layoutDirection=View.LAYOUT_DIRECTION_RTL }
        val ram = metricCard("RAM", "الذاكرة") { ramValue = it }
        val bat = metricCard("BAT", "البطارية") { batteryValue = it }
        row1.addView(ram, LinearLayout.LayoutParams(0, dp(124), 1f).apply { marginEnd=dp(6) })
        row1.addView(bat, LinearLayout.LayoutParams(0, dp(124), 1f).apply { marginStart=dp(6) })
        grid.addView(row1)
        val row2 = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; layoutDirection=View.LAYOUT_DIRECTION_RTL }
        val temp = metricCard("TEMP", "الحرارة") { tempValue = it }
        val storage = metricCard("DISK", "التخزين") { storageValue = it }
        row2.addView(temp, LinearLayout.LayoutParams(0, dp(124), 1f).apply { marginEnd=dp(6) })
        row2.addView(storage, LinearLayout.LayoutParams(0, dp(124), 1f).apply { marginStart=dp(6) })
        grid.addView(row2, lp(top=12))
        root.addView(grid)

        addSectionTitle("الجدولة والتنظيف التلقائي")
        val settingsCard = card(18).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        autoSwitch = Switch(this).apply {
            text = "تشغيل التحسين التلقائي في الخلفية"
            textSize = 15f
            setTextColor(Color.rgb(35,40,48))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        settingsCard.addView(autoSwitch)
        settingsCard.addView(text("Android يحدد التوقيت الفعلي تقريبياً لتوفير البطارية؛ لن يتم إيقاظ الجهاز بلا داعٍ.", 11f, false, Color.rgb(112,119,130), Gravity.RIGHT), lp(top=4))

        settingsCard.addView(text("كل كم ساعة يتم الفحص؟", 13f, true, Color.rgb(45,50,58), Gravity.RIGHT), lp(top=16))
        intervalSpinner = spinner(listOf("كل ساعة", "كل ساعتين", "كل 4 ساعات", "كل 6 ساعات", "كل 8 ساعات", "كل 12 ساعة", "كل 24 ساعة"))
        settingsCard.addView(intervalSpinner, lp(top=6, height=dp(48)))

        settingsCard.addView(text("يتدخل عند وصول RAM إلى", 13f, true, Color.rgb(45,50,58), Gravity.RIGHT), lp(top=14))
        ramSpinner = spinner(listOf("80%", "85%", "90%", "95%"))
        settingsCard.addView(ramSpinner, lp(top=6, height=dp(48)))

        settingsCard.addView(text("أوقف التحسين إذا وصلت الحرارة إلى", 13f, true, Color.rgb(45,50,58), Gravity.RIGHT), lp(top=14))
        tempSpinner = spinner(listOf("38°C", "40°C", "42°C", "44°C"))
        settingsCard.addView(tempSpinner, lp(top=6, height=dp(48)))

        chargingSwitch = Switch(this).apply {
            text = "نفّذ التحسين التلقائي وقت الشحن فقط"
            textSize = 14f
            setTextColor(Color.rgb(45,50,58))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        settingsCard.addView(chargingSwitch, lp(top=12))

        val save = Button(this).apply {
            text = "حفظ الإعدادات وتفعيل الجدول"
            textSize = 15f
            setTextColor(Color.WHITE)
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.rgb(28,104,255), 14)
            setOnClickListener { saveSettings() }
        }
        settingsCard.addView(save, lp(top=16, height=dp(52)))
        scheduleStatus = text("--", 12f, false, Color.rgb(86,94,105), Gravity.RIGHT)
        settingsCard.addView(scheduleStatus, lp(top=12))
        root.addView(settingsCard)

        addSectionTitle("أدوات سريعة")
        root.addView(tool("البطارية والتوفير", "فتح إعدادات البطارية وتحسين الاستهلاك") { openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS) })
        root.addView(tool("إدارة التطبيقات", "راجع التطبيقات الثقيلة وغير المستخدمة") { openSettings(Settings.ACTION_APPLICATION_SETTINGS) }, lp(top=10))
        root.addView(tool("Device Care / التخزين", "للتنظيف الأوسع الذي يحتاج صلاحيات النظام") { openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) }, lp(top=10))
        root.addView(tool("صلاحية بيانات الاستخدام", "تسمح بتحليل الاستخدام في تحديثات لاحقة") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }, lp(top=10))

        addSectionTitle("معلومات النسخة")
        val info = card(16).apply { setPadding(dp(16),dp(14),dp(16),dp(14)) }
        info.addView(text("الإصدار 1.1.0 • الجدولة الذكية", 14f, true, Color.rgb(45,50,58), Gravity.RIGHT))
        info.addView(text("فحص خلفي قابل للتخصيص + حد RAM + حد حرارة + خيار الشحن فقط + سجل آخر فحص", 12f, false, Color.rgb(112,119,130), Gravity.RIGHT), lp(top=5))
        root.addView(info)
        setContentView(scroll)
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = rounded(Color.rgb(238,242,247), 12)
        setPadding(dp(12),0,dp(12),0)
    }

    private fun loadSettings() {
        autoSwitch.isChecked = prefs.getBoolean("auto_enabled", false)
        chargingSwitch.isChecked = prefs.getBoolean("charging_only", false)
        val intervals = intArrayOf(1,2,4,6,8,12,24)
        val rams = intArrayOf(80,85,90,95)
        val temps = intArrayOf(38,40,42,44)
        intervalSpinner.setSelection(intervals.indexOf(prefs.getInt("interval_hours",4)).coerceAtLeast(0))
        ramSpinner.setSelection(rams.indexOf(prefs.getInt("ram_threshold",90)).coerceAtLeast(0))
        tempSpinner.setSelection(temps.indexOf(prefs.getInt("max_temp",42)).coerceAtLeast(0))
    }

    private fun saveSettings() {
        val intervals = intArrayOf(1,2,4,6,8,12,24)
        val rams = intArrayOf(80,85,90,95)
        val temps = intArrayOf(38,40,42,44)
        val hours = intervals[intervalSpinner.selectedItemPosition]
        val ramThreshold = rams[ramSpinner.selectedItemPosition]
        val maxTemp = temps[tempSpinner.selectedItemPosition]
        val enabled = autoSwitch.isChecked
        val chargingOnly = chargingSwitch.isChecked
        val now = System.currentTimeMillis()

        prefs.edit()
            .putBoolean("auto_enabled", enabled)
            .putBoolean("charging_only", chargingOnly)
            .putInt("interval_hours", hours)
            .putInt("ram_threshold", ramThreshold)
            .putInt("max_temp", maxTemp)
            .putLong("next_scan_estimate", if(enabled) now + hours*60L*60L*1000L else 0L)
            .apply()

        if (enabled) scheduleJob(hours, chargingOnly) else cancelJob()
        refreshScheduleStatus()
        Toast.makeText(this, if(enabled) "تم حفظ الجدولة" else "تم إيقاف الجدولة التلقائية", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleJob(hours: Int, chargingOnly: Boolean) {
        val scheduler = getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancel(JOB_ID)
        val intervalMs = hours * 60L * 60L * 1000L
        val builder = JobInfo.Builder(JOB_ID, ComponentName(this, OptimizerJobService::class.java))
            .setPeriodic(intervalMs)
            .setPersisted(true)
        if (chargingOnly) builder.setRequiresCharging(true)
        scheduler.schedule(builder.build())
    }

    private fun cancelJob() { (getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID) }

    private fun refreshScheduleStatus() {
        if (!::scheduleStatus.isInitialized) return
        val enabled = prefs.getBoolean("auto_enabled", false)
        if (!enabled) { scheduleStatus.text = "التنظيف التلقائي متوقف حالياً"; return }
        val last = prefs.getLong("last_scan", 0L)
        val next = prefs.getLong("next_scan_estimate", 0L)
        val action = prefs.getString("last_action", "لم يتم تنفيذ فحص خلفي بعد") ?: ""
        val fmt = SimpleDateFormat("HH:mm • dd/MM", Locale.getDefault())
        val lastText = if(last>0) fmt.format(Date(last)) else "لم يتم بعد"
        val nextText = if(next>0) fmt.format(Date(next)) else "حسب نظام Android"
        scheduleStatus.text = "آخر فحص: $lastText\nالنتيجة: $action\nالفحص القادم تقريبياً: $nextText"
    }

    private fun refreshMetrics() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        val ramUsed = ((1.0 - mi.availMem.toDouble()/mi.totalMem.toDouble())*100).roundToInt().coerceIn(0,100)
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if(level>=0) (level*100/scale) else 0
        val tempC = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val stat = StatFs(filesDir.absolutePath)
        val total = stat.totalBytes.toDouble(); val free = stat.availableBytes.toDouble()
        val storageUsed = ((1-free/total)*100).roundToInt().coerceIn(0,100)

        ramValue.text = "$ramUsed%"; batteryValue.text = "$batteryPct%"
        tempValue.text = if(tempC > 0) String.format(Locale.US,"%.1f°C",tempC) else "--"
        storageValue.text = "$storageUsed%"

        var score = 100
        score -= when { ramUsed >= 92 -> 20; ramUsed >= 85 -> 10; ramUsed >= 75 -> 4; else -> 0 }
        score -= when { tempC >= 44 -> 25; tempC >= 40 -> 12; tempC >= 37 -> 5; else -> 0 }
        score -= when { storageUsed >= 95 -> 20; storageUsed >= 90 -> 12; storageUsed >= 85 -> 6; else -> 0 }
        if (batteryPct in 1..15) score -= 4
        score = score.coerceIn(0,100)
        scoreText.text = score.toString()
        scoreSub.text = when {
            score >= 90 -> "حالة الجهاز ممتازة"
            score >= 75 -> "حالة الجهاز جيدة"
            score >= 60 -> "الجهاز يحتاج مراجعة بسيطة"
            else -> "يوجد ضغط ملحوظ على الجهاز"
        }
    }

    private fun runSmartBoost() {
        refreshMetrics(); System.gc()
        var deleted = 0L
        val cutoff = System.currentTimeMillis() - 6L*60L*60L*1000L
        cacheDir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) { val size=f.length(); if(f.delete()) deleted += size } }
        val kb = deleted/1024
        statusText.text = if (deleted>0) "تم التحسين وتنظيف ${kb} KB من الملفات المؤقتة الآمنة" else "الفحص مكتمل: لا يوجد تنظيف آمن مطلوب حالياً"
        Toast.makeText(this, "تم الفحص والتحسين الآمن", Toast.LENGTH_SHORT).show()
    }

    private fun metricCard(icon: String, title: String, setter:(TextView)->Unit): LinearLayout {
        val c=card(18).apply { setPadding(dp(15),dp(14),dp(15),dp(14)) }
        c.addView(text(icon, 11f, true, Color.rgb(28,104,255), Gravity.RIGHT))
        val v=text("--", 25f, true, Color.rgb(24,28,35), Gravity.RIGHT); setter(v); c.addView(v,lp(top=7))
        c.addView(text(title, 12f, false, Color.rgb(112,119,130), Gravity.RIGHT),lp(top=2)); return c
    }

    private fun tool(title:String, subtitle:String, click:()->Unit): LinearLayout {
        val c=card(16).apply { setPadding(dp(16),dp(14),dp(16),dp(14)); isClickable=true; isFocusable=true; setOnClickListener { click() } }
        c.addView(text(title,15f,true,Color.rgb(35,40,48),Gravity.RIGHT))
        c.addView(text(subtitle,12f,false,Color.rgb(112,119,130),Gravity.RIGHT),lp(top=4)); return c
    }

    private fun addSectionTitle(t:String){ root.addView(text(t,18f,true,Color.rgb(35,40,48),Gravity.RIGHT),lp(top=26,bottom=12)) }
    private fun addText(t:String,size:Float,bold:Boolean,color:Int,gravity:Int,top:Int=0){ root.addView(text(t,size,bold,color,gravity),lp(top=top)) }
    private fun text(t:String,size:Float,bold:Boolean,color:Int,gravity:Int)=TextView(this).apply { text=t;textSize=size;setTextColor(color);this.gravity=gravity;typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;layoutDirection=View.LAYOUT_DIRECTION_RTL }
    private fun card(radius:Int)=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;background=rounded(Color.WHITE,radius);elevation=dp(2).toFloat() }
    private fun rounded(color:Int,radius:Int)=GradientDrawable().apply { shape=GradientDrawable.RECTANGLE;setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun lp(top:Int=0,bottom:Int=0,height:Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,height).apply { topMargin=dp(top);bottomMargin=dp(bottom) }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt()
    private fun openSettings(action:String){ try { startActivity(Intent(action)) } catch(e:Exception){ startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}
