package com.shahboun.multi

import android.app.*
import android.content.*
import android.graphics.Typeface
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

    // Android's native Arabic shaping keeps the APK free from bundled third-party font files.
    private val arabicTitle by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
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
        store = CloneStore(this)
        RuntimeDiagnostics.log("UI", "MainActivity onCreate bridgeReady=${app.runtimeBridgeReady}")
        buildUi()
        requestNotifications()
        render()
    }

    private fun tv(textValue: String, size: Float = 16f, title: Boolean = false) = TextView(this).apply {
        text = textValue
        textSize = size
        typeface = if (title) arabicTitle else arabicBody
        setPadding(8, 8, 8, 8)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(24, 28, 24, 40)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(tv("Shahboun Multi", 28f, true))
        root.addView(tv("محرك: ${engine.name} • الجسر: ${if (app.runtimeBridgeReady) "جاهز" else "يحتاج فحص"}", 13f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton("+ إضافة نسخة") { pickApp() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(actionButton("قفل") { lockNow() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(actionButton("التشخيص") { startActivity(Intent(this, DebugActivity::class.java)) }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(actionButton("الإعدادات") { settingsDialog() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tools)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
    }

    private fun actionButton(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        typeface = arabicTitle
        setOnClickListener { action() }
    }

    private fun render() {
        listBox.removeAllViews()
        val visible = items.filter { !it.hidden }.sortedWith(compareByDescending<CloneProfile> { it.favorite }.thenBy { it.customName })
        if (visible.isEmpty()) listBox.addView(tv("لا توجد نسخ بعد. اضغط «إضافة نسخة»."))
        visible.forEach { c ->
            val card = MaterialCardView(this).apply { radius = 22f; setContentPadding(18, 14, 18, 14) }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(tv("${if (c.favorite) "★ " else ""}${c.customName}", 19f, true))
            body.addView(tv("${c.packageName}  •  نسخة ${c.slot}${if (c.frozen) "  •  مجمّدة" else ""}", 12f))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(actionButton("فتح") { launchClone(c) }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(actionButton("إدارة") { manage(c) }, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(actions)
            card.addView(body)
            listBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 10) })
        }
    }

    private fun launchClone(c: CloneProfile) {
        if (c.frozen) return toast("النسخة مجمّدة")
        val missing = runCatching { RuntimePermissionBroker.missingForGuest(this, c.packageName) }
            .onFailure { RuntimeDiagnostics.log("PERMISSION", "scan failed ${c.packageName}: ${it.stackTraceToString()}") }
            .getOrDefault(emptyList())
        if (missing.isNotEmpty()) {
            pendingLaunch = c
            RuntimeDiagnostics.log("PERMISSION", "request clone=${c.packageName}/${c.slot} count=${missing.size}")
            permissionLauncher.launch(missing.toTypedArray())
        } else launchNow(c)
    }

    private fun launchNow(c: CloneProfile) {
        RuntimeDiagnostics.log("LAUNCH", "start ${c.packageName}/${c.slot}")
        engine.launch(c.packageName, c.slot)
            .onSuccess { RuntimeDiagnostics.log("LAUNCH", "dispatch success ${c.packageName}/${c.slot}") }
            .onFailure {
                RuntimeDiagnostics.log("LAUNCH", "failed ${c.packageName}/${c.slot}: ${it.stackTraceToString()}")
                toast("تعذر فتح النسخة: ${it.message ?: "خطأ غير معروف"}")
            }
    }

    private fun installed(): List<InstalledApp> {
        val pm = packageManager
        return pm.getInstalledApplications(0)
            .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    private fun pickApp() {
        val apps = installed()
        val names = apps.map { "${it.label}\n${it.packageName}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("اختر تطبيقًا مثبتًا")
            .setItems(names) { _, i -> createDialog(apps[i]) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun createDialog(appInfo: InstalledApp) {
        val input = TextInputEditText(this).apply {
            hint = "اسم النسخة"
            setText("${appInfo.label} ${nextSlot(appInfo.packageName)}")
            typeface = arabicBody
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("إنشاء نسخة")
            .setView(input)
            .setPositiveButton("إنشاء") { _, _ ->
                val slot = nextSlot(appInfo.packageName)
                val name = input.text?.toString()?.trim().orEmpty().ifBlank { "${appInfo.label} $slot" }
                RuntimeDiagnostics.log("CLONE", "create ${appInfo.packageName}/$slot")
                engine.createClone(appInfo.packageName, slot)
                    .onSuccess {
                        val x = items
                        x += CloneProfile(System.currentTimeMillis(), appInfo.packageName, appInfo.label, name, slot)
                        store.save(x)
                        render()
                        RuntimeDiagnostics.log("CLONE", "created ${appInfo.packageName}/$slot")
                        toast("تم إنشاء النسخة")
                    }
                    .onFailure {
                        RuntimeDiagnostics.log("CLONE", "create failed ${appInfo.packageName}/$slot: ${it.stackTraceToString()}")
                        toast("تعذر إنشاء النسخة: ${it.message}")
                    }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun nextSlot(pkg: String) = (items.filter { it.packageName == pkg }.maxOfOrNull { it.slot } ?: -1) + 1

    private fun manage(c: CloneProfile) {
        val opts = arrayOf(
            "إعادة تسمية",
            if (c.favorite) "إلغاء المفضلة" else "إضافة للمفضلة",
            if (c.frozen) "إلغاء التجميد" else "تجميد",
            if (c.hidden) "إظهار" else "إخفاء",
            "مسح البيانات",
            "حذف النسخة"
        )
        MaterialAlertDialogBuilder(this).setTitle(c.customName).setItems(opts) { _, which ->
            when (which) {
                0 -> rename(c)
                1 -> update(c) { it.copy(favorite = !it.favorite) }
                2 -> update(c) { it.copy(frozen = !it.frozen) }
                3 -> update(c) { it.copy(hidden = !it.hidden) }
                4 -> engine.clearData(c.packageName, c.slot)
                    .onSuccess { RuntimeDiagnostics.log("CLONE", "cleared ${c.packageName}/${c.slot}"); toast("تم مسح بيانات النسخة") }
                    .onFailure { RuntimeDiagnostics.log("CLONE", "clear failed: ${it.stackTraceToString()}"); toast(it.message ?: "تعذر مسح البيانات") }
                5 -> confirmDelete(c)
            }
        }.show()
    }

    private fun rename(c: CloneProfile) {
        val input = TextInputEditText(this).apply { setText(c.customName); typeface = arabicBody }
        MaterialAlertDialogBuilder(this).setTitle("إعادة تسمية").setView(input)
            .setPositiveButton("حفظ") { _, _ -> update(c) { it.copy(customName = input.text.toString()) } }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun update(c: CloneProfile, f: (CloneProfile) -> CloneProfile) {
        val x = items
        val idx = x.indexOfFirst { it.id == c.id }
        if (idx >= 0) { x[idx] = f(x[idx]); store.save(x); render() }
    }

    private fun confirmDelete(c: CloneProfile) {
        MaterialAlertDialogBuilder(this).setTitle("حذف النسخة؟").setMessage("لن يتم حذف التطبيق الأصلي.")
            .setPositiveButton("حذف") { _, _ ->
                engine.remove(c.packageName, c.slot)
                    .onSuccess { RuntimeDiagnostics.log("CLONE", "removed ${c.packageName}/${c.slot}") }
                    .onFailure { RuntimeDiagnostics.log("CLONE", "remove failed: ${it.stackTraceToString()}") }
                val x = items
                x.removeAll { it.id == c.id }
                store.save(x)
                render()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun lockNow() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { toast("تم فتح Shahboun Multi") }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("قفل Shahboun Multi")
                .setSubtitle("استخدم البصمة أو قفل الجهاز")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
        )
    }

    private fun settingsDialog() {
        MaterialAlertDialogBuilder(this).setTitle("الإعدادات").setItems(
            arrayOf("حالة المحرك: ${engine.name}", "إعدادات البطارية", "إعدادات الإشعارات", "التشخيص", "حول التطبيق")
        ) { _, i ->
            when (i) {
                1 -> startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                2 -> startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                3 -> startActivity(Intent(this, DebugActivity::class.java))
                4 -> toast("Shahboun Multi Debug • Shahboun Clone Engine • Android 10–16")
            }
        }.show()
    }

    private fun requestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
