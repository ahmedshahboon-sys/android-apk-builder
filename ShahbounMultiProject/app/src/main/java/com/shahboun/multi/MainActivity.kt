package com.shahboun.multi

import android.app.*
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var store: CloneStore
    private val items get() = store.list()
    private val app get() = application as MultiApplication
    private val engine get() = app.engine

    private val bg = Color.rgb(13, 11, 18)
    private val surface = Color.rgb(24, 21, 32)
    private val surface2 = Color.rgb(31, 27, 42)
    private val primary = Color.rgb(132, 94, 247)
    private val textPrimary = Color.rgb(245, 242, 250)
    private val textSecondary = Color.rgb(180, 174, 193)
    private val success = Color.rgb(77, 210, 150)
    private val danger = Color.rgb(255, 102, 122)

    private val arabicTitle by lazy { Typeface.create("sans-serif", Typeface.BOLD) }
    private val arabicMedium by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val arabicBody by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    private var pendingLaunch: CloneProfile? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val clone = pendingLaunch
        pendingLaunch = null
        val denied = result.filterValues { !it }.keys
        RuntimeDiagnostics.log("PERMISSION", "result clone=${clone?.packageName}/${clone?.slot} denied=${denied.joinToString()}")
        if (denied.isNotEmpty()) toast("بعض الصلاحيات لم تُمنح؛ ممكن بعض وظائف النسخة ما تشتغلش")
        clone?.let { launchNow(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        store = CloneStore(this)
        RuntimeDiagnostics.log("UI", "MainActivity onCreate bridgeReady=${app.runtimeBridgeReady}")
        buildUi()
        requestNotifications()
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 20, strokeColor: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun tv(textValue: String, size: Float = 16f, weight: Int = 0, color: Int = textPrimary) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        typeface = when (weight) { 2 -> arabicTitle; 1 -> arabicMedium; else -> arabicBody }
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        includeFontPadding = false
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(bg) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(20), dp(18), dp(40))
            setBackgroundColor(bg)
        }
        scroll.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(18)) }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        brand.addView(tv("Shahboun Multi", 27f, 2))
        brand.addView(tv("مساحتك الخاصة لتكرار التطبيقات", 13f, 0, textSecondary).apply { setPadding(0, dp(5), 0, 0) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val logo = TextView(this).apply {
            text = "S"; textSize = 22f; setTextColor(Color.WHITE); typeface = arabicTitle; gravity = Gravity.CENTER; background = rounded(primary, 18)
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(14) })
        root.addView(header)

        val engineCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 18, Color.rgb(48, 43, 62)); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val engineTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        engineTexts.addView(tv(engine.name, 14f, 1))
        engineTexts.addView(tv("محرك النسخ", 11f, 0, textSecondary).apply { setPadding(0, dp(3), 0, 0) })
        engineCard.addView(engineTexts, LinearLayout.LayoutParams(0, -2, 1f))
        engineCard.addView(tv(if (app.runtimeBridgeReady) "●  جاهز" else "●  يحتاج فحص", 12f, 1, if (app.runtimeBridgeReady) success else danger).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, dp(36)))
        root.addView(engineCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        root.addView(primaryButton("＋  إضافة نسخة جديدة") { pickApp() }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(12) })
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(secondaryButton("التشخيص") { startActivity(Intent(this, DebugActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        tools.addView(secondaryButton("الإعدادات") { settingsDialog() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
        tools.addView(secondaryButton("قفل") { lockNow() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })

        val section = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        section.addView(tv("نسخ التطبيقات", 19f, 2), LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(tv("${items.count { !it.hidden }} نسخة", 12f, 1, textSecondary).apply { gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0); background = rounded(surface2, 14) }, LinearLayout.LayoutParams(-2, dp(32)))
        root.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
    }

    private fun primaryButton(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label; textSize = 15f; typeface = arabicMedium; setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(primary); cornerRadius = dp(18); insetTop = 0; insetBottom = 0; setOnClickListener { action() }
    }

    private fun secondaryButton(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label; textSize = 13f; typeface = arabicMedium; setTextColor(textPrimary)
        backgroundTintList = ColorStateList.valueOf(surface2); cornerRadius = dp(16); insetTop = 0; insetBottom = 0; setOnClickListener { action() }
    }

    private fun compactButton(label: String, primaryAction: Boolean, action: () -> Unit) = MaterialButton(this).apply {
        text = label; textSize = 13f; typeface = arabicMedium; setTextColor(if (primaryAction) Color.WHITE else textPrimary)
        backgroundTintList = ColorStateList.valueOf(if (primaryAction) primary else surface2); cornerRadius = dp(14); insetTop = 0; insetBottom = 0; minHeight = 0; setOnClickListener { action() }
    }

    private fun render() {
        listBox.removeAllViews()
        val visible = items.filter { !it.hidden }.sortedWith(compareByDescending<CloneProfile> { it.favorite }.thenBy { it.customName })
        if (visible.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = rounded(surface, 22, Color.rgb(45, 40, 57)); setPadding(dp(20), dp(32), dp(20), dp(32)) }
            empty.addView(tv("ما عندكش نسخ لحد الآن", 17f, 2).apply { gravity = Gravity.CENTER })
            empty.addView(tv("اضغط «إضافة نسخة جديدة» واختار التطبيق اللي تبي تكرره", 13f, 0, textSecondary).apply { gravity = Gravity.CENTER; setPadding(0, dp(9), 0, 0) })
            listBox.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }); return
        }
        visible.forEach { c ->
            val card = MaterialCardView(this).apply { radius = dp(22).toFloat(); cardElevation = 0f; setCardBackgroundColor(surface); strokeColor = Color.rgb(48, 43, 62); strokeWidth = dp(1); setContentPadding(dp(16), dp(16), dp(16), dp(14)) }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(tv((if (c.favorite) "★  " else "") + c.customName, 18f, 2))
            info.addView(tv("نسخة ${c.slot + 1}  •  ${c.packageName}", 11.5f, 0, textSecondary).apply { setPadding(0, dp(5), 0, 0) })
            top.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val icon = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; setPadding(dp(5), dp(5), dp(5), dp(5)); background = rounded(surface2, 16); setImageDrawable(runCatching { packageManager.getApplicationIcon(c.packageName) }.getOrNull()) }
            top.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(12) }); body.addView(top)
            val statusColor = if (c.frozen) danger else success
            body.addView(tv("●  ${if (c.frozen) "مجمّدة" else "جاهزة"}", 11.5f, 1, statusColor).apply { setPadding(0, dp(10), 0, 0) })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
            actions.addView(compactButton("فتح النسخة", true) { launchClone(c) }, LinearLayout.LayoutParams(0, dp(46), 1.5f).apply { marginEnd = dp(6) })
            actions.addView(compactButton("إدارة", false) { manage(c) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
            body.addView(actions); card.addView(body)
            listBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(8)) })
        }
    }

    private fun launchClone(c: CloneProfile) {
        if (c.frozen) return toast("النسخة مجمّدة")
        val missing = runCatching { RuntimePermissionBroker.missingForGuest(this, c.packageName) }.onFailure { RuntimeDiagnostics.log("PERMISSION", "scan failed ${c.packageName}: ${it.stackTraceToString()}") }.getOrDefault(emptyList())
        if (missing.isNotEmpty()) { pendingLaunch = c; RuntimeDiagnostics.log("PERMISSION", "request clone=${c.packageName}/${c.slot} count=${missing.size}"); permissionLauncher.launch(missing.toTypedArray()) } else launchNow(c)
    }

    private fun launchNow(c: CloneProfile) {
        RuntimeDiagnostics.log("LAUNCH", "start ${c.packageName}/${c.slot}")
        engine.launch(c.packageName, c.slot).onSuccess { RuntimeDiagnostics.log("LAUNCH", "dispatch success ${c.packageName}/${c.slot}") }.onFailure { RuntimeDiagnostics.log("LAUNCH", "failed ${c.packageName}/${c.slot}: ${it.stackTraceToString()}"); toast("تعذر فتح النسخة: ${it.message ?: "خطأ غير معروف"}") }
    }

    private fun installed(): List<InstalledApp> {
        val pm = packageManager
        return pm.getInstalledApplications(0).filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }.map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }.sortedBy { it.label.lowercase() }
    }

    private fun pickApp() {
        val apps = installed(); val names = apps.map { "${it.label}\n${it.packageName}" }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("اختر تطبيقًا مثبتًا").setItems(names) { _, i -> createDialog(apps[i]) }.setNegativeButton("إلغاء", null).show()
    }

    private fun createDialog(appInfo: InstalledApp) {
        val input = TextInputEditText(this).apply { hint = "اسم النسخة"; setText("${appInfo.label} ${nextSlot(appInfo.packageName) + 1}"); typeface = arabicBody }
        MaterialAlertDialogBuilder(this).setTitle("إنشاء نسخة").setView(input).setPositiveButton("إنشاء") { _, _ ->
            val slot = nextSlot(appInfo.packageName); val name = input.text?.toString()?.trim().orEmpty().ifBlank { "${appInfo.label} ${slot + 1}" }
            RuntimeDiagnostics.log("CLONE", "create ${appInfo.packageName}/$slot")
            engine.createClone(appInfo.packageName, slot).onSuccess { val x = items; x += CloneProfile(System.currentTimeMillis(), appInfo.packageName, appInfo.label, name, slot); store.save(x); render(); RuntimeDiagnostics.log("CLONE", "created ${appInfo.packageName}/$slot"); toast("تم إنشاء النسخة") }.onFailure { RuntimeDiagnostics.log("CLONE", "create failed ${appInfo.packageName}/$slot: ${it.stackTraceToString()}"); toast("تعذر إنشاء النسخة: ${it.message}") }
        }.setNegativeButton("إلغاء", null).show()
    }

    private fun nextSlot(pkg: String) = (items.filter { it.packageName == pkg }.maxOfOrNull { it.slot } ?: -1) + 1

    private fun manage(c: CloneProfile) {
        val opts = arrayOf("إعادة تسمية", if (c.favorite) "إلغاء المفضلة" else "إضافة للمفضلة", if (c.frozen) "إلغاء التجميد" else "تجميد", if (c.hidden) "إظهار" else "إخفاء", "مسح البيانات", "حذف النسخة")
        MaterialAlertDialogBuilder(this).setTitle(c.customName).setItems(opts) { _, which -> when (which) {
            0 -> rename(c); 1 -> update(c) { it.copy(favorite = !it.favorite) }; 2 -> update(c) { it.copy(frozen = !it.frozen) }; 3 -> update(c) { it.copy(hidden = !it.hidden) }
            4 -> engine.clearData(c.packageName, c.slot).onSuccess { RuntimeDiagnostics.log("CLONE", "cleared ${c.packageName}/${c.slot}"); toast("تم مسح بيانات النسخة") }.onFailure { RuntimeDiagnostics.log("CLONE", "clear failed: ${it.stackTraceToString()}"); toast(it.message ?: "تعذر مسح البيانات") }
            5 -> confirmDelete(c)
        } }.show()
    }

    private fun rename(c: CloneProfile) {
        val input = TextInputEditText(this).apply { setText(c.customName); typeface = arabicBody }
        MaterialAlertDialogBuilder(this).setTitle("إعادة تسمية").setView(input).setPositiveButton("حفظ") { _, _ -> update(c) { it.copy(customName = input.text.toString()) } }.setNegativeButton("إلغاء", null).show()
    }

    private fun update(c: CloneProfile, f: (CloneProfile) -> CloneProfile) { val x = items; val idx = x.indexOfFirst { it.id == c.id }; if (idx >= 0) { x[idx] = f(x[idx]); store.save(x); render() } }

    private fun confirmDelete(c: CloneProfile) {
        MaterialAlertDialogBuilder(this).setTitle("حذف النسخة؟").setMessage("لن يتم حذف التطبيق الأصلي.").setPositiveButton("حذف") { _, _ ->
            engine.remove(c.packageName, c.slot).onSuccess { RuntimeDiagnostics.log("CLONE", "removed ${c.packageName}/${c.slot}") }.onFailure { RuntimeDiagnostics.log("CLONE", "remove failed: ${it.stackTraceToString()}") }
            val x = items; x.removeAll { it.id == c.id }; store.save(x); render()
        }.setNegativeButton("إلغاء", null).show()
    }

    private fun lockNow() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() { override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { toast("تم فتح Shahboun Multi") } })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("قفل Shahboun Multi").setSubtitle("استخدم البصمة أو قفل الجهاز").setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
    }

    private fun settingsDialog() {
        MaterialAlertDialogBuilder(this).setTitle("الإعدادات").setItems(arrayOf("حالة المحرك: ${engine.name}", "إعدادات البطارية", "إعدادات الإشعارات", "التشخيص", "حول التطبيق")) { _, i -> when (i) {
            1 -> startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); 2 -> startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)); 3 -> startActivity(Intent(this, DebugActivity::class.java)); 4 -> toast("Shahboun Multi Debug • Shahboun Clone Engine • Android 10–16")
        } }.show()
    }

    private fun requestNotifications() { if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
