package com.shahboun.e5785;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.text.InputType;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * E5785 v1.3.0
 * Fixes the main v1.2 problem: public/read-only API replies were being treated as a successful
 * admin login. This class performs a real HiLink session + token login and reuses that session
 * for all API calls. It intentionally keeps MainActivityV12 as a rollback base.
 */
public class MainActivityV13 extends MainActivityV12 {
    private final Object secLock = new Object();
    private String apiCookie = "";
    private final ArrayDeque<String> tokens = new ArrayDeque<>();
    private final Set<String> deniedAfterAuth = new HashSet<>();
    private boolean authReady = false;
    private String lastDebug = "";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            // Update the visible version without duplicating the whole UI implementation.
            View head = root.getChildAt(0);
            if (head instanceof ViewGroup) {
                View names = ((ViewGroup) head).getChildAt(0);
                if (names instanceof ViewGroup && ((ViewGroup) names).getChildCount() > 1) {
                    View sub = ((ViewGroup) names).getChildAt(1);
                    if (sub instanceof TextView) ((TextView) sub).setText("Huawei E5785Lh-22c Edition  •  v1.3.0");
                }
            }
        } catch (Exception ignored) {}
    }

    @Override void loginDialog() {
        if (loginBusy || isFinishing()) return;
        loginBusy = true;
        LinearLayout box = form();
        EditText user = input("اسم المستخدم", "admin");
        user.setEnabled(false);
        EditText pass = input("رمز إدارة الراوتر", password);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(user); box.addView(pass);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("دخول Huawei E5785Lh-22c")
                .setMessage("سيتم إنشاء جلسة HiLink إدارية حقيقية. الاسم ثابت: admin")
                .setView(box).setPositiveButton("اتصال", null).create();
        d.setCancelable(false);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            password = pass.getText().toString();
            if (password == null) password = "";
            prefs.edit().putString("password", password).apply();
            status.setText("جارٍ إنشاء جلسة HiLink وتسجيل الدخول…");
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            directLogin(d);
        }));
        d.show();
    }

    private void directLogin(AlertDialog d) {
        new Thread(() -> {
            String result;
            try { result = loginSync(password); }
            catch (Exception e) { result = "ERR:" + e; }
            final String r = result;
            runOnUiThread(() -> {
                loginBusy = false;
                if (r.startsWith("OK")) {
                    logged = true;
                    authReady = true;
                    if (d.isShowing()) d.dismiss();
                    status.setText("متصل • جلسة HiLink إدارية");
                    set("model", "E5785Lh-22c");
                    probe();
                    refreshAll();
                } else {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    status.setText("تعذر تسجيل دخول الإدارة");
                    String msg = r.contains("108002") || r.contains("108006") ?
                            "رمز الإدارة غير صحيح." :
                            "تعذر إنشاء جلسة الإدارة. التفاصيل: " + humanError(r);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private String loginSync(String pass) throws Exception {
        synchronized (secLock) {
            apiCookie = ""; tokens.clear(); authReady = false;
            String st = securityInfo();
            if (st.contains("<error>")) return st;
            // The login token is single-use. Removing it here is critical: keeping it at the
            // head of the queue makes the first administrative POST reuse an already consumed
            // token and Huawei answers with 125003.
            String token = takeToken();
            if (token.isEmpty()) return "NO_TOKEN";

            String state = rawGet("/api/user/state-login", apiCookie, false);
            String pt = tagAny(state, "password_type");
            if (pt.isEmpty()) pt = "4";
            String enc = encodePassword(pass, pt, token);
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><request>" +
                    "<Username>admin</Username><Password>" + enc + "</Password><password_type>" + pt + "</password_type></request>";
            sessionTrace("LOGIN", "requesting administrative session");
            String resp = rawPost("/api/user/login", xml, token, apiCookie, true);
            if (resp.contains("<response>OK</response>") || resp.matches("(?s).*<response>\\s*OK\\s*</response>.*")) {
                authReady = true;
                // Confirm with state-login. Some firmwares use State=0 for authenticated.
                String verify = rawGet("/api/user/state-login", apiCookie, true);
                lastDebug = "LOGIN=" + resp + "\nSTATE=" + verify;
                sessionTrace("LOGIN", "administrative session ready");
                return "OK";
            }
            if (resp.contains("<code>108003</code>")) { // already logged in in this session
                authReady = true; return "OK_ALREADY";
            }
            lastDebug = "LOGIN_FAIL=" + resp;
            sessionTrace("LOGIN", "failed • code=" + pick(tagAny(resp,"code"),"unknown"));
            return resp;
        }
    }

    private String securityInfo() throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(BASE + "/api/webserver/SesTokInfo").openConnection();
        c.setConnectTimeout(4500); c.setReadTimeout(6000); c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/xml,text/xml,*/*");
        if (!apiCookie.isEmpty()) c.setRequestProperty("Cookie", apiCookie);
        int code = c.getResponseCode();
        String body = readStream(code >= 400 ? c.getErrorStream() : c.getInputStream());
        String ses = tagAny(body, "SesInfo");
        String tok = tagAny(body, "TokInfo");
        if (!ses.isEmpty()) apiCookie = ses;
        collectTokens(tok);
        collectSecurityHeaders(c);
        c.disconnect();
        return body;
    }

    private void collectSecurityHeaders(HttpURLConnection c) {
        try {
            for (Map.Entry<String,List<String>> entry : c.getHeaderFields().entrySet()) {
                if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
                for (String sc : entry.getValue()) {
                    if (sc == null || sc.isEmpty()) continue;
                    String one = sc.split(";",2)[0].trim();
                    if (one.toLowerCase(Locale.US).contains("sessionid")) apiCookie = one;
                }
            }
            collectTokens(c.getHeaderField("__RequestVerificationToken"));
            collectTokens(c.getHeaderField("__RequestVerificationTokenone"));
            collectTokens(c.getHeaderField("__RequestVerificationTokentwo"));
        } catch (Exception ignored) {}
    }

    private void collectTokens(String s) {
        if (s == null) return;
        synchronized (secLock) {
            for (String t : s.split("#")) {
                t = t.trim();
                if (!t.isEmpty() && !tokens.contains(t)) tokens.addLast(t);
            }
        }
    }
    private String takeToken() {
        String t = tokens.pollFirst();
        if (t == null) t = "";
        return t;
    }

    private String encodePassword(String pass, String type, String token) throws Exception {
        if (!"4".equals(type)) return Base64.encodeToString(pass.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String innerHex = sha256Hex(pass);
        String innerB64 = Base64.encodeToString(innerHex.getBytes(StandardCharsets.US_ASCII), Base64.NO_WRAP);
        String outerHex = sha256Hex("admin" + innerB64 + token);
        return Base64.encodeToString(outerHex.getBytes(StandardCharsets.US_ASCII), Base64.NO_WRAP);
    }

    private String sha256Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.US, "%02x", x & 0xff));
        return b.toString();
    }

    private String rawGet(String path, String ck, boolean collect) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL(BASE + (path.startsWith("/") ? path : "/" + path)).openConnection();
            c.setRequestMethod("GET"); c.setConnectTimeout(4500); c.setReadTimeout(6500); c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/xml,text/xml,*/*");
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            int code = c.getResponseCode();
            String body = readStream(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (collect) collectSecurityHeaders(c);
            if (body.trim().isEmpty()) return "<transport><status>"+code+"</status><empty>1</empty></transport>";
            return body;
        } catch (Exception e) { return "<transport><error>" + safe(e.toString()) + "</error></transport>"; }
        finally { if (c != null) c.disconnect(); }
    }

    private String rawPost(String path, String xml, String token, String ck, boolean collect) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL(BASE + (path.startsWith("/") ? path : "/" + path)).openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(4500); c.setReadTimeout(7000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            if (token != null && !token.isEmpty()) c.setRequestProperty("__RequestVerificationToken", token);
            if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            try (OutputStream out = c.getOutputStream()) { out.write(xml.getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            String body = readStream(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (collect) collectSecurityHeaders(c);
            if (body.trim().isEmpty()) return "<transport><status>"+code+"</status><empty>1</empty></transport>";
            return body;
        } catch (Exception e) { return "<transport><error>" + safe(e.toString()) + "</error></transport>"; }
        finally { if (c != null) c.disconnect(); }
    }

    private void ensureSecurity() {
        synchronized (secLock) {
            if (apiCookie.isEmpty() || tokens.isEmpty()) {
                try { securityInfo(); } catch (Exception ignored) {}
            }
        }
    }

    void sessionTrace(String event, String detail) { }

    private boolean authError(String x) {
        return x != null && (x.contains("<code>125001</code>") ||
                x.contains("<code>125002</code>") || x.contains("<code>125003</code>"));
    }

    private boolean protectedRead(String path) {
        return "/api/device/signal".equals(path) || "/api/device/information".equals(path) ||
                "/api/net/net-mode".equals(path) || "/api/net/net-mode-list".equals(path) ||
                "/api/wlan/host-list".equals(path);
    }

    @Override String getSync(String path) {
        ensureSecurity();
        String r = rawGet(path, apiCookie, true);
        boolean denied = r.contains("<code>100003</code>") && protectedRead(path) && !deniedAfterAuth.contains(path);
        if (authError(r) || denied) {
            synchronized (secLock) {
                authReady = false;
                try { if (!password.isEmpty()) loginSync(password); } catch (Exception ignored) {}
            }
            if (authReady) {
                r = rawGet(path, apiCookie, true);
                if (r.contains("<code>100003</code>")) deniedAfterAuth.add(path);
            }
        }
        return r;
    }

    @Override String postSync(String path, String xml) {
        synchronized (secLock) {
            try {
                if (!authReady) {
                    String login = loginSync(password);
                    if (!login.startsWith("OK")) return login;
                }
                String tok = takeToken();
                if (tok.isEmpty()) {
                    securityInfo();
                    tok = takeToken();
                }
                if (tok.isEmpty()) return "<transport><error>NO_TOKEN</error></transport>";
                String r = rawPost(path, xml, tok, apiCookie, true);
                if (!authError(r)) return r;

                authReady = false;
                sessionTrace("SESSION", "expired during POST • re-authenticating once");
                String login = loginSync(password);
                if (!login.startsWith("OK")) return r;
                tok = takeToken();
                if (tok.isEmpty()) {
                    securityInfo();
                    tok = takeToken();
                }
                if (tok.isEmpty()) return r;
                return rawPost(path, xml, tok, apiCookie, true);
            } catch (Exception e) {
                return "<transport><error>" + safe(e.toString()) + "</error></transport>";
            }
        }
    }

    @Override boolean bad(String x) {
        return x == null || x.trim().isEmpty() || x.contains("<transport><error>") ||
                x.contains("<code>100002</code>") || x.contains("<code>100003</code>") ||
                x.contains("<code>108006</code>") || x.contains("<code>125001</code>") ||
                x.contains("<code>125002</code>") || x.contains("<code>125003</code>");
    }

    @Override String stateOf(String x) {
        if (x == null || x.trim().isEmpty()) return "لا يوجد رد";
        if (x.contains("<empty>1</empty>")) return "اتصال ناجح لكن هذا المسار لا يعيد بيانات";
        if (x.contains("<code>100002</code>")) return "غير مدعوم في هذا الـFirmware";
        if (x.contains("<code>100003</code>")) return "يتطلب صلاحية إدارة أو الوظيفة مقفلة";
        if (x.contains("<code>125001</code>")) return "Token غير صالح — سيُجدد تلقائيًا";
        if (x.contains("<code>125002</code>") || x.contains("<code>125003</code>")) return "الجلسة انتهت — سيعاد الدخول";
        if (x.contains("<code>108002</code>") || x.contains("<code>108006</code>")) return "رمز الإدارة غير صحيح";
        if (x.contains("<transport><error>")) return "خطأ اتصال";
        if (x.contains("<error>")) return "رفض من الراوتر: " + humanError(x);
        return "متاحة";
    }

    private String humanError(String x) {
        if (x == null) return "غير معروف";
        if (x.contains("100003")) return "لا توجد صلاحية كافية";
        if (x.contains("100002")) return "غير مدعوم";
        if (x.contains("108002") || x.contains("108006")) return "رمز الإدارة غير صحيح";
        if (x.contains("125001")) return "Token غير صالح";
        if (x.contains("125002")) return "Session غير صالح";
        if (x.contains("125003")) return "Session/Token غير صالحين";
        String c = tagAny(x,"code"); return c.isEmpty() ? x : "كود " + c;
    }

    @Override void parseStatus(String x) {
        super.parseStatus(x);
        String users = pick(tagAny(x,"CurrentWifiUser","TotalWifiUser"));
        if (!users.isEmpty()) { set("wifiUsers", users); set("hosts", users); }
        set("wanip", pick(tagAny(x,"WanIPAddress","WanIP","PrimaryDns","SecondaryDns")));
    }

    @Override void parseSignal(String x) {
        super.parseSignal(x);
        // Huawei firmwares differ in casing and field names.
        set("rsrp", pick(tagAny(x,"rsrp","RSRP","LteRsrp")));
        set("rsrq", pick(tagAny(x,"rsrq","RSRQ","LteRsrq")));
        set("sinr", pick(tagAny(x,"sinr","SINR","LteSinr")));
        set("rssi", pick(tagAny(x,"rssi","RSSI","SignalStrength")));
        set("cell", pick(tagAny(x,"cell_id","CellID","cellid","CellId")));
        set("pci", pick(tagAny(x,"pci","PCI","PhysicalCellId")));
        set("earfcn", pick(tagAny(x,"earfcn","EARFCN","DlEarfcn")));
        String b = pick(tagAny(x,"band","Band","lte_band","LTEBand")); if (!b.isEmpty()) set("band", b);
    }

    @Override void hostsDialog() {
        fallback(new String[]{"/api/wlan/host-list","/api/lan/HostInfo","/api/ntwk/lan_host"}, x -> {
            if (x == null || x.isEmpty()) { show("الأجهزة المتصلة", "تعذر الحصول على القائمة. العدد الحالي من حالة الراوتر: " + values.getOrDefault("wifiUsers","—")); return; }
            if (x.contains("<code>100003</code>")) { show("الأجهزة المتصلة", "الراوتر منع قراءة تفاصيل الأجهزة بدون صلاحية كافية.\nتم تسجيل عدد الأجهزة من حالة الشبكة: " + values.getOrDefault("wifiUsers","—")); return; }
            show("الأجهزة المتصلة", prettyXml(x));
        });
    }

    @Override void wifiDialog() {
        fallback(new String[]{"/api/wlan/basic-settings","/api/ntwk/WlanBasic"}, x -> {
            if (bad(x)) { show("Wi-Fi", stateOf(x)); return; }
            String ssid = pick(tagAny(x,"WifiSsid","SSID","ssid"));
            String channel = pick(tagAny(x,"WifiChannel","Channel"));
            String country = pick(tagAny(x,"WifiCountry","Country"));
            String wps = pick(tagAny(x,"WifiWpscfg","WifiWpsEnbl","WPS"));
            String hidden = pick(tagAny(x,"WifiHide","Hidden"));
            String enabled = pick(tagAny(x,"WifiEnable","wifioffenable","Enable"));
            StringBuilder s = new StringBuilder();
            s.append("اسم الشبكة: ").append(pick(ssid,"—"));
            s.append("\nالحالة: ").append("1".equals(enabled)?"مفعلة":"0".equals(enabled)?"متوقفة":pick(enabled,"—"));
            s.append("\nالقناة: ").append(pick(channel,"تلقائي"));
            s.append("\nالدولة: ").append(pick(country,"—"));
            s.append("\nWPS: ").append("1".equals(wps)?"مفعّل":"0".equals(wps)?"متوقف":pick(wps,"—"));
            s.append("\nإخفاء الشبكة: ").append("1".equals(hidden)?"نعم":"0".equals(hidden)?"لا":pick(hidden,"—"));
            show("إعدادات Wi-Fi", s.toString());
        });
    }
}
