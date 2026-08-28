package com.shahboun.multi

import android.app.*
import android.os.Bundle
import android.content.*
import android.graphics.Typeface
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executor

class MainActivity:AppCompatActivity(){
    private lateinit var root:LinearLayout; private lateinit var listBox:LinearLayout; private lateinit var store:CloneStore
    private val items get()=store.list(); private val engine get()=(application as MultiApplication).engine
    private val cairo by lazy{ Typeface.create("Cairo",Typeface.NORMAL) }
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);store=CloneStore(this);buildUi();requestNotifications();render()}
    private fun tv(s:String,size:Float=16f)=TextView(this).apply{text=s;textSize=size;typeface=cairo;setPadding(8,8,8,8)}
    private fun buildUi(){
        val scroll=ScrollView(this); root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,28,24,40)};scroll.addView(root);setContentView(scroll)
        root.addView(tv("Shahboun Multi",28f));root.addView(tv("محرك: ${engine.name}",13f))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        row.addView(MaterialButton(this).apply{text="+ إضافة نسخة";typeface=cairo;setOnClickListener{pickApp()}},LinearLayout.LayoutParams(0,-2,1f))
        row.addView(MaterialButton(this).apply{text="قفل";typeface=cairo;setOnClickListener{lockNow()}},LinearLayout.LayoutParams(0,-2,1f));root.addView(row)
        val tools=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        tools.addView(MaterialButton(this).apply{text="إغلاق الكل";typeface=cairo;setOnClickListener{toast("تم إرسال أمر الإغلاق للنسخ المدارة عند توفر المحرك")}},LinearLayout.LayoutParams(0,-2,1f))
        tools.addView(MaterialButton(this).apply{text="إعدادات";typeface=cairo;setOnClickListener{settingsDialog()}},LinearLayout.LayoutParams(0,-2,1f));root.addView(tools)
        listBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(listBox)
    }
    private fun render(){listBox.removeAllViews();val visible=items.filter{!it.hidden}.sortedWith(compareByDescending<CloneProfile>{it.favorite}.thenBy{it.customName});if(visible.isEmpty())listBox.addView(tv("لا توجد نسخ بعد. اضغط «إضافة نسخة»."));visible.forEach{c->
        val card=MaterialCardView(this).apply{radius=22f;setContentPadding(18,14,18,14)}; val b=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        b.addView(tv("${if(c.favorite)"★ " else ""}${c.customName}",19f));b.addView(tv("${c.packageName}  •  نسخة ${c.slot}${if(c.frozen)"  •  مجمّدة" else ""}",12f))
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun btn(label:String,action:()->Unit)=MaterialButton(this).apply{text=label;typeface=cairo;setOnClickListener{action()}}
        actions.addView(btn("فتح"){if(c.frozen)toast("النسخة مجمّدة") else engine.launch(c.packageName,c.slot).onFailure{toast(it.message?:"تعذر الفتح")}},LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(btn("إدارة"){manage(c)},LinearLayout.LayoutParams(0,-2,1f));b.addView(actions);card.addView(b);listBox.addView(card,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,10,0,10)})
    }}
    private fun installed():List<InstalledApp>{ val pm=packageManager; return pm.getInstalledApplications(0).filter{it.packageName!=packageName && pm.getLaunchIntentForPackage(it.packageName)!=null}.map{InstalledApp(it.packageName,pm.getApplicationLabel(it).toString())}.sortedBy{it.label.lowercase()} }
    private fun pickApp(){val apps=installed();val names=apps.map{"${it.label}\n${it.packageName}"}.toTypedArray();MaterialAlertDialogBuilder(this).setTitle("اختر تطبيقًا مثبتًا").setItems(names){_,i->createDialog(apps[i])}.setNegativeButton("إلغاء",null).show()}
    private fun createDialog(app:InstalledApp){val input=TextInputEditText(this).apply{hint="اسم النسخة";setText("${app.label} ${nextSlot(app.packageName)}");typeface=cairo};MaterialAlertDialogBuilder(this).setTitle("إنشاء نسخة").setView(input).setPositiveButton("إنشاء"){_,_->
        val slot=nextSlot(app.packageName); val name=input.text?.toString()?.trim().orEmpty().ifBlank{"${app.label} $slot"};
        engine.createClone(app.packageName,slot).onSuccess{val x=items;x+=CloneProfile(System.currentTimeMillis(),app.packageName,app.label,name,slot);store.save(x);render();toast("تم إنشاء النسخة")}.onFailure{toast("محرك الاستنساخ غير مدمج بعد: ${it.message}")}
    }.setNegativeButton("إلغاء",null).show()}
    private fun nextSlot(pkg:String)=(items.filter{it.packageName==pkg}.maxOfOrNull{it.slot}?:-1)+1
    private fun manage(c:CloneProfile){val opts=arrayOf("إعادة تسمية","${if(c.favorite)"إلغاء المفضلة" else "إضافة للمفضلة"}","${if(c.frozen)"إلغاء التجميد" else "تجميد"}","${if(c.hidden)"إظهار" else "إخفاء"}","مسح البيانات","حذف النسخة");MaterialAlertDialogBuilder(this).setTitle(c.customName).setItems(opts){_,w->when(w){0->rename(c);1->update(c){it.copy(favorite=!it.favorite)};2->update(c){it.copy(frozen=!it.frozen)};3->update(c){it.copy(hidden=!it.hidden)};4->engine.clearData(c.packageName,c.slot).onFailure{toast(it.message?:"تعذر مسح البيانات")};5->confirmDelete(c)}}.show()}
    private fun rename(c:CloneProfile){val i=TextInputEditText(this).apply{setText(c.customName);typeface=cairo};MaterialAlertDialogBuilder(this).setTitle("إعادة تسمية").setView(i).setPositiveButton("حفظ"){_,_->update(c){it.copy(customName=i.text.toString())}}.setNegativeButton("إلغاء",null).show()}
    private fun update(c:CloneProfile,f:(CloneProfile)->CloneProfile){val x=items;val idx=x.indexOfFirst{it.id==c.id};if(idx>=0){x[idx]=f(x[idx]);store.save(x);render()}}
    private fun confirmDelete(c:CloneProfile){MaterialAlertDialogBuilder(this).setTitle("حذف النسخة؟").setMessage("لن يتم حذف التطبيق الأصلي.").setPositiveButton("حذف"){_,_->engine.remove(c.packageName,c.slot);val x=items;x.removeAll{it.id==c.id};store.save(x);render()}.setNegativeButton("إلغاء",null).show()}
    private fun lockNow(){val executor:Executor=ContextCompat.getMainExecutor(this);val p=BiometricPrompt(this,executor,object:BiometricPrompt.AuthenticationCallback(){override fun onAuthenticationSucceeded(r:BiometricPrompt.AuthenticationResult){toast("تم فتح Shahboun Multi")}});p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("قفل Shahboun Multi").setSubtitle("استخدم البصمة أو قفل الجهاز").setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())}
    private fun settingsDialog(){MaterialAlertDialogBuilder(this).setTitle("الإعدادات").setItems(arrayOf("حالة المحرك: ${engine.name}","إعدادات البطارية","إعدادات الإشعارات","حول التطبيق")){_,i->when(i){1->startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));2->startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName));3->toast("Development Build 0.1.0 • Android 10–16")}}.show()}
    private fun requestNotifications(){if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),77)}
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
