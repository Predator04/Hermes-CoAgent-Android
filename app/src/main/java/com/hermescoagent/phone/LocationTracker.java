package com.hermescoagent.phone;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodic GPS breadcrumb logger. When enabled, requests a fused location
 * every 5 minutes and appends {timestamp, lat, lng, accuracy} to a persisted
 * JSON list in SharedPreferences (capped at 500 entries). Only runs while
 * the foreground service is alive.
 */
public final class LocationTracker {

    private LocationTracker() {}

    private static final String PREFS = "hermes_tracking";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_ENTRIES = 500;
    private static final long INTERVAL_MS = 5L * 60L * 1000L;
    private static final long FIX_TIMEOUT_MS = 15_000L;

    private static final Object LOCK = new Object();
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> task;
    // Shared executor for the getCurrentLocation callback dispatch. Creating a
    // fresh single-thread executor per poll leaked one non-daemon thread every
    // 5 minutes forever.
    private static ExecutorService callbackExec;
    private static ExecutorService callbackExec() {
        synchronized (LOCK) {
            if (callbackExec == null) {
                callbackExec = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "hermes-loc-cb");
                    t.setDaemon(true);
                    return t;
                });
            }
            return callbackExec;
        }
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    /** Set on/off flag AND start/stop the scheduler. */
    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply();
        if (on) start(ctx);
        else stop();
    }

    /** Called from service startup to auto-resume if the pref is on. */
    public static void resumeIfEnabled(Context ctx) {
        if (isEnabled(ctx)) start(ctx);
    }

    private static void start(Context ctx) {
        synchronized (LOCK) {
            if (task != null && !task.isCancelled()) return;
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "hermes-tracking");
                    t.setDaemon(true);
                    return t;
                });
            }
            final Context app = ctx.getApplicationContext();
            task = scheduler.scheduleWithFixedDelay(() -> {
                try { pollAndStore(app); } catch (Throwable ignored) {}
            }, 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void stop() {
        synchronized (LOCK) {
            if (task != null) {
                try { task.cancel(false); } catch (Throwable ignored) {}
                task = null;
            }
        }
    }

    private static void pollAndStore(Context ctx) {
        // Background GPS breadcrumbs need BG_LOC on Android 10+; both toggles
        // (LOC and BG_LOC) must be checked by the user for the tracker to run.
        if (!PermissionPrefs.wants(ctx, PermissionPrefs.LOC)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !PermissionPrefs.wants(ctx, PermissionPrefs.BG_LOC)) {
            return;
        }
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        Location loc = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            loc = currentLocationBlocking(lm);
        }
        if (loc == null) loc = lastKnown(lm, LocationManager.FUSED_PROVIDER);
        if (loc == null) loc = lastKnown(lm, LocationManager.GPS_PROVIDER);
        if (loc == null) loc = lastKnown(lm, LocationManager.NETWORK_PROVIDER);
        if (loc == null) return;
        append(ctx, loc);
    }

    private static Location lastKnown(LocationManager lm, String provider) {
        try { return lm.getLastKnownLocation(provider); }
        catch (SecurityException | IllegalArgumentException e) { return null; }
    }

    @SuppressWarnings("MissingPermission")
    private static Location currentLocationBlocking(LocationManager lm) {
        final Location[] out = { null };
        final Object lk = new Object();
        final boolean[] done = { false };
        try {
            String provider = LocationManager.FUSED_PROVIDER;
            if (!lm.isProviderEnabled(provider)) {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
                else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
                else return null;
            }
            CancellationSignal cs = new CancellationSignal();
            lm.getCurrentLocation(provider, cs,
                    callbackExec(),
                    location -> {
                        synchronized (lk) { out[0] = location; done[0] = true; lk.notifyAll(); }
                    });
            synchronized (lk) {
                long deadline = System.currentTimeMillis() + FIX_TIMEOUT_MS;
                while (!done[0]) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) { cs.cancel(); break; }
                    try { lk.wait(left); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); cs.cancel(); break; }
                }
            }
        } catch (Throwable ignored) {}
        return out[0];
    }

    private static void append(Context ctx, Location loc) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr;
            try { arr = new JSONArray(sp.getString(KEY_HISTORY, "[]")); }
            catch (Exception e) { arr = new JSONArray(); }
            JSONObject entry = new JSONObject();
            entry.put("timestamp", loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis());
            entry.put("lat", loc.getLatitude());
            entry.put("lng", loc.getLongitude());
            entry.put("accuracy", loc.getAccuracy());
            arr.put(entry);
            // Trim oldest to cap.
            while (arr.length() > MAX_ENTRIES) {
                JSONArray trimmed = new JSONArray();
                for (int i = arr.length() - MAX_ENTRIES; i < arr.length(); i++) {
                    trimmed.put(arr.get(i));
                }
                arr = trimmed;
            }
            sp.edit().putString(KEY_HISTORY, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** Returns breadcrumbs newest-first. */
    public static JSONArray history(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray raw = new JSONArray(sp.getString(KEY_HISTORY, "[]"));
            JSONArray out = new JSONArray();
            for (int i = raw.length() - 1; i >= 0; i--) out.put(raw.get(i));
            return out;
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
