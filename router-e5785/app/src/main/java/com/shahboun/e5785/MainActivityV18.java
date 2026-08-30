package com.shahboun.e5785;

import android.os.SystemClock;

/** v1.6.1 hotfix: make the active PLMN scan restore the radio/data session afterwards. */
public class MainActivityV18 extends MainActivityV17 {

    @Override void showEndpoint(String path,String title){
        if(!"/api/net/plmn-list".equals(path)){
            super.showEndpoint(path,title);
            return;
        }
        safePlmnScan();
    }

    private void safePlmnScan(){
        if(!logged){show("بحث الشبكات","سجّل الدخول للراوتر أولًا.");return;}
        log("ACTION","بحث الشبكات الآمن","capture mode -> PLMN scan -> restore -> verify data");
        live=false;
        status.setText("جارٍ حفظ حالة الشبكة قبل البحث…");
        new Thread(()->{
            String original=getSync("/api/net/net-mode");
            String om=tagAny(original,"NetworkMode"),ob=tagAny(original,"NetworkBand"),ol=tagAny(original,"LTEBand");
            if(bad(original)||om.isEmpty()||ob.isEmpty()||ol.isEmpty()){
                runOnUiThread(()->{resumeLive18();show("بحث الشبكات","تعذر حفظ إعداد الشبكة الحالي، لذلك لم يبدأ البحث لحماية الاتصال.");});
                return;
            }
            prefs.edit().putString("snapMode",om).putString("snapBand",ob).putString("snapLte",ol).apply();
            runOnUiThread(()->status.setText("جارٍ البحث… قد ينقطع الإنترنت مؤقتًا"));

            String scan=getSync("/api/net/plmn-list");
            log("SCAN","PLMN-LIST","state="+stateOf(scan));

            runOnUiThread(()->status.setText("انتهى البحث • جارٍ استرجاع الشبكة والإنترنت…"));
            boolean recovered=restoreAndVerify18(om,ob,ol);
            if(!recovered){
                log("SCAN","DATA-RECOVERY","net-mode restore did not recover 901; requesting data reconnect");
                postSync("/api/dialup/connection","<request><Action>1</Action></request>");
                recovered=waitConnection18(18000);
            }
            final boolean ok=recovered;
            final String result=scan;
            runOnUiThread(()->{
                resumeLive18();
                status.setText(ok?"اكتمل البحث وعاد اتصال البيانات":"اكتمل البحث • تعذر تأكيد رجوع اتصال البيانات");
                if(bad(result)){
                    show("بحث الشبكات",stateOf(result)+"\n\n"+(ok?"تمت استعادة إعداد الشبكة واتصال البيانات.":"لم نؤكد رجوع اتصال البيانات. لا تكرر البحث؛ استخدم «إعادة الاتصال» وإذا بقيت المشكلة أعد تشغيل الراوتر."));
                }else{
                    show("الشبكات المتاحة",prettyXml(result)+"\n\n"+(ok?"✓ تم استرجاع إعداد الشبكة والتأكد من عودة الإنترنت.":"⚠ تم استرجاع الإعداد، لكن ConnectionStatus لم يرجع إلى 901 ضمن المهلة."));
                }
            });
        },"e5785-safe-plmn-scan").start();
    }

    private boolean restoreAndVerify18(String mode,String band,String lte){
        for(int attempt=1;attempt<=2;attempt++){
            String r=postSync("/api/net/net-mode","<request><NetworkMode>"+mode+"</NetworkMode><NetworkBand>"+band+"</NetworkBand><LTEBand>"+lte+"</LTEBand></request>");
            if(!okResponse(r)){sleep18(1000);continue;}
            long end=SystemClock.elapsedRealtime()+22000;
            while(SystemClock.elapsedRealtime()<end){
                String nm=getSync("/api/net/net-mode");
                String st=getSync("/api/monitoring/status");
                boolean same=!bad(nm)&&mode.equalsIgnoreCase(tagAny(nm,"NetworkMode"))&&band.equalsIgnoreCase(tagAny(nm,"NetworkBand"))&&lte.equalsIgnoreCase(tagAny(nm,"LTEBand"));
                if(same&&"901".equals(tagAny(st,"ConnectionStatus"))){
                    log("SCAN","RESTORE-VERIFIED","attempt="+attempt+" connection=901");
                    return true;
                }
                sleep18(1200);
            }
        }
        return false;
    }

    private boolean waitConnection18(long timeout){
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end){
            String st=getSync("/api/monitoring/status");
            if("901".equals(tagAny(st,"ConnectionStatus")))return true;
            sleep18(1200);
        }
        return false;
    }

    private void resumeLive18(){if(currentPage==0){live=true;tick();}h.postDelayed(this::refreshAll,1000);}
    private void sleep18(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
