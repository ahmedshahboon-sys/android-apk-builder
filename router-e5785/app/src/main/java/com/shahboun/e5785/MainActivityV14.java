package com.shahboun.e5785;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.util.*;

/**
 * Shahboun Router E5785 v1.4.0
 * - Full automatic band benchmark with safe original-state restore.
 * - Download / upload / latency / jitter / packet-loss + LTE signal scoring.
 * - Themed dialogs matching the application instead of Android white dialogs.
 * - Safer Snapshot and restore validation.
 * - Friendlier API errors and firmware status presentation.
 */
public class MainActivityV14 extends MainActivityV13 {
    private volatile boolean benchStop = false;
    private final List<BenchResult> lastBench = new ArrayList<>();

    private static final String[][] BAND_CONFIGS = {
            {"B1", "1", "03"},
            {"B3", "4", "03"},
            {"B7", "40", "03"},
            {"B8", "80", "03"},
            {"B20", "80000", "03"},
            {"B3+B7", "44", "03"},
            {"B3+B20", "80004", "03"},
            {"B7+B20", "80040", "03"},
            {"B3+B7+B20", "80044", "03"}
    };

    static class NetMetrics {
        double download, upload, ping, jitter, loss;
    }

    static class BenchResult {
        String name, mask, mode, rsrp="—", rsrq="—", sinr="—", rssi="—", cell="—", pci="—", earfcn="—";
        double download, upload, ping, jitter, loss, score;
        boolean ok;
        String error="";
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            View head = root.getChildAt(0);
            if (head instanceof ViewGroup) {
                View names = ((ViewGroup) head).getChildAt(0);
                if (names instanceof ViewGroup && ((ViewGroup) names).getChildCount() > 1) {
                    View sub = ((ViewGroup) names).getChildAt(1);
                    if (sub instanceof TextView) ((TextView) sub).setText("Huawei E5785Lh-22c Edition  •  v1.4.0");
                }
            }
        } catch (Exception ignored) {}
    }

    // --------------------------- THEMED DIALOGS ---------------------------

    private TextView dialogText(String s, int size, int color) {
        TextView t = text(s, size, color);
        t.setGravity(Gravity.RIGHT);
        t.setLineSpacing(0, 1.18f);
        return t;
    }

    private Dialog baseDialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    private LinearLayout dialogCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(16));
        card.setBackground(shape(SURF2, 24));
        return card;
    }

    private void finishDialogWindow(Dialog d) {
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(.72f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout((int)(getResources().getDisplayMetrics().widthPixels * .90f), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override void show(String title, String message) {
        styledMessage(title, message, null, null);
    }

    private void styledMessage(String title, String message, String extraLabel, Runnable extra) {
        Dialog d = baseDialog();
        LinearLayout card = dialogCard();
        TextView h1 = dialogText(title, 20, TEXT);
        h1.setPadding(0,0,0,dp(8));
        card.addView(h1);
        ScrollView sc = new ScrollView(this);
        TextView bodyTxt = dialogText(friendly(message), 13, MUTED);
        bodyTxt.setTextIsSelectable(true);
        sc.addView(bodyTxt);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.height = Math.min(dp(440), (int)(getResources().getDisplayMetrics().heightPixels*.52f));
        card.addView(sc, sp);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.LEFT);
        if (extraLabel != null && extra != null) {
            TextView e = btn(extraLabel, v -> { d.dismiss(); extra.run(); });
            actions.addView(e, new LinearLayout.LayoutParams(0, dp(50), 1));
            actions.addView(new Space(this), new LinearLayout.LayoutParams(dp(8),1));
        }
        TextView ok = btn("حسنًا", v -> d.dismiss());
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1,-2); ap.topMargin=dp(14);
        card.addView(actions, ap);
        d.setContentView(card);
        d.show();
        finishDialogWindow(d);
    }

    @Override void confirm(String title, String message, Runnable yes) {
        Dialog d = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogText(title, 20, TEXT));
        TextView m = dialogText(message, 13, MUTED); m.setPadding(0,dp(8),0,dp(12)); card.addView(m);
        LinearLayout row = new LinearLayout(this);
        TextView cancel = btn("إلغاء", v -> d.dismiss());
        TextView accept = btn("تأكيد", v -> { d.dismiss(); yes.run(); });
        accept.setTextColor(AMBER);
        row.addView(cancel,new LinearLayout.LayoutParams(0,dp(52),1));
        row.addView(new Space(this),new LinearLayout.LayoutParams(dp(8),1));
        row.addView(accept,new LinearLayout.LayoutParams(0,dp(52),1));
        card.addView(row);
        d.setContentView(card); d.show(); finishDialogWindow(d);
    }

    private String friendly(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "لا توجد بيانات.";
        if (raw.contains("SocketTimeoutException") || raw.toLowerCase(Locale.US).contains("timeout"))
            return "انتهت مهلة الاتصال. الراوتر لم يرد على هذه الوظيفة في الوقت المحدد.";
        if (raw.contains("<code>100002</code>"))
            return "هذه الوظيفة غير متاحة عبر Firmware جهازك الحالي.";
        if (raw.contains("<code>100003</code>"))
            return "الراوتر رفض العملية أو يتطلب صلاحية/طريقة طلب مختلفة.";
        if (raw.contains("<code>125001</code>") || raw.contains("<code>125002</code>") || raw.contains("<code>125003</code>"))
            return "انتهت جلسة HiLink أو رمز الحماية. التطبيق سيجدد الجلسة عند إعادة المحاولة.";
        return raw;
    }

    // Custom login, but reuse v1.3's proven private login engine through reflection so the
    // session/cookie/token state remains inside MainActivityV13.
    @Override void loginDialog() {
        if (loginBusy || isFinishing()) return;
        loginBusy = true;
        Dialog d = baseDialog();
        LinearLayout card = dialogCard();
        card.addView(dialogText("دخول الراوتر", 21, TEXT));
        TextView help = dialogText("Huawei E5785Lh-22c  •  اسم المستخدم ثابت: admin", 12, MUTED);
        help.setPadding(0,dp(4),0,dp(10)); card.addView(help);
        EditText pass = input("رمز إدارة الراوتر", password);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setTextColor(TEXT); pass.setHintTextColor(MUTED);
        card.addView(pass,new LinearLayout.LayoutParams(-1,dp(58)));
        LinearLayout row = new LinearLayout(this);
        TextView connect = btn("اتصال", null);
        row.addView(connect,new LinearLayout.LayoutParams(-1,dp(54)));
        card.addView(row);
        d.setCancelable(false); d.setContentView(card); d.show(); finishDialogWindow(d);
        connect.setOnClickListener(v -> {
            password = pass.getText()==null ? "" : pass.getText().toString();
            prefs.edit().putString("password",password).apply();
            connect.setEnabled(false); connect.setText("جارٍ الاتصال…"); status.setText("جارٍ إنشاء جلسة HiLink…");
            new Thread(() -> {
                String result;
                try {
                    Method m = MainActivityV13.class.getDeclaredMethod("loginSync", String.class);
                    m.setAccessible(true);
                    result = String.valueOf(m.invoke(this, password));
                } catch (Exception e) { result = "ERR:"+e; }
                final String r=result;
                runOnUiThread(() -> {
                    loginBusy=false;
                    if (r.startsWith("OK")) {
                        logged=true; d.dismiss(); status.setText("متصل • جلسة HiLink إدارية");
                        set("model","E5785Lh-22c"); probe(); refreshAll();
                    } else {
                        connect.setEnabled(true); connect.setText("اتصال");
                        status.setText("تعذر تسجيل دخول الإدارة");
                        styledMessage("تعذر الدخول", (r.contains("108002")||r.contains("108006")) ? "رمز الإدارة غير صحيح." : friendly(r), null, null);
                    }
                });
            }).start();
        });
    }

    // --------------------------- SAFER SNAPSHOT ---------------------------

    @Override void snapshot() {
        status.setText("جارٍ حفظ إعداد الشبكة الحالي…");
        new Thread(() -> {
            String x=getSync("/api/net/net-mode");
            String m=tagAny(x,"NetworkMode"), b=tagAny(x,"NetworkBand"), l=tagAny(x,"LTEBand");
            boolean ok=!bad(x)&&!m.isEmpty()&&!b.isEmpty()&&!l.isEmpty();
            if(ok) prefs.edit().putString("snapMode",m).putString("snapBand",b).putString("snapLte",l).apply();
            runOnUiThread(() -> { status.setText(ok?"تم حفظ الإعداد الأصلي":"تعذر حفظ الإعداد الأصلي"); toast(ok?"تم حفظ Snapshot كامل":"لم يتم الحفظ لأن القيم الأصلية غير مكتملة"); });
        }).start();
    }

    @Override void restoreBand() {
        String m=prefs.getString("snapMode",""), b=prefs.getString("snapBand",""), l=prefs.getString("snapLte","");
        if(m.isEmpty()||b.isEmpty()||l.isEmpty()){show("استرجاع الإعدادات","لا توجد لقطة إعدادات كاملة يمكن استرجاعها.");return;}
        status.setText("جارٍ استرجاع الإعداد الأصلي…");
        new Thread(() -> {
            String r=postSync("/api/net/net-mode",netXml(m,b,l));
            runOnUiThread(() -> {status.setText(okResponse(r)?"تم استرجاع الإعداد الأصلي":"فشل الاسترجاع"); show("استرجاع الإعدادات",okResponse(r)?"تمت إعادة إعداد الشبكة الأصلي بنجاح.":stateOf(r)); if(okResponse(r))h.postDelayed(this::refreshAll,3500);});
        }).start();
    }

    private String netXml(String mode,String band,String lte){return "<request><NetworkMode>"+mode+"</NetworkMode><NetworkBand>"+band+"</NetworkBand><LTEBand>"+lte+"</LTEBand></request>";}

    // --------------------------- FULL BAND BENCHMARK ---------------------------

    @Override void benchmark() {
        if(!logged){show("اختبار الترددات","سجّل الدخول للراوتر أولًا.");return;}
        benchStop=false; lastBench.clear();
        final Dialog progress=baseDialog();
        LinearLayout card=dialogCard();
        TextView title=dialogText("اختبار جميع الترددات",20,TEXT); card.addView(title);
        TextView sub=dialogText("سيتم حفظ الإعداد الأصلي، تجربة الترددات واحدًا واحدًا، ثم استرجاع الأصل قبل عرض التقرير.",12,MUTED); sub.setPadding(0,dp(4),0,dp(10)); card.addView(sub);
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(BAND_CONFIGS.length);bar.setProgress(0);card.addView(bar,new LinearLayout.LayoutParams(-1,dp(12)));
        TextView step=dialogText("التحضير…",15,TEXT);step.setPadding(0,dp(10),0,0);card.addView(step);
        TextView metrics=dialogText("",12,MUTED);card.addView(metrics);
        TextView stop=btn("إيقاف واسترجاع الإعداد الأصلي",v->{benchStop=true;v.setEnabled(false);((TextView)v).setText("جارٍ الإيقاف…");});
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(54));bp.topMargin=dp(12);card.addView(stop,bp);
        progress.setCancelable(false);progress.setContentView(card);progress.show();finishDialogWindow(progress);

        new Thread(() -> runBenchmark(progress,bar,step,metrics)).start();
    }

    private void runBenchmark(Dialog progress, ProgressBar bar, TextView step, TextView metrics) {
        String original=getSync("/api/net/net-mode");
        String om=tagAny(original,"NetworkMode"), ob=tagAny(original,"NetworkBand"), ol=tagAny(original,"LTEBand");
        if(bad(original)||om.isEmpty()||ob.isEmpty()||ol.isEmpty()){
            runOnUiThread(()->{progress.dismiss();show("اختبار الترددات","تعذر قراءة إعداد الشبكة الأصلي، لذلك تم إيقاف الاختبار لحماية الاتصال.\n\n"+stateOf(original));});return;
        }
        prefs.edit().putString("snapMode",om).putString("snapBand",ob).putString("snapLte",ol).apply();

        for(int i=0;i<BAND_CONFIGS.length;i++){
            if(benchStop)break;
            String[] cfg=BAND_CONFIGS[i]; final int idx=i;
            runOnUiThread(()->{bar.setProgress(idx);step.setText("التجربة "+(idx+1)+" من "+BAND_CONFIGS.length+"  •  "+cfg[0]);metrics.setText("تطبيق التردد وانتظار استقرار الشبكة…");});
            BenchResult br=new BenchResult();br.name=cfg[0];br.mask=cfg[1];br.mode=cfg[2];lastBench.add(br);
            String r=postSync("/api/net/net-mode",netXml(cfg[2],"3FFFFFFF",cfg[1]));
            if(!okResponse(r)){
                br.ok=false;br.error=stateOf(r);
                runOnUiThread(()->metrics.setText("لم يقبل الراوتر هذا الإعداد • سيتم الانتقال للتالي"));
                sleepMs(1500);continue;
            }
            sleepMs(6500);
            if(benchStop)break;
            String sig=getSync("/api/device/signal");
            if(bad(sig))sig=getSync("/api/monitoring/best-signal");
            br.rsrp=val(tagAny(sig,"rsrp","RSRP","LteRsrp"));
            br.rsrq=val(tagAny(sig,"rsrq","RSRQ","LteRsrq"));
            br.sinr=val(tagAny(sig,"sinr","SINR","LteSinr"));
            br.rssi=val(tagAny(sig,"rssi","RSSI","SignalStrength"));
            br.cell=val(tagAny(sig,"cell_id","CellID","cellid","CellId"));
            br.pci=val(tagAny(sig,"pci","PCI","PhysicalCellId"));
            br.earfcn=val(tagAny(sig,"earfcn","EARFCN","DlEarfcn"));
            runOnUiThread(()->metrics.setText("قياس Download / Upload / Ping / Jitter / Packet Loss…"));
            NetMetrics nm=measureNetwork();
            br.download=nm.download;br.upload=nm.upload;br.ping=nm.ping;br.jitter=nm.jitter;br.loss=nm.loss;
            br.score=score(br);br.ok=br.download>0 || br.upload>0 || br.ping>0;
            runOnUiThread(()->{bar.setProgress(idx+1);metrics.setText(String.format(Locale.US,"↓ %.1f Mbps   ↑ %.1f Mbps\nPing %.0f ms • Jitter %.0f ms • Loss %.0f%%\nRSRP %s • SINR %s",br.download,br.upload,br.ping,br.jitter,br.loss,br.rsrp,br.sinr));});
            sleepMs(1000);
        }

        runOnUiThread(()->{step.setText("استرجاع الإعداد الأصلي…");metrics.setText("لن نترك الراوتر على آخر تردد جُرّب.");});
        String restore=postSync("/api/net/net-mode",netXml(om,ob,ol));
        sleepMs(2500);
        BenchResult best=null;
        for(BenchResult r:lastBench)if(r.ok&&(best==null||r.score>best.score))best=r;
        final BenchResult finalBest=best;
        final boolean restored=okResponse(restore);
        runOnUiThread(()->{
            progress.dismiss();h.postDelayed(this::refreshAll,1200);
            if(benchStop){show("تم إيقاف الاختبار",restored?"تم إيقاف الاختبار واسترجاع الإعداد الأصلي.":"تم إيقاف الاختبار، لكن الراوتر لم يؤكد استرجاع الإعداد الأصلي. جرّب زر الاسترجاع الآمن.");return;}
            if(finalBest==null){show("نتيجة الاختبار","لم نحصل على قياسات ناجحة. تم "+(restored?"استرجاع":"محاولة استرجاع")+" الإعداد الأصلي.");return;}
            prefs.edit().putString("bestBandName",finalBest.name).putString("bestBandMask",finalBest.mask).putString("bestBandMode",finalBest.mode).putString("bestReport",reportText(finalBest)).apply();
            set("bestBand",finalBest.name+" • "+String.format(Locale.US,"%.1f Mbps",finalBest.download));
            showBenchmarkReport(finalBest,restored);
        });
    }

    private void showBenchmarkReport(BenchResult best, boolean restored) {
        Dialog d=baseDialog();LinearLayout card=dialogCard();
        card.addView(dialogText("تقرير اختبار الترددات",20,TEXT));
        TextView note=dialogText(restored?"تم إرجاع الراوتر تلقائيًا لإعدادك الأصلي حتى تختار بنفسك.":"تنبيه: لم يؤكد الراوتر استرجاع الإعداد الأصلي.",12,restored?GREEN:AMBER);note.setPadding(0,dp(4),0,dp(8));card.addView(note);
        StringBuilder all=new StringBuilder();
        int rank=1;
        List<BenchResult> sorted=new ArrayList<>(lastBench);sorted.sort((a,b)->Double.compare(b.score,a.score));
        for(BenchResult r:sorted){
            all.append(rank++).append(". ").append(r.name).append("\n");
            if(!r.ok){all.append("   فشل: ").append(r.error).append("\n\n");continue;}
            all.append(String.format(Locale.US,"   ↓ %.1f  ↑ %.1f Mbps • Ping %.0f ms • Jitter %.0f ms • Loss %.0f%%\n",r.download,r.upload,r.ping,r.jitter,r.loss));
            all.append("   RSRP ").append(r.rsrp).append(" • RSRQ ").append(r.rsrq).append(" • SINR ").append(r.sinr).append("\n\n");
        }
        all.append("الأفضل إجمالًا: ").append(best.name).append("\n").append(reportText(best));
        ScrollView sc=new ScrollView(this);TextView txt=dialogText(all.toString(),12,MUTED);txt.setTextIsSelectable(true);sc.addView(txt);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(430));card.addView(sc,sp);
        LinearLayout row=new LinearLayout(this);TextView keep=btn("الإعداد الأصلي",v->d.dismiss());TextView install=btn("تثبيت الأفضل",v->{d.dismiss();installBest(best);});install.setTextColor(GREEN);
        row.addView(keep,new LinearLayout.LayoutParams(0,dp(54),1));row.addView(new Space(this),new LinearLayout.LayoutParams(dp(8),1));row.addView(install,new LinearLayout.LayoutParams(0,dp(54),1));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(10);card.addView(row,rp);
        d.setContentView(card);d.show();finishDialogWindow(d);
    }

    private void installBest(BenchResult best){status.setText("جارٍ تثبيت "+best.name+"…");new Thread(()->{String r=postSync("/api/net/net-mode",netXml(best.mode,"3FFFFFFF",best.mask));runOnUiThread(()->{status.setText(okResponse(r)?"تم تثبيت "+best.name:"فشل تثبيت التردد");show("تثبيت أفضل تردد",okResponse(r)?"تم تثبيت "+best.name+". يمكنك دائمًا استخدام استرجاع Snapshot لإعادة الإعداد الأصلي.":stateOf(r));if(okResponse(r))h.postDelayed(this::refreshAll,3500);});}).start();}

    @Override void showBest(){String r=prefs.getString("bestReport","");if(r.isEmpty())show("أفضل تردد","لم يتم إجراء اختبار شامل بعد.");else show("أفضل تردد",prefs.getString("bestBandName","—")+"\n\n"+r);}

    private String reportText(BenchResult r){return String.format(Locale.US,"Download: %.1f Mbps\nUpload: %.1f Mbps\nPing: %.0f ms\nJitter: %.0f ms\nPacket Loss: %.0f%%\nRSRP: %s\nRSRQ: %s\nSINR: %s\nRSSI: %s",r.download,r.upload,r.ping,r.jitter,r.loss,r.rsrp,r.rsrq,r.sinr,r.rssi);}

    private double score(BenchResult r){double sinr=num(r.sinr);return r.download*4.0+r.upload*1.6+Math.max(-10,Math.min(30,sinr))*0.8-Math.min(400,r.ping)*0.07-Math.min(300,r.jitter)*0.05-r.loss*3.5;}
    private double num(String s){try{return Double.parseDouble(s.replaceAll("[^0-9.-]",""));}catch(Exception e){return 0;}}
    private String val(String s){return s==null||s.trim().isEmpty()?"—":s.trim();}
    private void sleepMs(long ms){try{Thread.sleep(ms);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}

    private NetMetrics measureNetwork(){NetMetrics n=new NetMetrics();n.download=measureDownloadSync();n.upload=measureUploadSync();double[] l=measureLatency();n.ping=l[0];n.jitter=l[1];n.loss=l[2];return n;}

    private double measureDownloadSync(){long bytes=0,start=System.nanoTime();HttpURLConnection c=null;try{URL u=new URL("https://speed.cloudflare.com/__down?bytes=3000000&x="+System.currentTimeMillis());c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(5000);c.setReadTimeout(12000);c.setUseCaches(false);try(InputStream in=c.getInputStream()){byte[] b=new byte[32768];int k;while((k=in.read(b))>0)bytes+=k;}double sec=(System.nanoTime()-start)/1e9;return sec>0?bytes*8/sec/1e6:0;}catch(Exception e){return 0;}finally{if(c!=null)c.disconnect();}}

    private double measureUploadSync(){int total=750000,sent=0;long start=System.nanoTime();HttpURLConnection c=null;try{c=(HttpURLConnection)new URL("https://speed.cloudflare.com/__up").openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setFixedLengthStreamingMode(total);c.setRequestProperty("Content-Type","application/octet-stream");byte[] block=new byte[32768];try(OutputStream out=c.getOutputStream()){while(sent<total){int n=Math.min(block.length,total-sent);out.write(block,0,n);sent+=n;}}int code=c.getResponseCode();try(InputStream in=code>=400?c.getErrorStream():c.getInputStream()){if(in!=null)while(in.read(block)>0){}}double sec=(System.nanoTime()-start)/1e9;return code<500&&sec>0?sent*8/sec/1e6:0;}catch(Exception e){return 0;}finally{if(c!=null)c.disconnect();}}

    private double[] measureLatency(){final int tries=7;List<Double> ok=new ArrayList<>();int fail=0;for(int i=0;i<tries;i++){HttpURLConnection c=null;long st=System.nanoTime();try{c=(HttpURLConnection)new URL("https://speed.cloudflare.com/__down?bytes=1&lat="+System.nanoTime()).openConnection();c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setUseCaches(false);try(InputStream in=c.getInputStream()){in.read();}ok.add((System.nanoTime()-st)/1e6);}catch(Exception e){fail++;}finally{if(c!=null)c.disconnect();}sleepMs(120);}if(ok.isEmpty())return new double[]{0,0,100};Collections.sort(ok);double ping=ok.get(ok.size()/2),jit=0;for(int i=1;i<ok.size();i++)jit+=Math.abs(ok.get(i)-ok.get(i-1));if(ok.size()>1)jit/=ok.size()-1;return new double[]{ping,jit,fail*100.0/tries};}

    // Replace the old label with the clear one requested by the user.
    @Override void networkPage(){section("الشبكة والترددات","Band Manager مع حفظ واسترجاع آمن");card(new String[][]{{"NetworkMode","netMode"},{"NetworkBand","netBand"},{"LTEBand","lteBand"},{"Cell ID","cell"},{"PCI","pci"},{"EARFCN","earfcn"},{"MIMO","mimo"},{"أفضل إعداد","bestBand"}});String[] names={"AUTO","B1","B3","B7","B8","B20","B3+B7","B3+B20","B7+B20","B3+B7+B20"};String[] masks={"20800800D5","1","4","40","80","80000","44","80004","80040","80044"};View.OnClickListener[] aa=new View.OnClickListener[names.length];for(int i=0;i<names.length;i++){final int j=i;aa[i]=v->applyBand(names[j],masks[j],j==0?"00":"03");}grid(names,aa);grid(new String[]{"حفظ Snapshot","استرجاع Snapshot","اختبار كل الترددات","الترددات المدعومة","بحث الشبكات","MIMO"},new View.OnClickListener[]{v->snapshot(),v->restoreBand(),v->benchmark(),v->showEndpoint("/api/net/net-mode-list","الترددات المدعومة"),v->showEndpoint("/api/net/plmn-list","الشبكات المتاحة"),v->showEndpoint("/api/device/mimo-setting","MIMO")});if(logged)refreshAll();}

    // --------------------------- FRIENDLIER API TOOLS ---------------------------

    @Override void showEndpoint(String p,String title){apiGet(p,x->{if(bad(x)){show(title,stateOf(x));return;}if("/api/online-update/status".equals(p)){String st=tagAny(x,"CurrentComponentStatus"),pr=tagAny(x,"DownloadProgress"),tot=tagAny(x,"TotalComponents");show(title,"حالة المكوّن: "+val(st)+"\nنسبة التنزيل: "+val(pr)+"%\nعدد المكونات: "+val(tot));return;}show(title,prettyXml(x));});}

    @Override void smsInbox(){status.setText("جارٍ قراءة الرسائل…");new Thread(()->{String req="<request><PageIndex>1</PageIndex><ReadCount>20</ReadCount><BoxType>1</BoxType><SortType>0</SortType><Ascending>0</Ascending><UnreadPreferred>0</UnreadPreferred></request>";String r=postSync("/api/sms/sms-list",req);if(r.contains("125003")){sleepMs(500);getSync("/api/webserver/SesTokInfo");r=postSync("/api/sms/sms-list",req);}final String rr=r;runOnUiThread(()->{status.setText("تمت محاولة قراءة الرسائل");show("صندوق SMS",bad(rr)?stateOf(rr):prettyXml(rr));});}).start();}

    @Override void powerDialog(){String battery=values.getOrDefault("battery","—");apiGet("/api/device/sleep-time",sl->{String sleep=bad(sl)?"—":val(tagAny(sl,"sleeptime","SleepTime"));show("البطارية والطاقة","البطارية الحالية: "+battery+"\nمهلة السكون: "+sleep+("—".equals(sleep)?"":" دقيقة")+"\n\nبعض نقاط API الإضافية للطاقة غير متاحة في Firmware الحالي، لذلك لا يتم عرض XML أخطاء للمستخدم.");});}
}
