package fr.ibiza.launcher;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** État partagé (média, véhicule) + journal de diagnostic. Tout passe par le thread principal. */
public final class Hub {
    public interface Listener { void onEvent(String type, String json); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
    private static boolean observing = false;

    public static String mediaJson = "{}";
    public static String vehicleJson = "{\"doors\":{\"fl\":false,\"fr\":false,\"rl\":false,\"rr\":false,\"trunk\":false,\"hood\":false},\"radar\":null,\"reverse\":false}";
    public static boolean launcherVisible = false;
    /** émulateur Android Studio : aucune donnée voiture, on coupe les détections de secours (propriétés, textes d'écran) */
    public static final boolean EMULATOR = android.os.Build.HARDWARE.contains("goldfish") || android.os.Build.HARDWARE.contains("ranchu")
            || android.os.Build.FINGERPRINT.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("emulator");
    public static String lightsJson = null;
    private static final java.util.regex.Pattern LIGHTS = java.util.regex.Pattern.compile("(^|_|\\.)(ill|illum|illumination|headlight|head_light|headlamp|lamp|car_light|small_light|backlight_night)($|_|\\.)", java.util.regex.Pattern.CASE_INSENSITIVE);
    public static Context app;

    private Hub() {}

    public static void add(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }
    public static void remove(Listener l) { LISTENERS.remove(l); }

    public static void emit(final String type, final String json) {
        MAIN.post(new Runnable() { @Override public void run() {
            if ("media".equals(type)) mediaJson = json;
            if ("vehicle".equals(type)) { vehicleJson = json; DoorOverlay.update(json); }
            for (Listener l : new ArrayList<>(LISTENERS)) l.onEvent(type, json);
        }});
    }

    /* ------------ journal de diagnostic (anneau de 600 lignes) ------------ */
    public static synchronized void log(String line) {
        String l = TS.format(new Date()) + "  " + line;
        LOG.addLast(l);
        while (LOG.size() > 600) LOG.removeFirst();
    }
    public static synchronized String diag() {
        StringBuilder sb = new StringBuilder();
        // plus récent en premier
        Object[] a = LOG.toArray();
        for (int i = a.length - 1; i >= 0 && i >= a.length - 120; i--) sb.append(a[i]).append('\n');
        return sb.toString();
    }
    public static synchronized void clear() { LOG.clear(); }
    public static synchronized String export(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            File f = new File(dir, "ibiza_diag.txt");
            FileOutputStream o = new FileOutputStream(f);
            try { o.write(CanBridge.propsSnapshot().getBytes("UTF-8")); o.write(("vehicule = " + vehicleJson + "\nordi = " + carJson + "\nAndroid SDK " + android.os.Build.VERSION.SDK_INT + "\n\n").getBytes("UTF-8")); } catch (Exception ignored) {}
            for (String s : LOG) { o.write(s.getBytes("UTF-8")); o.write('\n'); }
            o.close();
            return f.getAbsolutePath();
        } catch (Exception e) { return ""; }
    }

    /** Observe les réglages système : certains postes y écrivent l'état CAN (portes, marche arrière…). */
    public static void observeSettings(final Context c) {
        if (observing) return;
        observing = true;
        app = c.getApplicationContext();
        final Uri[] uris = { Settings.System.CONTENT_URI, Settings.Global.CONTENT_URI, Settings.Secure.CONTENT_URI };
        for (final Uri base : uris) {
            try {
                c.getContentResolver().registerContentObserver(base, true, new ContentObserver(MAIN) {
                    @Override public void onChange(boolean self, Uri uri) {
                        if (uri == null) return;
                        String k = uri.getLastPathSegment();
                        String v = null;
                        try {
                            if (base == Settings.System.CONTENT_URI) v = Settings.System.getString(c.getContentResolver(), k);
                            else if (base == Settings.Global.CONTENT_URI) v = Settings.Global.getString(c.getContentResolver(), k);
                            else v = Settings.Secure.getString(c.getContentResolver(), k);
                        } catch (Exception ignored) {}
                        if (k != null && (k.startsWith("screen_brightness") || k.equals("next_alarm_formatted"))) return;
                        log("SET " + base.getLastPathSegment() + "/" + k + " = " + v);
                        if (k != null && v != null && LIGHTS.matcher(k).find()) {
                            try { boolean on = Integer.parseInt(v.trim()) != 0; lightsJson = "{\"on\":" + on + ",\"key\":\"" + k + "\"}"; emit("lights", lightsJson); }
                            catch (Exception ignored) {}
                        }
                    }
                });
            } catch (Exception e) { log("observer KO " + base + " " + e); }
        }
    }

    /* ------------ données CAN venues des différentes sources ------------ */
    public static String carJson = null;
    private static final JSONObject CAR = new JSONObject();
    private static String lastDoorSrc = "";
    /** Fusionne des valeurs d'ordinateur de bord (outside, oil, coolant, battery, insp_km…) et prévient l'interface. */
    /** comme mergeCar, sans noter dans le journal les valeurs qui bougent sans arrêt (régime, vitesse) */
    public static synchronized void mergeCarQuiet(JSONObject part, String src) {
        try {
            boolean changed = false; JSONObject logged = new JSONObject();
            java.util.Iterator<String> it = part.keys();
            while (it.hasNext()) { String k = it.next(); Object v = part.get(k); if (!String.valueOf(v).equals(String.valueOf(CAR.opt(k)))) { CAR.put(k, v); changed = true; if (!k.equals("rpm") && !k.equals("speed")) logged.put(k, v); } }
            if (!changed) return;
            CAR.put("src", src); CAR.put("at", System.currentTimeMillis());
            carJson = CAR.toString();
            if (logged.length() > 0) log("ORDI (" + src + ") " + logged);
            emit("car", carJson);
        } catch (Exception ignored) {}
    }
    public static synchronized void mergeCar(JSONObject part, String src) {
        try {
            boolean changed = false;
            java.util.Iterator<String> it = part.keys();
            while (it.hasNext()) { String k = it.next(); Object v = part.get(k); if (!String.valueOf(v).equals(String.valueOf(CAR.opt(k)))) { CAR.put(k, v); changed = true; } }
            if (!changed) return;
            CAR.put("src", src); CAR.put("at", System.currentTimeMillis());
            carJson = CAR.toString();
            log("ORDI (" + src + ") " + part);
            emit("car", carJson);
        } catch (Exception ignored) {}
    }
    /** État des ouvrants lu par une source (diffusion, propriété…) ; garde radars / marche arrière. */
    public static void setDoors(String src, boolean[] d) {
        try {
            JSONObject v = new JSONObject(vehicleJson);
            JSONObject n = new JSONObject(vehicleFrom(d[0], d[1], d[2], d[3], d[4], d[5], false)).getJSONObject("doors");
            if (n.toString().equals(String.valueOf(v.optJSONObject("doors")))) return;
            v.put("doors", n);
            if (!src.equals(lastDoorSrc)) { log("PORTES : source = " + src); lastDoorSrc = src; }
            emit("vehicle", v.toString());
        } catch (Exception ignored) {}
    }

    public static String vehicleFrom(boolean fl, boolean fr, boolean rl, boolean rr, boolean trunk, boolean hood, boolean any) {
        try {
            JSONObject d = new JSONObject();
            d.put("fl", fl); d.put("fr", fr); d.put("rl", rl); d.put("rr", rr); d.put("trunk", trunk); d.put("hood", hood); d.put("any", any);
            JSONObject v = new JSONObject(); v.put("doors", d); v.put("radar", JSONObject.NULL); v.put("reverse", false);
            return v.toString();
        } catch (Exception e) { return vehicleJson; }
    }
}
