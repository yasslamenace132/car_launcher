package fr.ibiza.launcher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** v2.4 : petit rectangle musique en haut à droite, par-dessus Waze (fenêtre système).
 *  Titre, artiste, lecture/pause, suivant ; un appui sur le titre ouvre l'appli qui joue (Spotify, Bluetooth…).
 *  Alimenté par l'événement « media » (accès aux notifications). Caché tant que rien ne joue. */
public final class MiniPlayer {
    private static View view;
    private static boolean shown = false, wanted = false;
    private static TextView title, sub;
    private static SideBar.Glyph play;
    private static String pkg = "";
    private static boolean playing = false;
    private static Context app;

    private static final Hub.Listener MEDIA = new Hub.Listener() {
        @Override public void onEvent(String type, String json) { if ("media".equals(type)) apply(json); }
    };

    private MiniPlayer() {}

    static boolean enabled(Context c) { return !"false".equals(SideBar.pref(c, "mini", "true")); }

    /** le lanceur passe au second plan */
    static void onLauncherHidden(Context c) {
        app = c.getApplicationContext();
        wanted = enabled(c) && SideBar.overlayAllowed(c);
        if (!wanted) { hide(); return; }
        Hub.add(MEDIA);
        apply(Hub.mediaJson);
    }
    /** le lanceur revient : il affiche sa propre carte musique */
    static void onLauncherShown() {
        wanted = false;
        Hub.remove(MEDIA);
        hide();
    }

    private static void apply(String json) {
        String t = "", a = "";
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            t = o.optString("title", ""); a = o.optString("artist", "");
            if (a.length() == 0) a = o.optString("source", "");
            pkg = o.optString("pkg", pkg);
            playing = o.optBoolean("playing", playing);
        } catch (Exception ignored) {}
        if (!wanted || app == null) return;
        if (t.length() == 0) { hide(); return; }
        if (view == null) build(app);
        title.setText(t); sub.setText(a); sub.setVisibility(a.length() > 0 ? View.VISIBLE : View.GONE);
        play.name = playing ? "pause" : "play"; play.setContentDescription(playing ? "Pause" : "Lecture"); play.invalidate();
        show(app);
    }

    @SuppressWarnings("deprecation")
    private static void show(Context c) {
        if (shown) return;
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.round(SideBar.dp(c, 300)), Math.round(SideBar.dp(c, 68)), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Math.round(SideBar.dp(c, 12)); lp.y = Math.round(SideBar.dp(c, 12));
        try { ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).addView(view, lp); shown = true; }
        catch (Exception e) { Hub.log("mini-lecteur : affichage KO " + e); }
    }

    static void hide() {
        if (view == null || !shown) return;
        shown = false;
        try { ((WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(view); } catch (Exception ignored) {}
    }

    private static void build(final Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Math.round(SideBar.dp(c, 8));
        row.setPadding(Math.round(SideBar.dp(c, 14)), p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SideBar.BG); bg.setCornerRadius(SideBar.dp(c, 18));
        bg.setStroke(Math.max(1, Math.round(SideBar.dp(c, 1))), 0x40A0C8F0);
        row.setBackground(bg);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(c);
        title.setTextColor(SideBar.LABEL); title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        sub = new TextView(c);
        sub.setTextColor(SideBar.LABEL2); sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title); text.addView(sub);
        text.setClickable(true);
        text.setContentDescription("Ouvrir l'appli musique");
        text.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (pkg != null && pkg.length() > 0) SideBar.launch(c, pkg, null);
        } });
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int b = Math.round(SideBar.dp(c, 52));
        play = new SideBar.Glyph(c, "play", true);
        play.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            SideBar.media(c, "toggle"); playing = !playing; play.name = playing ? "pause" : "play"; play.invalidate();
        } });
        row.addView(play, new LinearLayout.LayoutParams(b, b));
        SideBar.Glyph next = new SideBar.Glyph(c, "next", false);
        next.setContentDescription("Titre suivant");
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { SideBar.media(c, "next"); } });
        row.addView(next, new LinearLayout.LayoutParams(b, b));
        view = row;
    }
}
