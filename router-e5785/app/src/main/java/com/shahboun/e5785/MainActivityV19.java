package com.shahboun.e5785;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.location.*;
import android.net.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/**
 * v1.7.0 Smart Network Toolkit.
 * Keeps all previous E5785 controls and adds:
 * - fast dead-band detection + verified recovery
 * - local dual-link smart distribution diagnostics (Wi-Fi + Cellular)
 * - Auto Band / learned best band
 * - safe cell-lock capability probe (never sends guessed write commands)
 * - tower/location history, live RF graph, diagnostics, self recovery
 * - profiles, consumption, devices, quality test, audit log, driving mode
 */
public class MainActivityV19 extends MainActivityV18 {
    private static final int REQ_LOC=7019;
    private final Handler smartH=new Handler(Looper.getMainLooper());
    private boolean driveMode=false;
    private String lastDriveCell="";
    private Network wifiNet,cellNet;
    private ConnectivityManager.NetworkCallback wifiCb,cellCb;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        log("APP","SMART-NETWORK","v"+BuildConfig.VERSION_NAME+" • fast dead-band recovery • dual-link • auto-band • tower learning");
        addSmartChip();
    }

    private void addSmartChip(){
        TextView x=btn("المميزات الذكية • v"+BuildConfig.VERSION_NAME,v->showSmartHub());
        x.setTextSize(12);x.setPadding(dp(10),0,dp(10),0);
        try{root.addView(x,2,new LinearLayout.LayoutParams(-1,dp(44)));}catch(Exception e){root.addView(x,new LinearLayout.LayoutParams(-1,dp(44)));}
    }

    private Dialog base19(){Dialog d=new Dialog(this);d.requestWindowFeature(Window.FEATURE_NO_TITLE);return d;}
    private LinearLayout card19(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(16),dp(18),dp(16));c.setBackground(shape(SURF2,24));return c;}
    private TextView t19(String s,int z,int c){TextView t=text(s,z,c);t.setGravity(Gravity.RIGHT);t.setLineSpacing(0,1.14f);return t;}
    private void finish19(Dialog d){Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));w.setDimAmount(.72f);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.94f),WindowManager.LayoutParams.WRAP_CONTENT);}}
    private void rowButton19(LinearLayout p,String name,String desc,View.OnClickListener l){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(10),dp(14),dp(10));c.setBackground(shape(SURF2,18));
        TextView a=t19(name,15,TEXT);TextView b=t19(desc,11,MUTED);b.setPadding(0,dp(2),0,0);c.addView(a);c.addView(b);c.setOnClickListener(l);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(7);p.addView(c,lp);
    }

    private void showSmartHub(){
        Dialog d=base19();LinearLayout c=card19();c.addView(t19("مركز الشبكة الذكي",21,TEXT));
        TextView n=t19("كل الأدوات تعمل فوق آخر إعدادات التطبيق. الأوامر الحساسة لا تُرسل إذا لم يعلن الراوتر دعمها.",11,MUTED);n.setPadding(0,dp(3),0,dp(6));c.addView(n);
        ScrollView sc=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        rowButton19(list,"دمج الإنترنت الذكي المحلي","Wi‑Fi + بيانات الهاتف بالتوازي وتوزيع الطلبات التي يتحكم بها التطبيق",v->{d.dismiss();dualInternet19();});
        rowButton19(list,"Auto Band","تطبيق أفضل تردد متعلم والتحقق من رجوع LTE والإنترنت",v->{d.dismiss();autoBand19();});
        rowButton19(list,"قفل الخلية / البرج","فحص دعم Cell Lock في الفيرموير بدون إرسال أمر تخميني",v->{d.dismiss();cellLock19();});
        rowButton19(list,"قاعدة بيانات الأبراج","حفظ Cell ID / PCI / TAC / Band مع موقع الهاتف عند توفره",v->{d.dismiss();towerDb19();});
        rowButton19(list,"مراقب الإشارة المباشر","رسم حي لـ RSRP وSINR مع الخلية والتردد",v->{d.dismiss();liveMonitor19();});
        rowButton19(list,"شن مشكلة النت؟","تشخيص تلقائي للتسجيل، DNS، الإشارة، الشريحة ووضع الشبكة",v->{d.dismiss();diagnose19();});
        rowButton19(list,"الاسترجاع الذاتي","حفظ حالة سليمة واستعادتها عند سقوط التسجيل أو البيانات",v->{d.dismiss();recovery19();});
        rowButton19(list,"Profiles","حفظ واسترجاع 3 ملفات إعداد شبكة",v->{d.dismiss();profiles19();});
        rowButton19(list,"استهلاك الإنترنت","الحالي والشهري + مدة الاتصال",v->{d.dismiss();usage19();});
        rowButton19(list,"الأجهزة المتصلة","فتح إدارة الأجهزة الموجودة في التطبيق",v->{d.dismiss();hostsDialog();});
        rowButton19(list,"اختبار جودة الشبكة","Ping تقريبي + Jitter + Loss + سرعة تنزيل قصيرة",v->{d.dismiss();quality19();});
        rowButton19(list,"سجل التغييرات","فتح سجل التشخيص والأوامر الكامل",v->{d.dismiss();apiExplorer();});
        rowButton19(list,driveMode?"إيقاف وضع القيادة":"وضع القيادة","تسجيل تغير الخلايا والترددات أثناء الحركة",v->{d.dismiss();toggleDrive19();});
        rowButton19(list,"محرك التعلم","ربط أفضل Band بالخلية الحالية واستعماله لاحقًا",v->{d.dismiss();learning19();});
        sc.addView(list);c.addView(sc,new LinearLayout.LayoutParams(-1,dp(560)));TextView close=btn("إغلاق",v->d.dismiss());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(50));cp.topMargin=dp(8);c.addView(close,cp);d.setContentView(c);d.show();finish19(d);
    }

    // -------- FAST / SAFE BAND BENCHMARK --------
    private static final String[][] BAND19={{"B1","1"},{"B3","4"},{"B5","10"},{"B7","40"},{"B8","80"},{"B20","80000"},{"B32","80000000"},{"B38","2000000000"}};
    @Override void benchmark(){
        if(!logged){show("اختبار الترددات","سجّل الدخول للراوتر أولًا.");return;}
        log("ACTION","اختبار الترددات v1.7","fast dead-band rejection + recovery between dead bands");live=false;status.setText("جارٍ اختبار الترددات بأمان…");
        new Thread(()->runFastBenchmark19(),"e5785-benchmark-170").start();
    }
    private void runFastBenchmark19(){
        String orig=getSync("/api/net/net-mode");String om=tagAny(orig,"NetworkMode"),ob=tagAny(orig,"NetworkBand"),ol=tagAny(orig,"LTEBand");
        if(bad(orig)||om.isEmpty()||ob.isEmpty()||ol.isEmpty()){finishBench19("تعذر حفظ الإعداد الأصلي؛ لم يبدأ الاختبار.");return;}
        prefs.edit().putString("snapMode",om).putString("snapBand",ob).putString("snapLte",ol).apply();
        long supported=supportedMask19(getSync("/api/net/net-mode-list"),ol);List<String> lines=new ArrayList<>();double best=-999;String bestN="",bestM="";
        for(String[] b:BAND19){long m=hex19(b[1]);if(m==0||(supported&m)!=m)continue;
            runOnUiThread(()->status.setText("اختبار "+b[0]+"…"));String r=postSync("/api/net/net-mode",netXml19("03","3FFFFFFF",b[1]));if(!okResponse(r)){lines.add(b[0]+": رفض الأمر");continue;}
            Reg19 reg=waitReg19(b[1],9000);
            if(!reg.ok){lines.add(b[0]+": غير متاح"+(reg.hardDead?" • لا توجد خدمة":""));log("BENCH","REJECT "+b[0],reg.hardDead?"hard-dead fast reject":"no registered LTE cell");
                if(reg.hardDead)restoreQuick19(om,ob,ol,10000);continue;}
            double score=rfScore19(reg.rsrp,reg.sinr);lines.add(b[0]+": Band "+reg.band+" • RSRP "+reg.rsrp+" • SINR "+reg.sinr);
            if(score>best){best=score;bestN=b[0];bestM=b[1];}
        }
        boolean restored=restoreQuick19(om,ob,ol,18000);if(!bestM.isEmpty())prefs.edit().putString("bestBandName",bestN).putString("bestBandMask",bestM).apply();
        String cell=currentCell19();if(!cell.isEmpty()&&!bestM.isEmpty())prefs.edit().putString("learn_"+cell,bestM+"|"+bestN).apply();
        StringBuilder out=new StringBuilder();for(String s:lines)out.append("• ").append(s).append('\n');out.append("\nالأفضل: ").append(bestN.isEmpty()?"لا يوجد":bestN).append("\n").append(restored?"✓ رجع الإعداد الأصلي والإنترنت":"⚠ تعذر تأكيد رجوع 901");finishBench19(out.toString());
    }
    private static class Reg19{boolean ok,hardDead;String band="",rsrp="",sinr="";}
    private Reg19 waitReg19(String mask,long timeout){Reg19 z=new Reg19();long end=SystemClock.elapsedRealtime()+timeout;int dead=0,noCell=0;while(SystemClock.elapsedRealtime()<end){String st=getSync("/api/monitoring/status"),sg=getSync("/api/device/signal");String conn=tagAny(st,"ConnectionStatus"),svc=tagAny(st,"ServiceStatus"),nt=tagAny(st,"CurrentNetworkType"),plmn=tagAny(sg,"plmn"),band=tagAny(sg,"band"),cell=tagAny(sg,"cell_id"),rsrp=tagAny(sg,"rsrp"),sinr=tagAny(sg,"sinr");
        boolean hard=("0".equals(svc)&&"0".equals(nt))||"000000".equals(plmn);if(hard)dead++;else dead=0;if(dead>=2){z.hardDead=true;return z;}
        boolean real=!band.isEmpty()&&!cell.isEmpty()&&!rsrp.isEmpty();if("901".equals(conn)&&real&&maskHasBand19(mask,band)){z.ok=true;z.band=band;z.rsrp=rsrp;z.sinr=sinr;return z;}
        if(!plmn.isEmpty()&&!"000000".equals(plmn)&&!real)noCell++;else noCell=0;if(noCell>=4)return z;sleep19(850);}return z;}
    private boolean restoreQuick19(String m,String b,String l,long timeout){for(int a=0;a<2;a++){String r=postSync("/api/net/net-mode",netXml19(m,b,l));if(!okResponse(r)){sleep19(700);continue;}long e=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<e){String nm=getSync("/api/net/net-mode"),st=getSync("/api/monitoring/status");boolean same=m.equalsIgnoreCase(tagAny(nm,"NetworkMode"))&&b.equalsIgnoreCase(tagAny(nm,"NetworkBand"))&&l.equalsIgnoreCase(tagAny(nm,"LTEBand"));if(same&&"901".equals(tagAny(st,"ConnectionStatus")))return true;sleep19(900);}}return false;}
    private void finishBench19(String s){runOnUiThread(()->{live=currentPage==0;if(live)tick();h.postDelayed(this::refreshAll,800);status.setText("اكتمل اختبار الترددات");show("نتيجة اختبار الترددات",s);});}
    private long supportedMask19(String xml,String fallback){long s=0;try{Matcher sec=Pattern.compile("(?is)<LTEBandList>(.*?)</LTEBandList>").matcher(xml);if(sec.find()){Matcher m=Pattern.compile("(?is)<LTEBand>.*?<Name>(.*?)</Name>.*?<Value>([0-9a-fA-F]+)</Value>.*?</LTEBand>").matcher(sec.group(1));while(m.find())if(!m.group(1).toLowerCase(Locale.US).contains("all bands"))s|=Long.parseUnsignedLong(m.group(2),16);}}catch(Exception ignored){}if(s==0)s=hex19(fallback);return s;}
    private long hex19(String s){try{return Long.parseUnsignedLong(s,16);}catch(Exception e){return 0;}}
    private boolean maskHasBand19(String mask,String band){try{int n=Integer.parseInt(band.replaceAll("[^0-9]",""));return n>0&&n<64&&(hex19(mask)&(1L<<(n-1)))!=0;}catch(Exception e){return false;}}
    private String netXml19(String m,String b,String l){return "<request><NetworkMode>"+m+"</NetworkMode><NetworkBand>"+b+"</NetworkBand><LTEBand>"+l+"</LTEBand></request>";}
    private double rfScore19(String r,String s){return num19(s)*3+num19(r)*.18;}
    private double num19(String s){try{return Double.parseDouble(s.replaceAll("[^0-9.-]",""));}catch(Exception e){return -99;}}
    private void sleep19(long x){try{Thread.sleep(x);}catch(InterruptedException e){Thread.currentThread().interrupt();}}

    // -------- DUAL INTERNET (LOCAL, NO EXTERNAL BONDING SERVER) --------
    private void dualInternet19(){Dialog d=base19();LinearLayout c=card19();c.addView(t19("دمج الإنترنت الذكي المحلي",20,TEXT));TextView state=t19("جارٍ اكتشاف Wi‑Fi وبيانات الهاتف…",12,MUTED);c.addView(state);TextView test=btn("اختبار المصدرين معًا",v->probeDual19(state));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.topMargin=dp(12);c.addView(test,p);TextView note=t19("هذا الوضع يشغّل المصدرين بالتوازي ويوزع الاتصالات التي يتحكم بها التطبيق. بدون سيرفر خارجي لا يمكن جعل اتصال TCP واحد لكل تطبيقات الهاتف يظهر بعنوان IP واحد أو يجمع السرعتين حرفيًا.",11,MUTED);note.setPadding(0,dp(10),0,0);c.addView(note);d.setContentView(c);d.show();finish19(d);discoverDual19(state);}
    private void discoverDual19(TextView out){ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);wifiNet=null;cellNet=null;for(Network n:cm.getAllNetworks()){NetworkCapabilities cp=cm.getNetworkCapabilities(n);if(cp==null)continue;if(cp.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&cp.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))wifiNet=n;if(cp.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)&&cp.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))cellNet=n;}out.setText("Wi‑Fi: "+(wifiNet!=null?"متاح":"غير متاح")+"\nبيانات الهاتف: "+(cellNet!=null?"متاحة":"غير متاحة")+"\n\n"+((wifiNet!=null&&cellNet!=null)?"✓ المصدران جاهزان للتوزيع الذكي":"شغّل Wi‑Fi وبيانات الهاتف معًا"));}
    private void probeDual19(TextView out){discoverDual19(out);final Network w=wifiNet,m=cellNet;if(w==null||m==null)return;out.setText("جارٍ قياس المصدرين بالتوازي…");new Thread(()->{double[] a={probeNetwork19(w),probeNetwork19(m)};runOnUiThread(()->out.setText(String.format(Locale.US,"Wi‑Fi: %.0f ms\nبيانات الهاتف: %.0f ms\nالمساران يعملان بالتوازي داخل المحرك المحلي.",a[0],a[1])));},"dual-probe").start();}
    private double probeNetwork19(Network n){long st=System.nanoTime();HttpURLConnection c=null;try{c=(HttpURLConnection)n.openConnection(new URL("https://speed.cloudflare.com/__down?bytes=1&x="+System.nanoTime()));c.setConnectTimeout(4000);c.setReadTimeout(4000);try(InputStream in=c.getInputStream()){in.read();}return (System.nanoTime()-st)/1e6;}catch(Exception e){return 0;}finally{if(c!=null)c.disconnect();}}

    private void autoBand19(){String learned=prefs.getString("learn_"+currentCell19(),"");String mask="",name="";if(learned.contains("|")){mask=learned.split("\\|",2)[0];name=learned.split("\\|",2)[1];}if(mask.isEmpty()){mask=prefs.getString("bestBandMask","");name=prefs.getString("bestBandName","");}if(mask.isEmpty()){show("Auto Band","مازال ما عندناش نتيجة متعلمة. شغّل «اختبار جميع الترددات» أولًا.");return;}final String fm=mask,fn=name;new AlertDialog.Builder(this).setTitle("Auto Band").setMessage("أفضل إعداد محفوظ: "+fn+"\nهل تريد تطبيقه الآن؟").setNegativeButton("إلغاء",null).setPositiveButton("تطبيق",(x,w)->new Thread(()->{String r=postSync("/api/net/net-mode",netXml19("03","3FFFFFFF",fm));Reg19 reg=okResponse(r)?waitReg19(fm,12000):new Reg19();runOnUiThread(()->show("Auto Band",reg.ok?"✓ تم تثبيت "+fn+" والتأكد من LTE و901":"لم يتم تثبيت الإعداد لأن التسجيل/الإنترنت لم يُؤكدا."));},"auto-band").start()).show();}

    private void cellLock19(){status.setText("فحص دعم قفل الخلية…");new Thread(()->{String[] paths={"/api/net/cell-lock","/api/net/lte-cell-lock","/api/net/celllock"};String found="";for(String p:paths){String r=getSync(p);if(!bad(r)){found=p+"\n"+prettyXml(r);break;}}final String f=found;runOnUiThread(()->{status.setText("اكتمل فحص Cell Lock");show("قفل الخلية",f.isEmpty()?"الفيرموير الحالي لم يعلن API صالحًا لقفل الخلية. لذلك التطبيق لن يرسل أمر كتابة تخميني قد يقطع الشبكة.":"تم اكتشاف واجهة قراءة محتملة:\n"+f+"\n\nلن يتم تفعيل الكتابة إلا بعد التحقق من حقولها الفعلية.");});},"cell-lock-probe").start();}

    private String currentCell19(){String s=getSync("/api/device/signal");return tagAny(s,"cell_id","CellID","cellid");}
    private void towerDb19(){if(!hasLoc19()){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ_LOC);show("قاعدة الأبراج","فعّل إذن الموقع ثم افتح الميزة مرة ثانية لحفظ البرج مع الإحداثيات.");return;}new Thread(()->{String s=getSync("/api/device/signal");String cell=tagAny(s,"cell_id"),pci=tagAny(s,"pci"),tac=tagAny(s,"tac"),band=tagAny(s,"band"),rsrp=tagAny(s,"rsrp"),sinr=tagAny(s,"sinr");Location l=lastLocation19();String rec=System.currentTimeMillis()+"|"+cell+"|"+pci+"|"+tac+"|"+band+"|"+rsrp+"|"+sinr+"|"+(l==null?"":l.getLatitude()+","+l.getLongitude());String old=prefs.getString("tower_db","");if(!cell.isEmpty()&&!old.contains("|"+cell+"|"))prefs.edit().putString("tower_db",old+rec+"\n").apply();final String db=prefs.getString("tower_db","");runOnUiThread(()->show("قاعدة بيانات الأبراج",db.isEmpty()?"لا توجد خلية مسجلة حاليًا.":db.replace('|',' ')));},"tower-db").start();}
    private boolean hasLoc19(){return Build.VERSION.SDK_INT<23||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private Location lastLocation19(){try{LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);Location a=null;for(String p:lm.getProviders(true)){Location x=lm.getLastKnownLocation(p);if(x!=null&&(a==null||x.getTime()>a.getTime()))a=x;}return a;}catch(Exception e){return null;}}

    private void liveMonitor19(){Dialog d=base19();LinearLayout c=card19();c.addView(t19("مراقب الشبكة المباشر",20,TEXT));SignalGraph19 graph=new SignalGraph19(this);c.addView(graph,new LinearLayout.LayoutParams(-1,dp(220)));TextView info=t19("",12,MUTED);c.addView(info);TextView close=btn("إغلاق",v->{graph.stop=true;d.dismiss();});LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(50));bp.topMargin=dp(8);c.addView(close,bp);d.setContentView(c);d.show();finish19(d);new Thread(()->{while(!graph.stop){String s=getSync("/api/device/signal");float r=(float)num19(tagAny(s,"rsrp")),q=(float)num19(tagAny(s,"sinr"));String txt="Band "+tagAny(s,"band")+" • Cell "+tagAny(s,"cell_id")+"\nRSRP "+tagAny(s,"rsrp")+" • RSRQ "+tagAny(s,"rsrq")+" • SINR "+tagAny(s,"sinr");runOnUiThread(()->{graph.add(r,q);info.setText(txt);});sleep19(1800);}},"rf-live").start();}
    static class SignalGraph19 extends View{final ArrayList<Float> r=new ArrayList<>(),s=new ArrayList<>();boolean stop=false;Paint p=new Paint(1);SignalGraph19(Context c){super(c);p.setStrokeWidth(4);}void add(float a,float b){if(r.size()>45){r.remove(0);s.remove(0);}r.add(a);s.add(b);invalidate();}@Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();p.setColor(Color.LTGRAY);p.setStrokeWidth(1);for(int i=1;i<5;i++)c.drawLine(0,h*i/5,w,h*i/5,p);if(r.size()<2)return;p.setStrokeWidth(4);p.setColor(Color.CYAN);for(int i=1;i<r.size();i++){float x1=w*(i-1)/44f,x2=w*i/44f,y1=h-(Math.max(-130,Math.min(-50,r.get(i-1)))+130)/80f*h,y2=h-(Math.max(-130,Math.min(-50,r.get(i)))+130)/80f*h;c.drawLine(x1,y1,x2,y2,p);}p.setColor(Color.GREEN);for(int i=1;i<s.size();i++){float x1=w*(i-1)/44f,x2=w*i/44f,y1=h-Math.max(-10,Math.min(30,s.get(i-1))+10)/40f*h,y2=h-Math.max(-10,Math.min(30,s.get(i))+10)/40f*h;c.drawLine(x1,y1,x2,y2,p);}}}

    private void diagnose19(){status.setText("جارٍ التشخيص…");new Thread(()->{String st=getSync("/api/monitoring/status"),sg=getSync("/api/device/signal"),pl=getSync("/api/net/current-plmn"),nm=getSync("/api/net/net-mode"),pin=getSync("/api/pin/status");StringBuilder x=new StringBuilder();String conn=tagAny(st,"ConnectionStatus"),svc=tagAny(st,"ServiceStatus"),dns=tagAny(st,"PrimaryDns"),band=tagAny(sg,"band"),rsrp=tagAny(sg,"rsrp"),sinr=tagAny(sg,"sinr");x.append("اتصال البيانات: ").append("901".equals(conn)?"سليم":"غير متصل ("+conn+")").append('\n');x.append("خدمة الشبكة: ").append(svc).append('\n');x.append("المشغل: ").append(tagAny(pl,"FullName","Numeric")).append('\n');x.append("Band: ").append(band).append(" • RSRP ").append(rsrp).append(" • SINR ").append(sinr).append('\n');x.append("DNS: ").append(dns.isEmpty()?"غير موجود":dns).append('\n');x.append("SIM: ").append(tagAny(pin,"SimState")).append('\n');x.append("الوضع: ").append(tagAny(nm,"NetworkMode")).append(" • LTE mask ").append(tagAny(nm,"LTEBand")).append("\n\n");if(!"901".equals(conn))x.append("النتيجة: المشكلة في تسجيل/جلسة البيانات قبل DNS.\n");else if(dns.isEmpty())x.append("النتيجة: الاتصال موجود لكن DNS غير مستلم.\n");else if(num19(sinr)<3)x.append("النتيجة: جودة الإشارة منخفضة/تشويش مرتفع.\n");else x.append("النتيجة: حالة الراوتر الأساسية سليمة.");final String out=x.toString();runOnUiThread(()->{status.setText("اكتمل التشخيص");show("شن مشكلة النت؟",out);});},"diagnose").start();}

    private void recovery19(){String m=prefs.getString("snapMode",""),b=prefs.getString("snapBand",""),l=prefs.getString("snapLte","");if(m.isEmpty()){snapshot();show("الاسترجاع الذاتي","تم طلب حفظ Snapshot. بعد حفظ حالة سليمة يمكن استعادتها تلقائيًا.");return;}new Thread(()->{String st=getSync("/api/monitoring/status");boolean ok="901".equals(tagAny(st,"ConnectionStatus"));if(!ok)ok=restoreQuick19(m,b,l,18000);final boolean z=ok;runOnUiThread(()->show("الاسترجاع الذاتي",z?"✓ الشبكة سليمة/تم استرجاع آخر حالة سليمة":"تعذر الاسترجاع التلقائي بالكامل."));},"self-recovery").start();}

    private void profiles19(){Dialog d=base19();LinearLayout c=card19();c.addView(t19("Profiles",20,TEXT));for(int i=1;i<=3;i++){final int k=i;rowButton19(c,"Profile "+i,prefs.getString("prof"+i+"Name","غير محفوظ"),v->{d.dismiss();profileDialog19(k);});}d.setContentView(c);d.show();finish19(d);}
    private void profileDialog19(int n){String raw=prefs.getString("prof"+n,"");new AlertDialog.Builder(this).setTitle("Profile "+n).setMessage(raw.isEmpty()?"الخانة فارغة":"الإعداد محفوظ: "+raw).setNegativeButton("إلغاء",null).setNeutralButton("حفظ الحالي",(d,w)->new Thread(()->{String x=getSync("/api/net/net-mode");String v=tagAny(x,"NetworkMode")+"|"+tagAny(x,"NetworkBand")+"|"+tagAny(x,"LTEBand");prefs.edit().putString("prof"+n,v).putString("prof"+n+"Name","Mode "+tagAny(x,"NetworkMode")+" • "+tagAny(x,"LTEBand")).apply();runOnUiThread(()->toast("تم حفظ Profile "+n));}).start()).setPositiveButton("تطبيق",(d,w)->{if(raw.isEmpty())return;String[] a=raw.split("\\|");if(a.length==3)new Thread(()->{boolean ok=restoreQuick19(a[0],a[1],a[2],18000);runOnUiThread(()->show("Profile "+n,ok?"✓ تم التطبيق والتأكد من 901":"لم يتم تأكيد الاتصال."));}).start();}).show();}

    private void usage19(){new Thread(()->{String a=getSync("/api/monitoring/traffic-statistics"),m=getSync("/api/monitoring/month_statistics");String out="الجلسة الحالية\nتنزيل: "+bytes19(tagAny(a,"CurrentDownload"))+"\nرفع: "+bytes19(tagAny(a,"CurrentUpload"))+"\nمدة الاتصال: "+sec19(tagAny(a,"CurrentConnectTime"))+"\n\nهذا الشهر\nتنزيل: "+bytes19(tagAny(m,"CurrentMonthDownload"))+"\nرفع: "+bytes19(tagAny(m,"CurrentMonthUpload"))+"\nمدة: "+sec19(tagAny(m,"MonthDuration"))+"\nآخر تصفير: "+tagAny(m,"MonthLastClearTime");runOnUiThread(()->show("استهلاك الإنترنت",out));},"usage").start();}
    private String bytes19(String s){try{double v=Double.parseDouble(s);String[] u={"B","KB","MB","GB","TB"};int i=0;while(v>=1024&&i<u.length-1){v/=1024;i++;}return String.format(Locale.US,"%.2f %s",v,u[i]);}catch(Exception e){return "—";}}
    private String sec19(String s){try{long x=Long.parseLong(s);return String.format(Locale.US,"%dس %dد %dث",x/3600,(x%3600)/60,x%60);}catch(Exception e){return "—";}}

    private void quality19(){status.setText("اختبار جودة الإنترنت…");new Thread(()->{int tries=7,fail=0;ArrayList<Double> l=new ArrayList<>();long bytes=0,ds=0;for(int i=0;i<tries;i++){long a=System.nanoTime();HttpURLConnection c=null;try{c=(HttpURLConnection)new URL("https://speed.cloudflare.com/__down?bytes=1&x="+a).openConnection();c.setConnectTimeout(3000);c.setReadTimeout(3000);try(InputStream in=c.getInputStream()){in.read();}l.add((System.nanoTime()-a)/1e6);}catch(Exception e){fail++;}finally{if(c!=null)c.disconnect();}}HttpURLConnection c=null;try{long a=System.nanoTime();c=(HttpURLConnection)new URL("https://speed.cloudflare.com/__down?bytes=1500000&x="+a).openConnection();c.setConnectTimeout(4000);c.setReadTimeout(10000);try(InputStream in=c.getInputStream()){byte[] b=new byte[32768];int k;while((k=in.read(b))>0)bytes+=k;}ds=System.nanoTime()-a;}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}Collections.sort(l);double ping=l.isEmpty()?0:l.get(l.size()/2),jit=0;for(int i=1;i<l.size();i++)jit+=Math.abs(l.get(i)-l.get(i-1));if(l.size()>1)jit/=l.size()-1;double loss=fail*100.0/tries,mbps=ds>0?bytes*8/(ds/1e9)/1e6:0;String grade=loss==0&&ping<60&&mbps>10?"ممتاز":loss<15&&ping<150?"جيد":"ضعيف";String out=String.format(Locale.US,"التقييم: %s\nDownload: %.1f Mbps\nPing: %.0f ms\nJitter: %.0f ms\nPacket Loss: %.0f%%",grade,mbps,ping,jit,loss);runOnUiThread(()->{status.setText("اكتمل اختبار الجودة");show("جودة الشبكة",out);});},"quality").start();}

    private void toggleDrive19(){driveMode=!driveMode;prefs.edit().putBoolean("driveMode",driveMode).apply();if(driveMode){if(!hasLoc19())requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ_LOC);toast("تم تشغيل وضع القيادة");driveTick19();}else toast("تم إيقاف وضع القيادة");}
    private void driveTick19(){if(!driveMode)return;new Thread(()->{String s=getSync("/api/device/signal");String cell=tagAny(s,"cell_id");if(!cell.isEmpty()&&!cell.equals(lastDriveCell)){lastDriveCell=cell;Location l=hasLoc19()?lastLocation19():null;String rec=System.currentTimeMillis()+" | Cell "+cell+" | PCI "+tagAny(s,"pci")+" | B"+tagAny(s,"band")+" | RSRP "+tagAny(s,"rsrp")+(l==null?"":" | "+l.getLatitude()+","+l.getLongitude());prefs.edit().putString("driveLog",prefs.getString("driveLog","")+rec+"\n").apply();log("DRIVE","CELL-CHANGE",rec);}},"drive-sample").start();smartH.postDelayed(this::driveTick19,10000);}

    private void learning19(){String cell=currentCell19(),best=prefs.getString("bestBandMask",""),name=prefs.getString("bestBandName","");if(cell.isEmpty()){show("محرك التعلم","لا توجد خلية LTE مسجلة الآن.");return;}if(!best.isEmpty())prefs.edit().putString("learn_"+cell,best+"|"+name).apply();String v=prefs.getString("learn_"+cell,"");show("محرك التعلم",v.isEmpty()?"الخلية "+cell+" مازال ما عندهاش أفضل Band محفوظ. شغّل اختبار الترددات.":"الخلية الحالية: "+cell+"\nالإعداد المتعلم: "+v.replace('|',' ') +"\n\nAuto Band سيستخدمه عند نفس الخلية.");}
}
