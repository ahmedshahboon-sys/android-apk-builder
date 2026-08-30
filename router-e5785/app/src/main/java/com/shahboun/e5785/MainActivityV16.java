package com.shahboun.e5785;

import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** v1.6.0: fixed single-use HiLink tokens and bounded, serialized live refresh. */
public class MainActivityV16 extends MainActivityV15 {
    private final Object refreshLock=new Object();
    private volatile boolean refreshRunning=false,probeRunning=false,tickPending=false;
    private long lastDeepRefresh=0;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        log("APP","SESSION-FIX","v1.6.0 • single-use tokens • serialized refresh • private diagnostics");
        try{View head=root.getChildAt(0);if(head instanceof ViewGroup){View names=((ViewGroup)head).getChildAt(0);if(names instanceof ViewGroup&&((ViewGroup)names).getChildCount()>1){View sub=((ViewGroup)names).getChildAt(1);if(sub instanceof TextView)((TextView)sub).setText("Huawei E5785Lh-22c Edition  •  v1.6.0");}}}catch(Exception ignored){}
    }

    @Override void tick(){
        if(!live){tickPending=false;return;}
        if(tickPending)return;
        tickPending=true;
        h.postDelayed(()->{tickPending=false;if(live&&logged)refreshAll();tick();},4000);
    }

    @Override void refreshAll(){
        if(!logged||refreshRunning)return;
        refreshRunning=true;
        final boolean deep=SystemClock.elapsedRealtime()-lastDeepRefresh>30000;
        if(deep)lastDeepRefresh=SystemClock.elapsedRealtime();
        new Thread(()->{
            final Map<String,String> r=new HashMap<>();
            try{
                synchronized(refreshLock){
                    r.put("status",getSync("/api/monitoring/status"));
                    r.put("traffic",getSync("/api/monitoring/traffic-statistics"));
                    r.put("signal",getSync("/api/device/signal"));
                    if(deep){
                        r.put("info",getSync("/api/device/basic_information"));
                        r.put("plmn",getSync("/api/net/current-plmn"));
                        r.put("mode",getSync("/api/net/net-mode"));
                        r.put("wifi",getSync("/api/wlan/basic-settings"));
                        r.put("pin",getSync("/api/pin/status"));
                        r.put("sms",getSync("/api/sms/sms-count"));
                    }
                }
            }finally{
                runOnUiThread(()->{
                    try{
                        parseStatus(r.get("status"));parseTraffic(r.get("traffic"));parseSignal(r.get("signal"));
                        String x=r.get("info");if(!bad(x)){set("model",pick(tagAny(x,"DeviceName","devicename","Classify"),"E5785Lh-22c"));set("firmware",tagAny(x,"SoftwareVersion","FirmwareVersion"));}
                        x=r.get("plmn");if(!bad(x))set("operator",pick(tagAny(x,"FullName","ShortName","Numeric")));
                        x=r.get("mode");if(!bad(x)){set("netMode",tagAny(x,"NetworkMode"));set("netBand",tagAny(x,"NetworkBand"));set("lteBand",tagAny(x,"LTEBand"));}
                        x=r.get("wifi");if(!bad(x))set("ssid",tagAny(x,"WifiSsid","SSID"));
                        x=r.get("pin");if(!bad(x)){set("pin",tagAny(x,"PinOptState","PinState"));set("sim",tagAny(x,"SimState"));}
                        x=r.get("sms");if(!bad(x))set("sms",pick(tagAny(x,"LocalUnread","LocalInbox","LocalOutbox")));
                    }finally{refreshRunning=false;}
                });
            }
        },"e5785-refresh").start();
    }

    @Override void probe(){
        if(!logged||probeRunning)return;
        probeRunning=true;status.setText("جارٍ فحص القدرات الأساسية…");
        final String[][] checks={
                {"معلومات الجهاز","/api/device/basic_information"},{"الحالة","/api/monitoring/status"},
                {"الترافيك","/api/monitoring/traffic-statistics"},{"الإشارة","/api/device/signal"},
                {"المشغل","/api/net/current-plmn"},{"وضع الشبكة","/api/net/net-mode"},
                {"قائمة الترددات","/api/net/net-mode-list"},{"PIN","/api/pin/status"},
                {"SMS","/api/sms/sms-count"},{"Wi-Fi","/api/wlan/basic-settings"},
                {"إحصائيات الشهر","/api/monitoring/month_statistics"},{"حالة التحديث","/api/online-update/status"}
        };
        new Thread(()->{
            LinkedHashMap<String,String> found=new LinkedHashMap<>();int ok=0;
            synchronized(refreshLock){for(String[] c:checks){String x=getSync(c[1]);String s=stateOf(x);if("متاحة".equals(s))ok++;found.put(c[0]+" • "+c[1],s);}}
            final int count=ok;runOnUiThread(()->{capState.clear();capState.putAll(found);set("caps",count+"/"+checks.length);status.setText("فحص القدرات: "+count+" متاحة من "+checks.length);probeRunning=false;capDialog();});
        },"e5785-probe").start();
    }
}
