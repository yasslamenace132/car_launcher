package fr.ibiza.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** v2.1 : compteur de trajet par le GPS, qui continue quand Waze ou Spotify est au premier plan.
 *  Service au premier plan (Android coupe le GPS des applis en arrière-plan sans cela) : une petite notification
 *  « Trajet en cours » reste affichée. Totaux enregistrés dans les préférences « trip », envoyés à l'interface
 *  par l'événement « trip » : {km, sec, vmax, total, day, kmh}. */
public final class TripService extends Service implements LocationListener {
    static TripService self;
    private static final String CHANNEL = "trajet";
    private static final int NOTIF_ID = 71;
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);

    private SharedPreferences sp;
    private double km, total, vmax, sec;
    private String day;
    private long last = 0, lastSave = 0;
    private int kmh = 0;

    /** démarre le service si la localisation est autorisée (appelé par le lanceur quand il s'affiche) */
    static void start(Context c) {
        if (self != null) return;
        if (Build.VERSION.SDK_INT >= 23 && c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Intent i = new Intent(c, TripService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { Hub.log("Trajet : démarrage KO " + e); }
    }

    /** état courant, même si le service ne tourne pas (lu dans les préférences) */
    static String state(Context c) {
        if (self != null) return self.json();
        SharedPreferences p = c.getSharedPreferences("trip", MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject();
            boolean today = today().equals(p.getString("day", ""));
            o.put("km", today ? p.getFloat("km", 0) : 0); o.put("sec", today ? p.getFloat("sec", 0) : 0);
            o.put("vmax", today ? p.getFloat("vmax", 0) : 0); o.put("total", p.getFloat("total", 0));
            o.put("day", today()); o.put("kmh", 0); o.put("running", false);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** remise à zéro du trajet du jour (le cumul « total » sert au kilométrage et n'est jamais remis à zéro) */
    static void reset(Context c) {
        if (self != null) { self.km = 0; self.sec = 0; self.vmax = 0; self.save(); Hub.emit("trip", self.json()); return; }
        c.getSharedPreferences("trip", MODE_PRIVATE).edit().putFloat("km", 0).putFloat("sec", 0).putFloat("vmax", 0).putString("day", today()).apply();
        Hub.emit("trip", state(c));
    }

    private static String today() { synchronized (DAY) { return DAY.format(new Date()); } }

    @Override public void onCreate() {
        super.onCreate();
        self = this;
        sp = getSharedPreferences("trip", MODE_PRIVATE);
        km = sp.getFloat("km", 0); sec = sp.getFloat("sec", 0); vmax = sp.getFloat("vmax", 0);
        total = sp.getFloat("total", 0); day = sp.getString("day", today());
        rollDay();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIF_ID, notif());
        } catch (Exception e) { Hub.log("Trajet : premier plan KO " + e); }
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            Hub.log("Trajet : GPS suivi en arrière-plan");
        } catch (SecurityException e) { Hub.log("Trajet : localisation refusée"); stopSelf(); }
        catch (Exception e) { Hub.log("Trajet : GPS KO " + e); stopSelf(); }
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        try { ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(this); } catch (Exception ignored) {}
        save();
        self = null;
        super.onDestroy();
    }

    private Notification notif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Trajet en cours", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_MIN);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Car Launcher")
                .setContentText("Trajet en cours : les kilomètres sont comptés")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, pf));
        return b.build();
    }

    private void rollDay() {
        String t = today();
        if (!t.equals(day)) { km = 0; sec = 0; vmax = 0; day = t; }
    }

    private void save() {
        lastSave = SystemClock.elapsedRealtime();
        if (sp == null) return;
        sp.edit().putFloat("km", (float) km).putFloat("sec", (float) sec).putFloat("vmax", (float) vmax)
                .putFloat("total", (float) total).putString("day", day).apply();
    }

    String json() {
        try {
            JSONObject o = new JSONObject();
            o.put("km", Math.round(km * 1000) / 1000.0); o.put("sec", Math.round(sec)); o.put("vmax", Math.round(vmax));
            o.put("total", Math.round(total * 1000) / 1000.0); o.put("day", day); o.put("kmh", kmh); o.put("running", true);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @Override public void onLocationChanged(Location l) {
        long now = SystemClock.elapsedRealtime();
        float v = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
        kmh = Math.round(v);
        rollDay();
        if (last > 0 && v >= 3) {                       // en dessous de 3 km/h : arrêt (le GPS « bouge » à l'arrêt)
            double dt = Math.min(5, (now - last) / 1000.0), dk = v * dt / 3600.0;
            km += dk; total += dk; sec += dt; if (v > vmax) vmax = v;
        }
        last = now;
        if (now - lastSave > 30000) save();
        Hub.emit("trip", json());
    }
    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}
}
