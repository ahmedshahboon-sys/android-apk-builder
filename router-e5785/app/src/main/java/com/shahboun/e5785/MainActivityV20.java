package com.shahboun.e5785;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/**
 * v1.8.0 UI + signal intelligence layer.
 * Keeps every V19 feature and adds:
 * - orange + blue visual identity
 * - network score (0..100)
 * - human-readable RF quality, stability and speed grade
 * - rolling RSRP history
 * - clearer LTE band/frequency presentation
 * - simplified four-button quick actions on the dashboard
 */
public class MainActivityV20 extends MainActivityV19 {
    private static final int SH_BLUE = Color.rgb(35, 116, 225);
    private static final int SH_BLUE_DARK = Color.rgb(24, 73, 145);
    private static final int SH_ORANGE = Color.rgb(255, 145, 30);
    private static final int SH_ORANGE_DARK = Color.rgb(176, 86, 18);
    private static final int SH_PANEL = Color.rgb(27, 35, 48);

    private final ArrayDeque<Double> rsrp20 = new ArrayDeque<>();
    private final Handler scoreH20 = new Handler(Looper.getMainLooper());
    private boolean scoreLoop20 = false;

    private final Runnable scoreTick20 = new Runnable(){
        @Override public void run(){
            if(scoreLoop20){ updateIntelligence20(); scoreH20.postDelayed(this, 2200); }
        }
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        log("APP","SMART-DASHBOARD","v"+BuildConfig.VERSION_NAME+" • orange/blue identity • network score • RF stability");
        applyBrand20();
        scoreLoop20 = true;
        scoreH20.removeCallbacks(scoreTick20);
        scoreH20.postDelayed(scoreTick20, 900);
    }

    @Override protected void onDestroy(){
        scoreLoop20=false;
        scoreH20.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void applyBrand20(){
        try{
            getWindow().setStatusBarColor(SH_BLUE_DARK);
            getWindow().setNavigationBarColor(Color.rgb(12,18,28));
            status.setBackgroundColor(SH_BLUE_DARK);

            View head=root.getChildAt(0);
            if(head instanceof ViewGroup){
                ViewGroup hg=(ViewGroup)head;
                if(hg.getChildCount()>0 && hg.getChildAt(0) instanceof ViewGroup){
                    ViewGroup names=(ViewGroup)hg.getChildAt(0);
                    if(names.getChildCount()>0 && names.getChildAt(0) instanceof TextView){
                        ((TextView)names.getChildAt(0)).setTextColor(SH_ORANGE);
                    }
                    if(names.getChildCount()>1 && names.getChildAt(1) instanceof TextView){
                        ((TextView)names.getChildAt(1)).setText("Huawei E5785Lh-22c • Smart Edition • v"+BuildConfig.VERSION_NAME);
                        ((TextView)names.getChildAt(1)).setTextColor(Color.rgb(132,183,255));
                    }
                }
            }
            if(nav!=null){
                nav.setBackgroundColor(Color.rgb(15,24,38));
                for(int i=0;i<nav.getChildCount();i++){
                    View v=nav.getChildAt(i);
                    if(v instanceof TextView)((TextView)v).setTextColor(i==0?SH_ORANGE:Color.rgb(167,188,219));
                }
            }
        }catch(Exception ignored){}
    }

    @Override void page(int p){
        super.page(p);
        try{
            for(int i=0;i<nav.getChildCount();i++){
                View v=nav.getChildAt(i);
                if(v instanceof TextView){
                    ((TextView)v).setTextColor(i==p?SH_ORANGE:Color.rgb(167,188,219));
                    v.setBackgroundColor(i==p?Color.rgb(29,47,72):Color.TRANSPARENT);
                }
            }
        }catch(Exception ignored){}
    }

    @Override void home(){
        section("حالة الشبكة الذكية","ملخص مباشر يفهم الإشارة بدل عرض أرقام فقط");
        smartHero20();

        section("الاتصال والإشارة","قراءات Huawei HiLink الحية");
        card(new String[][]{
                {"الموديل","model"},{"المشغل","operator"},{"نوع الشبكة","network"},{"Band / التردد","bandFriendly"},
                {"RSRP","rsrp"},{"RSRQ","rsrq"},{"SINR","sinr"},{"Cell ID","cell"}
        });

        section("السرعة والاستهلاك","سرعة لحظية من عدادات الراوتر بدون استهلاك Speed Test");
        card(new String[][]{
                {"تنزيل لحظي","liveDl"},{"رفع لحظي","liveUl"},{"إجمالي تنزيل","totalDl"},{"إجمالي رفع","totalUl"},
                {"مدة الاتصال","uptime"},{"أجهزة Wi‑Fi","hosts"},{"WAN IP","wanip"},{"SSID","ssid"}
        });

        section("اختصارات","أكثر الأدوات استخدامًا");
        quickGrid20();
        live=true;tick();if(logged)refreshAll();
    }

    private void smartHero20(){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackground(shape(SH_PANEL,22));

        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView score=text(values.getOrDefault("networkScore","—"),32,SH_ORANGE);score.setGravity(Gravity.CENTER);
        score.setBackground(shape(Color.rgb(45,42,39),18));fields.put("networkScore",score);
        top.addView(score,new LinearLayout.LayoutParams(dp(92),dp(76)));

        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(12),0,0,0);
        TextView q=text(values.getOrDefault("qualityLabel","جارٍ تحليل الشبكة…"),17,TEXT);q.setPadding(0,0,0,0);fields.put("qualityLabel",q);
        TextView hint=text(values.getOrDefault("smartHint","سنقارن RSRP وRSRQ وSINR والثبات تلقائيًا."),11,MUTED);hint.setPadding(0,dp(3),0,0);fields.put("smartHint",hint);
        info.addView(q);info.addView(hint);top.addView(info,new LinearLayout.LayoutParams(0,-2,1));c.addView(top);

        LinearLayout badges=new LinearLayout(this);badges.setPadding(0,dp(12),0,0);
        addBadge20(badges,"جودة RF","qualityGrade",SH_BLUE);
        addBadge20(badges,"الثبات","stabilityGrade",SH_ORANGE_DARK);
        addBadge20(badges,"السرعة","speedGrade",SH_BLUE_DARK);
        c.addView(badges);

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));body.addView(c,lp);
    }

    private void addBadge20(LinearLayout row,String label,String key,int color){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);
        box.setPadding(dp(5),dp(7),dp(5),dp(7));box.setBackground(shape(color,16));
        TextView a=text(label,10,Color.rgb(225,235,249));a.setGravity(Gravity.CENTER);a.setPadding(0,0,0,0);
        TextView b=text(values.getOrDefault(key,"—"),12,Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(0,dp(2),0,0);fields.put(key,b);
        box.addView(a);box.addView(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(62),1);p.setMargins(dp(3),0,dp(3),0);row.addView(box,p);
    }

    private void quickGrid20(){
        LinearLayout r1=new LinearLayout(this);r1.setPadding(0,dp(4),0,dp(4));
        r1.addView(btn("Auto Band",SH_BLUE,v->autoBand20()),new LinearLayout.LayoutParams(0,dp(60),1));
        Space s1=new Space(this);r1.addView(s1,new LinearLayout.LayoutParams(dp(8),1));
        r1.addView(btn("تشخيص ذكي",SH_ORANGE_DARK,v->smartDiag()),new LinearLayout.LayoutParams(0,dp(60),1));body.addView(r1);

        LinearLayout r2=new LinearLayout(this);r2.setPadding(0,dp(4),0,dp(4));
        r2.addView(btn("الأجهزة المتصلة",SH_BLUE_DARK,v->hostsDialog()),new LinearLayout.LayoutParams(0,dp(60),1));
        Space s2=new Space(this);r2.addView(s2,new LinearLayout.LayoutParams(dp(8),1));
        r2.addView(btn("إعادة تشغيل",SH_ORANGE,v->confirmReboot20()),new LinearLayout.LayoutParams(0,dp(60),1));body.addView(r2);
    }

    private void autoBand20(){
        String mask=prefs.getString("bestBandMask","");
        String name=prefs.getString("bestBandName","");
        if(mask.isEmpty()){
            show("Auto Band","ما فيش تردد متعلم حتى الآن. شغّل Benchmark تلقائي أولًا، وبعدها التطبيق يحفظ أفضل Band ويربطه بالخلية الحالية.");
            benchmark();return;
        }
        show("Auto Band","أفضل تردد متعلم حاليًا: "+(name.isEmpty()?mask:name)+"\n\nسيتم استخدام محرك Auto Band الآمن الموجود في مركز الشبكة الذكي.");
        try{showSmartCenter20();}catch(Exception e){benchmark();}
    }

    private void showSmartCenter20(){
        // V19 already exposes its smart hub through the chip. Keep implementation centralized there.
        View chip=root.getChildCount()>2?root.getChildAt(2):null;
        if(chip!=null)chip.performClick();
    }

    private void confirmReboot20(){
        new AlertDialog.Builder(this)
                .setTitle("إعادة تشغيل الراوتر")
                .setMessage("سيتم قطع Wi‑Fi والإنترنت مؤقتًا إلى أن يشتغل الراوتر من جديد. هل تبي تكمل؟")
                .setNegativeButton("إلغاء",null)
                .setPositiveButton("إعادة تشغيل",(d,w)->reboot())
                .show();
    }

    @Override void networkPage(){
        super.networkPage();
        section("دليل الترددات","عرض مبسط للترددات الشائعة بدل أرقام Band فقط");
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(10),dp(14),dp(10));c.setBackground(shape(SH_PANEL,20));
        String[] rows={"B1  •  2100 MHz","B3  •  1800 MHz","B5  •  850 MHz","B7  •  2600 MHz","B8  •  900 MHz","B20 •  800 MHz","B32 •  1500 MHz SDL","B38 •  2600 MHz TDD"};
        for(int i=0;i<rows.length;i++){
            TextView t=text(rows[i],13,(i%2==0)?Color.rgb(159,198,255):Color.rgb(255,188,119));t.setPadding(dp(8),dp(6),dp(8),dp(6));c.addView(t);
        }
        body.addView(c,new LinearLayout.LayoutParams(-1,-2));
    }

    private void updateIntelligence20(){
        String r=values.get("rsrp"), q=values.get("rsrq"), s=values.get("sinr");
        double rsrp=num20(r),rsrq=num20(q),sinr=num20(s);
        if(validRsrp20(rsrp)){
            rsrp20.addLast(rsrp);while(rsrp20.size()>12)rsrp20.removeFirst();
        }
        int rf=rfScore20(rsrp,rsrq,sinr);int stability=stabilityScore20();int speed=speedScore20(values.get("liveDl"));
        int overall=(int)Math.round(rf*.58 + stability*.22 + speed*.20);overall=Math.max(0,Math.min(100,overall));

        String quality=grade20(overall);String rfGrade=grade20(rf);String stableGrade=grade20(stability);String speedGrade=grade20(speed);
        set("networkScore",overall+"/100");set("qualityLabel","جودة الشبكة: "+quality);
        set("qualityGrade",rfGrade);set("stabilityGrade",stableGrade);set("speedGrade",speedGrade);
        set("bandFriendly",friendlyBand20(values.get("band")));
        set("smartHint",hint20(rsrp,rsrq,sinr,stability,overall));
    }

    private double num20(String x){
        if(x==null)return Double.NaN;try{return Double.parseDouble(x.replaceAll("[^0-9.+-]","").trim());}catch(Exception e){return Double.NaN;}
    }
    private boolean validRsrp20(double x){return !Double.isNaN(x)&&x<-35&&x>-150;}
    private int rfScore20(double r,double q,double s){
        int n=0,w=0;
        if(validRsrp20(r)){n+=clamp20((int)Math.round((r+120)*100/45.0))*45;w+=45;}
        if(!Double.isNaN(q)&&q<0&&q>-40){n+=clamp20((int)Math.round((q+20)*100/17.0))*20;w+=20;}
        if(!Double.isNaN(s)&&s>-30&&s<50){n+=clamp20((int)Math.round((s+5)*100/30.0))*35;w+=35;}
        return w==0?50:n/w;
    }
    private int stabilityScore20(){
        if(rsrp20.size()<4)return 60;double sum=0;for(double x:rsrp20)sum+=x;double mean=sum/rsrp20.size();double v=0;for(double x:rsrp20){double d=x-mean;v+=d*d;}double sd=Math.sqrt(v/rsrp20.size());return clamp20((int)Math.round(100-sd*12));
    }
    private int speedScore20(String v){
        if(v==null||v.trim().isEmpty()||"—".equals(v.trim()))return 55;double x=num20(v);if(Double.isNaN(x))return 55;String z=v.toLowerCase(Locale.US);
        double mbps=x;if(z.contains("kb/s")||z.contains("kbps"))mbps=x/1000.0;else if(z.contains("mb/s"))mbps=x*8.0;
        if(mbps>=40)return 100;if(mbps>=20)return 88;if(mbps>=10)return 76;if(mbps>=5)return 64;if(mbps>=2)return 50;if(mbps>0)return 35;return 20;
    }
    private int clamp20(int x){return Math.max(0,Math.min(100,x));}
    private String grade20(int n){if(n>=88)return "ممتاز";if(n>=74)return "جيد جدًا";if(n>=60)return "جيد";if(n>=45)return "متوسط";if(n>=30)return "ضعيف";return "ضعيف جدًا";}
    private String hint20(double r,double q,double s,int stability,int overall){
        if(overall>=85)return "الوضع ممتاز. لا تغيّر مكان الراوتر إلا لو تبي تختبر Band أسرع.";
        if(!Double.isNaN(s)&&s<5)return "التشويش مرتفع (SINR منخفض). جرّب مكان أعلى أو Band مختلف.";
        if(validRsrp20(r)&&r<-105)return "الإشارة ضعيفة. قرّب الراوتر من نافذة أو مكان مفتوح ثم شغّل Auto Band.";
        if(!Double.isNaN(q)&&q<-15)return "جودة الخلية ضعيفة رغم وجود إشارة. Benchmark قد يلقى Band أنظف.";
        if(stability<50)return "الإشارة تتذبذب. ثبّت مكان الراوتر وراقب الرسم الحي قبل تغيير الإعدادات.";
        return "الشبكة قابلة للتحسين. شغّل Benchmark وخلي التطبيق يقارن الترددات الآمنة.";
    }
    private String friendlyBand20(String b){
        if(b==null||b.trim().isEmpty()||"—".equals(b.trim()))return "—";String x=b.replaceAll("[^0-9]","");
        if("1".equals(x))return "B1 • 2100 MHz";if("3".equals(x))return "B3 • 1800 MHz";if("5".equals(x))return "B5 • 850 MHz";if("7".equals(x))return "B7 • 2600 MHz";
        if("8".equals(x))return "B8 • 900 MHz";if("20".equals(x))return "B20 • 800 MHz";if("32".equals(x))return "B32 • 1500 MHz SDL";if("38".equals(x))return "B38 • 2600 MHz TDD";return b;
    }
}
