package com.shahboun.e5785;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** v1.8.0 smart dashboard + orange/blue identity, built on all V19 features. */
public class MainActivityV20 extends MainActivityV19 {
    private static final int SH_BLUE=Color.rgb(35,116,225);
    private static final int SH_BLUE_DARK=Color.rgb(24,73,145);
    private static final int SH_ORANGE=Color.rgb(255,145,30);
    private static final int SH_ORANGE_DARK=Color.rgb(176,86,18);
    private static final int SH_PANEL=Color.rgb(27,35,48);
    private final ArrayDeque<Double> rsrpHist=new ArrayDeque<>();
    private final Handler scoreH=new Handler(Looper.getMainLooper());
    private boolean scoreLoop;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        applyBrand();
        scoreLoop=true;
        scoreH.postDelayed(scoreTick,1000);
        log("APP","SMART-DASHBOARD","v"+BuildConfig.VERSION_NAME+" • orange/blue • score • RF stability");
    }

    @Override protected void onDestroy(){scoreLoop=false;scoreH.removeCallbacksAndMessages(null);super.onDestroy();}

    private final Runnable scoreTick=new Runnable(){@Override public void run(){if(scoreLoop){updateScore();scoreH.postDelayed(this,2200);}}};

    private void applyBrand(){
        try{
            getWindow().setStatusBarColor(SH_BLUE_DARK);
            getWindow().setNavigationBarColor(Color.rgb(12,18,28));
            status.setBackgroundColor(SH_BLUE_DARK);
            View head=root.getChildAt(0);
            if(head instanceof ViewGroup){ViewGroup h=(ViewGroup)head;if(h.getChildCount()>0&&h.getChildAt(0) instanceof ViewGroup){ViewGroup n=(ViewGroup)h.getChildAt(0);if(n.getChildCount()>0&&n.getChildAt(0) instanceof TextView)((TextView)n.getChildAt(0)).setTextColor(SH_ORANGE);if(n.getChildCount()>1&&n.getChildAt(1) instanceof TextView){((TextView)n.getChildAt(1)).setText("Huawei E5785Lh-22c • Smart Edition • v"+BuildConfig.VERSION_NAME);((TextView)n.getChildAt(1)).setTextColor(Color.rgb(132,183,255));}}}
            if(nav!=null)nav.setBackgroundColor(Color.rgb(15,24,38));
        }catch(Exception ignored){}
    }

    @Override void page(int p){super.page(p);try{for(int i=0;i<nav.getChildCount();i++){View v=nav.getChildAt(i);if(v instanceof TextView){((TextView)v).setTextColor(i==p?SH_ORANGE:Color.rgb(167,188,219));v.setBackgroundColor(i==p?Color.rgb(29,47,72):Color.TRANSPARENT);}}}catch(Exception ignored){}}

    @Override void home(){
        section("حالة الشبكة الذكية","تقييم مباشر لجودة الاتصال بدل الأرقام فقط");
        hero();
        section("الاتصال والإشارة","قراءات Huawei HiLink الحية");
        card(new String[][]{{"الموديل","model"},{"المشغل","operator"},{"نوع الشبكة","network"},{"Band / التردد","bandFriendly"},{"RSRP","rsrp"},{"RSRQ","rsrq"},{"SINR","sinr"},{"Cell ID","cell"}});
        section("السرعة والاستهلاك","سرعة لحظية من عدادات الراوتر");
        card(new String[][]{{"تنزيل لحظي","liveDl"},{"رفع لحظي","liveUl"},{"إجمالي تنزيل","totalDl"},{"إجمالي رفع","totalUl"},{"مدة الاتصال","uptime"},{"أجهزة Wi‑Fi","hosts"},{"WAN IP","wanip"},{"SSID","ssid"}});
        section("اختصارات","أكثر الأدوات استخدامًا");quickGrid();
        live=true;tick();if(logged)refreshAll();
    }

    private void hero(){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackground(shape(SH_PANEL,22));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView score=text(values.getOrDefault("networkScore","—"),30,SH_ORANGE);score.setGravity(Gravity.CENTER);score.setBackground(shape(Color.rgb(45,42,39),18));fields.put("networkScore",score);top.addView(score,new LinearLayout.LayoutParams(dp(92),dp(76)));
        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(12),0,0,0);
        TextView q=text(values.getOrDefault("qualityLabel","جارٍ تحليل الشبكة…"),17,TEXT);q.setPadding(0,0,0,0);fields.put("qualityLabel",q);
        TextView hint=text(values.getOrDefault("smartHint","يتم تحليل RSRP وRSRQ وSINR والثبات تلقائيًا."),11,MUTED);hint.setPadding(0,dp(3),0,0);fields.put("smartHint",hint);info.addView(q);info.addView(hint);top.addView(info,new LinearLayout.LayoutParams(0,-2,1));c.addView(top);
        LinearLayout badges=new LinearLayout(this);badges.setPadding(0,dp(12),0,0);badge(badges,"جودة RF","qualityGrade",SH_BLUE);badge(badges,"الثبات","stabilityGrade",SH_ORANGE_DARK);badge(badges,"السرعة","speedGrade",SH_BLUE_DARK);c.addView(badges);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));body.addView(c,lp);
    }

    private void badge(LinearLayout row,String label,String key,int color){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setPadding(dp(5),dp(7),dp(5),dp(7));box.setBackground(shape(color,16));TextView a=text(label,10,Color.rgb(225,235,249));a.setGravity(Gravity.CENTER);a.setPadding(0,0,0,0);TextView b=text(values.getOrDefault(key,"—"),12,Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(0,dp(2),0,0);fields.put(key,b);box.addView(a);box.addView(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(62),1);p.setMargins(dp(3),0,dp(3),0);row.addView(box,p);}

    private TextView colorBtn(String s,int color,View.OnClickListener l){TextView b=btn(s,l);b.setBackground(shape(color,18));return b;}
    private void quickGrid(){LinearLayout r1=new LinearLayout(this);r1.setPadding(0,dp(4),0,dp(4));r1.addView(colorBtn("Auto Band",SH_BLUE,v->benchmark()),new LinearLayout.LayoutParams(0,dp(60),1));r1.addView(new Space(this),new LinearLayout.LayoutParams(dp(8),1));r1.addView(colorBtn("تشخيص ذكي",SH_ORANGE_DARK,v->smartDiag()),new LinearLayout.LayoutParams(0,dp(60),1));body.addView(r1);LinearLayout r2=new LinearLayout(this);r2.setPadding(0,dp(4),0,dp(4));r2.addView(colorBtn("الأجهزة المتصلة",SH_BLUE_DARK,v->hostsDialog()),new LinearLayout.LayoutParams(0,dp(60),1));r2.addView(new Space(this),new LinearLayout.LayoutParams(dp(8),1));r2.addView(colorBtn("إعادة تشغيل",SH_ORANGE,v->confirmReboot()),new LinearLayout.LayoutParams(0,dp(60),1));body.addView(r2);}

    private void confirmReboot(){new AlertDialog.Builder(this).setTitle("إعادة تشغيل الراوتر").setMessage("سيتم قطع Wi‑Fi والإنترنت مؤقتًا إلى أن يشتغل الراوتر من جديد.").setNegativeButton("إلغاء",null).setPositiveButton("إعادة تشغيل",(d,w)->reboot()).show();}

    @Override void networkPage(){super.networkPage();section("دليل الترددات","الترددات الشائعة بشكل واضح");LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(10),dp(14),dp(10));c.setBackground(shape(SH_PANEL,20));String[] rows={"B1 • 2100 MHz","B3 • 1800 MHz","B5 • 850 MHz","B7 • 2600 MHz","B8 • 900 MHz","B20 • 800 MHz","B32 • 1500 MHz SDL","B38 • 2600 MHz TDD"};for(int i=0;i<rows.length;i++){TextView t=text(rows[i],13,(i%2==0)?Color.rgb(159,198,255):Color.rgb(255,188,119));t.setPadding(dp(8),dp(6),dp(8),dp(6));c.addView(t);}body.addView(c,new LinearLayout.LayoutParams(-1,-2));}

    private void updateScore(){double r=num(values.get("rsrp")),q=num(values.get("rsrq")),s=num(values.get("sinr"));if(validRsrp(r)){rsrpHist.addLast(r);while(rsrpHist.size()>12)rsrpHist.removeFirst();}int rf=rfScore(r,q,s),stab=stabilityScore(),spd=speedScore(values.get("liveDl"));int all=clamp((int)Math.round(rf*.58+stab*.22+spd*.20));set("networkScore",all+"/100");set("qualityLabel","جودة الشبكة: "+grade(all));set("qualityGrade",grade(rf));set("stabilityGrade",grade(stab));set("speedGrade",grade(spd));set("bandFriendly",friendlyBand(values.get("band")));set("smartHint",hint(r,q,s,stab,all));}
    private double num(String x){if(x==null)return Double.NaN;try{return Double.parseDouble(x.replaceAll("[^0-9.+-]","").trim());}catch(Exception e){return Double.NaN;}}
    private boolean validRsrp(double x){return !Double.isNaN(x)&&x<-35&&x>-150;}
    private int rfScore(double r,double q,double s){int n=0,w=0;if(validRsrp(r)){n+=clamp((int)Math.round((r+120)*100/45.0))*45;w+=45;}if(!Double.isNaN(q)&&q<0&&q>-40){n+=clamp((int)Math.round((q+20)*100/17.0))*20;w+=20;}if(!Double.isNaN(s)&&s>-30&&s<50){n+=clamp((int)Math.round((s+5)*100/30.0))*35;w+=35;}return w==0?50:n/w;}
    private int stabilityScore(){if(rsrpHist.size()<4)return 60;double sum=0;for(double x:rsrpHist)sum+=x;double mean=sum/rsrpHist.size(),v=0;for(double x:rsrpHist){double d=x-mean;v+=d*d;}return clamp((int)Math.round(100-Math.sqrt(v/rsrpHist.size())*12));}
    private int speedScore(String v){if(v==null||v.trim().isEmpty()||"—".equals(v.trim()))return 55;double x=num(v);if(Double.isNaN(x))return 55;String z=v.toLowerCase(Locale.US);double mbps=x;if(z.contains("kb/s")||z.contains("kbps"))mbps=x/1000.0;else if(z.contains("mb/s"))mbps=x*8.0;if(mbps>=40)return 100;if(mbps>=20)return 88;if(mbps>=10)return 76;if(mbps>=5)return 64;if(mbps>=2)return 50;if(mbps>0)return 35;return 20;}
    private int clamp(int x){return Math.max(0,Math.min(100,x));}
    private String grade(int n){if(n>=88)return "ممتاز";if(n>=74)return "جيد جدًا";if(n>=60)return "جيد";if(n>=45)return "متوسط";if(n>=30)return "ضعيف";return "ضعيف جدًا";}
    private String hint(double r,double q,double s,int st,int all){if(all>=85)return "الوضع ممتاز. خليك على نفس المكان والتردد.";if(!Double.isNaN(s)&&s<5)return "التشويش مرتفع نسبيًا؛ جرّب Band مختلف أو غيّر مكان الراوتر.";if(validRsrp(r)&&r<-105)return "الإشارة ضعيفة؛ قرّب الراوتر من نافذة أو مكان أعلى.";if(!Double.isNaN(q)&&q<-14)return "جودة الخلية منخفضة؛ Auto Band ممكن يعطي نتيجة أفضل.";if(st<55)return "الإشارة متذبذبة؛ تابع مراقب الإشارة أو جرّب تثبيت الراوتر في مكان أفضل.";return "الاتصال مقبول. جرّب Benchmark إذا تبي تبحث عن Band أفضل.";}
    private String friendlyBand(String b){if(b==null||b.trim().isEmpty())return "—";String x=b.replaceAll("[^0-9]","");if("1".equals(x))return "B1 • 2100 MHz";if("3".equals(x))return "B3 • 1800 MHz";if("5".equals(x))return "B5 • 850 MHz";if("7".equals(x))return "B7 • 2600 MHz";if("8".equals(x))return "B8 • 900 MHz";if("20".equals(x))return "B20 • 800 MHz";if("32".equals(x))return "B32 • 1500 MHz SDL";if("38".equals(x))return "B38 • 2600 MHz TDD";return b;}
}
