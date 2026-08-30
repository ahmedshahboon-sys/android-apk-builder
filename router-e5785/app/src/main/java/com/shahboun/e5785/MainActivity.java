package com.shahboun.e5785;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    final Handler h = new Handler(Looper.getMainLooper());
    final String BASE="http://192.168.8.1";
    final int BG=Color.rgb(15,18,24), SURF=Color.rgb(28,33,42), SURF2=Color.rgb(36,43,54),
            TEXT=Color.rgb(248,249,251), MUTED=Color.rgb(168,178,192), ACC=Color.rgb(44,207,183),
            BLUE=Color.rgb(74,144,245), GREEN=Color.rgb(77,201,126), AMBER=Color.rgb(245,177,66), RED=Color.rgb(238,92,92);
    final Map<String,TextView> fields=new LinkedHashMap<>();
    final Map<String,String> values=new HashMap<>();
    final Map<String,String> capState=new LinkedHashMap<>();
    SharedPreferences prefs;
    LinearLayout root,body,nav; TextView status; WebView web;
    boolean logged=false, live=false, loginBusy=false;
    long prevDl=-1,prevUl=-1,prevTs=0;
    String password="admin";
    int currentPage=0;

    final String[][] PROBES = {
      {"معلومات الجهاز","/api/device/information"},{"معلومات الجهاز البديلة","/api/device/basic_information"},
      {"حالة الاتصال","/api/monitoring/status"},{"إحصائيات البيانات","/api/monitoring/traffic-statistics"},
      {"الإشارة","/api/device/signal"},{"أفضل إشارة","/api/monitoring/best-signal"},
      {"المشغل","/api/net/current-plmn"},{"وضع الشبكة","/api/net/net-mode"},
      {"قائمة أوضاع الشبكة","/api/net/net-mode-list"},{"حدود الشبكة","/api/net/net-mode-limit"},
      {"حالة PIN","/api/pin/status"},{"حالة SIM Lock","/api/pin/simlock"},
      {"عدد SMS","/api/sms/sms-count"},{"الأجهزة Wi‑Fi","/api/wlan/host-list"},
      {"إعداد Wi‑Fi","/api/wlan/basic-settings"},{"إعداد Wi‑Fi الجديد","/api/ntwk/WlanBasic"},
      {"شبكة الضيوف","/api/ntwk/guest_network"},{"WPS","/api/ntwk/wlanwps"},
      {"فلترة MAC","/api/ntwk/macfilter"},{"QoS","/api/app/qos"},
      {"QoS للأجهزة","/api/app/qosclass_host"},{"Firewall","/api/ntwk/firewall"},
      {"التحكم الأبوي","/api/ntwk/parentControl"},{"Repeater","/api/ntwk/repeaterstate"},
      {"تشخيص سريع","/api/monitoring/onekey_diag"},{"تشخيص الإنترنت","/api/system/diagnose_internet"},
      {"البطارية المتبقية","/api/monitoring/get-battery-remain"},{"إحصائيات الشهر","/api/monitoring/month_statistics"},
      {"إحصائيات يومية","/api/monitoring/get-statistic-by-day"},{"Speed Test داخلي","/api/app/speedtest"},
      {"تحديث النظام","/api/online-update/status"},{"MIMO","/api/device/mimo-setting"},
      {"طاقة تلقائية","/api/device/autopoweroff"},{"Sleep","/api/device/sleep-time"},
      {"سطوع الشاشة","/api/device/screenlight"},{"USSD","/api/ussd/status"},
      {"APN","/api/dialup/profiles"},{"بيانات الهاتف","/api/dialup/mobile-dataswitch"},
      {"Topology","/api/device/topology"},{"SD Card","/api/sdcard/sdcapacity"},
      {"Bluetooth","/api/bluetooth/feature-switch"},{"Capabilities","/api/system/devcapacity"},
      {"Module Switch","/api/global/module-switch"},{"Device Features","/api/device/device-feature-switch"},
      {"Network Features","/api/net/net-feature-switch"},{"Web Features","/api/user/web-feature-switch"}
    };

    @Override public void onCreate(Bundle b){
      super.onCreate(b);
      prefs=getSharedPreferences("e5785clean",MODE_PRIVATE);
      password=prefs.getString("password","admin");
      getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
      build(); web.loadUrl(BASE+"/"); h.postDelayed(this::loginDialog,700);
    }
    int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    GradientDrawable shape(int c,int r){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));g.setStroke(dp(1),Color.rgb(55,65,78));return g;}
    TextView text(String s,int z,int c){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c);t.setGravity(Gravity.RIGHT);t.setPadding(dp(10),dp(9),dp(10),dp(9));return t;}
    TextView btn(String s,int color,View.OnClickListener l){TextView b=text(s,14,TEXT);b.setGravity(Gravity.CENTER);b.setBackground(shape(color,18));b.setOnClickListener(l);return b;}
    TextView btn(String s,View.OnClickListener l){return btn(s,SURF2,l);}
    void build(){
      root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setFitsSystemWindows(true);
      LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(18),dp(12),dp(14),dp(8));
      LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);
      TextView title=text("Shahboun Router",26,TEXT);title.setPadding(0,0,0,0);names.addView(title);
      TextView sub=text("Huawei E5785Lh-22c Edition  •  v1.1.0",11,MUTED);sub.setPadding(0,2,0,0);names.addView(sub);
      head.addView(names,new LinearLayout.LayoutParams(0,-2,1));
      head.addView(btn("↻",SURF2,v->refreshAll()),new LinearLayout.LayoutParams(dp(52),dp(52)));
      root.addView(head);
      status=text("جارٍ الاتصال براوتر Huawei…",12,TEXT);status.setGravity(Gravity.CENTER);status.setBackgroundColor(Color.rgb(22,27,35));root.addView(status,new LinearLayout.LayoutParams(-1,dp(46)));
      ScrollView sc=new ScrollView(this);sc.setFillViewport(true);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),dp(10),dp(14),dp(30));sc.addView(body);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
      web=new WebView(this);WebSettings ws=web.getSettings();ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);ws.setCacheMode(WebSettings.LOAD_NO_CACHE);web.setWebChromeClient(new WebChromeClient());web.setWebViewClient(new WebViewClient());web.setVisibility(View.GONE);root.addView(web,new LinearLayout.LayoutParams(1,1));
      nav=new LinearLayout(this);nav.setBackgroundColor(Color.rgb(20,24,31));
      String[] ns={"الرئيسية","الشبكة","الأجهزة","الرسائل","الأدوات"};
      for(int i=0;i<ns.length;i++){final int p=i;TextView x=text(ns[i],11,MUTED);x.setGravity(Gravity.CENTER);x.setOnClickListener(v->page(p));nav.addView(x,new LinearLayout.LayoutParams(0,dp(62),1));}
      root.addView(nav);setContentView(root);page(0);
    }
    void clear(){body.removeAllViews();fields.clear();}
    void section(String a,String b){TextView x=text(a,20,TEXT);x.setPadding(2,dp(14),2,0);body.addView(x);TextView y=text(b,11,MUTED);y.setPadding(2,0,2,dp(8));body.addView(y);}
    void card(String[][] rows){
      LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(8),dp(12),dp(8));c.setBackground(shape(SURF,20));
      for(String[] r:rows){LinearLayout q=new LinearLayout(this);q.setGravity(Gravity.CENTER_VERTICAL);TextView l=text(r[0],12,MUTED);TextView v=text(values.getOrDefault(r[1],"—"),12,TEXT);v.setGravity(Gravity.LEFT);fields.put(r[1],v);q.addView(l,new LinearLayout.LayoutParams(0,-2,1));q.addView(v,new LinearLayout.LayoutParams(0,-2,1));c.addView(q);}
      LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(10));body.addView(c,p);
    }
    void grid(String[] labels,View.OnClickListener[] actions){
      for(int i=0;i<labels.length;i+=2){LinearLayout r=new LinearLayout(this);r.setPadding(0,dp(4),0,dp(4));r.addView(btn(labels[i],actions[i]),new LinearLayout.LayoutParams(0,dp(60),1));Space s=new Space(this);r.addView(s,new LinearLayout.LayoutParams(dp(8),1));if(i+1<labels.length)r.addView(btn(labels[i+1],actions[i+1]),new LinearLayout.LayoutParams(0,dp(60),1));body.addView(r);}
    }
    void set(String k,String v){if(v==null||v.trim().isEmpty())return;values.put(k,v.trim());TextView t=fields.get(k);if(t!=null)t.setText(v.trim());}
    void page(int p){currentPage=p;live=false;clear();if(p==0)home();else if(p==1)networkPage();else if(p==2)devicesPage();else if(p==3)messagesPage();else toolsPage();}
    void home(){
      section("لوحة الحالة","قراءات حية كل ثانيتين من HiLink");
      card(new String[][]{{"الموديل","model"},{"إصدار النظام","firmware"},{"المشغل","operator"},{"نوع الشبكة","network"},{"Band","band"},{"RSRP","rsrp"},{"RSRQ","rsrq"},{"SINR","sinr"},{"RSSI","rssi"},{"البطارية","battery"}});
      section("الأداء والاستهلاك","السرعة اللحظية بدون اختبار بيانات");
      card(new String[][]{{"تنزيل لحظي","liveDl"},{"رفع لحظي","liveUl"},{"إجمالي تنزيل","totalDl"},{"إجمالي رفع","totalUl"},{"مدة الاتصال","uptime"},{"أجهزة Wi‑Fi","hosts"},{"WAN IP","wanip"},{"SSID","ssid"}});
      grid(new String[]{"تشخيص ذكي","اختبار سرعة","فحص القدرات","أفضل تردد","إعادة اتصال","إعادة تشغيل"},new View.OnClickListener[]{v->smartDiag(),v->speedTest(),v->probe(),v->showBest(),v->reconnect(),v->reboot()});
      live=true;tick();if(logged)refreshAll();
    }
    void networkPage(){
      section("الشبكة والترددات","تحكم متقدم مع Snapshot واسترجاع");
      card(new String[][]{{"NetworkMode","netMode"},{"NetworkBand","netBand"},{"LTEBand","lteBand"},{"Cell ID","cell"},{"PCI","pci"},{"EARFCN","earfcn"},{"MIMO","mimo"},{"أفضل إعداد","bestBand"}});
      String[] names={"AUTO","B1","B3","B7","B8","B20","B3+B7","B3+B20","B7+B20","B3+B7+B20"};
      String[] masks={"20800800D5","1","4","40","80","80000","44","80004","80040","80044"};
      View.OnClickListener[] aa=new View.OnClickListener[names.length];for(int i=0;i<names.length;i++){final int j=i;aa[i]=v->applyBand(names[j],masks[j],j==0?"00":"03");}grid(names,aa);
      grid(new String[]{"حفظ Snapshot","استرجاع Snapshot","Benchmark تلقائي","قائمة الترددات المدعومة","بحث الشبكات","MIMO"},new View.OnClickListener[]{v->snapshot(),v->restoreBand(),v->benchmark(),v->showEndpoint("/api/net/net-mode-list","الترددات المدعومة"),v->showEndpoint("/api/net/plmn-list","الشبكات المتاحة"),v->showEndpoint("/api/device/mimo-setting","MIMO")});
      if(logged)refreshAll();
    }
    void devicesPage(){
      section("Wi‑Fi والأجهزة","إدارة الشبكة والمستخدمين");
      card(new String[][]{{"SSID","ssid"},{"عدد الأجهزة","hosts"},{"Wi‑Fi Users","wifiUsers"},{"Guest Wi‑Fi","guest"},{"WPS","wps"},{"Repeater","repeater"}});
      grid(new String[]{"الأجهزة المتصلة","Wi‑Fi","شبكة الضيوف","WPS","حظر / سماح MAC","QoS","Firewall","التحكم الأبوي","Wi‑Fi Repeater","Wi‑Fi Scan"},new View.OnClickListener[]{v->hostsDialog(),v->wifiDialog(),v->showEndpoint("/api/ntwk/guest_network","Guest Wi‑Fi"),v->showEndpoint("/api/ntwk/wlanwps","WPS"),v->showEndpoint("/api/ntwk/macfilter","MAC Filter"),v->qosDialog(),v->showEndpoint("/api/ntwk/firewall","Firewall"),v->showEndpoint("/api/ntwk/parentControl","Parental Control"),v->showEndpoint("/api/ntwk/repeaterstate","Repeater"),v->showEndpoint("/api/ntwk/wifiscan","Wi‑Fi Scan")});
      if(logged)refreshAll();
    }
    void messagesPage(){
      section("الرسائل والشريحة","SMS وUSSD وAPN وPIN");
      card(new String[][]{{"SIM","sim"},{"PIN","pin"},{"Network Lock","simlock"},{"SMS","sms"},{"APN","apn"},{"بيانات الهاتف","mobileData"}});
      grid(new String[]{"صندوق SMS","إرسال SMS","USSD","APN","PIN / PUK","حالة SIM Lock","تشغيل/إيقاف البيانات","اختيار المشغل"},new View.OnClickListener[]{v->smsInbox(),v->smsSend(),v->ussdDialog(),v->apnDialog(),v->simInfo(),v->showEndpoint("/api/pin/simlock","SIM Lock"),v->showEndpoint("/api/dialup/mobile-dataswitch","Mobile Data"),v->showEndpoint("/api/net/plmn-list","الشبكات المتاحة")});
    }
    void toolsPage(){
      section("أدوات متقدمة","ميزات مخفية تظهر حسب Firmware جهازك");
      card(new String[][]{{"القدرات","caps"},{"التشخيص","diag"},{"البطارية المتبقية","batteryRemain"},{"تحديث النظام","update"},{"SD Card","sd"},{"Speed Test","speed"}});
      grid(new String[]{"فحص القدرات الكامل","تشخيص One‑Key","تشخيص الإنترنت","سجل الاستهلاك","البطارية والطاقة","تحديث Firmware","Topology","SD Card","Speed Test داخلي","إعدادات الشاشة","API Explorer","واجهة الراوتر","استعادة المصنع","حول التطبيق"},new View.OnClickListener[]{
        v->probe(),v->showEndpoint("/api/monitoring/onekey_diag","One‑Key Diagnostic"),v->showEndpoint("/api/system/diagnose_internet","Internet Diagnostic"),
        v->trafficHistory(),v->powerDialog(),v->showEndpoint("/api/online-update/status","Firmware Update"),v->showEndpoint("/api/device/topology","Topology"),v->showEndpoint("/api/sdcard/sdcapacity","SD Card"),
        v->showEndpoint("/api/app/speedtest","Internal Speed Test"),v->showEndpoint("/api/device/screenlight","Screen"),v->apiExplorer(),v->showWeb(),v->factoryReset(),v->about()
      });
    }
    void tick(){if(!live)return;if(logged)refreshAll();h.postDelayed(this::tick,2000);}

    void loginDialog(){
      if(loginBusy||isFinishing())return;loginBusy=true;
      LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(20),0,dp(20),0);
      EditText u=new EditText(this);u.setText("admin");u.setEnabled(false);b.addView(u);
      EditText p=new EditText(this);p.setText(password);p.setHint("رمز إدارة الراوتر");p.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);b.addView(p);
      AlertDialog d=new AlertDialog.Builder(this).setTitle("دخول Huawei E5785Lh-22c").setMessage("اسم المستخدم admin — التطبيق سيستخدم جلسة HiLink الأصلية.").setView(b).setPositiveButton("اتصال",null).create();
      d.setCancelable(false);d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{password=p.getText().toString();if(password.isEmpty())password="admin";prefs.edit().putString("password",password).apply();status.setText("جارٍ تسجيل الدخول…");web.loadUrl(BASE+"/");h.postDelayed(()->webLogin(d),1000);}));d.show();
    }
    void webLogin(AlertDialog d){
      String p=esc(password);String js="javascript:(()=>{try{let u=document.querySelector('input[name=username],#username,input[type=text]');let pw=document.querySelector('input[name=password],#password,input[type=password]');if(!pw)return 'NOFORM';if(u)u.value='admin';pw.value='"+p+"';pw.dispatchEvent(new Event('input',{bubbles:true}));pw.dispatchEvent(new Event('change',{bubbles:true}));let b=document.querySelector('#login_btn,#loginbutton,#loginBtn,button[type=submit],input[type=submit],.login_button,.btn_login,[onclick*=login i]');if(b){b.click();return 'CLICK'}if(pw.form){pw.form.submit();return 'SUBMIT'}return 'NOBUTTON'}catch(e){return 'ERR'}})()";
      web.evaluateJavascript(js,r->h.postDelayed(()->verifyLogin(d),2300));
    }
    void verifyLogin(AlertDialog d){
      apiGet("/api/device/information",x->{loginBusy=false;if(bad(x)){status.setText("فشل الدخول — تحقق من رمز الإدارة");Toast.makeText(this,"رمز الإدارة غير صحيح أو الجلسة لم تُقبل.",Toast.LENGTH_LONG).show();return;}
      logged=true;if(d.isShowing())d.dismiss();set("model",pick(tagAny(x,"DeviceName","device_name","Classify"),"E5785Lh-22c"));status.setText("متصل • Huawei E5785Lh-22c / HiLink");probe();refreshAll();});
    }

    String esc(String s){return s.replace("\\","\\\\").replace("'","\\'").replace("\n","");}
    String decode(String s){if(s==null||s.equals("null"))return "";if(s.startsWith("\"")&&s.endsWith("\""))s=s.substring(1,s.length()-1);return s.replace("\\u003C","<").replace("\\u003E",">").replace("\\\"","\"").replace("\\n","\n").replace("\\/","/").replace("\\\\","\\");}
    interface CB{void ok(String x);}
    void apiGet(String path,CB cb){String js="javascript:(async()=>{try{let r=await fetch('"+path+"',{credentials:'same-origin',cache:'no-store'});return await r.text()}catch(e){return '<error>'+e+'</error>'}})()";web.evaluateJavascript(js,v->cb.ok(decode(v)));}
    void apiPost(String path,String xml,CB cb){
      String e=esc(xml);String js="javascript:(async()=>{try{let s=await (await fetch('/api/webserver/SesTokInfo',{credentials:'same-origin',cache:'no-store'})).text();let m=s.match(/<TokInfo>(.*?)<\\/TokInfo>/i);if(!m)return '<error>NO_TOKEN</error>';let r=await fetch('"+path+"',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/xml','__RequestVerificationToken':m[1]},body:'"+e+"'});return await r.text()}catch(e){return '<error>'+e+'</error>'}})()";
      web.evaluateJavascript(js,v->cb.ok(decode(v)));
    }
    boolean bad(String x){return x==null||x.isEmpty()||x.contains("<error>")||x.contains("<code>100002</code>")||x.contains("<code>108006</code>");}
    String tag(String x,String n){if(x==null)return "";Matcher m=Pattern.compile("<"+Pattern.quote(n)+">(.*?)</"+Pattern.quote(n)+">",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(x);return m.find()?m.group(1).trim():"";}
    String tagAny(String x,String... ns){for(String n:ns){String v=tag(x,n);if(!v.isEmpty())return v;}return "";}
    long numAny(String x,String...ns){try{return Long.parseLong(tagAny(x,ns).replaceAll("[^0-9]",""));}catch(Exception e){return -1;}}
    String pick(String...ss){for(String s:ss)if(s!=null&&!s.trim().isEmpty())return s.trim();return "";}

    void refreshAll(){
      if(!logged)return;
      apiGet("/api/device/information",x->{set("model",pick(tagAny(x,"DeviceName","Classify"),"E5785Lh-22c"));set("firmware",tagAny(x,"SoftwareVersion","software_version","FirmwareVersion"));});
      apiGet("/api/device/signal",x->{if(!bad(x))parseSignal(x);else apiGet("/api/monitoring/best-signal",this::parseSignal);});
      apiGet("/api/monitoring/status",this::parseStatus);
      apiGet("/api/monitoring/traffic-statistics",this::parseTraffic);
      apiGet("/api/net/current-plmn",x->set("operator",pick(tagAny(x,"FullName","ShortName","Numeric","Rat"))));
      apiGet("/api/net/net-mode",x->{set("netMode",tagAny(x,"NetworkMode","networkmode"));set("netBand",tagAny(x,"NetworkBand","networkband"));set("lteBand",tagAny(x,"LTEBand","lteband"));});
      apiGet("/api/wlan/host-list",this::parseHosts);
      apiGet("/api/wlan/basic-settings",x->{String s=tagAny(x,"WifiSsid","SSID","ssid");if(!s.isEmpty())set("ssid",s);});
      apiGet("/api/pin/status",x->{set("pin",tagAny(x,"SimState","PinState","pin_state"));set("sim",tagAny(x,"SimState","sim_status"));});
      apiGet("/api/sms/sms-count",x->set("sms",pick(tagAny(x,"LocalUnread","LocalInbox","LocalOutbox"))));
      apiGet("/api/monitoring/get-battery-remain",x->set("batteryRemain",pick(tagAny(x,"RemainTime","BatteryRemainTime","remain_time"))));
      apiGet("/api/ntwk/guest_network",x->set("guest",pick(tagAny(x,"WifiEnable","Enable","enabled"))));
      apiGet("/api/ntwk/repeaterstate",x->set("repeater",pick(tagAny(x,"State","status"))));
      apiGet("/api/device/mimo-setting",x->set("mimo",pick(tagAny(x,"MIMO","mimo","Mode"))));
    }
    void parseSignal(String x){
      set("rsrp",tagAny(x,"rsrp","RSRP"));set("rsrq",tagAny(x,"rsrq","RSRQ"));set("sinr",tagAny(x,"sinr","SINR"));set("rssi",tagAny(x,"rssi","RSSI"));
      set("cell",tagAny(x,"cell_id","CellID","cellid"));set("pci",tagAny(x,"pci","PCI"));set("earfcn",tagAny(x,"earfcn","EARFCN"));
      String b=tagAny(x,"band","Band","lte_band");if(!b.isEmpty())set("band",b);
    }
    void parseStatus(String x){
      if(bad(x))return;String nw=tagAny(x,"CurrentNetworkTypeEx","CurrentNetworkType","NetworkType");if(!nw.isEmpty())set("network",networkName(nw));
      String bat=tagAny(x,"BatteryPercent","BatteryLevel","battery_percent");if(!bat.isEmpty())set("battery",bat.endsWith("%")?bat:bat+"%");
      set("wanip",tagAny(x,"WanIPAddress","PrimaryDns","SecondaryDns"));set("mobileData",tagAny(x,"ConnectionStatus","DataSwitch"));
      String wu=tagAny(x,"CurrentWifiUser","TotalWifiUser");if(!wu.isEmpty())set("wifiUsers",wu);
    }
    String networkName(String x){if(x.equals("19")||x.equals("101")||x.equals("7"))return "4G LTE";if(x.equals("20")||x.equals("41"))return "5G";if(x.equals("2")||x.equals("3")||x.equals("4")||x.equals("5")||x.equals("6"))return "3G";return x;}
    void parseTraffic(String x){
      if(bad(x))return;long dl=numAny(x,"TotalDownload","total_download"),ul=numAny(x,"TotalUpload","total_upload"),now=System.currentTimeMillis();
      if(dl>=0)set("totalDl",bytes(dl));if(ul>=0)set("totalUl",bytes(ul));set("uptime",duration(numAny(x,"CurrentConnectTime","CurrentConnectTimeSeconds")));
      if(prevDl>=0&&dl>=prevDl&&prevTs>0){double sec=(now-prevTs)/1000.0;if(sec>0){set("liveDl",String.format(Locale.US,"%.2f Mbps",(dl-prevDl)*8/sec/1e6));set("liveUl",String.format(Locale.US,"%.2f Mbps",(ul-prevUl)*8/sec/1e6));}}
      if(dl>=0){prevDl=dl;prevUl=ul;prevTs=now;}
    }
    void parseHosts(String x){if(bad(x))return;Matcher m=Pattern.compile("<Host>",Pattern.CASE_INSENSITIVE).matcher(x);int n=0;while(m.find())n++;set("hosts",String.valueOf(n));}
    String bytes(long b){if(b<0)return "—";double d=b;if(d>1073741824)return String.format(Locale.US,"%.2f GB",d/1073741824d);if(d>1048576)return String.format(Locale.US,"%.2f MB",d/1048576d);return String.format(Locale.US,"%.1f KB",d/1024d);}
    String duration(long s){if(s<0)return "—";return String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s%3600)/60,s%60);}

    void probe(){
      if(!logged)return;capState.clear();status.setText("جارٍ فحص قدرات Firmware…");final int[] i={0},ok={0};
      Runnable[] r=new Runnable[1];r[0]=()->{if(i[0]>=PROBES.length){set("caps",ok[0]+"/"+PROBES.length);status.setText("فحص القدرات: "+ok[0]+" مدعومة من "+PROBES.length);if(currentPage==4)capDialog();return;}
        String name=PROBES[i[0]][0],path=PROBES[i[0]][1];i[0]++;apiGet(path,x->{String st;if(bad(x))st="غير متاحة";else if(x.contains("<response>")||x.matches("(?s).*<[^/!][^>]*>.*")){st="متاحة";ok[0]++;}else st="رد فارغ";capState.put(name+" • "+path,st);h.post(r[0]);});};r[0].run();
    }
    void capDialog(){StringBuilder s=new StringBuilder();for(Map.Entry<String,String> e:capState.entrySet())s.append(e.getValue().equals("متاحة")?"✓ ":"• ").append(e.getKey()).append("\n   ").append(e.getValue()).append("\n");show("فحص قدرات E5785",s.toString());}

    void applyBand(String name,String mask,String mode){
      if(!logged)return;snapshot();String xml="<request><NetworkMode>"+mode+"</NetworkMode><NetworkBand>3FFFFFFF</NetworkBand><LTEBand>"+mask+"</LTEBand></request>";
      apiPost("/api/net/net-mode",xml,x->{if(okResponse(x)){set("bestBand",name);prefs.edit().putString("lastBand",name).apply();toast("تم تطبيق "+name);}else toast("لم يقبل Firmware الإعداد: "+errorText(x));h.postDelayed(this::refreshAll,2200);});
    }
    void snapshot(){apiGet("/api/net/net-mode",x->{if(bad(x)){toast("تعذر قراءة الإعداد الحالي");return;}prefs.edit().putString("snapMode",tagAny(x,"NetworkMode")).putString("snapBand",tagAny(x,"NetworkBand")).putString("snapLte",tagAny(x,"LTEBand")).apply();toast("تم حفظ Snapshot");});}
    void restoreBand(){String m=prefs.getString("snapMode",""),b=prefs.getString("snapBand",""),l=prefs.getString("snapLte","");if(m.isEmpty()){toast("لا يوجد Snapshot");return;}apiPost("/api/net/net-mode","<request><NetworkMode>"+m+"</NetworkMode><NetworkBand>"+b+"</NetworkBand><LTEBand>"+l+"</LTEBand></request>",x->{toast(okResponse(x)?"تم الاسترجاع":"فشل الاسترجاع");h.postDelayed(this::refreshAll,1800);});}
    boolean okResponse(String x){return x!=null&&(x.contains("<response>OK</response>")||x.contains("<response>ok</response>"));}
    String errorText(String x){String c=tagAny(x,"code");return c.isEmpty()?"غير مدعوم/صلاحية":c;}
    void benchmark(){toast("سيبدأ Benchmark آمن للترددات الشائعة");String[] n={"B3","B7","B20","B3+B7"};String[] m={"4","40","80000","44"};final int[] i={0};final double[] best={-1};final String[] bn={""};snapshot();Runnable[] r=new Runnable[1];r[0]=()->{if(i[0]>=n.length){prefs.edit().putString("bestBand",bn[0]).apply();set("bestBand",bn[0]);toast("أفضل نتيجة: "+bn[0]);return;}int j=i[0]++;apiPost("/api/net/net-mode","<request><NetworkMode>03</NetworkMode><NetworkBand>3FFFFFFF</NetworkBand><LTEBand>"+m[j]+"</LTEBand></request>",x->{if(!okResponse(x)){h.post(r[0]);return;}h.postDelayed(()->measureDownload(v->{if(v>best[0]){best[0]=v;bn[0]=n[j]+" • "+String.format(Locale.US,"%.1f Mbps",v);}h.post(r[0]);}),4500);});};r[0].run();}
    void showBest(){show("أفضل تردد",pick(prefs.getString("bestBand",""),values.get("bestBand"),"لم يتم Benchmark بعد"));}

    interface DCB{void done(double v);}
    void measureDownload(DCB cb){new Thread(()->{double mbps=0;long t=System.nanoTime();long n=0;try{URL u=new URL("https://speed.cloudflare.com/__down?bytes=3000000");URLConnection c=u.openConnection();c.setConnectTimeout(5000);c.setReadTimeout(10000);InputStream in=c.getInputStream();byte[] b=new byte[32768];int k;while((k=in.read(b))>0)n+=k;in.close();double s=(System.nanoTime()-t)/1e9;mbps=n*8/s/1e6;}catch(Exception e){}double v=mbps;runOnUiThread(()->cb.done(v));}).start();}
    void speedTest(){status.setText("جارٍ اختبار السرعة…");long start=System.currentTimeMillis();measureDownload(v->{double ping=System.currentTimeMillis()-start;set("speed",String.format(Locale.US,"%.1f Mbps",v));set("ping",String.format(Locale.US,"%.0f ms",ping));prefs.edit().putString("lastSpeed",values.get("speed")).apply();status.setText("اكتمل اختبار السرعة");show("اختبار السرعة","Download: "+values.get("speed")+"\nLatency تقريبي: "+values.get("ping"));});}
    void smartDiag(){String rsrp=values.get("rsrp"),sinr=values.get("sinr");StringBuilder s=new StringBuilder();s.append("الموديل: ").append(pick(values.get("model"),"—")).append("\nالشبكة: ").append(pick(values.get("network"),"—")).append("\nBand: ").append(pick(values.get("band"),"—")).append("\nRSRP: ").append(pick(rsrp,"—")).append("\nSINR: ").append(pick(sinr,"—")).append("\n\n");s.append("التقييم: ");try{double r=Double.parseDouble(rsrp.replaceAll("[^0-9.-]",""));double q=Double.parseDouble(sinr.replaceAll("[^0-9.-]",""));if(r>-85&&q>15)s.append("الإشارة ممتازة.");else if(r>-95&&q>5)s.append("الإشارة جيدة.");else if(q<5)s.append("تشويش مرتفع؛ جرّب Band آخر أو مكان مختلف.");else s.append("الإشارة متوسطة/ضعيفة.");}catch(Exception e){s.append("يلزم تحديث قراءات الإشارة.");}show("التشخيص الذكي",s.toString());}
    void reconnect(){apiPost("/api/dialup/connection","<request><Action>1</Action></request>",x->{if(!okResponse(x))apiPost("/api/device/control","<request><Control>1</Control></request>",y->toast(okResponse(y)?"تم إرسال إعادة الاتصال":"الأمر غير مدعوم"));else toast("تم إرسال إعادة الاتصال");});}
    void reboot(){confirm("إعادة تشغيل الراوتر","سيتم قطع الشبكة مؤقتًا.",()->apiPost("/api/device/control","<request><Control>1</Control></request>",x->toast(okResponse(x)?"تم إرسال إعادة التشغيل":"لم يقبل الراوتر الأمر")));}

    void hostsDialog(){apiGet("/api/wlan/host-list",x->{if(bad(x)){show("الأجهزة","تعذر قراءة قائمة الأجهزة.");return;}StringBuilder s=new StringBuilder();Matcher m=Pattern.compile("<Host>(.*?)</Host>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(x);int i=0;while(m.find()){String z=m.group(1);s.append(++i).append(". ").append(pick(tagAny(z,"HostName","hostname"),"جهاز")).append("\nIP: ").append(tagAny(z,"IpAddress","IPAddress")).append("\nMAC: ").append(tagAny(z,"MacAddress","MACAddress")).append("\n\n");}show("الأجهزة المتصلة",i==0?"لا توجد أجهزة ظاهرة.":s.toString());});}
    void wifiDialog(){apiGet("/api/wlan/basic-settings",x->{String ssid=tagAny(x,"WifiSsid","SSID");LinearLayout l=form();EditText a=input("اسم الشبكة",ssid);EditText p=input("كلمة مرور Wi‑Fi","");l.addView(a);l.addView(p);new AlertDialog.Builder(this).setTitle("إعداد Wi‑Fi").setView(l).setPositiveButton("حفظ",(d,w)->{String xml="<request><WifiSsid>"+xml(a.getText().toString())+"</WifiSsid>"+(p.getText().length()>0?"<WifiWpapsk>"+xml(p.getText().toString())+"</WifiWpapsk>":"")+"</request>";apiPost("/api/wlan/basic-settings",xml,r->toast(okResponse(r)?"تم الحفظ":"لم يقبل Firmware التعديل"));}).setNegativeButton("إلغاء",null).show();});}
    void qosDialog(){apiGet("/api/app/qos",x->show("QoS",bad(x)?"QoS غير متاح في Firmware الحالي.":prettyXml(x)+"\n\nإذا كان endpoint قابلًا للكتابة، نستخدمه لتحديد الأولوية لكل جهاز."));}
    void smsInbox(){apiPost("/api/sms/sms-list","<request><PageIndex>1</PageIndex><ReadCount>20</ReadCount><BoxType>1</BoxType><SortType>0</SortType><Ascending>0</Ascending><UnreadPreferred>0</UnreadPreferred></request>",x->show("صندوق SMS",bad(x)?errorText(x):prettyXml(x)));}
    void smsSend(){LinearLayout l=form();EditText n=input("الرقم","");EditText m=input("الرسالة","");l.addView(n);l.addView(m);new AlertDialog.Builder(this).setTitle("إرسال SMS").setView(l).setPositiveButton("إرسال",(d,w)->{String xml="<request><Index>-1</Index><Phones><Phone>"+xml(n.getText().toString())+"</Phone></Phones><Sca></Sca><Content>"+xml(m.getText().toString())+"</Content><Length>"+m.getText().length()+"</Length><Reserved>1</Reserved><Date>-1</Date></request>";apiPost("/api/sms/send-sms",xml,x->toast(okResponse(x)?"تم الإرسال":"فشل الإرسال: "+errorText(x)));}).setNegativeButton("إلغاء",null).show();}
    void ussdDialog(){LinearLayout l=form();EditText c=input("كود USSD مثل *121#","");l.addView(c);new AlertDialog.Builder(this).setTitle("USSD").setView(l).setPositiveButton("إرسال",(d,w)->{apiPost("/api/ussd/send","<request><content>"+xml(c.getText().toString())+"</content><codeType>CodeType</codeType></request>",x->{h.postDelayed(()->apiGet("/api/ussd/get",r->show("نتيجة USSD",prettyXml(r))),1500);});}).setNegativeButton("إلغاء",null).show();}
    void apnDialog(){apiGet("/api/dialup/profiles",x->show("APN Profiles",bad(x)?"غير متاح":prettyXml(x)));}
    void simInfo(){apiGet("/api/pin/status",x->{StringBuilder s=new StringBuilder(prettyXml(x));apiGet("/api/pin/simlock",y->show("SIM / PIN",s+"\n\nSIM Lock:\n"+prettyXml(y)));});}
    void trafficHistory(){apiGet("/api/monitoring/month_statistics",x->{if(bad(x))apiGet("/api/monitoring/statistics_3days",y->show("سجل الاستهلاك",prettyXml(y)));else show("سجل الاستهلاك",prettyXml(x));});}
    void powerDialog(){String[] ep={"/api/monitoring/get-battery-remain","/api/device/autopoweroff","/api/device/sleep-time","/api/device/fastbootswitch"};final int[] i={0};StringBuilder s=new StringBuilder();Runnable[] r=new Runnable[1];r[0]=()->{if(i[0]>=ep.length){show("البطارية والطاقة",s.toString());return;}String p=ep[i[0]++];apiGet(p,x->{s.append(p).append("\n").append(prettyXml(x)).append("\n\n");h.post(r[0]);});};r[0].run();}
    void apiExplorer(){final EditText e=input("API path","/api/device/information");new AlertDialog.Builder(this).setTitle("API Explorer").setView(e).setPositiveButton("قراءة",(d,w)->showEndpoint(e.getText().toString(),"API Explorer")).setNegativeButton("إلغاء",null).show();}
    void showEndpoint(String path,String title){apiGet(path,x->show(title,prettyXml(x)));}
    String prettyXml(String x){if(x==null||x.isEmpty())return "لا يوجد رد";return x.replace("><",">\n<").replaceAll("<\\/?response>","").trim();}
    void showWeb(){Intent i=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(BASE));startActivity(i);}
    void factoryReset(){confirm("استعادة ضبط المصنع","تحذير: هذا يمسح إعدادات الراوتر بالكامل، بما فيها Wi‑Fi وAPN.",()->confirm("تأكيد أخير","هل أنت متأكد؟",()->apiPost("/api/device/control","<request><Control>2</Control></request>",x->toast(okResponse(x)?"تم إرسال أمر المصنع":"الأمر غير متاح"))));}
    void about(){show("Shahboun Router","الإصدار 1.1.0\nHuawei E5785Lh-22c Edition\n\nواجهة مخصصة لـ HiLink مع Capability Scan، Band Manager، SMS، USSD، Wi‑Fi، الأجهزة، التشخيص، الترافيك، الطاقة وأدوات Firmware.\n\nتصميم وتطوير: أحمد شهبون");}

    LinearLayout form(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(18),0,dp(18),0);return l;}
    EditText input(String hint,String val){EditText e=new EditText(this);e.setHint(hint);e.setText(val);e.setTextColor(Color.DKGRAY);e.setHintTextColor(Color.GRAY);return e;}
    String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    void show(String t,String m){new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("حسنًا",null).show();}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void confirm(String t,String m,Runnable yes){new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("نعم",(d,w)->yes.run()).setNegativeButton("إلغاء",null).show();}
}
