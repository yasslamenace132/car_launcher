package fr.ibiza.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** v2.2 : mise à jour depuis la page « Dernière version » du dépôt GitHub (public).
 *  version.json donne le numéro de la dernière version ; si elle est plus récente, l'APK est téléchargé
 *  puis installé avec PackageInstaller : Android affiche simplement « Installer cette mise à jour ? ».
 *  Événement « update » vers l'interface : {state, local, remote, name, progress, msg}
 *  state : checking | none | available | downloading | installing | done | error | permission */
public final class Updater extends BroadcastReceiver {
    static final String DEFAULT_REPO = "yasslamenace132/car_launcher";
    private static final String ACTION = "fr.ibiza.launcher.UPDATE_STATUS";
    private static volatile boolean busy = false;

    private static String base(String repo) {
        if (repo == null || !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) repo = DEFAULT_REPO;
        return "https://github.com/" + repo + "/releases/download/derniere-version/";
    }

    @SuppressWarnings("deprecation")
    static int localCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    private static void emit(String state, int local, int remote, String name, int progress, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state); o.put("local", local); o.put("remote", remote);
            o.put("name", name == null ? "" : name); o.put("progress", progress); o.put("msg", msg == null ? "" : msg);
            Hub.emit("update", o.toString());
        } catch (Exception ignored) {}
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000); h.setReadTimeout(20000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "CarLauncher-Updater");
        h.setRequestProperty("Cache-Control", "no-cache");
        return h;
    }

    /** vérifie s'il existe une version plus récente (en arrière-plan) */
    static void check(final Context ctx, final String repo) {
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() { public void run() {
            int local = localCode(c);
            emit("checking", local, 0, null, 0, null);
            try {
                HttpURLConnection h = open(base(repo) + "version.json?t=" + System.currentTimeMillis());
                if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
                InputStream in = h.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                JSONObject v = new JSONObject(bo.toString("UTF-8"));
                int remote = v.optInt("versionCode", 0);
                emit(remote > local ? "available" : "none", local, remote, v.optString("versionName", ""), 0, null);
            } catch (Exception e) {
                Hub.log("Mise à jour : vérification KO " + e);
                emit("error", local, 0, null, 0, "Pas de connexion à Internet ?");
            }
        } }, "maj-check").start();
    }

    /** télécharge puis installe la dernière version */
    static void install(final Context ctx, final String repo) {
        final Context c = ctx.getApplicationContext();
        if (busy) return;
        if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
            emit("permission", localCode(c), 0, null, 0, "Autorise l'installation d'applis pour Car Launcher, puis réessaie.");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception e) { Hub.log("Mise à jour : réglage sources inconnues KO " + e); }
            return;
        }
        busy = true;
        new Thread(new Runnable() { public void run() {
            int local = localCode(c);
            PackageInstaller.Session session = null;
            try {
                HttpURLConnection h = open(base(repo) + "car-launcher.apk?t=" + System.currentTimeMillis());
                if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
                long total = h.getContentLength();
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(c.getPackageName());
                if (total > 0) sp.setSize(total);
                int id = pi.createSession(sp);
                session = pi.openSession(id);
                InputStream in = h.getInputStream();
                OutputStream out = session.openWrite("car-launcher.apk", 0, total > 0 ? total : -1);
                byte[] b = new byte[65536]; int n; long done = 0; int lastPct = -1;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n); done += n;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    if (pct != lastPct && pct % 5 == 0) { lastPct = pct; emit("downloading", local, 0, null, pct, null); }
                }
                session.fsync(out); out.close(); in.close();
                emit("installing", local, 0, null, 100, null);
                Intent cb = new Intent(c, Updater.class).setAction(ACTION);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent p = PendingIntent.getBroadcast(c, 9, cb, flags);
                session.commit(p.getIntentSender());
                session.close(); session = null;
            } catch (Exception e) {
                Hub.log("Mise à jour : téléchargement KO " + e);
                if (session != null) { try { session.abandon(); } catch (Exception ignored) {} }
                emit("error", local, 0, null, 0, "Téléchargement impossible. Vérifie la connexion et réessaie.");
            } finally { busy = false; }
        } }, "maj-install").start();
    }

    /** retour de PackageInstaller : demande de confirmation, succès ou échec */
    @Override public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { c.startActivity(confirm); } catch (Exception e) { Hub.log("Mise à jour : confirmation KO " + e); } }
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            emit("done", localCode(c), 0, null, 100, null);
        } else {
            String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Hub.log("Mise à jour : échec " + st + " " + m);
            emit("error", localCode(c), 0, null, 0, st == PackageInstaller.STATUS_FAILURE_ABORTED ? "Installation annulée." : "Installation refusée par Android.");
        }
    }

    static boolean installAllowed(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }
}
