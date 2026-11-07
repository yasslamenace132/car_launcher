package fr.ibiza.launcher;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends Activity implements Hub.Listener {
    static boolean startedOnce = false;   // v2.4 : ouverture auto de Waze une fois par démarrage
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/www/index.html";

    private WebView web;
    private final Handler handler = new Handler();
    private final LruCache<String, byte[]> icons = new LruCache<>(120);
    private boolean immersive = true;
    private android.webkit.ValueCallback<Uri[]> fileCb;
    private boolean gpsOn = false;
    private final android.location.LocationListener gps = new android.location.LocationListener() {
        @Override public void onLocationChanged(android.location.Location l) {
            float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
            CanBridge.gpsKmh = Math.round(kmh); CanBridge.gpsAt = System.currentTimeMillis();
            if (!CanBridge.canSpeed) js("speed", "{\"kmh\":" + Math.round(kmh) + "}");
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };
    void startGps() {
        if (gpsOn) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 7); return;
        }
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 0, gps);
            gpsOn = true;
        } catch (Exception e) { Hub.log("GPS : " + e); }
    }
    void stopGps() {
        if (!gpsOn) return;
        try { ((android.location.LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(gps); } catch (Exception ignored) {}
        gpsOn = false;
    }
    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (rc == 7 && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) { startGps(); TripService.start(this); }
        if (rc == 9 && pendingPdf != null) { String j = pendingPdf; pendingPdf = null; startPdf(j); }
    }

    /* v1.11 : PDF du carnet d'entretien — Android 9 et avant : on demande une fois l'accès au stockage pour
       l'enregistrer dans Téléchargements (sinon il va dans le dossier de l'appli) */
    private String pendingPdf = null;
    void startPdf(String json) {
        android.content.SharedPreferences sp = getSharedPreferences("ibz", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29 && pendingPdf == null && !sp.getBoolean("pdfAsked", false)
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingPdf = json; sp.edit().putBoolean("pdfAsked", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9);
            return;
        }
        final String j = json;
        new Thread(new Runnable() { public void run() {
            final JSONObject res = new JSONObject();
            try {
                JSONObject r = new JSONObject(j);
                android.graphics.pdf.PdfDocument doc = MaintPdf.render(r);
                try {
                    String where = MaintPdf.save(MainActivity.this, doc, r.optString("file"));
                    res.put("ok", true); res.put("where", where);
                    Hub.log("PDF entretien : " + where);
                } finally { doc.close(); }
            } catch (Exception e) {
                Hub.log("PDF : " + e);
                try { res.put("ok", false); res.put("err", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
            }
            handler.post(new Runnable() { public void run() { js("pdf", res.toString()); } });
        }}).start();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 42 && fileCb != null) {
            Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
            fileCb.onReceiveValue(u == null ? null : new Uri[]{u});
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }
    private java.io.File customIcon(String pkg) {
        java.io.File d = getExternalFilesDir("icons");
        if (d == null) return null;
        java.io.File f = new java.io.File(d, pkg + ".png");
        return f.exists() ? f : null;
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { sleptAt = System.currentTimeMillis(); js("sleep", "{}"); }
    };
    static long sleptAt = 0;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Hub.app = getApplicationContext();
        Hub.observeSettings(this);
        CanBridge.start(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        immersive = !"false".equals(getSharedPreferences("ibz", MODE_PRIVATE).getString("immersive", "true"));
        DoorOverlay.enabled = !"false".equals(getSharedPreferences("ibz", MODE_PRIVATE).getString("live", "true"));
        MovingAlert.enabled = !"false".equals(getSharedPreferences("ibz", MODE_PRIVATE).getString("moveAlert", "true"));

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 23) s.setOffscreenPreRaster(true);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*");
                    startActivityForResult(Intent.createChooser(i, "Choisir une image"), 42);
                } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                return serve(u.getPath());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());
            }
        });
        setContentView(web);
        web.loadUrl(START);
        spotifyAuth(getIntent());                                     // v2.6

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED); f.addAction(Intent.ACTION_PACKAGE_REMOVED); f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED); else registerReceiver(pkgReceiver, f);
        /* v3.1 : écran éteint (contact coupé, mise en veille) : le prochain retour est un nouveau trajet */
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, sf, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(screenReceiver, sf);
        Hub.add(this);
        Hub.log("Lanceur démarré · Android SDK " + Build.VERSION.SDK_INT + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · " + Build.HARDWARE);
    }

    /* ---------------- fichiers web + icônes d'apps ---------------- */
    private WebResourceResponse serve(String path) {
        try {
            if (path.startsWith("/www/")) {
                InputStream in = getAssets().open(path.substring(1));
                return new WebResourceResponse(mime(path), mime(path).startsWith("text") || path.endsWith(".js") ? "utf-8" : null, in);
            }
            if (path.startsWith("/icons/")) {
                String[] p = path.substring(7).split("/");
                String pkg = Uri.decode(p[0]), cls = Uri.decode(p[1].replace(".png", ""));
                if (cls.contains("?")) cls = cls.substring(0, cls.indexOf('?'));
                byte[] png = icons.get(pkg + "/" + cls);
                if (png == null) { png = iconPng(pkg, cls); if (png != null) icons.put(pkg + "/" + cls, png); }
                if (png != null) return new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
            }
        } catch (Exception e) { Hub.log("serve " + path + " : " + e); }
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
    }
    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg")) return "image/jpeg";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
    private byte[] iconPng(String pkg, String cls) {
        try {
            java.io.File cf = customIcon(pkg);
            if (cf != null) {
                java.io.FileInputStream in = new java.io.FileInputStream(cf);
                ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                in.close(); return o.toByteArray();
            }
            PackageManager pm = getPackageManager();
            Drawable d;
            try { d = pm.getActivityIcon(new ComponentName(pkg, cls)); } catch (Exception e) { d = pm.getApplicationIcon(pkg); }
            int sz = 144;
            Bitmap bmp;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                bmp = Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), sz, sz, true);
            } else {
                bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp); d.setBounds(0, 0, sz, sz); d.draw(c);
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            return o.toByteArray();
        } catch (Exception e) { return null; }
    }

    /* ---------------- pont JavaScript ---------------- */
    class Bridge {
        @JavascriptInterface public String getApps() {
            JSONArray a = new JSONArray();
            try {
                Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = getPackageManager().queryIntentActivities(i, 0);
                for (ResolveInfo r : list) {
                    ActivityInfo ai = r.activityInfo;
                    if (getPackageName().equals(ai.packageName)) continue;
                    JSONObject o = new JSONObject();
                    o.put("pkg", ai.packageName); o.put("cls", ai.name);
                    o.put("label", String.valueOf(r.loadLabel(getPackageManager())));
                    if (customIcon(ai.packageName) != null) o.put("custom", true);
                    a.put(o);
                }
            } catch (Exception e) { Hub.log("getApps " + e); }
            return a.toString();
        }
        /* v2.1 : ouverture par la barre latérale (fenêtre à droite de la barre si le poste le permet) */
        @JavascriptInterface public void launch(final String pkg, final String cls) {
            handler.post(new Runnable() { public void run() { SideBar.launch(MainActivity.this, pkg, cls); } });
        }
        @JavascriptInterface public void setBar(final String json) {
            handler.post(new Runnable() { public void run() { SideBar.setApps(MainActivity.this, json); } });
        }
        @JavascriptInterface public String barInfo() { return SideBar.info(MainActivity.this); }
        @JavascriptInterface public String tripState() { return TripService.state(MainActivity.this); }
        /* v2.2 : mise à jour depuis GitHub */
        @JavascriptInterface public void checkUpdate(String repo) { Updater.check(MainActivity.this, repo); }
        @JavascriptInterface public void installUpdate(String repo) { Updater.install(MainActivity.this, repo); }
        @JavascriptInterface public int appVersion() { return Updater.localCode(MainActivity.this); }
        /* v2.4 : Waze ouvert d'office une seule fois après le démarrage du poste ; fiche Play Store */
        /* v2.6 : Spotify, volume, réveil d'applis en arrière-plan, processeur */
        @JavascriptInterface public String pendingAuth() { String p = pendingAuth; pendingAuth = null; return p == null ? "" : p; }
        @JavascriptInterface public void openUrl(final String url) {
            handler.post(new Runnable() { public void run() {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { Hub.log("openUrl KO " + e); }
            } });
        }
        @JavascriptInterface public int volume(String dir) {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            int d = "up".equals(dir) ? android.media.AudioManager.ADJUST_RAISE : "down".equals(dir) ? android.media.AudioManager.ADJUST_LOWER : android.media.AudioManager.ADJUST_SAME;
            try { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, d, 0); } catch (Exception e) { Hub.log("volume KO " + e); }
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            return max > 0 ? Math.round(100f * am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / max) : 0;
        }
        /** ouvre une appli le temps qu'elle s'active (Spotify, BT Music) puis revient sur le lanceur */
        @JavascriptInterface public void wakeApp(final String pkg, final int ms) {
            handler.post(new Runnable() { public void run() {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) { Hub.log("wakeApp : introuvable " + pkg); return; }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                try { startActivity(i); } catch (Exception e) { Hub.log("wakeApp KO " + e); return; }
                handler.postDelayed(new Runnable() { public void run() {
                    Intent back = new Intent(MainActivity.this, MainActivity.class);
                    back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    back.putExtra("ibz_return", true);
                    try { startActivity(back); } catch (Exception ignored) {}
                } }, Math.max(800, Math.min(ms, 8000)));
            } });
        }
        @JavascriptInterface public String abi() { return Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : ""; }
        @JavascriptInterface public void setNav(String pkg, float left) {
            getSharedPreferences("ibz", MODE_PRIVATE).edit().putString("navPkg", pkg == null ? "" : pkg).putFloat("navLeft", left).apply();
        }
        /* v3.0 : tableau de bord (applis en fenêtres sur les cadres de l'accueil) */
        @JavascriptInterface public void tile(final String json) {
            handler.post(new Runnable() { public void run() { Dash.tile(MainActivity.this, web, json); } });
        }
        @JavascriptInterface public void front() {
            handler.post(new Runnable() { public void run() { Dash.front(MainActivity.this); } });
        }
        @JavascriptInterface public void launchPlain(final String pkg, final String cls) {
            handler.post(new Runnable() { public void run() { Dash.openPlain(MainActivity.this, pkg, cls); } });
        }
        @JavascriptInterface public boolean firstStart() { boolean f = !startedOnce; startedOnce = true; return f; }
        @JavascriptInterface public boolean installed(String pkg) {
            try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void openStore(final String pkg) {
            handler.post(new Runnable() { public void run() {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (Exception e2) { Hub.log("Play Store KO " + e2); }
                }
            } });
        }
        @JavascriptInterface public void tripReset() { TripService.reset(MainActivity.this); }
        @JavascriptInterface public String getState() {
            try {
                JSONObject o = new JSONObject();
                o.put("media", new JSONObject(Hub.mediaJson));
                o.put("vehicle", new JSONObject(Hub.vehicleJson));
                JSONObject p = new JSONObject();
                p.put("notif", listenerEnabled());
                p.put("access", accessEnabled());
                p.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(MainActivity.this));
                p.put("home", isDefaultHome());
                o.put("perms", p);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void media(String cmd) {
            if (MediaListener.command(cmd)) return;
            int key;
            switch (cmd) {
                case "next": key = KeyEvent.KEYCODE_MEDIA_NEXT; break;
                case "prev": key = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
                case "play": key = KeyEvent.KEYCODE_MEDIA_PLAY; break;
                default: key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            }
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
        }
        @JavascriptInterface public void openSystem(String which) {
            Intent i;
            switch (which) {
                case "home": i = new Intent(Settings.ACTION_HOME_SETTINGS); break;
                // désinstallation du lanceur (Android ne le permet qu'une fois un autre écran d'accueil choisi par défaut)
                case "uninstall": i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName())); break;
                case "appinfo": i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())); break;
                case "notif": i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"); break;
                case "access": i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); break;
                case "overlay": i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())); break;
                default: i = new Intent(Settings.ACTION_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Exception e) {
                if ("home".equals(which)) { // repli : sélecteur d'écran d'accueil
                    Intent h = new Intent(Intent.ACTION_MAIN); h.addCategory(Intent.CATEGORY_HOME);
                    startActivity(Intent.createChooser(h, "Écran d'accueil").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else {
                    try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
                }
            }
        }
        @JavascriptInterface public void setPref(String k, String v) {
            getSharedPreferences("ibz", MODE_PRIVATE).edit().putString(k, v).apply();
            if ("immersive".equals(k)) { immersive = "true".equals(v); handler.post(new Runnable() { public void run() { applyImmersive(); } }); }
            if ("live".equals(k)) DoorOverlay.enabled = !"false".equals(v);
            if ("moveAlert".equals(k)) MovingAlert.enabled = !"false".equals(v);
            if ("bar".equals(k) || "barMode".equals(k)) handler.post(new Runnable() { public void run() {
                SideBar.reload(MainActivity.this);
                if (Hub.launcherVisible) SideBar.onLauncherShown(MainActivity.this);
            } });
        }
        @JavascriptInterface public void simulate(String json) { CanBridge.simUntil = System.currentTimeMillis() + 15000; Hub.log("SIMULATION " + json); Hub.emit("vehicle", json); }
        @JavascriptInterface public void canRefresh() { CanWatchService.requestRefresh(MainActivity.this); }
        @JavascriptInterface public void testMoveAlert() { MovingAlert.test(); }
        /* v1.11 : carnet d'entretien en PDF */
        @JavascriptInterface public void exportPdf(final String json) { handler.post(new Runnable() { public void run() { startPdf(json); } }); }
        @JavascriptInterface public void openPdf() { handler.post(new Runnable() { public void run() {
            if (!MaintPdf.open(MainActivity.this)) js("toast", "Aucune appli pour lire les PDF : utilise « Partager » ou ouvre le fichier depuis le gestionnaire de fichiers");
        } }); }
        @JavascriptInterface public void sharePdf() { handler.post(new Runnable() { public void run() {
            if (!MaintPdf.share(MainActivity.this)) js("toast", "Partage impossible sur ce poste");
        } }); }
        @JavascriptInterface public boolean canRefreshPossible() { return CanWatchService.self != null && getPackageManager().getLaunchIntentForPackage("com.hcn.autocan") != null; }
        @JavascriptInterface public String canScan() { return CanBridge.discover(true); }
        @JavascriptInterface public String canExport() { return CanBridge.exportApks(); }
        @JavascriptInterface public boolean canLogs() { return CanBridge.logsAllowed(); }
        @JavascriptInterface public boolean canLive() { return LogCan.live(); }
        @JavascriptInterface public void tryRoot() { RootGrant.tryAsync(MainActivity.this, true); }
        @JavascriptInterface public int canFrames() { return LogCan.frames; }
        @JavascriptInterface public String getCar() { return Hub.carJson == null ? "{}" : Hub.carJson; }
        @JavascriptInterface public void launchPkg(final String pkg) {
            handler.post(new Runnable() { public void run() { SideBar.launch(MainActivity.this, pkg, null); } });
        }
        @JavascriptInterface public String getDiag() { return Hub.diag(); }
        @JavascriptInterface public String exportDiag() { return Hub.export(MainActivity.this); }
        @JavascriptInterface public void clearDiag() { Hub.clear(); }
        @JavascriptInterface public String iconDir() { java.io.File d = getExternalFilesDir("icons"); return d == null ? "" : d.getAbsolutePath(); }
        @JavascriptInterface public void reloadIcons() { icons.evictAll(); Hub.emit("apps", "{}"); }
    }

    boolean listenerEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }
    boolean accessEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName());
    }
    boolean isDefaultHome() {
        Intent h = new Intent(Intent.ACTION_MAIN); h.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
    }

    /* ---------------- événements vers le JS ---------------- */
    @Override public void onEvent(String type, String json) { js(type, json); }
    void js(String type, String json) {
        if (web == null) return;
        String q = JSONObject.quote(json == null ? "{}" : json);
        web.evaluateJavascript("window.onNative&&window.onNative('" + type + "'," + q + ")", null);
    }
    private final Runnable statusTick = new Runnable() { public void run() {
        try {
            JSONObject o = new JSONObject();
            boolean bt = false;
            try { BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter(); bt = a != null && a.isEnabled(); } catch (Throwable ignored) {}
            boolean wifi = false;
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                NetworkInfo n = cm.getActiveNetworkInfo();
                wifi = n != null && n.isConnected() && n.getType() == ConnectivityManager.TYPE_WIFI;
            } catch (Throwable ignored) {}
            o.put("bt", bt); o.put("wifi", wifi);
            js("status", o.toString());
            MediaListener.tickPosition();
        } catch (Exception ignored) {}
        handler.postDelayed(this, 1000);
    }};

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { icons.evictAll(); js("apps", "{}"); }
    };

    void applyImmersive() {
        View d = getWindow().getDecorView();
        if (immersive) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        } else d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }
    void sendNight() {
        int m = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (m == android.content.res.Configuration.UI_MODE_NIGHT_UNDEFINED) return;
        js("night", "{\"night\":" + (m == android.content.res.Configuration.UI_MODE_NIGHT_YES) + "}");
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        Hub.log("Configuration : uiMode=" + c.uiMode);
        sendNight();
    }
    @Override public void onWindowFocusChanged(boolean has) { super.onWindowFocusChanged(has); if (has) applyImmersive(); }

    @Override protected void onResume() {
        super.onResume();
        Hub.launcherVisible = true; DoorOverlay.hide();
        applyImmersive();
        web.onResume();
        boolean silent = CanWatchService.returning; CanWatchService.returning = false;
        js("resume", silent ? "{\"silent\":true}" : "{}");
        js("media", Hub.mediaJson); js("vehicle", Hub.vehicleJson);
        sendNight();
        if (Hub.lightsJson != null) js("lights", Hub.lightsJson);
        if (Hub.carJson != null) js("car", Hub.carJson);
        handler.removeCallbacks(statusTick); handler.post(statusTick);
        startGps();
        TripService.start(this);                       // v2.1 : trajet compté même quand Waze est devant
        js("trip", TripService.state(this));
        SideBar.onLauncherShown(this);                 // v2.1 : le lanceur a sa propre barre
    }
    @Override protected void onPause() {
        super.onPause();
        Hub.launcherVisible = false;
        js("pause", "{}");
        handler.removeCallbacks(statusTick);
        stopGps();
        DoorOverlay.update(Hub.vehicleJson);
        SideBar.onLauncherHidden(this);                // v2.1 : la barre reste visible par-dessus l'appli ouverte
    }
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (spotifyAuth(i)) return;                                   // v2.6 : retour de la connexion Spotify
        if (!i.getBooleanExtra("ibz_return", false)) js("home", "{}");
    }
    /* v2.6 : carlauncher://spotify-callback?code=…&state=… -> interface (gardé si la page n'est pas encore prête) */
    static String pendingAuth = null;
    boolean spotifyAuth(Intent i) {
        if (i == null || i.getData() == null || !"carlauncher".equals(i.getData().getScheme())) return false;
        Uri u = i.getData();
        try {
            JSONObject o = new JSONObject();
            o.put("code", u.getQueryParameter("code") == null ? "" : u.getQueryParameter("code"));
            o.put("state", u.getQueryParameter("state") == null ? "" : u.getQueryParameter("state"));
            o.put("error", u.getQueryParameter("error") == null ? "" : u.getQueryParameter("error"));
            pendingAuth = o.toString();
            js("spotifyAuth", pendingAuth);
        } catch (Exception e) { Hub.log("Spotify : retour KO " + e); }
        return true;
    }
    View contentView() { return web; }
    @Override public void onBackPressed() { js("home", "{}"); } // un lanceur ne se ferme pas
    @Override protected void onDestroy() {
        Hub.remove(this);
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
