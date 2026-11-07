package fr.ibiza.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Observe (en lecture seule) les fenêtres affichées par les autres apps — en particulier la fenêtre
 * flottante « portes / radars » de l'autoradio — pour en déduire l'état des ouvrants.
 * Rien n'est modifié dans le système. Tout ce qui est vu est noté dans le journal de diagnostic.
 */
public class CanWatchService extends AccessibilityService {
    private static final Pattern DOORISH = Pattern.compile("door|trunk|hood|bonnet|tailgate|radar|rada|leida|parking|park_|sensor|probe", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_FL = Pattern.compile("(front_?left|left_?front|\\bf_?l\\b|_fl\\b|_lf\\b|\\blf_|\\bfl_|zuoqian|qianzuo|door_?1\\b|door_?lf|door_?fl)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_FR = Pattern.compile("(front_?right|right_?front|\\bf_?r\\b|_fr\\b|_rf\\b|\\brf_|\\bfr_|youqian|qianyou|door_?2\\b|door_?rf|door_?fr)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_RL = Pattern.compile("(rear_?left|left_?rear|back_?left|left_?back|_rl\\b|_lr\\b|_bl\\b|\\brl_|\\blr_|zuohou|houzuo|door_?3\\b|door_?lr|door_?rl)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_RR = Pattern.compile("(rear_?right|right_?rear|back_?right|right_?back|_rr\\b|_br\\b|\\brr_|youhou|houyou|door_?4\\b|door_?rr)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TRUNK = Pattern.compile("(trunk|tail_?gate|tailgate|\\bboot\\b|back_?door|rear_?door_?box|houbei|houmen|hou_?gai)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_HOOD = Pattern.compile("(hood|bonnet|engine_?cover|front_?cover|qiangai|yinqing|qian_?gai)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_RADAR = Pattern.compile("(radar|rada|leida|park|sensor|probe)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CM = Pattern.compile("(\\d{1,3})\\s*(cm|CM)");

    private final Map<String, String> lastSig = new HashMap<>();
    private JSONObject userMap = null;
    private boolean overlaySeen = false;
    private final Set<String> candidates = new HashSet<>();
    private final Set<String> textApps = new HashSet<>();     // appli CAN (ordinateur de bord) et radio : textes lus
    private long lastScan = 0;
    private boolean lastRadar = false;
    private static final Pattern RADIOPKG = Pattern.compile("radio|\\bfm\\b|\\.fm|tuner", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENWORD = Pattern.compile("ouvert|open|开", Pattern.CASE_INSENSITIVE);

    /** « Porte avant gauche ouverte », « Coffre ouvert », « Door open »… */
    private void doorsFromSentence(String tx, String src) {
        if (tx == null || !OPENWORD.matcher(tx).find()) return;
        String l = tx.toLowerCase();
        boolean[] d = new boolean[6]; boolean any = false;
        boolean av = l.contains("avant") || l.contains("front"), ar = l.contains("arrière") || l.contains("arriere") || l.contains("rear");
        boolean g = l.contains("gauche") || l.contains("left") || l.contains("conducteur") || l.contains("driver"), dr = l.contains("droite") || l.contains("right") || l.contains("passager") || l.contains("passenger");
        if (l.contains("porte") || l.contains("door")) {
            if (g && !ar) { d[0] = true; any = true; } if (dr && !ar) { d[1] = true; any = true; }
            if (g && ar) { d[2] = true; any = true; } if (dr && ar) { d[3] = true; any = true; }
        }
        if (l.contains("coffre") || l.contains("hayon") || l.contains("trunk") || l.contains("boot") || l.contains("tailgate")) { d[4] = true; any = true; }
        if (l.contains("capot") || l.contains("hood") || l.contains("bonnet")) { d[5] = true; any = true; }
        if (any) Hub.setDoors(src, d);
    }
    private String radioRole() {
        try { String r = getSharedPreferences("ibz", MODE_PRIVATE).getString("roles", null); if (r == null) return ""; Object o = new org.json.JSONTokener(r).nextValue(); if (o instanceof String) o = new JSONObject((String) o); String v = ((JSONObject) o).optString("radio", ""); int i = v.indexOf('/'); return i > 0 ? v.substring(0, i) : v; }
        catch (Exception e) { return ""; }
    }
    private static final Pattern CARPKG = Pattern.compile("can|car|bus|vehicle|radar|door|park", Pattern.CASE_INSENSITIVE);

    /* ======== lecture automatique de l'ordinateur de bord CarCan ========
       CarCan (com.hcn.autocan) ne publie ses données (carburant, km, batterie…) qu'à son propre écran : son
       service « ICustomDataServices » existe mais n'envoie rien (vérifié dans l'APK v43). On ouvre donc CarCan,
       on touche « CarInfo », on lit l'écran, puis on revient sur le lanceur — masqué par le rideau depuis la v1.10. */
    static CanWatchService self;
    static volatile long refreshUntil = 0;
    private static boolean finishing = false;
    private static int gen = 0;
    static volatile boolean returning = false;   // retour discret : le lanceur ne repasse pas à l'accueil            // numéro de lecture (un ancien délai de sécurité ne coupe pas la suivante)
    private static final Handler UI = new Handler(Looper.getMainLooper());
    /* v1.10 : lecture « invisible » — un rideau (image figée du lanceur) couvre l'écran pendant que CarCan
       s'ouvre dessous ; si l'accessibilité n'est pas active, on garde l'ancien comportement (CarCan visible). */
    static void requestRefresh(final android.content.Context c) {
        if (refreshUntil > 0) return;          // lecture déjà en cours
        if (LogCan.live()) {                   // v1.15 : valeurs déjà reçues en direct par le journal, pas besoin d'ouvrir CarCan
            Hub.log("CarCan : inutile, données en direct (journal)");
            if (Hub.carJson != null) Hub.emit("car", Hub.carJson);
            return;
        }
        final Intent i;
        try { i = c.getPackageManager().getLaunchIntentForPackage("com.hcn.autocan"); } catch (Exception e) { Hub.log("CarCan refresh : " + e); return; }
        if (i == null) { Hub.log("CarCan introuvable"); return; }
        // v1.15.1 : CarCan accepte l'extra « ToFragment » (nom de classe du fragment) -> ouverture directe sur la page des infos,
        // sans passer par le menu (relevé dans CanActivity.ProcessExtraData / onResume, CarCan v43)
        i.putExtra("ToFragment", "com.hcn.autocan.SimpleSoft.VW.VWInfoFragment");
        refreshUntil = System.currentTimeMillis() + 9000; finishing = false; final int my = ++gen; if (self != null) { self.menuTries = 0; self.lastMenu = ""; }
        final Runnable go = new Runnable() { public void run() {
            try {
                Hub.log("CarCan : lecture automatique de l'ordinateur de bord" + (Curtain.shown() ? " (masquée)" : ""));
                // CLEAR_TOP + SINGLE_TOP : si CarCan tourne déjà, l'intention (et son extra « ToFragment ») lui est bien remise
                // (avec NEW_TASK seul, Android ramène juste l'ancienne tâche, restée sur le menu « Infos car »)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                android.os.Bundle o = null;
                try { o = android.app.ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle(); } catch (Exception ignored) {}
                c.startActivity(i, o);
                // un menu immobile n'envoie plus d'événement : on relit l'écran de CarCan régulièrement pendant la lecture
                UI.postDelayed(new Runnable() { public void run() {
                    if (refreshUntil == 0 || gen != my || self == null) return;
                    try { self.scanWindows(); } catch (Throwable ignored) {}
                    UI.postDelayed(this, 1400);
                }}, 1200);
                UI.postDelayed(new Runnable() { public void run() { if (refreshUntil > 0 && gen == my) { Hub.log("CarCan : délai dépassé"); finishRefresh(); } } }, 9000);
            } catch (Exception e) { Hub.log("CarCan refresh : " + e); refreshUntil = 0; Curtain.hide(); }
        }};
        if (self != null && c instanceof MainActivity) {
            Curtain.snapshot((MainActivity) c, ((MainActivity) c).contentView(), new Curtain.Snap() { public void done(android.graphics.Bitmap b) {
                Curtain.show(self, b);
                UI.postDelayed(go, 60);            // le rideau est à l'écran avant que CarCan ne s'ouvre
            }});
        } else go.run();
    }
    static void finishRefresh() {
        if (finishing) return;
        finishing = true;
        refreshUntil = 0;
        android.content.Context c = Hub.app;
        if (c == null) { Curtain.hide(); return; }
        Intent h = new Intent(c, MainActivity.class);
        h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        h.putExtra("ibz_return", true); returning = true;    // retour discret : le lanceur reste sur la page où l'on était
        android.os.Bundle o = null;
        try { o = android.app.ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle(); } catch (Exception ignored) {}
        try { c.startActivity(h, o); } catch (Exception ignored) {}
        // le rideau ne se lève qu'une fois le lanceur revenu au premier plan
        final long t0 = System.currentTimeMillis();
        UI.post(new Runnable() { public void run() {
            if (Hub.launcherVisible || System.currentTimeMillis() - t0 > 2000) UI.postDelayed(new Runnable() { public void run() { Curtain.hide(); } }, 180);
            else UI.postDelayed(this, 40);
        }});
    }
    private static final Object EARLY = new Object();
    private long lastMenuClick = 0;
    /** pendant une lecture auto : ouvre la page « CarInfo » si on est sur le menu de CarCan */
    /* v1.14.2 : CarCan s'ouvre d'abord sur un menu (« Infos car », « CarInfo »…) ; on cherche l'entrée par son texte
       normalisé (casse, espaces, accents ignorés), on la touche par l'accessibilité, et si la page ne change pas,
       par un vrai toucher à l'écran (certains menus de ces autoradios n'écoutent que les touchers). */
    private static final Pattern[] MENU = {
            Pattern.compile("^(car ?infos?|infos? ?(du |de la )?(car|voiture|vehicule))$"),
            Pattern.compile("(car ?infos?|infos? ?car\\b|infos? (du |de la )?(voiture|vehicule)|obd|odb|ordinateur de bord|trip ?computer|drive ?info)"),
            Pattern.compile("(informations? de (voyage|conduite)|informations?$)")
    };
    private int menuTries = 0;
    private String lastMenu = "";
    private static String norm(CharSequence cs) {
        if (cs == null) return "";
        String t = java.text.Normalizer.normalize(cs.toString(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toLowerCase(java.util.Locale.FRANCE).replaceAll("[_\\-/]+", " ").replaceAll("\\s+", " ").trim();
    }
    private AccessibilityNodeInfo findMenu(AccessibilityNodeInfo n, Pattern p, int depth) {
        if (n == null || depth > 25) return null;
        String t = norm(n.getText()), cd = norm(n.getContentDescription());
        if ((t.length() > 0 && t.length() < 40 && p.matcher(t).find()) || (cd.length() > 0 && cd.length() < 40 && p.matcher(cd).find())) return n;
        for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo r = findMenu(n.getChild(i), p, depth + 1); if (r != null) return r; }
        return null;
    }
    private void driveRefresh(AccessibilityNodeInfo root, List<String> ids) {
        if (refreshUntil == 0 || System.currentTimeMillis() > refreshUntil) return;
        if (ids.contains("ODBpage") || ids.contains("odbtextValue") || ids.contains("textValue") || ids.contains("odbtextName")) {
            menuTries = 0;
            UI.postDelayed(new Runnable() { public void run() { if (refreshUntil > 0) { Hub.log("CarCan : lu, retour au lanceur"); finishRefresh(); } } }, 900);
            return;
        }
        if (System.currentTimeMillis() - lastMenuClick < 1300) return;
        AccessibilityNodeInfo hit = null; Pattern used = null;
        for (Pattern p : MENU) { hit = findMenu(root, p, 0); if (hit != null) { used = p; break; } }
        if (hit == null) { if (menuTries == 0) { Hub.log("CarCan : entrée « Infos car » introuvable sur cet écran"); menuTries = -1; } return; }
        String label = hit.getText() != null ? hit.getText().toString() : String.valueOf(hit.getContentDescription());
        if (!label.equals(lastMenu)) { menuTries = 0; lastMenu = label; }
        menuTries++;
        lastMenuClick = System.currentTimeMillis();
        // les cases du menu CarCan ne réagissent qu'au TOUCHER (OnTouchListener sur l'image), pas au clic d'accessibilité :
        // on touche l'image de la case (voisine du libellé), sinon la case entière, sinon le libellé
        android.graphics.Rect r = new android.graphics.Rect();
        AccessibilityNodeInfo img = imageNear(hit);
        if (menuTries == 1 && img != null) { img.getBoundsInScreen(r); Hub.log("CarCan : toucher l'image de « " + label + " » " + r.toShortString()); }
        else if (menuTries <= 2 && hit.getParent() != null) { hit.getParent().getBoundsInScreen(r); Hub.log("CarCan : toucher la case de « " + label + " » " + r.toShortString()); }
        else if (menuTries <= 4) { hit.getBoundsInScreen(r); Hub.log("CarCan : toucher le libellé « " + label + " » " + r.toShortString()); }
        else { if (menuTries == 5) hit.performAction(AccessibilityNodeInfo.ACTION_CLICK); return; }
        if (r.width() > 0 && r.height() > 0) tapAt(r.centerX(), r.centerY());
    }
    /** l'image (ImageView) de la même case que le libellé trouvé */
    private static AccessibilityNodeInfo imageNear(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int up = 0; up < 3 && p != null; up++) {
            p = p.getParent(); if (p == null) break;
            for (int i = 0; i < p.getChildCount(); i++) {
                AccessibilityNodeInfo c = p.getChild(i);
                if (c != null && c.getClassName() != null && c.getClassName().toString().contains("ImageView")) return c;
            }
        }
        return null;
    }
    /** toucher simulé (Android 7+) ; le rideau laisse passer le toucher le temps du geste */
    private void tapAt(int x, int y) {
        if (android.os.Build.VERSION.SDK_INT < 24) return;
        try {
            Curtain.passThrough(true);
            android.graphics.Path p = new android.graphics.Path(); p.moveTo(x, y);
            android.accessibilityservice.GestureDescription g = new android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 80)).build();
            boolean ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(android.accessibilityservice.GestureDescription d) { UI.postDelayed(new Runnable() { public void run() { Curtain.passThrough(false); } }, 120); }
                @Override public void onCancelled(android.accessibilityservice.GestureDescription d) { Hub.log("CarCan : toucher annulé"); Curtain.passThrough(false); }
            }, null);
            if (!ok) { Hub.log("CarCan : toucher refusé (désactive puis réactive l'accessibilité Ibiza)"); Curtain.passThrough(false); }
        } catch (Throwable e) { Hub.log("CarCan : toucher " + e); Curtain.passThrough(false); }
    }

    @Override protected void onServiceConnected() {
        self = this;
        Hub.app = getApplicationContext();
        Hub.log("Accessibilité connectée : surveillance des fenêtres active");
        loadMap();
        CanBridge.start(this);
    }

    private void loadMap() {
        try {
            File f = new File(getExternalFilesDir(null), "ibiza_map.json");
            if (!f.exists()) return;
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()]; int n = in.read(b); in.close();
            userMap = new JSONObject(new String(b, 0, n, "UTF-8"));
            Hub.log("Correspondance perso chargée : " + userMap.toString());
        } catch (Exception e) { Hub.log("ibiza_map.json illisible : " + e); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p != null && getPackageName().contentEquals(p)) return;
        int t = e.getEventType();
        if (t == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) { // toasts et notifications : « Porte ouverte »…
            String tx = String.valueOf(e.getText());
            Hub.log("TOAST/NOTIF " + p + " · " + tx);
            if (!CanBridge.cemDoors && !Hub.EMULATOR) doorsFromSentence(tx, "message " + p);
            return;
        }
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && p != null) {
            Hub.log("FENÊTRE " + p + " · " + e.getClassName() + (e.getText().isEmpty() ? "" : " · " + e.getText()));
            if (!CanBridge.cemDoors && !Hub.EMULATOR) doorsFromSentence(String.valueOf(e.getText()), "fenêtre " + p);
        }
        // les changements de contenu ne déclenchent un balayage que pour les apps intéressantes (portes, CAN, radio)
        if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && (p == null || !(candidates.contains(p.toString()) || textApps.contains(p.toString())))) return;
        long now = System.currentTimeMillis();
        if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastScan < 400) return; // la radio rafraîchit très souvent
        lastScan = now;
        scanWindows();
    }

    private void scanWindows() {
        List<AccessibilityWindowInfo> ws;
        try { ws = getWindows(); } catch (Exception ex) { return; }
        boolean overlayNow = false;
        boolean[] door = new boolean[6]; // fl fr rl rr trunk hood
        boolean radar = false, reverse = false;
        List<Integer> dists = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AccessibilityWindowInfo w : ws) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence pk = root.getPackageName();
            String pkg = pk == null ? "?" : pk.toString();
            if (getPackageName().equals(pkg)) continue;
            List<String> ids = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            List<String[]> pairs = new ArrayList<>();
            collect(root, ids, texts, pairs, 0);
            if (pkg.equals("com.hcn.autocan")) driveRefresh(root, ids);
            // --- appli radio : fréquence / nom de station ; appli CAN : ordinateur de bord ---
            boolean radioApp = RADIOPKG.matcher(pkg).find() || pkg.equals(radioRole());
            if (radioApp) {
                textApps.add(pkg);
                String[] rd = CarText.parseRadio(pairs);
                if (rd != null) CanBridge.radio(rd[0], rd[1], rd[2], "écran " + pkg);
            } else if (pkg.equals("com.hcn.autocan")) {
                JSONObject car = CarText.parseHcn(pairs);
                if (car.length() > 0) { textApps.add(pkg); Hub.mergeCar(car, "CarCan"); }
                if (refreshUntil > 0 && !finishing && car.length() >= 3) {   // valeurs lues : retour immédiat
                    refreshUntil = System.currentTimeMillis() + 2000;
                    UI.removeCallbacksAndMessages(EARLY); UI.postAtTime(new Runnable() { public void run() { if (refreshUntil > 0) { Hub.log("CarCan : lu, retour au lanceur"); finishRefresh(); } } }, EARLY, android.os.SystemClock.uptimeMillis() + 350);
                }
            } else if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD && texts.size() > 1) {
                JSONObject car = CarText.parseScreen(texts);
                if (car.length() >= 2 || (car.length() >= 1 && CARPKG.matcher(pkg).find())) { textApps.add(pkg); Hub.mergeCar(car, "écran " + pkg); }
            }
            Rect r = new Rect(); w.getBoundsInScreen(r);
            String key = pkg + "#" + w.getId();
            seen.add(key);
            boolean candidate = false;
            for (String id : ids) if (DOORISH.matcher(id).find()) { candidate = true; break; }
            if (userMap != null && pkg.equals(userMap.optString("package"))) candidate = true;
            if (candidate) candidates.add(pkg);
            boolean interesting = candidate || w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM || CARPKG.matcher(pkg).find();
            String sig = interesting ? ids.toString() + texts.toString() : ids.toString();
            if (!sig.equals(lastSig.get(key))) {
                lastSig.put(key, sig);
                if (interesting) Hub.log("VUE " + pkg + " type=" + w.getType() + " " + r.toShortString() + "\n    ids=" + ids + "\n    textes=" + texts);
                else Hub.log("vue " + pkg + " type=" + w.getType() + " (" + ids.size() + " éléments)");
            }
            if (!candidate || w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            // fenêtre portes / radars détectée
            overlayNow = true;
            if (userMap != null && pkg.equals(userMap.optString("package"))) {
                JSONObject d = userMap.optJSONObject("doors");
                String[] k = {"fl", "fr", "rl", "rr", "trunk", "hood"};
                if (d != null) for (int i = 0; i < 6; i++) { String id = d.optString(k[i], ""); if (id.length() > 0 && ids.contains(id)) door[i] = true; }
                String rid = userMap.optString("radar", "");
                if (rid.length() > 0 && ids.contains(rid)) { radar = true; reverse = true; }
            } else {
                for (String id : ids) {
                    if (P_RADAR.matcher(id).find()) { radar = true; reverse = true; continue; }
                    if (P_TRUNK.matcher(id).find()) door[4] = true;
                    else if (P_HOOD.matcher(id).find()) door[5] = true;
                    else if (P_FL.matcher(id).find()) door[0] = true;
                    else if (P_FR.matcher(id).find()) door[1] = true;
                    else if (P_RL.matcher(id).find()) door[2] = true;
                    else if (P_RR.matcher(id).find()) door[3] = true;
                }
            }
            for (String s : texts) { java.util.regex.Matcher m = CM.matcher(s); while (m.find()) dists.add(Integer.parseInt(m.group(1))); }
        }
        // oublie les fenêtres disparues
        lastSig.keySet().retainAll(seen);
        if (CanBridge.cemDoors) { // les portes viennent des propriétés CEM ; ici on ne garde que les radars
            if (radar != lastRadar) {
                lastRadar = radar;
                try {
                    JSONObject v = new JSONObject(Hub.vehicleJson);
                    if (radar) {
                        JSONObject rd = new JSONObject(); org.json.JSONArray rear = new org.json.JSONArray();
                        for (int i = 0; i < 4; i++) rear.put(i < dists.size() ? (Object) dists.get(i) : JSONObject.NULL);
                        rd.put("rear", rear); rd.put("front", new org.json.JSONArray("[null,null,null,null]"));
                        v.put("radar", rd); v.put("reverse", true);
                    } else { v.put("radar", JSONObject.NULL); v.put("reverse", false); }
                    Hub.emit("vehicle", v.toString());
                } catch (Exception ignored) {}
            }
            overlaySeen = overlayNow; return;
        }
        if (Hub.EMULATOR) { overlaySeen = false; return; }      // émulateur : pas de fenêtre portes à interpréter
        if (overlayNow || overlaySeen) {
            boolean anySpecific = door[0] || door[1] || door[2] || door[3] || door[4] || door[5];
            try {
                JSONObject v = new JSONObject(Hub.vehicleFrom(door[0], door[1], door[2], door[3], door[4], door[5], overlayNow && !anySpecific && !radar));
                if (radar) {
                    JSONObject rd = new JSONObject();
                    org.json.JSONArray rear = new org.json.JSONArray();
                    for (int i = 0; i < 4; i++) rear.put(i < dists.size() ? (Object) dists.get(i) : JSONObject.NULL);
                    rd.put("rear", rear); rd.put("front", new org.json.JSONArray("[null,null,null,null]"));
                    v.put("radar", rd); v.put("reverse", reverse);
                }
                String js = v.toString();
                if (!js.equals(Hub.vehicleJson)) Hub.emit("vehicle", js);
            } catch (Exception ignored) {}
        }
        overlaySeen = overlayNow;
    }

    private void collect(AccessibilityNodeInfo n, List<String> ids, List<String> texts, List<String[]> pairs, int depth) {
        if (n == null || depth > 25 || ids.size() > 250) return;
        if (n.isVisibleToUser()) {
            String id = n.getViewIdResourceName(), sid = null;
            if (id != null) { int s = id.indexOf(":id/"); sid = s >= 0 ? id.substring(s + 4) : id; ids.add(sid); }
            CharSequence tx = n.getText(); if (tx != null && tx.length() > 0 && tx.length() < 80) { texts.add(tx.toString()); pairs.add(new String[]{sid, tx.toString()}); }
            CharSequence cd = n.getContentDescription(); if (cd != null && cd.length() > 0 && cd.length() < 60) texts.add("[" + cd + "]");
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), ids, texts, pairs, depth + 1);
    }

    @Override public void onInterrupt() {}
}
