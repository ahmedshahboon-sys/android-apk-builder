package com.shahboun.multi

import android.app.*
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var store: CloneStore
    private val items get() = store.list()
    private val app get() = application as MultiApplication
    private val engine get() = app.engine
    private val bg = Color.rgb(13,11,18)
    private val surface = Color.rgb(24,21,32)
    private val surface2 = Color.rgb(31,27,42)
    private val primary = Color.rgb(255,122,0)
    private val textPrimary = Color.rgb(245,242,250)
    private val textSecondary = Color.rgb(180,174,193)
    private val success = Color.rgb(77,210,150)
    private val danger = Color.rgb(255,102,122)
    private val arabicTitle: Typeface get() = CairoFontManager.typeface(this,700)
    private val arabicMedium: Typeface get() = CairoFontManager.typeface(this,500)
    private val arabicBody: Typeface get() = CairoFontManager.typeface(this,400)
    private var pendingLaunch: CloneProfile? = null
    private var pendingIconClone: CloneProfile? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val clone = pendingLaunch; pendingLaunch = null
        val denied = result.filterValues { !it }.keys
        RuntimeDiagnostics.log("PERMISSION","result clone=${clone?.packageName}/${clone?.slot} denied=${denied.joinToString()}")
        if (denied.isNotEmpty()) toast("بعض الصلاحيات لم تُمنح؛ ممكن بعض وظائف النسخة ما تشتغلش")
        clone?.let { launchNow(it) }
    }

    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val clone = pendingIconClone; pendingIconClone = null
        if (clone == null || uri == null) return@registerForActivityResult
        runCatching {
            val dir = File(filesDir,"clone_icons").apply{mkdirs()}
            val target = File(dir,"${clone.id}.img")
            contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } } ?: error("تعذر قراءة الصورة")
            val decoded = BitmapFactory.decodeFile(target.absolutePath) ?: error("الصورة غير صالحة")
            require(decoded.width > 0 && decoded.height > 0) { "الصورة غير صالحة" }
            update(clone){it.copy(customIconPath=target.absolutePath)}
            toast("تم تغيير أيقونة النسخة")
        }.onFailure { toast("تعذر تغيير الأيقونة: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        store = CloneStore(this)
        RuntimeDiagnostics.log("UI","MainActivity onCreate bridgeReady=${app.runtimeBridgeReady}")
        buildUi()
        CairoFontManager.prepare(this){ runOnUiThread { if(::root.isInitialized) CairoFontManager.applyTo(root,this) } }
        requestNotifications(); render(); handleShortcutIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if(intent?.action!=ACTION_OPEN_CLONE)return
        val pkg=intent.getStringExtra(EXTRA_SHORTCUT_PACKAGE)?:return
        val slot=intent.getIntExtra(EXTRA_SHORTCUT_SLOT,-1)
        val clone=items.firstOrNull{it.packageName==pkg&&it.slot==slot}?:return
        launchClone(clone)
    }

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(color:Int,radius:Int=20,strokeColor:Int?=null)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(color);cornerRadius=dp(radius).toFloat();strokeColor?.let{setStroke(dp(1),it)}}
    private fun tv(value:String,size:Float=16f,weight:Int=0,color:Int=textPrimary)=TextView(this).apply{text=value;textSize=size;setTextColor(color);typeface=when(weight){2->arabicTitle;1->arabicMedium;else->arabicBody};gravity=Gravity.CENTER_VERTICAL or Gravity.END;includeFontPadding=false}

    private fun buildUi(){
        val scroll=ScrollView(this).apply{isFillViewport=true;setBackgroundColor(bg)}
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;setPadding(dp(18),dp(20),dp(18),dp(40));setBackgroundColor(bg)}
        scroll.addView(root,ViewGroup.LayoutParams(-1,-2));setContentView(scroll)
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(4),0,dp(18))}
        val brand=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.END}
        brand.addView(tv("مكرّر التطبيقات",27f,2));brand.addView(tv("مساحتك الخاصة لتكرار التطبيقات",13f,0,textSecondary).apply{setPadding(0,dp(5),0,0)})
        header.addView(brand,LinearLayout.LayoutParams(0,-2,1f));header.addView(tv("م",22f,2,Color.WHITE).apply{gravity=Gravity.CENTER;background=rounded(primary,18)},LinearLayout.LayoutParams(dp(52),dp(52)).apply{marginStart=dp(14)});root.addView(header)
        val engineCard=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=rounded(surface,18,Color.rgb(48,43,62));setPadding(dp(14),dp(12),dp(14),dp(12))}
        val engineTexts=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};engineTexts.addView(tv(engine.name,14f,1));engineTexts.addView(tv("محرك النسخ",11f,0,textSecondary).apply{setPadding(0,dp(3),0,0)})
        engineCard.addView(engineTexts,LinearLayout.LayoutParams(0,-2,1f));engineCard.addView(tv(if(app.runtimeBridgeReady)"●  جاهز" else "●  يحتاج فحص",12f,1,if(app.runtimeBridgeReady)success else danger).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-2,dp(36)))
        root.addView(engineCard,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(16)})
        root.addView(primaryButton("＋  إضافة نسخة جديدة"){pickApp()},LinearLayout.LayoutParams(-1,dp(54)).apply{bottomMargin=dp(12)})
        val tools=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        tools.addView(secondaryButton("التشخيص"){startActivity(Intent(this,DebugActivity::class.java))},LinearLayout.LayoutParams(0,dp(48),1f).apply{marginEnd=dp(6)})
        tools.addView(secondaryButton("الإعدادات"){settingsDialog()},LinearLayout.LayoutParams(0,dp(48),1f).apply{marginStart=dp(6);marginEnd=dp(6)});tools.addView(secondaryButton("قفل"){lockNow()},LinearLayout.LayoutParams(0,dp(48),1f).apply{marginStart=dp(6)})
        root.addView(tools,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(24)})
        val section=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};section.addView(tv("نسخ التطبيقات",19f,2),LinearLayout.LayoutParams(0,-2,1f));section.addView(tv("${items.count{!it.hidden}} نسخة",12f,1,textSecondary).apply{gravity=Gravity.CENTER;setPadding(dp(12),0,dp(12),0);background=rounded(surface2,14)},LinearLayout.LayoutParams(-2,dp(32)));root.addView(section,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)});listBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(listBox)
    }

    private fun primaryButton(label:String,action:()->Unit)=MaterialButton(this).apply{text=label;textSize=15f;typeface=arabicMedium;setTextColor(Color.WHITE);backgroundTintList=ColorStateList.valueOf(primary);cornerRadius=dp(18);insetTop=0;insetBottom=0;setOnClickListener{action()}}
    private fun secondaryButton(label:String,action:()->Unit)=MaterialButton(this).apply{text=label;textSize=13f;typeface=arabicMedium;setTextColor(textPrimary);backgroundTintList=ColorStateList.valueOf(surface2);cornerRadius=dp(16);insetTop=0;insetBottom=0;setOnClickListener{action()}}
    private fun compactButton(label:String,p:Boolean,action:()->Unit)=MaterialButton(this).apply{text=label;textSize=13f;typeface=arabicMedium;setTextColor(if(p)Color.WHITE else textPrimary);backgroundTintList=ColorStateList.valueOf(if(p)primary else surface2);cornerRadius=dp(14);insetTop=0;insetBottom=0;minHeight=0;setOnClickListener{action()}}

    private fun render(){
        listBox.removeAllViews();val visible=items.filter{!it.hidden}.sortedWith(compareByDescending<CloneProfile>{it.favorite}.thenBy{it.customName})
        if(visible.isEmpty()){val empty=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=rounded(surface,22,Color.rgb(45,40,57));setPadding(dp(20),dp(32),dp(20),dp(32))};empty.addView(tv("ما عندكش نسخ لحد الآن",17f,2).apply{gravity=Gravity.CENTER});empty.addView(tv("اضغط «إضافة نسخة جديدة» واختار التطبيق اللي تبي تكرره",13f,0,textSecondary).apply{gravity=Gravity.CENTER;setPadding(0,dp(9),0,0)});listBox.addView(empty);return}
        visible.forEach{c->
            val card=MaterialCardView(this).apply{radius=dp(22).toFloat();cardElevation=0f;setCardBackgroundColor(surface);strokeColor=Color.rgb(48,43,62);strokeWidth=dp(1);setContentPadding(dp(16),dp(16),dp(16),dp(14))}
            val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
            info.addView(tv((if(c.favorite)"★  "else"")+c.customName,18f,2));info.addView(tv("نسخة ${c.slot+1}  •  ${c.packageName}",11.5f,0,textSecondary).apply{setPadding(0,dp(5),0,0)});top.addView(info,LinearLayout.LayoutParams(0,-2,1f))
            val icon=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_INSIDE;setPadding(dp(5),dp(5),dp(5),dp(5));background=rounded(surface2,16);setImageDrawable(loadCloneDrawable(c))};top.addView(icon,LinearLayout.LayoutParams(dp(54),dp(54)).apply{marginStart=dp(12)});body.addView(top)
            body.addView(tv("●  ${if(c.frozen)"مجمّدة"else"جاهزة"}",11.5f,1,if(c.frozen)danger else success).apply{setPadding(0,dp(10),0,0)})
            val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(14),0,0)};actions.addView(compactButton("فتح النسخة",true){launchClone(c)},LinearLayout.LayoutParams(0,dp(46),1.5f).apply{marginEnd=dp(6)});actions.addView(compactButton("إدارة",false){manage(c)},LinearLayout.LayoutParams(0,dp(46),1f).apply{marginStart=dp(6)});body.addView(actions);card.addView(body);listBox.addView(card,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(6),0,dp(8))})
        }
        if(CairoFontManager.isReady(this))CairoFontManager.applyTo(listBox,this)
    }

    private fun launchClone(c:CloneProfile){if(c.frozen)return toast("النسخة مجمّدة");val missing=runCatching{RuntimePermissionBroker.missingForGuest(this,c.packageName)}.getOrDefault(emptyList());if(missing.isNotEmpty()){pendingLaunch=c;RuntimeDiagnostics.log("PERMISSION","request clone=${c.packageName}/${c.slot} count=${missing.size}");permissionLauncher.launch(missing.toTypedArray())}else launchNow(c)}
    private fun launchNow(c:CloneProfile){RuntimeDiagnostics.log("LAUNCH","start ${c.packageName}/${c.slot}");engine.launch(c.packageName,c.slot).onSuccess{RuntimeDiagnostics.log("LAUNCH","dispatch success ${c.packageName}/${c.slot}")}.onFailure{RuntimeDiagnostics.log("LAUNCH","failed ${c.packageName}/${c.slot}: ${it.stackTraceToString()}");toast("تعذر فتح النسخة: ${it.message?:"خطأ غير معروف"}")}}
    private fun installed():List<InstalledApp>{val pm=packageManager;return pm.getInstalledApplications(0).filter{it.packageName!=packageName&&pm.getLaunchIntentForPackage(it.packageName)!=null}.map{InstalledApp(it.packageName,pm.getApplicationLabel(it).toString())}.sortedBy{it.label.lowercase()}}
    private fun showStyled(builder:MaterialAlertDialogBuilder):AlertDialog{val d=builder.show();d.window?.decorView?.let{CairoFontManager.applyTo(it,this)};d.getButton(-1)?.setTextColor(primary);d.getButton(-2)?.setTextColor(primary);d.getButton(-3)?.setTextColor(primary);return d}

    private fun pickApp(){
        val apps=installed();if(apps.isEmpty())return toast("ما لقيناش تطبيقات قابلة للتكرار")
        val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;setPadding(dp(8),dp(6),dp(8),dp(6))}
        val rows=mutableListOf<Pair<View,InstalledApp>>()
        apps.forEach{a->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;setPadding(dp(10),dp(9),dp(10),dp(9));background=rounded(surface2,14);isClickable=true;isFocusable=true}
            val icon=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_INSIDE;setImageDrawable(runCatching{packageManager.getApplicationIcon(a.packageName)}.getOrNull());setPadding(dp(3),dp(3),dp(3),dp(3))}
            row.addView(icon,LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginEnd=dp(10)})
            val labels=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.END};labels.addView(tv(a.label,15f,1).apply{gravity=Gravity.END});labels.addView(tv(a.packageName,10.5f,0,textSecondary).apply{gravity=Gravity.END;setPadding(0,dp(3),0,0)});row.addView(labels,LinearLayout.LayoutParams(0,-2,1f));container.addView(row,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(3),0,dp(3))});rows+=row to a
        }
        val scroll=ScrollView(this).apply{isFillViewport=false;addView(container,ViewGroup.LayoutParams(-1,-2))}
        val search=TextInputEditText(this).apply{hint="ابحث باسم التطبيق أو الحزمة";setSingleLine(true);setTextColor(textPrimary);setHintTextColor(textSecondary);typeface=arabicBody;gravity=Gravity.CENTER_VERTICAL or Gravity.END;layoutDirection=View.LAYOUT_DIRECTION_RTL;background=rounded(surface2,14,Color.rgb(48,43,62));setPadding(dp(14),0,dp(14),0)}
        val noResults=tv("ما فيش نتائج مطابقة",13f,1,textSecondary).apply{gravity=Gravity.CENTER;visibility=View.GONE;setPadding(0,dp(16),0,dp(10))}
        val pickerRoot=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;setPadding(dp(8),dp(4),dp(8),0)}
        pickerRoot.addView(search,LinearLayout.LayoutParams(-1,dp(50)).apply{setMargins(0,0,0,dp(8))});pickerRoot.addView(noResults,LinearLayout.LayoutParams(-1,-2));pickerRoot.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val dialog=showStyled(MaterialAlertDialogBuilder(this).setTitle("اختر تطبيقًا مثبتًا").setView(pickerRoot).setNegativeButton("إلغاء",null))
        pickerRoot.layoutParams=pickerRoot.layoutParams.apply{height=(resources.displayMetrics.heightPixels*.72f).toInt()}
        search.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){val q=s?.toString()?.trim()?.lowercase().orEmpty();var shown=0;rows.forEach{(row,a)->val match=q.isBlank()||a.label.lowercase().contains(q)||a.packageName.lowercase().contains(q);row.visibility=if(match)View.VISIBLE else View.GONE;if(match)shown++};noResults.visibility=if(shown==0)View.VISIBLE else View.GONE};override fun afterTextChanged(s:android.text.Editable?){}})
        rows.forEach{(row,a)->row.setOnClickListener{RuntimeDiagnostics.log("UI","app selected ${a.packageName}");dialog.dismiss();createDialog(a)}}
        dialog.window?.decorView?.let{CairoFontManager.applyTo(it,this)}
    }

    private fun createDialog(a:InstalledApp){val input=TextInputEditText(this).apply{hint="اسم النسخة";setText("${a.label} ${nextSlot(a.packageName)+1}");typeface=arabicBody};showStyled(MaterialAlertDialogBuilder(this).setTitle("إنشاء نسخة").setView(input).setPositiveButton("إنشاء"){_,_->val slot=nextSlot(a.packageName);val name=input.text?.toString()?.trim().orEmpty().ifBlank{"${a.label} ${slot+1}"};RuntimeDiagnostics.log("CLONE","create ${a.packageName}/$slot");engine.createClone(a.packageName,slot).onSuccess{val x=items;x+=CloneProfile(System.currentTimeMillis(),a.packageName,a.label,name,slot);store.save(x);render();toast("تم إنشاء النسخة")}.onFailure{RuntimeDiagnostics.log("CLONE","create failed ${a.packageName}/$slot: ${it.stackTraceToString()}");toast("تعذر إنشاء النسخة: ${it.message}")}}.setNegativeButton("إلغاء",null))}
    private fun nextSlot(pkg:String)=(items.filter{it.packageName==pkg}.maxOfOrNull{it.slot}?:-1)+1

    private fun manage(c:CloneProfile){
        val opts=arrayOf(
            "إعادة تسمية",if(c.favorite)"إلغاء المفضلة"else"إضافة للمفضلة",if(c.frozen)"إلغاء التجميد"else"تجميد",if(c.hidden)"إظهار"else"إخفاء",
            "إيقاف النسخة","مسح الكاش","حجم التخزين","إدارة الصلاحيات","تغيير الأيقونة","إضافة اختصار للشاشة الرئيسية",
            "تحديث النسخة من التطبيق الأصلي","مسح البيانات","حذف النسخة"
        )
        showStyled(MaterialAlertDialogBuilder(this).setTitle(c.customName).setItems(opts){_,w->when(w){
            0->rename(c);1->update(c){it.copy(favorite=!it.favorite)};2->update(c){it.copy(frozen=!it.frozen)};3->update(c){it.copy(hidden=!it.hidden)}
            4->engine.forceStop(c.packageName,c.slot).onSuccess{toast("تم إيقاف النسخة")}.onFailure{toast("تعذر الإيقاف: ${it.message}")}
            5->engine.clearCache(c.packageName,c.slot).onSuccess{toast("تم مسح الكاش")}.onFailure{toast("تعذر مسح الكاش: ${it.message}")}
            6->showCloneSize(c);7->showPermissions(c);8->{pendingIconClone=c;iconPicker.launch("image/*")};9->pinShortcut(c)
            10->updateCloneRuntime(c);11->confirmClearData(c);12->confirmDelete(c)
        }})
    }

    private fun showCloneSize(c:CloneProfile){val bytes=runCatching{engine.cloneSizeBytes(c.packageName,c.slot)}.getOrDefault(0L);showStyled(MaterialAlertDialogBuilder(this).setTitle("حجم ${c.customName}").setMessage(formatBytes(bytes)).setPositiveButton("حسنًا",null))}
    private fun showPermissions(c:CloneProfile){
        val requested=runCatching{RuntimePermissionBroker.requestedByGuest(this,c.packageName)}.getOrDefault(emptyList())
        val lines=if(requested.isEmpty())"لا توجد صلاحيات حساسة مطلوبة." else requested.joinToString("\n"){p->"${if(checkSelfPermission(p)==android.content.pm.PackageManager.PERMISSION_GRANTED)"✓"else"○"}  $p"}
        showStyled(MaterialAlertDialogBuilder(this).setTitle("صلاحيات ${c.customName}").setMessage(lines).setPositiveButton("منح الناقص"){_,_->val missing=RuntimePermissionBroker.missingForGuest(this,c.packageName);if(missing.isEmpty())toast("كل الصلاحيات المطلوبة ممنوحة")else{pendingLaunch=c;permissionLauncher.launch(missing.toTypedArray())}}.setNegativeButton("إغلاق",null))
    }
    private fun confirmClearData(c:CloneProfile){showStyled(MaterialAlertDialogBuilder(this).setTitle("مسح بيانات النسخة؟").setMessage("سيتم حذف الحسابات والإعدادات والملفات الخاصة بهذه النسخة فقط، ولن يتم حذف التطبيق الأصلي أو النسخ الأخرى.").setPositiveButton("مسح"){_,_->engine.clearData(c.packageName,c.slot).onSuccess{toast("تم مسح بيانات النسخة")}.onFailure{toast("تعذر مسح البيانات: ${it.message?:"خطأ غير معروف"}")}}.setNegativeButton("إلغاء",null))}
    private fun updateCloneRuntime(c:CloneProfile){showStyled(MaterialAlertDialogBuilder(this).setTitle("تحديث النسخة؟").setMessage("سيتم تحديث ملفات التطبيق من النسخة الأصلية المثبتة مع الاحتفاظ ببيانات هذه النسخة. إذا فشل التحديث سيتم إرجاع الملفات القديمة تلقائيًا.").setPositiveButton("تحديث"){_,_->RuntimeDiagnostics.log("UPDATE","requested ${c.packageName}/${c.slot}");engine.updateClone(c.packageName,c.slot).onSuccess{RuntimeDiagnostics.log("UPDATE","success ${c.packageName}/${c.slot}");toast("تم تحديث النسخة مع الاحتفاظ ببياناتها")}.onFailure{RuntimeDiagnostics.log("UPDATE","failed ${c.packageName}/${c.slot}: ${it.stackTraceToString()}");toast("تعذر تحديث النسخة: ${it.message?:"خطأ غير معروف"}")}}.setNegativeButton("إلغاء",null))}
    private fun rename(c:CloneProfile){val input=TextInputEditText(this).apply{setText(c.customName);typeface=arabicBody};showStyled(MaterialAlertDialogBuilder(this).setTitle("إعادة تسمية").setView(input).setPositiveButton("حفظ"){_,_->update(c){it.copy(customName=input.text.toString().trim().ifBlank{c.customName})}}.setNegativeButton("إلغاء",null))}
    private fun update(c:CloneProfile,f:(CloneProfile)->CloneProfile){val x=items;val i=x.indexOfFirst{it.id==c.id};if(i>=0){x[i]=f(x[i]);store.save(x);render()}}
    private fun confirmDelete(c:CloneProfile){showStyled(MaterialAlertDialogBuilder(this).setTitle("حذف النسخة؟").setMessage("سيتم حذف كل بيانات هذه النسخة فقط. لن يتم حذف التطبيق الأصلي.").setPositiveButton("حذف"){_,_->engine.remove(c.packageName,c.slot).onSuccess{if(c.customIconPath.isNotBlank())File(c.customIconPath).delete();val x=items;x.removeAll{it.id==c.id};store.save(x);render();toast("تم حذف النسخة")}.onFailure{toast("تعذر حذف النسخة: ${it.message}")}}.setNegativeButton("إلغاء",null))}

    private fun loadCloneDrawable(c:CloneProfile):Drawable?{if(c.customIconPath.isNotBlank()){val b=BitmapFactory.decodeFile(c.customIconPath);if(b!=null)return BitmapDrawable(resources,b)};return runCatching{packageManager.getApplicationIcon(c.packageName)}.getOrNull()}
    private fun pinShortcut(c:CloneProfile){
        if(android.os.Build.VERSION.SDK_INT<26)return toast("اختصارات الشاشة غير مدعومة على هذا الإصدار")
        val manager=getSystemService(ShortcutManager::class.java);if(manager?.isRequestPinShortcutSupported!=true)return toast("اللانشر لا يدعم تثبيت الاختصارات")
        val launch=Intent(this,MainActivity::class.java).apply{action=ACTION_OPEN_CLONE;putExtra(EXTRA_SHORTCUT_PACKAGE,c.packageName);putExtra(EXTRA_SHORTCUT_SLOT,c.slot);addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)}
        val bitmap=drawableToBitmap(loadCloneDrawable(c))
        val info=ShortcutInfo.Builder(this,"clone_${c.id}").setShortLabel(c.customName.take(40)).setLongLabel(c.customName.take(80)).setIntent(launch).setIcon(Icon.createWithBitmap(bitmap)).build()
        if(manager.requestPinShortcut(info,null))toast("تم إرسال الاختصار للشاشة الرئيسية")else toast("تعذر إضافة الاختصار")
    }
    private fun drawableToBitmap(drawable:Drawable?):Bitmap{if(drawable is BitmapDrawable&&drawable.bitmap!=null)return drawable.bitmap;val w=(drawable?.intrinsicWidth?:128).coerceAtLeast(1);val h=(drawable?.intrinsicHeight?:128).coerceAtLeast(1);return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{b->val c=Canvas(b);drawable?.setBounds(0,0,c.width,c.height);drawable?.draw(c)}}
    private fun formatBytes(bytes:Long):String{val units=arrayOf("B","KB","MB","GB");var value=bytes.toDouble();var i=0;while(value>=1024&&i<units.lastIndex){value/=1024;i++};return "%.2f %s".format(value,units[i])}

    private fun lockNow(){val executor:Executor=ContextCompat.getMainExecutor(this);val prompt=BiometricPrompt(this,executor,object:BiometricPrompt.AuthenticationCallback(){override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){toast("تم فتح مكرّر التطبيقات")}});prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("قفل مكرّر التطبيقات").setSubtitle("استخدم البصمة أو قفل الجهاز").setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())}
    private fun openWhatsApp(){runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/218921984045")))}.onFailure{toast("تعذر فتح واتساب")}}
    private fun aboutDialog(){showStyled(MaterialAlertDialogBuilder(this).setTitle("حول مكرّر التطبيقات").setMessage("مكرّر التطبيقات • ${BuildConfig.VERSION_NAME}\nتصميم أحمد شهبون\nShahboun Clone Engine • Android 10–16").setPositiveButton("واتساب مباشر"){_,_->openWhatsApp()}.setNegativeButton("إغلاق",null))}
    private fun hiddenClonesDialog(){val hidden=items.filter{it.hidden};if(hidden.isEmpty())return toast("ما عندكش نسخ مخفية");showStyled(MaterialAlertDialogBuilder(this).setTitle("النسخ المخفية").setItems(hidden.map{it.customName}.toTypedArray()){_,i->update(hidden[i]){it.copy(hidden=false)};toast("تم إظهار النسخة")}.setNegativeButton("إغلاق",null))}
    private fun settingsDialog(){val options=arrayOf("حالة المحرك: ${engine.name}","النسخ المخفية","إعدادات البطارية","إعدادات الإشعارات","التشخيص","حول التطبيق");showStyled(MaterialAlertDialogBuilder(this).setTitle("الإعدادات").setItems(options){_,i->when(i){1->hiddenClonesDialog();2->startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));3->startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName));4->startActivity(Intent(this,DebugActivity::class.java));5->aboutDialog()}})}
    private fun requestNotifications(){if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),77)}
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()

    companion object{
        private const val ACTION_OPEN_CLONE="com.shahboun.multi.OPEN_CLONE"
        private const val EXTRA_SHORTCUT_PACKAGE="shortcut.package"
        private const val EXTRA_SHORTCUT_SLOT="shortcut.slot"
    }
}