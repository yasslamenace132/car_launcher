package fr.ibiza.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Récupère les infos CAN (portes, ordinateur de bord, radio) sans connaître à l'avance le firmware du poste.
 *
 * Sur le Junsun V1, le boîtier CAN (profil Volkswagen, protocole Raise/Hiworld) parle au MCU de l'autoradio,
 * puis l'appli « CAN / Infos véhicule » du poste affiche les données et les diffuse aux autres apps du système
 * (lanceur d'origine, fenêtre des portes…). On n'a pas accès au port série du boîtier sans root ; on écoute donc
 * ce que le système diffuse, par quatre voies :
 *
 *  1. Découverte automatique : on ouvre les APK des applis CAN / voiture / lanceur d'origine, on y lit toutes les
 *     chaînes qui ressemblent à des actions d'Intent (manifeste + code), et on s'abonne à toutes. Chaque diffusion
 *     reçue est notée dans le journal (action + contenu), et interprétée si les noms de champs sont parlants.
 *  2. Propriétés système (getprop) : certains firmwares y écrivent l'état des portes / la marche arrière.
 *  3. Journal système (logcat), si la permission READ_LOGS a été accordée une fois par adb.
 *  4. Lecture des écrans (accessibilité, voir CanWatchService) : ordinateur de bord, radio.
 *
 * Correspondances perso possibles dans Android/data/fr.ibiza.launcher/files/ibiza_map.json (voir README).
 */
public final class CanBridge {
    private CanBridge() {}

    private static boolean started = false;
    private static Context app;
    private static Handler bg;
    private static final Set<String> registered = new LinkedHashSet<>();
    private static final Map<String, String> lastByAction = new HashMap<>();
    private static Map<String, String> props = new HashMap<>();
    private static long lastBroadcastLog = 0; private static int burst = 0;

    static final Pattern CARPKG = Pattern.compile("can|car|bus|vehicle|radar|door|park|mcu|setting|launcher|home|ui|syu|microntek|twd|nwd|zxw|ts\\.|tw\\.|hct|kld|fyt|junsun|raise|hiworld|xinbas", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIONISH = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+){2,8}$");
    private static final Pattern ACTION_TOPIC = Pattern.compile("can|car|door|radar|temp|mcu|bus|info|radio|reverse|back|park|trip|obd|vehicle|meter|fuel|oil|light|ill|acc|music|media|meta|play|fm|tuner|state|status|data|update|notify|change", Pattern.CASE_INSENSITIVE);

    /* actions connues (lecteurs Android : titre / artiste) + quelques actions CAN répandues sur les postes chinois */
    private static final String[] KNOWN = {
            "com.android.music.metachanged", "com.android.music.playstatechanged", "com.android.music.queuechanged",
            "com.htc.music.metachanged", "com.sec.android.app.music.metachanged", "com.miui.player.metachanged",
            "com.andrew.apollo.metachanged", "com.spotify.music.metadatachanged", "com.spotify.music.playbackstatechanged",
            "com.microntek.canbusdisplay", "com.microntek.music.report", "com.microntek.radio.report",
            "com.syu.canbus.door", "com.syu.radio.Info", "com.syu.music.info",
            "com.nwd.action.ACTION_CAN_INFO", "android.intent.action.CANBUS", "com.can.doorinfo",
            "android.intent.action.BACKCAR", "com.android.backcar", "android.intent.action.REVERSE"
    };

    public static synchronized void start(Context c) {
        if (started) return;
        started = true;
        app = c.getApplicationContext();
        HandlerThread t = new HandlerThread("ibiza-can"); t.start();
        bg = new Handler(t.getLooper());
        for (String a : KNOWN) listen(a);
        JSONObject map = userMap();
        if (map != null) { JSONArray b = map.optJSONArray("broadcasts"); if (b != null) for (int i = 0; i < b.length(); i++) listen(b.optString(i)); }
        bg.post(new Runnable() { public void run() { discover(false); } });
        bg.postDelayed(propPoll, 3000);
        bg.postDelayed(fastPoll, 800);
        startLogcat();
        LogCan.start();          // v1.15 : ordinateur de bord en direct depuis le journal de CarCan
        if (!logsAllowed() && !Hub.EMULATOR && app != null) RootGrant.tryAsync(app, false);   // v1.15.1 : s'autorise seul si l'autoradio est rooté
        Hub.log("CAN : écoute démarrée (" + registered.size() + " actions)");
    }

    static JSONObject userMap() {
        try {
            File f = new File(app.getExternalFilesDir(null), "ibiza_map.json");
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f); byte[] b = new byte[(int) f.length()]; int n = in.read(b); in.close();
            return new JSONObject(new String(b, 0, n, "UTF-8"));
        } catch (Exception e) { return null; }
    }

    /* ======================= 1. diffusions ======================= */
    private static final BroadcastReceiver RX = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { try { onBroadcast(i); } catch (Exception e) { Hub.log("CAN rx : " + e); } }
    };

    private static void listen(final String action) {
        if (action == null || action.length() == 0 || registered.contains(action)) return;
        registered.add(action);
        final IntentFilter f = new IntentFilter(action);
        Runnable r = new Runnable() { public void run() {
            try {
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(RX, f, null, bg, Context.RECEIVER_EXPORTED);
                else app.registerReceiver(RX, f, null, bg);
            } catch (Exception e) { Hub.log("CAN abonnement " + action + " : " + e); }
        }};
        if (bg != null) bg.post(r); else r.run();
    }

    private static void onBroadcast(Intent i) {
        String action = i.getAction();
        Bundle x = i.getExtras();
        JSONObject flat = new JSONObject();
        if (x != null) for (String k : x.keySet()) {
            Object v = x.get(k);
            try { flat.put(k, describe(v)); } catch (Exception ignored) {}
        }
        String sig = flat.toString();
        if (!sig.equals(lastByAction.get(action))) {
            lastByAction.put(action, sig);
            long now = System.currentTimeMillis();
            if (now - lastBroadcastLog > 1000) { burst = 0; lastBroadcastLog = now; }
            if (++burst <= 8) Hub.log("DIFF " + action + " " + (sig.length() > 400 ? sig.substring(0, 400) + "…" : sig));
        }
        if (x == null) return;
        // --- lecteurs Android : titre / artiste ---
        if (action.contains("metachanged") || action.contains("metadatachanged") || action.contains("music.info") || action.contains("music.report")) {
            String title = str(x, "track", "title", "name", "song");
            if (title != null) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("title", title); o.put("artist", nz(str(x, "artist", "singer", "album"))); o.put("kind", "bcast");
                    o.put("source", "Lecteur");
                    if (x.containsKey("playing")) o.put("playing", x.getBoolean("playing"));
                    MediaListener.setFallback(o);
                } catch (Exception ignored) {}
            }
        }
        if (action.contains("radio")) {
            String freq = str(x, "freq", "frequency", "band_freq", "curfreq");
            String ps = str(x, "ps", "psname", "station", "name", "rds_ps");
            if (freq != null || ps != null) radio(freq, ps, str(x, "rt", "radiotext", "rds_rt"), action);
        }
        // --- noms de champs parlants : portes / données ---
        boolean[] doors = new boolean[6]; boolean doorSeen = false;
        JSONObject car = new JSONObject();
        for (String k : x.keySet()) {
            Object v = x.get(k);
            int di = CarText.doorIndex(k);
            Boolean b = asBool(v);
            if (di >= 0 && b != null) { doors[di] = b; doorSeen = true; continue; }
            Double d = asNum(v);
            if (d != null) { String ck = CarText.keyFor(k); if (ck != null) try { car.put(ck, d); } catch (Exception ignored) {} }
        }
        if (doorSeen && !cemDoors) Hub.setDoors("diffusion " + action, doors);
        if (car.length() > 0) Hub.mergeCar(car, "diffusion");
    }

    private static Object describe(Object v) {
        if (v == null) return JSONObject.NULL;
        if (v instanceof byte[]) { byte[] b = (byte[]) v; StringBuilder s = new StringBuilder("hex:"); for (int j = 0; j < b.length && j < 64; j++) s.append(String.format("%02X", b[j] & 0xFF)); return s.toString(); }
        if (v instanceof int[]) { int[] a = (int[]) v; JSONArray j = new JSONArray(); for (int q = 0; q < a.length && q < 64; q++) j.put(a[q]); return j; }
        if (v instanceof Bundle) { JSONObject o = new JSONObject(); Bundle b = (Bundle) v; for (String k : b.keySet()) try { o.put(k, describe(b.get(k))); } catch (Exception ignored) {} return o; }
        if (v instanceof Number || v instanceof Boolean) return v;
        return String.valueOf(v);
    }
    private static String str(Bundle x, String... keys) {
        for (String k : x.keySet()) for (String w : keys) if (k.equalsIgnoreCase(w)) { Object v = x.get(k); if (v != null && String.valueOf(v).trim().length() > 0) return String.valueOf(v).trim(); }
        return null;
    }
    private static String nz(String s) { return s == null ? "" : s; }
    static Boolean asBool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Integer || v instanceof Long || v instanceof Byte || v instanceof Short) { long n = ((Number) v).longValue(); if (n == 0 || n == 1) return n == 1; }
        if (v instanceof String) { String s = ((String) v).trim().toLowerCase(); if (s.equals("1") || s.equals("true") || s.equals("open") || s.equals("on")) return true; if (s.equals("0") || s.equals("false") || s.equals("close") || s.equals("closed") || s.equals("off")) return false; }
        return null;
    }
    static Double asNum(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) try { return Double.parseDouble(((String) v).trim().replace(',', '.')); } catch (Exception ignored) {}
        return null;
    }

    static void radio(String freq, String ps, String rt, String src) {
        try {
            JSONObject o = new JSONObject();
            String f = freq == null ? null : CarText.prettyFreq(freq);
            o.put("title", ps != null && ps.length() > 0 ? ps : (f != null ? f : "Radio"));
            String sub = (ps != null && f != null) ? f : "";
            if (rt != null && rt.length() > 0) sub = sub.length() > 0 ? sub + " · " + rt : rt;
            o.put("artist", sub); o.put("source", "Radio"); o.put("kind", "radio"); o.put("from", src);
            if (MediaListener.fallback != null && MediaListener.fallback.optString("title").equals(o.optString("title")) && MediaListener.fallback.optString("artist").equals(o.optString("artist"))) return;
            MediaListener.setFallback(o);
        } catch (Exception ignored) {}
    }

    /* ======================= découverte dans les APK ======================= */
    /** Liste les applis susceptibles de gérer le CAN, lit leurs actions d'Intent, s'abonne. Renvoie un rapport. */
    static String discover(boolean verbose) {
        StringBuilder rep = new StringBuilder();
        try {
            PackageManager pm = app.getPackageManager();
            Set<String> pk = candidatePackages();
            SharedPreferences sp = app.getSharedPreferences("ibz_can", Context.MODE_PRIVATE);
            int before = registered.size();
            for (String p : pk) {
                try {
                    PackageInfo pi = pm.getPackageInfo(p, PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
                    String label = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
                    long ver = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                    rep.append("• ").append(label).append(" (").append(p).append(" v").append(pi.versionName).append(")\n");
                    if (verbose) {
                        appendComps(rep, "service", pi.services); appendComps(rep, "récepteur", pi.receivers); appendComps(rep, "fournisseur", pi.providers);
                    }
                    String cacheKey = p + "@" + ver, cached = sp.getString(cacheKey, null);
                    List<String> acts = new ArrayList<>();
                    if (cached != null) { JSONArray a = new JSONArray(cached); for (int i = 0; i < a.length(); i++) acts.add(a.getString(i)); }
                    else { acts = actionsIn(pi.applicationInfo); sp.edit().putString(cacheKey, new JSONArray(acts).toString()).apply(); }
                    for (String a : acts) listen(a);
                    if (verbose && acts.size() > 0) rep.append("    actions : ").append(acts.size() > 40 ? acts.subList(0, 40) + " …" : acts.toString()).append('\n');
                } catch (Exception e) { rep.append("  ").append(p).append(" : ").append(e).append('\n'); }
            }
            String head = "CAN : " + pk.size() + " applis analysées, " + (registered.size() - before) + " nouvelles actions écoutées (" + registered.size() + " au total)";
            Hub.log(head + "\n" + rep);
            return head + "\n" + rep;
        } catch (Exception e) { Hub.log("CAN découverte : " + e); return "Erreur : " + e; }
    }

    private static void appendComps(StringBuilder rep, String kind, ComponentInfo[] cs) {
        if (cs == null) return;
        for (ComponentInfo ci : cs) if (ci.exported) rep.append("    ").append(kind).append(" ").append(ci.name).append('\n');
    }

    static Set<String> candidatePackages() {
        PackageManager pm = app.getPackageManager();
        Set<String> out = new LinkedHashSet<>();
        JSONObject map = userMap();
        if (map != null && map.optString("package").length() > 0) out.add(map.optString("package"));
        // lanceurs d'origine (ils affichent souvent portes / température : ils écoutent donc les bonnes diffusions)
        Intent h = new Intent(Intent.ACTION_MAIN); h.addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : pm.queryIntentActivities(h, 0)) if (!app.getPackageName().equals(r.activityInfo.packageName)) out.add(r.activityInfo.packageName);
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            boolean sys = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (!sys || ai.packageName.equals(app.getPackageName())) continue;
            String p = ai.packageName;
            if (p.startsWith("com.android.") && !p.matches(".*(can|car|launcher).*")) continue;
            if (p.startsWith("com.google.") || p.startsWith("android")) continue;
            String label = String.valueOf(pm.getApplicationLabel(ai));
            if (CARPKG.matcher(p).find() || label.matches("(?i).*(can|voiture|car|véhicule|vehicle|réglage|setting|radio|bus).*")) out.add(p);
        }
        return out;
    }

    /** Chaînes « actions d'Intent » trouvées dans l'APK (manifeste binaire + fichiers dex). */
    static List<String> actionsIn(ApplicationInfo ai) {
        Set<String> found = new LinkedHashSet<>();
        List<String> apks = new ArrayList<>();
        apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) apks.add(s);
        for (String path : apks) {
            ZipFile z = null;
            try {
                z = new ZipFile(path);
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement(); String n = e.getName();
                    if (n.equals("AndroidManifest.xml")) for (String s : axmlStrings(read(z, e))) consider(s, found);
                    else if (n.matches("classes\\d*\\.dex") && e.getSize() < 40_000_000) for (String s : dexStrings(read(z, e))) consider(s, found);
                }
            } catch (Exception ex) { Hub.log("CAN lecture " + path + " : " + ex); }
            finally { try { if (z != null) z.close(); } catch (Exception ignored) {} }
        }
        // les APK « odex » (code hors de l'APK) n'ont pas de dex : on garde au moins le manifeste
        List<String> l = new ArrayList<>(found);
        return l.size() > 400 ? l.subList(0, 400) : l;
    }
    private static void consider(String s, Set<String> out) {
        if (s.length() < 8 || s.length() > 90 || !ACTIONISH.matcher(s).matches()) return;
        if (s.startsWith("android.") || s.startsWith("java.") || s.startsWith("javax.") || s.startsWith("androidx.") || s.startsWith("kotlin") || s.startsWith("com.google.") || s.startsWith("org.")) return;
        String last = s.substring(s.lastIndexOf('.') + 1);
        // une classe Java (MainActivity) n'est pas une action ; une action finit souvent en MAJUSCULES ou en minuscules
        if (last.matches(".*(Activity|Service|Receiver|Provider|Fragment|View|Adapter|Manager|Helper|Util|Utils|Impl|Callback|Listener|Dialog|Layout|Application|Binder|Stub|Proxy|Exception|Handler|Task|Thread|Bean|Entity|Model)$")) return;
        if (last.matches("(java|kt|png|jpg|xml|json|so|txt|db|html|js)$")) return;
        if (!ACTION_TOPIC.matcher(s).find()) return;
        out.add(s);
    }
    private static byte[] read(ZipFile z, ZipEntry e) throws Exception {
        InputStream in = z.getInputStream(e); ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close(); return o.toByteArray();
    }
    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    private static int u32(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24; }
    /** pool de chaînes d'un XML binaire Android */
    static List<String> axmlStrings(byte[] b) {
        List<String> out = new ArrayList<>();
        try {
            int o = 8; if (u16(b, o) != 0x0001) return out;
            int count = u32(b, o + 8), flags = u32(b, o + 16), strStart = u32(b, o + 20);
            boolean utf8 = (flags & 0x100) != 0;
            for (int i = 0; i < count; i++) {
                int p = o + strStart + u32(b, o + 28 + i * 4);
                if (utf8) {
                    int n = b[p] & 0xFF; p += (n & 0x80) != 0 ? 2 : 1;
                    int len = b[p] & 0xFF; if ((len & 0x80) != 0) { len = ((len & 0x7F) << 8) | (b[p + 1] & 0xFF); p += 2; } else p += 1;
                    out.add(new String(b, p, len, "UTF-8"));
                } else {
                    int len = u16(b, p); p += 2; if ((len & 0x8000) != 0) { len = ((len & 0x7FFF) << 16) | u16(b, p); p += 2; }
                    out.add(new String(b, p, len * 2, "UTF-16LE"));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }
    /** table de chaînes d'un fichier dex */
    static List<String> dexStrings(byte[] b) {
        List<String> out = new ArrayList<>();
        try {
            int n = u32(b, 0x38), off = u32(b, 0x3C);
            for (int i = 0; i < n; i++) {
                int p = u32(b, off + i * 4), len = 0, shift = 0, c;
                do { c = b[p++] & 0xFF; len |= (c & 0x7F) << shift; shift += 7; } while ((c & 0x80) != 0);
                if (len < 8 || len > 90) continue;
                int e = p; while (b[e] != 0) e++;
                out.add(new String(b, p, e - p, "UTF-8"));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Copie l'APK de chaque appli CAN dans Android/data/fr.ibiza.launcher/files/apk pour analyse. */
    static String exportApks() {
        try {
            File dir = new File(app.getExternalFilesDir(null), "apk"); dir.mkdirs();
            int n = 0; PackageManager pm = app.getPackageManager();
            for (String p : candidatePackages()) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                    File src = new File(ai.sourceDir);
                    if (src.length() > 120_000_000L) continue;
                    FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(new File(dir, p + ".apk"));
                    byte[] buf = new byte[65536]; int r; while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    in.close(); out.close(); n++;
                } catch (Exception e) { Hub.log("export " + p + " : " + e); }
            }
            Hub.log("CAN : " + n + " APK copiés dans " + dir.getAbsolutePath());
            return n + " APK → " + dir.getAbsolutePath();
        } catch (Exception e) { return ""; }
    }

    /* ======================= 2. propriétés système ======================= */
    private static final Pattern PROP_KEY = Pattern.compile("can|door|car|radar|reverse|backcar|back_car|mcu|temp|ill|lamp|light|park|gear|acc|cem|radio|rds|fm|freq", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROP_NOISE = Pattern.compile("^log\\.|statistics|^ro\\.|^init\\.svc", Pattern.CASE_INSENSITIVE);

    /* ======================= 2 bis. postes « hcn » (Junsun MT8163) : propriétés CEM lues en direct =======================
       persist.vendor.cem.cardoor1..5 : 1 = fermé, 2 = ouvert (relevé sur le poste : porte conducteur = cardoor1).
       Ordre confirmé dans CarCan (HCNConstants) : 1 avant gauche, 2 avant droite, 3 arrière gauche, 4 arrière droite, 5 coffre (modifiable dans
       ibiza_map.json : "cemDoors": [0,1,2,3,4] = index fl fr rl rr trunk hood pour cardoor1..5).
       Lecture par SystemProperties (sans lancer de processus) toutes les 0,5 s. */
    static volatile boolean cemDoors = false;
    static volatile long simUntil = 0;
    static volatile int canKmh = -1, gpsKmh = 0; static volatile long gpsAt = 0;   // v1.12 : vitesse pour l'alerte « ouvrant ouvert en roulant »
    static volatile boolean canSpeed = false;          // vitesse lue sur le CAN : le GPS n'est plus utilisé pour l'affichage                 // « Simuler » dans les réglages : on ne réécrase pas pendant 15 s          // vrai dès que le poste publie les portes ainsi : source de référence
    private static java.lang.reflect.Method SP_GET;
    static String sysprop(String k) {
        try {
            if (SP_GET == null) SP_GET = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            return (String) SP_GET.invoke(null, k);
        } catch (Throwable t) { return props.get(k); }
    }
    private static int[] cemMap() {
        int[] m = {0, 1, 2, 3, 4, 5};
        try { JSONObject u = userMap(); JSONArray a = u == null ? null : u.optJSONArray("cemDoors"); if (a != null) for (int i = 0; i < a.length() && i < 6; i++) m[i] = a.getInt(i); } catch (Exception ignored) {}
        return m;
    }
    private static int[] CEM = null;
    private static final Map<String, String> fastLast = new HashMap<>();
    private static final Runnable fastPoll = new Runnable() { public void run() {
        try {
            if (CEM == null) CEM = cemMap();
            boolean[] d = new boolean[6]; boolean any = false;
            for (int n = 1; n <= 6; n++) {
                String k = "persist.vendor.cem.cardoor" + n, v = sysprop(k);
                if (v == null || v.length() == 0) continue;
                any = true;
                if (!v.equals(fastLast.get(k))) { if (fastLast.containsKey(k)) Hub.log("PORTE " + k + " = " + v + (v.equals("2") ? " (ouverte)" : " (fermée)")); fastLast.put(k, v); }
                int idx = CEM[n - 1];
                if (idx >= 0 && idx < 6) d[idx] = d[idx] || v.trim().equals("2");
            }
            if (any && System.currentTimeMillis() > simUntil) { if (!cemDoors) Hub.log("PORTES : lues dans persist.vendor.cem.cardoor* (1 = fermé, 2 = ouvert)"); cemDoors = true; Hub.setDoors("propriétés CEM", d); }
            // clignotants (light0 gauche, light1 droit, light2 warning ; 1 = éteint, 2 = allumé) et vitesse CAN (km/h)
            String tl = sysprop("persist.vendor.cem.light0"), tr = sysprop("persist.vendor.cem.light1");
            String turn = (("2".equals(tl)) ? "L" : "") + (("2".equals(tr)) ? "R" : "");
            if (!turn.equals(fastLast.get("turn"))) { fastLast.put("turn", turn); Hub.emit("turn", "{\"left\":" + turn.contains("L") + ",\"right\":" + turn.contains("R") + "}"); }
            String sp = sysprop("persist.vendor.cem.speed");
            if ("1".equals(sysprop("persist.sys.cem.speed.from.can")) && sp != null && sp.matches("\\d{1,3}")) {
                if (!sp.equals(fastLast.get("speed"))) { fastLast.put("speed", sp); canSpeed = true; Hub.emit("speed", "{\"kmh\":" + sp + ",\"src\":\"can\"}"); }
            }
            if (canSpeed && sp != null && sp.matches("\\d{1,3}")) canKmh = Integer.parseInt(sp);
            if (any) {
                int kmh = canSpeed && canKmh >= 0 ? canKmh : (System.currentTimeMillis() - gpsAt < 5000 ? gpsKmh : 0);
                MovingAlert.check(d, kmh);
            }
        } catch (Exception e) { Hub.log("cem : " + e); }
        bg.postDelayed(this, 500);
    }};
    private static final Pattern PROP_LINE = Pattern.compile("^\\[(.+?)\\]: \\[(.*)\\]$");
    private static int propTicks = 0;
    private static final Runnable propPoll = new Runnable() { public void run() {
        try {
            Map<String, String> now = new HashMap<>();
            Process pr = Runtime.getRuntime().exec("getprop");
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) { Matcher m = PROP_LINE.matcher(line); if (m.find() && PROP_KEY.matcher(m.group(1)).find()) now.put(m.group(1), m.group(2)); }
            r.close(); pr.destroy();
            boolean first = props.isEmpty();
            if (first) { Map<String, String> show = new java.util.TreeMap<>(); for (Map.Entry<String, String> e : now.entrySet()) if (!PROP_NOISE.matcher(e.getKey()).find()) show.put(e.getKey(), e.getValue()); Hub.log("PROP au démarrage : " + show); }
            boolean[] doors = new boolean[6]; boolean seen = false; JSONObject car = new JSONObject();
            for (Map.Entry<String, String> e : now.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                if (PROP_NOISE.matcher(k).find()) continue;
                if (!first && !v.equals(props.get(k))) Hub.log("PROP " + k + " = " + v + " (avant : " + props.get(k) + ")");
                if (k.startsWith("persist.vendor.cem.cardoor")) continue;     // traité par fastPoll (1/2 et non 0/1)
                int di = CarText.doorIndex(k); Boolean b = asBool(v);
                if (di >= 0 && b != null && !k.toLowerCase().contains("boot")) { doors[di] = doors[di] || b; seen = true; if (b && !v.equals(props.get(k))) Hub.log("PORTES : propriété " + k + " = " + v); }   // « boot » = démarrage système, pas le coffre
                String ck = CarText.keyFor(k); Double d = asNum(v);
                if (ck != null && d != null) car.put(ck, d);
            }
            props = now;
            if (seen && !cemDoors && !Hub.EMULATOR) Hub.setDoors("propriétés", doors);   // émulateur : pas de CAN, pas de détection de secours
            if (car.length() > 0) Hub.mergeCar(car, "propriétés");
        } catch (Exception e) { if (propTicks == 0) Hub.log("getprop : " + e); }
        propTicks++;
        bg.postDelayed(this, 5000);
    }};

    /** Propriétés intéressantes (pour l'export du journal). */
    static String propsSnapshot() {
        StringBuilder b = new StringBuilder("=== Propriétés système (instantané) ===\n");
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(props).entrySet()) if (!PROP_NOISE.matcher(e.getKey()).find()) b.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        return b.append("=== fin ===\n").toString();
    }

    /* ======================= 3. logcat (permission READ_LOGS par adb) ======================= */
    private static final Pattern LOG_HIT = Pattern.compile("door|canbus|can_|radar|reverse|backcar|outtemp|out_temp|temperature|maintenance|inspection|mileage|fuel|车门|保养|温度|雷达|倒车", Pattern.CASE_INSENSITIVE);
    static boolean logsAllowed() { return app != null && app.checkCallingOrSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED; }
    private static void startLogcat() {
        if (!logsAllowed()) { Hub.log("logcat : non autorisé (optionnel : adb shell pm grant fr.ibiza.launcher android.permission.READ_LOGS)"); return; }
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                Process pr = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "brief", "-T", "1"});
                BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                String line; long win = 0; int n = 0;
                Hub.log("logcat : lecture active");
                while ((line = r.readLine()) != null) {
                    if (line.contains("fr.ibiza.launcher") || !LOG_HIT.matcher(line).find()) continue;
                    long now = System.currentTimeMillis(); if (now - win > 1000) { win = now; n = 0; }
                    if (++n <= 6) Hub.log("LOG " + (line.length() > 220 ? line.substring(0, 220) : line));
                }
            } catch (Exception e) { Hub.log("logcat : " + e); }
        }}, "ibiza-logcat");
        t.setDaemon(true); t.start();
    }
}
