package com.shahboun.e5785;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** v1.5.0: always-on diagnostic recorder for real E5785 API troubleshooting. */
public class MainActivityV15 extends MainActivityV14 {
    private static final int MAX_LOG_CHARS=250000;
    private final Object logLock=new Object();
    private final StringBuilder diag=new StringBuilder();
    private boolean diagEnabled=true;
    private TextView diagChip;
    private int seq=0;

    @Override public void onCreate(Bundle b){
        super.onCreate(b); addDiagnosticChip(); log("APP","START","Shahboun Router E5785 v1.5.0");
        try{View head=root.getChildAt(0);if(head instanceof ViewGroup){View names=((ViewGroup)head).getChildAt(0);if(names instanceof ViewGroup&&((ViewGroup)names).getChildCount()>1){View sub=((ViewGroup)names).getChildAt(1);if(sub instanceof TextView)((TextView)sub).setText("Huawei E5785Lh-22c Edition  •  v1.5.0");}}}catch(Exception ignored){}
    }

    private void addDiagnosticChip(){diagChip=btn("التشخيص • 0",v->showDiagnostic());diagChip.setTextSize(11);diagChip.setPadding(dp(10),0,dp(10),0);try{root.addView(diagChip,1,new LinearLayout.LayoutParams(-1,dp(42)));}catch(Exception e){root.addView(diagChip,new LinearLayout.LayoutParams(-1,dp(42)));}}
    private String now(){return new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date());}
    protected void log(String kind,String name,String details){if(!diagEnabled)return;synchronized(logLock){seq++;diag.append('#').append(String.format(Locale.US,"%03d",seq)).append("  ").append(now()).append("  [").append(kind).append("] ").append(name).append('\n').append(details==null?"":redact(details)).append("\n\n");if(diag.length()>MAX_LOG_CHARS){int cut=diag.indexOf("\n\n#",diag.length()-MAX_LOG_CHARS);diag.delete(0,cut>=0?cut+2:diag.length()-MAX_LOG_CHARS);}}runOnUiThread(()->{if(diagChip!=null)diagChip.setText("التشخيص • "+seq);});}
    private String redact(String s){if(s==null)return "";s=s.replaceAll("(?is)<Password>.*?</Password>","<Password>***</Password>");s=s.replaceAll("(?i)(__RequestVerificationToken[:=]\\s*)[^\\s]+","$1***");s=s.replaceAll("(?i)(SessionID=)[^;\\s]+","$1***");String[] tags={"SerialNumber","Imei","Imsi","Iccid","Msisdn","MacAddress","MacAddress1","MacAddress2","WanIPAddress","IpAddress","WifiSsid","AssociatedSsid","HostName"};for(String tag:tags)s=s.replaceAll("(?is)<"+tag+">.*?</"+tag+">","<"+tag+">***</"+tag+">");return s.length()>12000?s.substring(0,12000)+"\n…[truncated]":s;}
    private String snapshotLog(){synchronized(logLock){return diag.toString();}}

    private Dialog diagDialog(String title,String message,String extraLabel,Runnable extra){
        Dialog d=new Dialog(this);d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(20),dp(18),dp(20),dp(16));card.setBackground(shape(SURF2,24));
        TextView h=text(title,20,TEXT);h.setGravity(Gravity.RIGHT);h.setPadding(0,0,0,dp(8));card.addView(h);
        ScrollView sc=new ScrollView(this);TextView body=text(message,13,MUTED);body.setGravity(Gravity.RIGHT);body.setLineSpacing(0,1.18f);body.setTextIsSelectable(true);sc.addView(body);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.height=Math.min(dp(440),(int)(getResources().getDisplayMetrics().heightPixels*.52f));card.addView(sc,sp);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.LEFT);if(extraLabel!=null&&extra!=null){TextView e=btn(extraLabel,v->{d.dismiss();extra.run();});actions.addView(e,new LinearLayout.LayoutParams(0,dp(50),1));actions.addView(new Space(this),new LinearLayout.LayoutParams(dp(8),1));}TextView ok=btn("حسنًا",v->d.dismiss());actions.addView(ok,new LinearLayout.LayoutParams(0,dp(50),1));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=dp(14);card.addView(actions,ap);
        d.setContentView(card);d.show();Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setDimAmount(.72f);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.90f),WindowManager.LayoutParams.WRAP_CONTENT);}return d;
    }
    private void showDiagnostic(){String body=snapshotLog();if(body.isEmpty())body="لا توجد عمليات مسجلة بعد.";final String out=body;diagDialog("سجل التشخيص",out,"نسخ السجل",()->copyReport(out));}
    private void copyReport(String s){ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("Shahboun E5785 diagnostics",s));toast("تم نسخ سجل التشخيص");}
    private void copyFullReport(){StringBuilder s=new StringBuilder();s.append("SHAHBOUN ROUTER E5785 DIAGNOSTIC REPORT\nVersion: 1.5.0\n");s.append("Model: ").append(pick(values.get("model"),"—")).append("\nFirmware: ").append(pick(values.get("firmware"),"—")).append("\nOperator: ").append(pick(values.get("operator"),"—")).append("\nNetwork: ").append(pick(values.get("network"),"—")).append("\nBand: ").append(pick(values.get("band"),"—")).append("\nRSRP: ").append(pick(values.get("rsrp"),"—")).append("  RSRQ: ").append(pick(values.get("rsrq"),"—")).append("  SINR: ").append(pick(values.get("sinr"),"—")).append("\n\n--- LOG ---\n").append(snapshotLog());copyReport(s.toString());}
    private void clearLog(){synchronized(logLock){diag.setLength(0);seq=0;}if(diagChip!=null)diagChip.setText("التشخيص • 0");toast("تم مسح السجل");}

    @Override String getSync(String path){long t=System.currentTimeMillis();String r=super.getSync(path);long ms=System.currentTimeMillis()-t;log("GET",path,"duration_ms="+ms+"\nstate="+stateOf(r)+"\nhuawei_code="+pick(tagAny(r,"code"),"none")+"\nresponse=\n"+r);return r;}
    @Override String postSync(String path,String xml){long t=System.currentTimeMillis();log("POST-REQUEST",path,"request=\n"+xml);String r=super.postSync(path,xml);long ms=System.currentTimeMillis()-t;log("POST-RESPONSE",path,"duration_ms="+ms+"\nstate="+stateOf(r)+"\nhuawei_code="+pick(tagAny(r,"code"),"none")+"\nresponse=\n"+r);return r;}
    @Override void sessionTrace(String event,String detail){log("AUTH",event,detail);}
    @Override void benchmark(){log("ACTION","اختبار جميع الترددات","requested=true");super.benchmark();}
    @Override void snapshot(){log("ACTION","حفظ Snapshot","requested=true");super.snapshot();}
    @Override void restoreBand(){log("ACTION","استرجاع Snapshot","requested=true");super.restoreBand();}
    @Override void reconnect(){log("ACTION","إعادة الاتصال","requested=true");super.reconnect();}
    @Override void reboot(){log("ACTION","إعادة تشغيل الراوتر","requested=true");super.reboot();}
    @Override void hostsDialog(){log("ACTION","الأجهزة المتصلة","requested=true");super.hostsDialog();}
    @Override void wifiDialog(){log("ACTION","Wi-Fi","requested=true");super.wifiDialog();}
    @Override void smsInbox(){log("ACTION","SMS Inbox","requested=true");super.smsInbox();}
    @Override void ussdDialog(){log("ACTION","USSD","requested=true");super.ussdDialog();}

    @Override void apiExplorer(){
        diagDialog("أدوات التشخيص","التسجيل يبدأ تلقائيًا عند فتح التطبيق. جرّب الأزرار التي فيها مشاكل، وبعدها افتح «التشخيص» وانسخ السجل.\n\nكلمات المرور ورموز الجلسة وأرقام تعريف الجهاز والشريحة وبيانات أجهزة Wi-Fi تُخفى من التقرير.","نسخ تقرير كامل",this::copyFullReport);
    }
}
