package fr.ibiza.launcher;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** v2.1 : barre latérale qui reste affichée par-dessus Waze, Spotify… (fenêtre système à gauche de l'écran).
 *  Heure, applis du dock, lecture/pause et titre suivant, retour à l'accueil, repli en languette.
 *  Trois façons d'afficher les applis à côté d'elle (réglage « barMode ») :
 *   - « window »  : l'appli s'ouvre dans une fenêtre à droite de la barre (poste qui gère le multi-fenêtres) ;
 *   - « reserve » : la bande de gauche est réservée une fois pour toutes depuis un PC (adb shell wm overscan, Android 10 et avant) ;
 *   - « over »    : l'appli est en plein écran, la barre se pose par-dessus sa bande de gauche ;
 *   - « auto »    : « window » si le poste gère le multi-fenêtres, sinon « over ». */
public final class SideBar {
    static final int ACCENT = 0xFF5CC8FF, LABEL = 0xFFEEF3F8, LABEL2 = 0xADD6E2F0, BG = 0xF50B121B, SLOT = 0x1A8CAACD, SLOT_ON = 0x2E5CC8FF;
    static final float WIDTH_DP = 84, TAB_W_DP = 26, TAB_H_DP = 120;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm", Locale.FRANCE);
    private static View view;
    private static boolean shown = false, collapsed = false, playing = false;
    private static String active = null;
    private static TextView clock;
    private static Glyph play;
    private static final List<View> slots = new ArrayList<>();
    private static final List<String> slotPkgs = new ArrayList<>();
    private static final Runnable TICK = new Runnable() { public void run() { tick(); } };
    private static final Hub.Listener MEDIA = new Hub.Listener() {
        @Override public void onEvent(String type, String json) {
            if (!"media".equals(type)) return;
            try { playing = new JSONObject(json).optBoolean("playing", playing); } catch (Exception ignored) {}
            if (play != null) { play.name = playing ? "pause" : "play"; play.setContentDescription(playing ? "Pause" : "Lecture"); play.invalidate(); }
        }
    };

    private SideBar() {}

    /* ---------------- réglages ---------------- */
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("ibz", Context.MODE_PRIVATE); }
    /** l'interface enregistre ses réglages en JSON ("auto" avec guillemets) : on les retire */
    static String pref(Context c, String k, String dflt) {
        String v = prefs(c).getString(k, dflt);
        return v == null ? dflt : v.replace("\"", "").trim();
    }
    static boolean enabled(Context c) { return !"false".equals(pref(c, "bar", "true")); }
    static String mode(Context c) { String m = pref(c, "barMode", "auto"); return m.length() == 0 ? "auto" : m; }

    static boolean freeformSupported(Context c) {
        if (Build.VERSION.SDK_INT < 24) return false;
        String forced = pref(c, "freeform", "auto");               // v3.1 : réglage manuel si la détection se trompe
        if ("on".equals(forced)) return true;
        if ("off".equals(forced)) return false;
        try { if (c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return true; } catch (Exception ignored) {}
        try { return Settings.Global.getInt(c.getContentResolver(), "enable_freeform_support", 0) != 0; } catch (Exception e) { return false; }
    }
    /** méthode réellement utilisée */
    static String effectiveMode(Context c) {
        String m = mode(c);
        if ("auto".equals(m)) return freeformSupported(c) ? "window" : "over";
        if ("window".equals(m) && !freeformSupported(c)) return "over";
        if ("reserve".equals(m) && Build.VERSION.SDK_INT > 29) return "over";       // la commande « wm overscan » n'existe plus après Android 10
        return m;
    }
    static boolean overlayAllowed(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }
    static int widthPx(Context c) { return Math.round(dp(c, WIDTH_DP)); }

    /** infos pour l'écran Réglages du lanceur */
    static String info(Context c) {
        try {
            JSONObject o = new JSONObject();
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            o.put("api", Build.VERSION.SDK_INT); o.put("release", Build.VERSION.RELEASE);
            o.put("freeform", freeformSupported(c)); o.put("barPx", widthPx(c));
            o.put("screenW", dm.widthPixels); o.put("screenH", dm.heightPixels);
            o.put("mode", mode(c)); o.put("effective", effectiveMode(c));
            o.put("overlay", overlayAllowed(c)); o.put("enabled", enabled(c));
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** applis du dock envoyées par l'interface : [{pkg, cls, label}] */
    static void setApps(Context c, String json) {
        prefs(c).edit().putString("bar_apps", json == null ? "[]" : json).apply();
        if (view != null) { rebuild(c); }
    }

    /* ---------------- lancement d'une appli à côté de la barre ---------------- */
    static void launch(Context c, String pkg, String cls) {
        Intent i;
        if (cls != null && cls.length() > 0) {
            i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(pkg, cls));
        } else {
            i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) { Hub.log("barre : appli introuvable " + pkg); return; }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Bundle opts = null;
        if (enabled(c) && "window".equals(effectiveMode(c)) && Build.VERSION.SDK_INT >= 24) {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int left = widthPx(c);
            // v2.6 : la navigation occupe la partie droite, le cadre Spotify du lanceur reste visible à gauche
            SharedPreferences sp = prefs(c);
            if (pkg.equals(sp.getString("navPkg", ""))) {
                float f = sp.getFloat("navLeft", 0f);
                if (f > 0.1f && f < 0.9f) left = Math.round(dm.widthPixels * f);
            }
            opts = ActivityOptions.makeBasic().setLaunchBounds(new Rect(left, 0, dm.widthPixels, dm.heightPixels)).toBundle();
        }
        active = pkg;
        try { c.startActivity(i, opts); }
        catch (Exception e) {
            try { Intent j = c.getPackageManager().getLaunchIntentForPackage(pkg); if (j != null) { j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(j, opts); } }
            catch (Exception e2) { Hub.log("barre : lancement KO " + pkg + " " + e2); }
        }
        markActive();
    }

    static void goHome(Context c) {
        active = null; markActive();
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { c.startActivity(i); } catch (Exception e) { Hub.log("barre : accueil KO " + e); }
    }

    static void media(Context c, String cmd) {
        if (MediaListener.command(cmd)) return;
        int key = "next".equals(cmd) ? KeyEvent.KEYCODE_MEDIA_NEXT : "prev".equals(cmd) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
    }

    /* ---------------- affichage ---------------- */
    /** le lanceur passe au second plan : on affiche la barre */
    static void onLauncherHidden(Context c) {
        /* v3.0 : en mode fenêtres, le lanceur reste visible derrière les applis (sa barre et ses cadres) :
           ni barre flottante ni mini-lecteur, ils feraient doublon */
        if (Dash.recent() || "window".equals(effectiveMode(c))) { hide(); MiniPlayer.onLauncherShown(); return; }
        MiniPlayer.onLauncherHidden(c);               // v2.4 : rectangle musique en haut à droite
        if (!enabled(c) || !overlayAllowed(c)) { hide(); return; }
        show(c);
    }
    /** le lanceur revient : il a sa propre barre, sauf en mode « bande réservée » où la barre système reste */
    static void onLauncherShown(Context c) {
        MiniPlayer.onLauncherShown();
        active = null; markActive();
        if (enabled(c) && overlayAllowed(c) && "reserve".equals(effectiveMode(c))) show(c); else hide();
    }

    static void show(Context c) {
        c = c.getApplicationContext();
        if (view == null) build(c);
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = params(c);
        try {
            if (view.getParent() != null) wm.updateViewLayout(view, lp); else wm.addView(view, lp);
            if (!shown) { Hub.add(MEDIA); MEDIA.onEvent("media", Hub.mediaJson); }
            shown = true; tick();
        } catch (Exception e) { Hub.log("barre : affichage KO " + e); }
    }

    static void hide() {
        H.removeCallbacks(TICK);
        if (view == null || !shown) return;
        shown = false; Hub.remove(MEDIA);
        try { ((WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(view); } catch (Exception ignored) {}
    }

    /** réglage modifié : on reconstruit si la barre est à l'écran */
    static void reload(Context c) {
        if (!shown) return;
        if (!enabled(c)) { hide(); return; }
        rebuild(c);
    }

    private static void rebuild(Context c) {
        boolean was = shown;
        hide(); view = null;
        if (was) show(c);
    }

    @SuppressWarnings("deprecation")
    private static WindowManager.LayoutParams params(Context c) {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if ("reserve".equals(effectiveMode(c))) flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN;   // dessine dans la bande réservée
        int w = collapsed ? Math.round(dp(c, TAB_W_DP)) : widthPx(c);
        int h = collapsed ? Math.round(dp(c, TAB_H_DP)) : ViewGroup.LayoutParams.MATCH_PARENT;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = collapsed ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.START | Gravity.TOP;
        lp.x = 0; lp.y = 0;
        return lp;
    }

    private static void setCollapsed(Context c, boolean v) {
        collapsed = v;
        boolean was = shown;
        hide(); view = null;
        if (was) show(c);
    }

    private static void tick() {
        H.removeCallbacks(TICK);
        if (!shown) return;
        if (clock != null) clock.setText(HM.format(new Date()));
        H.postDelayed(TICK, 10000);
    }

    private static void markActive() {
        for (int i = 0; i < slots.size(); i++) {
            boolean on = slotPkgs.get(i).equals(active);
            View s = slots.get(i);
            GradientDrawable g = (GradientDrawable) s.getBackground();
            g.setColor(on ? SLOT_ON : SLOT);
            g.setStroke(Math.max(1, Math.round(dp(s.getContext(), on ? 2 : 1))), on ? ACCENT : 0x268CAACD);
        }
    }

    /* ---------------- construction ---------------- */
    private static void build(final Context c) {
        slots.clear(); slotPkgs.clear(); clock = null; play = null;
        if (collapsed) { view = buildTab(c); return; }

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(dp(c, 8));
        col.setPadding(pad, Math.round(dp(c, 12)), pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setStroke(Math.max(1, Math.round(dp(c, 1))), 0x26A0C8F0);
        col.setBackground(bg);

        // heure + trait d'accent
        clock = new TextView(c);
        clock.setTextColor(LABEL); clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); clock.setGravity(Gravity.CENTER);
        col.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(c); GradientDrawable lg = new GradientDrawable(); lg.setColor(ACCENT); lg.setCornerRadius(dp(c, 1)); line.setBackground(lg);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Math.round(dp(c, 22)), Math.max(2, Math.round(dp(c, 2))));
        llp.topMargin = Math.round(dp(c, 4)); llp.bottomMargin = Math.round(dp(c, 8));
        col.addView(line, llp);

        // applis du dock
        try {
            JSONArray a = new JSONArray(prefs(c).getString("bar_apps", "[]"));
            for (int i = 0; i < a.length() && i < 5; i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                final String pkg = o.optString("pkg", ""), cls = o.optString("cls", ""), label = o.optString("label", pkg);
                if (pkg.length() == 0) continue;
                FrameLayout slot = new FrameLayout(c);
                GradientDrawable sg = new GradientDrawable(); sg.setColor(SLOT); sg.setCornerRadius(dp(c, 14)); slot.setBackground(sg);
                ImageView iv = new ImageView(c);
                iv.setImageDrawable(icon(c, pkg, cls)); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int ip = Math.round(dp(c, 7)); iv.setPadding(ip, ip, ip, ip);
                slot.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                slot.setContentDescription(label);
                slot.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { launch(c, pkg, cls); } });
                col.addView(slot, weighted(c, 6));
                slots.add(slot); slotPkgs.add(pkg);
            }
        } catch (Exception e) { Hub.log("barre : applis " + e); }

        View sep = new View(c); sep.setBackgroundColor(0x26A0C8F0);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Math.round(dp(c, 40)), Math.max(1, Math.round(dp(c, 1))));
        sp.topMargin = Math.round(dp(c, 6)); sp.bottomMargin = Math.round(dp(c, 6));
        col.addView(sep, sp);

        // musique
        play = new Glyph(c, playing ? "pause" : "play", true);
        play.setContentDescription(playing ? "Pause" : "Lecture");
        play.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { media(c, "toggle"); playing = !playing; play.name = playing ? "pause" : "play"; play.invalidate(); } });
        col.addView(play, weighted(c, 4));
        Glyph next = new Glyph(c, "next", false); next.setContentDescription("Titre suivant");
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { media(c, "next"); } });
        col.addView(next, weighted(c, 4));

        // accueil + repli
        Glyph home = new Glyph(c, "home", false); home.setContentDescription("Accueil");
        GradientDrawable hg = new GradientDrawable(); hg.setColor(SLOT); hg.setCornerRadius(dp(c, 14)); home.setBackground(hg);
        home.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { goHome(c); } });
        col.addView(home, weighted(c, 6));
        Glyph fold = new Glyph(c, "left", false); fold.setContentDescription("Replier la barre");
        fold.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setCollapsed(c, true); } });
        col.addView(fold, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(dp(c, 34))));

        view = col;
        markActive();
    }

    /** languette affichée quand la barre est repliée */
    private static View buildTab(final Context c) {
        Glyph tab = new Glyph(c, "right", false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG); g.setStroke(Math.max(1, Math.round(dp(c, 1))), 0x40A0C8F0);
        float r = dp(c, 13); g.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        tab.setBackground(g);
        tab.setContentDescription("Déplier la barre");
        tab.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setCollapsed(c, false); } });
        return tab;
    }

    private static LinearLayout.LayoutParams weighted(Context c, float marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.bottomMargin = Math.round(dp(c, marginDp));
        return lp;
    }

    private static Drawable icon(Context c, String pkg, String cls) {
        PackageManager pm = c.getPackageManager();
        try { if (cls != null && cls.length() > 0) return pm.getActivityIcon(new ComponentName(pkg, cls)); } catch (Exception ignored) {}
        try { return pm.getApplicationIcon(pkg); } catch (Exception e) { return pm.getDefaultActivityIcon(); }
    }

    static float dp(Context c, float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()); }

    /** pictogrammes dessinés (pas de police d'icônes à embarquer) */
    static final class Glyph extends View {
        String name; final boolean filled;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Glyph(Context c, String name, boolean filled) {
            super(c); this.name = name; this.filled = filled;
            setClickable(true); setFocusable(false);
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, s = Math.min(w, h);
            if (filled) {
                p.setStyle(Paint.Style.FILL); p.setColor(ACCENT);
                cv.drawCircle(cx, cy, s * 0.42f, p);
                p.setColor(0xFF06121C);
            } else { p.setColor(LABEL); }
            float u = s * (filled ? 0.16f : 0.2f);   // demi-taille du pictogramme
            path.reset();
            switch (name) {
                case "play":
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(cx - u * 0.75f, cy - u); path.lineTo(cx + u, cy); path.lineTo(cx - u * 0.75f, cy + u); path.close();
                    cv.drawPath(path, p); break;
                case "pause":
                    p.setStyle(Paint.Style.FILL);
                    cv.drawRoundRect(new RectF(cx - u * 0.8f, cy - u, cx - u * 0.25f, cy + u), u * 0.15f, u * 0.15f, p);
                    cv.drawRoundRect(new RectF(cx + u * 0.25f, cy - u, cx + u * 0.8f, cy + u), u * 0.15f, u * 0.15f, p);
                    break;
                case "next":
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(cx - u, cy - u); path.lineTo(cx + u * 0.45f, cy); path.lineTo(cx - u, cy + u); path.close();
                    cv.drawPath(path, p);
                    cv.drawRoundRect(new RectF(cx + u * 0.6f, cy - u, cx + u, cy + u), u * 0.12f, u * 0.12f, p);
                    break;
                case "home":
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * 0.06f); p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeCap(Paint.Cap.ROUND);
                    path.moveTo(cx - u, cy - u * 0.1f); path.lineTo(cx, cy - u); path.lineTo(cx + u, cy - u * 0.1f);
                    path.lineTo(cx + u, cy + u); path.lineTo(cx - u, cy + u); path.close();
                    cv.drawPath(path, p); break;
                case "left": case "right":
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, s * 0.07f)); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
                    p.setColor(LABEL2);
                    float d = "left".equals(name) ? 1 : -1, k = Math.min(u, dp(getContext(), 7));
                    path.moveTo(cx + k * 0.5f * d, cy - k); path.lineTo(cx - k * 0.5f * d, cy); path.lineTo(cx + k * 0.5f * d, cy + k);
                    cv.drawPath(path, p); break;
                default: break;
            }
        }
    }
}
