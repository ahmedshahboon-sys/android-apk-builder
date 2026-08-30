package com.shahboun.e5785;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/**
 * v1.6.1
 * - Builds the benchmark from the LTE bands reported by /api/net/net-mode-list.
 * - Includes B5/B32/B38 when the modem reports them instead of using the old fixed list.
 * - Rejects false positives where PLMN exists but there is no registered LTE cell/signal.
 * - Waits for ConnectionStatus=901 and a real LTE cell before measuring.
 * - Verifies the original network mode after the benchmark and retries restore once if needed.
 */
public class MainActivityV17 extends MainActivityV16 {
    private volatile boolean benchStop17=false;
    private final List<BandResult17> results17=new ArrayList<>();

    private static final String[][] KNOWN_SINGLE_BANDS={
            {"B1","1"},{"B3","4"},{"B5","10"},{"B7","40"},
            {"B8","80"},{"B20","80000"},{"B32","80000000"},{"B38","2000000000"}
    };
    private static final String[][] COMMON_COMBOS={
            {"B3+B7","44"},{"B3+B20","80004"},{"B7+B20","80040"},{"B3+B7+B20","80044"}
    };

    static class BandResult17 {
        String name,mask,mode="03";
        String band="—",rsrp="—",rsrq="—",sinr="—",rssi="—",cell="—",pci="—",earfcn="—";
        double download,upload,ping,jitter,loss,score;
        boolean ok;
        String error="";
    }

    static class NetMetrics17 { double download,upload,ping,jitter,loss; }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        log("APP","BAND-FIX","v1.6.1 • dynamic supported bands • registered-cell validation • verified restore");
        try{
            View head=root.getChildAt(0);
            if(head instanceof ViewGroup){
                View names=((ViewGroup)head).getChildAt(0);
                if(names instanceof ViewGroup&&((ViewGroup)names).getChildCount()>1){
                    View sub=((ViewGroup)names).getChildAt(1);
                    if(sub instanceof TextView)((TextView)sub).setText("Huawei E5785Lh-22c Edition  •  v1.6.1");
                }
            }
        }catch(Exception ignored){}
    }

    private TextView dText17(String s,int size,int color){
        TextView t=text(s,size,color);t.setGravity(Gravity.RIGHT);t.setLineSpacing(0,1.18f);return t;
    }
    private Dialog dBase17(){Dialog d=new Dialog(this);d.requestWindowFeature(Window.FEATURE_NO_TITLE);return d;}
    private LinearLayout dCard17(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(20),dp(18),dp(20),dp(16));c.setBackground(shape(SURF2,24));return c;}
    private void finishD17(Dialog d){Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setDimAmount(.72f);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.90f),WindowManager.LayoutParams.WRAP_CONTENT);}}
    private void sleep17(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private String val17(String s){return s==null||s.trim().isEmpty()?"—":s.trim();}
    private String netXml17(String mode,String band,String lte){return "<request><NetworkMode>"+mode+"</NetworkMode><NetworkBand>"+band+"</NetworkBand><LTEBand>"+lte+"</LTEBand></request>";}

    @Override void benchmark(){
        log("ACTION","اختبار جميع الترددات v1.6.1","requested=true");
        if(!logged){show("اختبار الترددات","سجّل الدخول للراوتر أولًا.");return;}
        benchStop17=false;live=false;results17.clear();

        final Dialog progress=dBase17();
        LinearLayout card=dCard17();
        card.addView(dText17("اختبار جميع الترددات",20,TEXT));
        TextView note=dText17("سيتم اختبار الترددات التي يعلن الراوتر نفسه أنه يدعمها. أثناء تبديل التردد قد ينقطع الإنترنت مؤقتًا، وبعد النهاية سيتم التحقق من رجوع الإعداد الأصلي والاتصال.",12,MUTED);
        note.setPadding(0,dp(4),0,dp(10));card.addView(note);
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgress(0);card.addView(bar,new LinearLayout.LayoutParams(-1,dp(12)));
        TextView step=dText17("قراءة الترددات المدعومة…",15,TEXT);step.setPadding(0,dp(10),0,0);card.addView(step);
        TextView metrics=dText17("",12,MUTED);card.addView(metrics);
        TextView stop=btn("إيقاف واسترجاع الإعداد الأصلي",v->{benchStop17=true;v.setEnabled(false);((TextView)v).setText("جارٍ الإيقاف والاسترجاع…");});
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(54));bp.topMargin=dp(12);card.addView(stop,bp);
        progress.setCancelable(false);progress.setContentView(card);progress.show();finishD17(progress);
        new Thread(()->runBenchmark17(progress,bar,step,metrics),"e5785-band-benchmark-161").start();
    }

    private void runBenchmark17(Dialog progress,ProgressBar bar,TextView step,TextView metrics){
        String original=getSync("/api/net/net-mode");
        final String om=tagAny(original,"NetworkMode"),ob=tagAny(original,"NetworkBand"),ol=tagAny(original,"LTEBand");
        if(bad(original)||om.isEmpty()||ob.isEmpty()||ol.isEmpty()){
            finishBenchmarkUi17(progress,"اختبار الترددات","تعذر قراءة إعداد الشبكة الأصلي. تم إيقاف الاختبار قبل إرسال أي تغيير.");return;
        }
        prefs.edit().putString("snapMode",om).putString("snapBand",ob).putString("snapLte",ol).apply();

        String listXml=getSync("/api/net/net-mode-list");
        List<String[]> configs=buildSupportedConfigs17(listXml);
        if(configs.isEmpty()){
            restoreOriginal17(om,ob,ol);
            finishBenchmarkUi17(progress,"اختبار الترددات","تعذر تحديد قائمة LTE المدعومة من الراوتر، لذلك لم يتم إجبار الجهاز على قائمة ثابتة غير مؤكدة.");return;
        }
        log("BENCH","SUPPORTED-BANDS","count="+configs.size()+"\n"+joinConfigs17(configs));

        for(int i=0;i<configs.size();i++){
            if(benchStop17)break;
            final int idx=i,total=configs.size();
            final String[] cfg=configs.get(i);
            runOnUiThread(()->{
                bar.setProgress((idx*100)/Math.max(1,total));
                step.setText("التجربة "+(idx+1)+" من "+total+"  •  "+cfg[0]);
                metrics.setText("تبديل التردد… قد ينقطع الإنترنت مؤقتًا.\nبانتظار تسجيل LTE حقيقي وعودة اتصال البيانات…");
            });

            BandResult17 br=new BandResult17();br.name=cfg[0];br.mask=cfg[1];results17.add(br);
            String apply=postSync("/api/net/net-mode",netXml17("03","3FFFFFFF",cfg[1]));
            if(!okResponse(apply)){
                br.error=stateOf(apply);br.ok=false;
                if(isSessionFatal17(apply))break;
                sleep17(1000);continue;
            }

            String[] registered=waitForRegisteredSignal17(cfg[1],15000);
            if(registered==null){
                br.ok=false;br.error="لا يوجد تسجيل LTE/اتصال بيانات صالح على هذا الإعداد";
                log("BENCH","REJECT "+cfg[0],"reason=no registered LTE cell or ConnectionStatus!=901");
                continue;
            }
            String sig=registered[0];
            br.band=val17(tagAny(sig,"band","Band"));
            br.rsrp=val17(tagAny(sig,"rsrp","RSRP","LteRsrp"));
            br.rsrq=val17(tagAny(sig,"rsrq","RSRQ","LteRsrq"));
            br.sinr=val17(tagAny(sig,"sinr","SINR","LteSinr"));
            br.rssi=val17(tagAny(sig,"rssi","RSSI","SignalStrength"));
            br.cell=val17(tagAny(sig,"cell_id","CellID","cellid","CellId"));
            br.pci=val17(tagAny(sig,"pci","PCI","PhysicalCellId"));
            br.earfcn=val17(tagAny(sig,"earfcn","EARFCN","DlEarfcn"));

            runOnUiThread(()->metrics.setText("تم تسجيل LTE فعليًا على "+cfg[0]+".\nجاري قياس Download / Upload / Ping / Jitter / Packet Loss…"));
            NetMetrics17 nm=measureNetwork17();
            br.download=nm.download;br.upload=nm.upload;br.ping=nm.ping;br.jitter=nm.jitter;br.loss=nm.loss;
            br.ok=br.download>0||br.upload>0||br.ping>0;
            if(!br.ok){br.error="LTE مسجل لكن اختبار الإنترنت لم يعطِ قياسًا صالحًا";}
            br.score=score17(br);
            runOnUiThread(()->metrics.setText(String.format(Locale.US,"Band %s • RSRP %s • SINR %s\n↓ %.1f Mbps   ↑ %.1f Mbps\nPing %.0f ms • Jitter %.0f ms • Loss %.0f%%",br.band,br.rsrp,br.sinr,br.download,br.upload,br.ping,br.jitter,br.loss)));
        }

        runOnUiThread(()->{step.setText("استرجاع الإعداد الأصلي…");metrics.setText("يتم الآن التحقق من NetworkMode وLTEBand وعودة اتصال البيانات.");});
        boolean restored=restoreOriginal17(om,ob,ol);
        BandResult17 best=null;
        for(BandResult17 r:results17)if(r.ok&&(best==null||r.score>best.score))best=r;
        final BandResult17 finalBest=best;
        final boolean finalRestored=restored;
        runOnUiThread(()->{
            progress.dismiss();
            if(currentPage==0){live=true;tick();}
            h.postDelayed(this::refreshAll,1200);
            if(benchStop17){show("تم إيقاف الاختبار",finalRestored?"تم إيقاف الاختبار واسترجاع الإعداد الأصلي والتأكد من رجوع الاتصال.":"تم إيقاف الاختبار، لكن لم يكتمل تأكيد رجوع الاتصال. استخدم استرجاع Snapshot إذا لزم.");return;}
            if(finalBest==null){show("نتيجة الاختبار","لم يتم اعتماد أي نتيجة وهمية. لم نجد ترددًا اجتاز شروط: LTE Cell + RSRP + Band + ConnectionStatus=901 + قياس إنترنت.\n\n"+(finalRestored?"تم استرجاع الإعداد الأصلي والتأكد منه.":"تعذر تأكيد الاسترجاع بالكامل."));return;}
            prefs.edit().putString("bestBandName",finalBest.name).putString("bestBandMask",finalBest.mask).putString("bestBandMode",finalBest.mode).putString("bestReport",report17(finalBest)).apply();
            set("bestBand",finalBest.name+" • "+String.format(Locale.US,"%.1f Mbps",finalBest.download));
            showReport17(finalBest,finalRestored);
        });
    }

    private List<String[]> buildSupportedConfigs17(String xml){
        List<String[]> out=new ArrayList<>();
        long supported=0;
        if(!bad(xml)){
            try{
                Matcher section=Pattern.compile("(?is)<LTEBandList>(.*?)</LTEBandList>").matcher(xml);
                if(section.find()){
                    Matcher m=Pattern.compile("(?is)<LTEBand>.*?<Name>(.*?)</Name>.*?<Value>([0-9a-fA-F]+)</Value>.*?</LTEBand>").matcher(section.group(1));
                    while(m.find()){
                        String name=m.group(1).replace("&#x2F;","/");String value=m.group(2);
                        if(!name.toLowerCase(Locale.US).contains("all bands")){
                            try{supported|=Long.parseUnsignedLong(value,16);}catch(Exception ignored){}
                        }
                    }
                }
            }catch(Exception ignored){}
        }
        if(supported==0){
            // Do not invent model-wide support if the endpoint failed. The only fallback is the
            // exact original LTE mask already exposed by this connected modem.
            try{String current=getSync("/api/net/net-mode");supported=Long.parseUnsignedLong(tagAny(current,"LTEBand"),16);}catch(Exception ignored){}
        }
        for(String[] b:KNOWN_SINGLE_BANDS){long mask=parseHex17(b[1]);if(mask!=0&&(supported&mask)==mask)out.add(new String[]{b[0],b[1],"03"});}
        for(String[] b:COMMON_COMBOS){long mask=parseHex17(b[1]);if(mask!=0&&(supported&mask)==mask)out.add(new String[]{b[0],b[1],"03"});}
        return out;
    }

    private long parseHex17(String s){try{return Long.parseUnsignedLong(s,16);}catch(Exception e){return 0;}}
    private String joinConfigs17(List<String[]> x){StringBuilder s=new StringBuilder();for(String[] c:x)s.append(c[0]).append('=').append(c[1]).append('\n');return s.toString();}

    private String[] waitForRegisteredSignal17(String expectedMask,long timeoutMs){
        long end=SystemClock.elapsedRealtime()+timeoutMs;
        while(!benchStop17&&SystemClock.elapsedRealtime()<end){
            String statusXml=getSync("/api/monitoring/status");
            String sig=getSync("/api/device/signal");
            String conn=tagAny(statusXml,"ConnectionStatus");
            String band=tagAny(sig,"band","Band");
            String cell=tagAny(sig,"cell_id","CellID","cellid","CellId");
            String rsrp=tagAny(sig,"rsrp","RSRP","LteRsrp");
            String mode=tagAny(sig,"mode","Mode");
            boolean realSignal=!bad(sig)&&!band.isEmpty()&&!cell.isEmpty()&&!rsrp.isEmpty()&&!mode.isEmpty();
            boolean expected=realSignal&&maskContainsBand17(expectedMask,band);
            if("901".equals(conn)&&expected)return new String[]{sig,statusXml};
            sleep17(1000);
        }
        return null;
    }

    private boolean maskContainsBand17(String maskHex,String bandText){
        try{
            int b=Integer.parseInt(bandText.replaceAll("[^0-9]",""));
            if(b<1||b>63)return false;
            long bit=1L<<(b-1);return (parseHex17(maskHex)&bit)!=0;
        }catch(Exception e){return false;}
    }

    private boolean restoreOriginal17(String mode,String band,String lte){
        for(int attempt=1;attempt<=2;attempt++){
            String r=postSync("/api/net/net-mode",netXml17(mode,band,lte));
            if(!okResponse(r)){sleep17(1200);continue;}
            long end=SystemClock.elapsedRealtime()+25000;
            while(SystemClock.elapsedRealtime()<end){
                String current=getSync("/api/net/net-mode");
                String statusXml=getSync("/api/monitoring/status");
                boolean same=!bad(current)&&mode.equalsIgnoreCase(tagAny(current,"NetworkMode"))&&band.equalsIgnoreCase(tagAny(current,"NetworkBand"))&&lte.equalsIgnoreCase(tagAny(current,"LTEBand"));
                if(same&&"901".equals(tagAny(statusXml,"ConnectionStatus"))){
                    log("BENCH","RESTORE-VERIFIED","attempt="+attempt+"\nmode="+mode+"\nlte="+lte+"\nconnection=901");return true;
                }
                sleep17(1200);
            }
            log("BENCH","RESTORE-RETRY","attempt="+attempt+" did not verify mode+connection");
        }
        return false;
    }

    private boolean isSessionFatal17(String r){return r!=null&&(r.contains("<code>100003</code>")||r.contains("<code>125001</code>")||r.contains("<code>125002</code>")||r.contains("<code>125003</code>"));}

    private void finishBenchmarkUi17(Dialog progress,String title,String message){
        runOnUiThread(()->{if(progress.isShowing())progress.dismiss();if(currentPage==0){live=true;tick();}show(title,message);});
    }

    private double score17(BandResult17 r){double sinr=num17(r.sinr);return r.download*4.0+r.upload*1.6+Math.max(-10,Math.min(30,sinr))*0.8-Math.min(400,r.ping)*0.07-Math.min(300,r.jitter)*0.05-r.loss*3.5;}
    private double num17(String s){try{return Double.parseDouble(s.replaceAll("[^0-9.-]",""));}catch(Exception e){return 0;}}
    private String report17(BandResult17 r){return String.format(Locale.US,"Band: %s\nDownload: %.1f Mbps\nUpload: %.1f Mbps\nPing: %.0f ms\nJitter: %.0f ms\nPacket Loss: %.0f%%\nRSRP: %s\nRSRQ: %s\nSINR: %s\nRSSI: %s",r.band,r.download,r.upload,r.ping,r.jitter,r.loss,r.rsrp,r.rsrq,r.sinr,r.rssi);}

    private void showReport17(BandResult17 best,boolean restored){
        Dialog d=dBase17();LinearLayout card=dCard17();
        card.addView(dText17("تقرير اختبار الترددات",20,TEXT));
        TextView n=dText17(restored?"تم إرجاع الراوتر للإعداد الأصلي والتأكد من عودة الاتصال.":"تنبيه: لم يكتمل تأكيد الاسترجاع والاتصال.",12,restored?GREEN:AMBER);n.setPadding(0,dp(4),0,dp(8));card.addView(n);
        List<BandResult17> sorted=new ArrayList<>(results17);sorted.sort((a,b)->Double.compare(b.score,a.score));
        StringBuilder all=new StringBuilder();int rank=1;
        for(BandResult17 r:sorted){all.append(rank++).append(". ").append(r.name).append("\n");if(!r.ok){all.append("   غير معتمد: ").append(r.error).append("\n\n");continue;}all.append(String.format(Locale.US,"   LTE Band %s • ↓ %.1f ↑ %.1f Mbps • Ping %.0f ms • Loss %.0f%%\n",r.band,r.download,r.upload,r.ping,r.loss));all.append("   RSRP ").append(r.rsrp).append(" • RSRQ ").append(r.rsrq).append(" • SINR ").append(r.sinr).append("\n\n");}
        all.append("الأفضل إجمالًا: ").append(best.name).append("\n").append(report17(best));
        ScrollView sc=new ScrollView(this);TextView txt=dText17(all.toString(),12,MUTED);txt.setTextIsSelectable(true);sc.addView(txt);card.addView(sc,new LinearLayout.LayoutParams(-1,dp(430)));
        LinearLayout row=new LinearLayout(this);TextView keep=btn("الإعداد الأصلي",v->d.dismiss());TextView install=btn("تثبيت الأفضل",v->{d.dismiss();installBest17(best);});install.setTextColor(GREEN);row.addView(keep,new LinearLayout.LayoutParams(0,dp(54),1));row.addView(new Space(this),new LinearLayout.LayoutParams(dp(8),1));row.addView(install,new LinearLayout.LayoutParams(0,dp(54),1));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(10);card.addView(row,rp);
        d.setContentView(card);d.show();finishD17(d);
    }

    private void installBest17(BandResult17 best){
        status.setText("جارٍ تثبيت "+best.name+"…");new Thread(()->{String r=postSync("/api/net/net-mode",netXml17("03","3FFFFFFF",best.mask));String[] reg=okResponse(r)?waitForRegisteredSignal17(best.mask,20000):null;boolean ok=okResponse(r)&&reg!=null;runOnUiThread(()->{status.setText(ok?"تم تثبيت "+best.name:"لم يتم اعتماد التثبيت");show("تثبيت أفضل تردد",ok?"تم تثبيت "+best.name+" والتأكد من تسجيل LTE وعودة اتصال البيانات.":"أرسل الراوتر التغيير لكن لم نؤكد تسجيل LTE واتصال البيانات؛ يمكنك استرجاع Snapshot.");if(ok)h.postDelayed(this::refreshAll,1200);});},"e5785-install-band").start();
    }

    private NetMetrics17 measureNetwork17(){NetMetrics17 n=new NetMetrics17();n.download=measureDownload17();n.upload=measureUpload17();double[] l=measureLatency17();n.ping=l[0];n.jitter=l[1];n.loss=l[2];return n;}
    private double measureDownload17(){long bytes=0,start=System.nanoTime();HttpURLConnection c=null;try{URL u=new URL("https://speed.cloudflare.com/__down?bytes=3000000&x="+System.currentTimeMillis());c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(5000);c.setReadTimeout(12000);c.setUseCaches(false);try(InputStream in=c.getInputStream()){byte[] b=new byte[32768];int k;while((k=in.read(b))>0)bytes+=k;}double sec=(System.nanoTime()-start)/1e9;return sec>0?bytes*8/sec/1e6:0;}catch(Exception e){return 0;}finally{if(c!=null)c.disconnect();}}
    private double measureUpload17(){int total=750000,sent=0;long start=System.nanoTime();HttpURLConnection c=null;try{c=(HttpURLConnection)new URL("https://speed.cloudflare.com/__up").openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setFixedLengthStreamingMode(total);c.setRequestProperty("Content-Type","application/octet-stream");byte[] block=new byte[32768];try(OutputStream out=c.getOutputStream()){while(sent<total){int n=Math.min(block.length,total-sent);out.write(block,0,n);sent+=n;}}int code=c.getResponseCode();try(InputStream in=code>=400?c.getErrorStream():c.getInputStream()){if(in!=null)while(in.read(block)>0){}}double sec=(System.nanoTime()-start)/1e9;return code<500&&sec>0?sent*8/sec/1e6:0;}catch(Exception e){return 0;}finally{if(c!=null)c.disconnect();}}
    private double[] measureLatency17(){final int tries=7;List<Double> ok=new ArrayList<>();int fail=0;for(int i=0;i<tries;i++){HttpURLConnection c=null;long st=System.nanoTime();try{c=(HttpURLConnection)new URL("https://speed.cloudflare.com/__down?bytes=1&lat="+System.nanoTime()).openConnection();c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setUseCaches(false);try(InputStream in=c.getInputStream()){in.read();}ok.add((System.nanoTime()-st)/1e6);}catch(Exception e){fail++;}finally{if(c!=null)c.disconnect();}sleep17(120);}if(ok.isEmpty())return new double[]{0,0,100};Collections.sort(ok);double ping=ok.get(ok.size()/2),jit=0;for(int i=1;i<ok.size();i++)jit+=Math.abs(ok.get(i)-ok.get(i-1));if(ok.size()>1)jit/=ok.size()-1;return new double[]{ping,jit,fail*100.0/tries};}
}
