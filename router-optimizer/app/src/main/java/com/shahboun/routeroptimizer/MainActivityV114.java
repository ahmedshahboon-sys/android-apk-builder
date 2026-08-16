package com.shahboun.routeroptimizer;

import android.app.*;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import java.util.Locale;

/** v0.11.4 auth rollback/fix.
 * Restores the working WebUI-session login flow used by the early versions,
 * while keeping the newer Deep Driver features from MainActivity.
 */
public class MainActivityV114 extends MainActivity {
  private boolean loginBusy=false;

  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    try{
      LinearLayout head=(LinearLayout)root.getChildAt(0);
      LinearLayout names=(LinearLayout)head.getChildAt(0);
      TextView sub=(TextView)names.getChildAt(1);
      sub.setText("Smart Router Control  •  v0.11.4");
    }catch(Exception ignored){}
  }

  @Override void loginDialog(){
    if(phoneMode || loginBusy)return;
    LinearLayout box=new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(20),dp(5),dp(20),0);
    EditText user=new EditText(this);
    user.setText("admin");
    user.setEnabled(false);
    box.addView(user,new LinearLayout.LayoutParams(-1,dp(52)));
    EditText pass=new EditText(this);
    pass.setText(password);
    pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    box.addView(pass,new LinearLayout.LayoutParams(-1,dp(52)));

    AlertDialog d=new AlertDialog.Builder(this)
      .setTitle("دخول الراوتر")
      .setMessage("اسم المستخدم: admin\nسيتم استخدام نفس تسجيل الدخول الذي تقبله واجهة الراوتر الأصلية.")
      .setView(box)
      .setPositiveButton("اتصال",null)
      .create();
    d.setCancelable(false);
    d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      password=pass.getText().toString();
      if(password==null||password.isEmpty())password="admin";
      prefs.edit().putString("routerPassword",password).apply();
      authenticated=false;
      loginBusy=true;
      status.setText("جارٍ تسجيل الدخول عبر واجهة الراوتر...");
      web.loadUrl(baseUrl+"/");
      h.postDelayed(()->performWebUiLogin(d),850);
    }));
    d.show();
  }

  @Override void autoLogin(){
    // Login is controlled explicitly by performWebUiLogin() so we do not
    // accidentally submit the form twice on firmwares with slow page loads.
  }

  private void performWebUiLogin(AlertDialog d){
    String p=password.replace("\\","\\\\").replace("'","\\'");
    String js="javascript:(()=>{try{"
      +"let u=document.querySelector('input[name=username],#username,input[id*=user i],input[type=text]');"
      +"let pw=document.querySelector('input[name=password],#password,input[id*=pass i],input[type=password]');"
      +"if(!pw)return 'NOFORM';"
      +"if(u)u.value='admin';pw.focus();pw.value='"+p+"';"
      +"pw.dispatchEvent(new Event('input',{bubbles:true}));pw.dispatchEvent(new Event('change',{bubbles:true}));"
      +"let b=document.querySelector('#login_btn,#loginbutton,#loginBtn,button[type=submit],input[type=submit],.login_button,.btn_login,[onclick*=login i]');"
      +"if(b){b.click();return 'CLICKED'}"
      +"let f=pw.form;if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();return 'SUBMITTED'}"
      +"return 'NOBUTTON'}catch(e){return 'ERR|'+e}})()";
    web.evaluateJavascript(js,r->h.postDelayed(()->verifyWebUiSession(d),2200));
  }

  private void verifyWebUiSession(AlertDialog d){
    String js="javascript:(async()=>{try{"
      +"let hasPass=!!document.querySelector('input[name=password],#password,input[id*=pass i],input[type=password]');"
      +"const g=async u=>{try{return await (await fetch(u,{credentials:'same-origin',cache:'no-store'})).text()}catch(e){return ''}};"
      +"let a=await g('/api/device/information');let b=await g('/api/device/signal');let c=await g('/api/net/net-mode');let s=await g('/api/user/state-login');"
      +"let deep=/<DeviceName>|<SerialNumber>|<rsrp>|<NetworkMode>/i.test(a+b+c);"
      +"let st=(s.match(/<State>(.*?)<\\/State>/i)||[])[1];"
      +"if(deep||st==='0'||!hasPass)return 'OK|'+st+'|'+a+'|'+b+'|'+c;"
      +"return 'FAIL|FORM_STILL_VISIBLE|'+s"
      +"}catch(e){return 'ERR|'+e}})()";
    web.evaluateJavascript(js,v->{
      loginBusy=false;
      String out=decode(v);
      if(out.startsWith("OK|")){
        authenticated=true;
        huawei=true;
        vendor="Huawei / HiLink";
        driver="HuaweiHiLink Deep";
        prefs.edit().putBoolean("huawei",true).apply();
        if(d!=null&&d.isShowing())d.dismiss();
        status.setText("تم الدخول • Huawei HiLink Deep Driver");
        showPage(0);
        refreshAll();
      }else{
        Toast.makeText(this,"رمز الدخول غير صحيح أو لم تكتمل جلسة الراوتر",Toast.LENGTH_LONG).show();
        status.setText("فشل تسجيل الدخول");
      }
    });
  }

  @Override void verify(AlertDialog d){ verifyWebUiSession(d); }

  @Override void about(){
    new AlertDialog.Builder(this).setTitle("Shahboun Router v0.11.4")
      .setMessage("WebUI Session Login Restored + Deep Huawei HiLink Driver + Universal Core\n\n• استعادة أسلوب الدخول الذي كان يعمل في النسخ الأولى\n• Deep API بعد إنشاء جلسة الراوتر الأصلية\n• Live dashboard\n• Band Manager\n• Speed history + Best Band\n• SIM diagnostics\n• API/Firmware Explorer\n• Safe Restore\n\nتصميم وتطوير: أحمد شهبون\n0921984045")
      .setPositiveButton("حسنًا",null).show();
  }
}
