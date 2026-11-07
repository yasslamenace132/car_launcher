package fr.ibiza.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;

/** v3.0 : tableau de bord. Pose les vraies applis (Waze, Spotify…) en fenêtres sur les cadres de l'accueil.
 *  Nécessite les fenêtres de forme libre d'Android (Options pour les développeurs). Les fenêtres gardent
 *  leur position ; relancer une appli déjà ouverte la ramène simplement au premier plan. */
public final class Dash {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final int WINDOWING_MODE_FREEFORM = 5;
    static long lastTile = 0;

    private Dash() {}

    /** json : [{pkg, cls, x, y, w, h}] en pixels de la page (le décalage de la page à l'écran est ajouté ici) */
    static void tile(final Activity a, final View page, final String json) {
        final int[] loc = new int[2];
        try { page.getLocationOnScreen(loc); } catch (Exception ignored) {}
        try {
            final JSONArray arr = new JSONArray(json);
            lastTile = SystemClock.elapsedRealtime();
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                H.postDelayed(new Runnable() { public void run() {
                    Rect r = new Rect(loc[0] + o.optInt("x"), loc[1] + o.optInt("y"),
                            loc[0] + o.optInt("x") + o.optInt("w"), loc[1] + o.optInt("y") + o.optInt("h"));
                    open(a, o.optString("pkg"), o.optString("cls"), r);
                    lastTile = SystemClock.elapsedRealtime();
                } }, i * 450L);
            }
        } catch (Exception e) { Hub.log("tableau de bord : " + e); }
    }

    /** vrai juste après un placement (le lanceur passe en pause mais reste visible derrière les fenêtres) */
    static boolean recent() { return SystemClock.elapsedRealtime() - lastTile < 4000; }

    static void open(Activity a, String pkg, String cls, Rect bounds) {
        if (pkg == null || pkg.length() == 0) return;
        Intent i;
        if (cls != null && cls.length() > 0) {
            i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(pkg, cls));
        } else {
            i = a.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) { Hub.log("tableau de bord : appli absente " + pkg); return; }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Bundle opts = null;
        if (Build.VERSION.SDK_INT >= 24 && bounds != null) {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchBounds(bounds);
            try {   // API cachée : force le mode fenêtre si le système l'accepte (sinon les bornes suffisent en mode forme libre)
                Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                m.invoke(o, WINDOWING_MODE_FREEFORM);
            } catch (Throwable ignored) {}
            opts = o.toBundle();
        }
        try { a.startActivity(i, opts); }
        catch (Exception e) { Hub.log("tableau de bord : ouverture KO " + pkg + " " + e); }
    }

    /** ouverture simple (plein écran), utilisée pour réveiller l'appli « BT Music » du poste */
    static void openPlain(Activity a, String pkg, String cls) { open(a, pkg, cls, null); }

    /** ramène le lanceur devant les fenêtres (pour afficher Réglages, Véhicule…) */
    static void front(Activity a) {
        try { a.startActivity(new Intent(a, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
        catch (Exception e) { Hub.log("tableau de bord : retour KO " + e); }
    }
}
