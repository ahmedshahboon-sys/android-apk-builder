package com.shahboun.routeroptimizer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.text.InputType;
import android.widget.*;
import org.json.JSONArray;
import java.util.Locale;

/** v0.11.3 authentication hotfix.
 * Uses Huawei HiLink's token-bound password_type=4 API login first,
 * then verifies /api/user/state-login. The old DOM form is only a fallback.
 */
public class MainActivityV113 extends MainActivity {
  private boolean loginBusy=false;

  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    try{
      LinearLayout head=(LinearLayout)root.getChildAt(0);
      LinearLayout names=(LinearLayout)head.getChildAt(0);
      TextView sub=(TextView)names.getChildAt(1);
      sub.setText("Smart Router Control  •  v0.11.3");
    }catch(Exception ignored){}
  }

  @Override void autoLogin(){
    // Do not click the vendor WebUI form automatically. Huawei HiLink login
    // is performed through /api/user/login so the API session and tokens match.
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
      .setMessage("اسم المستخدم: admin\nسيتم الدخول مباشرة إلى API الراوتر.")
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
      status.setText("جارٍ تسجيل الدخول إلى Huawei HiLink...");
      apiLogin(d);
    }));
    d.show();
  }

  private void apiLogin(AlertDialog d){
    String p=password.replace("\\","\\\\").replace("'","\\'").replace("\n","");
    String js="javascript:(async()=>{try{"
      +"const txt=async(u,o)=>{let r=await fetch(u,Object.assign({credentials:'same-origin',cache:'no-store'},o||{}));return await r.text()};"
      +"const tag=(x,n)=>{let m=x.match(new RegExp('<'+n+'>(.*?)<\\\\/'+n+'>','i'));return m?m[1]:''};"
      +"const shaHex=async(s)=>{let b=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(s));return Array.from(new Uint8Array(b)).map(x=>x.toString(16).padStart(2,'0')).join('')};"
      +"const b64sha=async(s)=>btoa(await shaHex(s));"
      +"let st=await txt('/api/user/state-login');if(tag(st,'State')==='0')return 'OK|ALREADY|'+st;"
      +"let ses=await txt('/api/webserver/SesTokInfo');let tok=tag(ses,'TokInfo');if(!tok)return 'FAIL|NO_TOKEN|'+ses;"
      +"let inner=await b64sha('"+p+"');let pwd=await b64sha('admin'+inner+tok);"
      +"let body='<?xml version=\"1.0\" encoding=\"UTF-8\"?><request><Username>admin</Username><Password>'+pwd+'</Password><password_type>4</password_type></request>';"
      +"let lr=await txt('/api/user/login',{method:'POST',headers:{'Content-Type':'application/xml','__RequestVerificationToken':tok,'X-Requested-With':'XMLHttpRequest'},body:body});"
      +"let st2=await txt('/api/user/state-login');let state=tag(st2,'State');"
      +"if(lr.indexOf('<response>OK</response>')>=0||state==='0'||lr.indexOf('108003')>=0)return 'OK|'+lr+'|'+st2;"
      +"return 'FAIL|'+lr+'|'+st2;"
      +"}catch(e){return 'NETERR|'+e}})()";

    web.evaluateJavascript(js,val->{
      String out=decode(val);
      loginBusy=false;
      String low=out.toLowerCase(Locale.US);
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
        return;
      }
      if(low.contains("108002")||low.contains("108006")){
        Toast.makeText(this,"رمز دخول الراوتر غير صحيح",Toast.LENGTH_LONG).show();
        status.setText("رمز الدخول غير صحيح");
        return;
      }
      if(low.contains("108007")){
        Toast.makeText(this,"محاولات دخول كثيرة. انتظر قليلًا ثم حاول من جديد.",Toast.LENGTH_LONG).show();
        status.setText("الراوتر أوقف محاولات الدخول مؤقتًا");
        return;
      }
      if(low.contains("100002")||low.contains("no_token")||low.contains("neterr")){
        status.setText("طريقة API غير متاحة • نجرب واجهة الراوتر");
        fallbackFormLogin(d);
        return;
      }
      Toast.makeText(this,"فشل الدخول. تحقق من الرمز ثم أعد المحاولة.",Toast.LENGTH_LONG).show();
      status.setText("تعذر تسجيل الدخول إلى الإدارة");
    });
  }

  private void fallbackFormLogin(AlertDialog d){
    loginBusy=true;
    web.loadUrl(baseUrl+"/");
    h.postDelayed(()->{
      String p=password.replace("\\","\\\\").replace("'","\\'");
      String js="javascript:(()=>{try{let u=document.querySelector('input[name=username],#username,input[type=text]');let pw=document.querySelector('input[name=password],#password,input[type=password]');if(!pw)return 'noform';if(u)u.value='admin';pw.value='"+p+"';let b=document.querySelector('#login_btn,#loginbutton,button[type=submit],input[type=submit],.login_button,.btn_login');if(b){b.click();return 'clicked'}return 'nobutton'}catch(e){return 'err'}})()";
      web.evaluateJavascript(js,r->h.postDelayed(()->verifyState(d),1400));
    },700);
  }

  private void verifyState(AlertDialog d){
    String js="javascript:(async()=>{try{let r=await fetch('/api/user/state-login',{credentials:'same-origin',cache:'no-store'});let t=await r.text();let m=t.match(/<State>(.*?)<\\/State>/i);return (m&&m[1]==='0')?'OK|'+t:'FAIL|'+t}catch(e){return 'NETERR|'+e}})()";
    web.evaluateJavascript(js,v->{
      loginBusy=false;
      String out=decode(v);
      if(out.startsWith("OK|")){
        authenticated=true;huawei=true;vendor="Huawei / HiLink";driver="HuaweiHiLink Deep";
        if(d!=null&&d.isShowing())d.dismiss();
        status.setText("تم الدخول • Huawei HiLink Deep Driver");
        showPage(0);refreshAll();
      }else{
        Toast.makeText(this,"رمز الدخول غير صحيح أو لم تقبل واجهة الراوتر الجلسة",Toast.LENGTH_LONG).show();
        status.setText("فشل تسجيل الدخول");
      }
    });
  }

  @Override void verify(AlertDialog d){
    verifyState(d);
  }

  @Override void about(){
    new AlertDialog.Builder(this).setTitle("Shahboun Router v0.11.3")
      .setMessage("Huawei HiLink Authentication Hotfix + Deep Driver + Universal Router Core\n\n• API login password_type=4\n• Session/token verification\n• Live dashboard كل ثانيتين\n• Direct Band Manager\n• Differential live traffic\n• Speed history + Best Band\n• SIM Lock diagnostics\n• API/Firmware Explorer\n• Safe Restore\n\nتصميم وتطوير: أحمد شهبون\n0921984045")
      .setPositiveButton("حسنًا",null).show();
  }
}
