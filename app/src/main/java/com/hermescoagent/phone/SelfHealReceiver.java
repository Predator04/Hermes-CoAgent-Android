package com.hermescoagent.phone;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

/**
 * Keeps the remote-control service alive without user interaction:
 *  - HEAL_CHECK fires every ~30 min. If RemoteControlService isn't running
 *    (and the user hasn't disabled auto-start), we restart it.
 *  - CHECK_UPDATE fires every ~6 h. If the auto_update pref is on and a new
 *    release is available on GitHub, we auto-download + launch the system
 *    installer.
 *
 * Both alarms are inexact so they don't require SCHEDULE_EXACT_ALARM.
 */
public class SelfHealReceiver extends BroadcastReceiver {

    public static final String ACTION_HEAL_CHECK = "com.hermescoagent.phone.HEAL_CHECK";
    public static final String ACTION_CHECK_UPDATE = "com.hermescoagent.phone.CHECK_UPDATE";
    public static final String KEY_AUTO_UPDATE = "auto_update";

    private static final long HEAL_INTERVAL_MS = 30L * 60L * 1000L;      // 30 min
    private static final long UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L; // 6 h

    private static final int REQ_HEAL = 9001;
    private static final int REQ_UPDATE = 9002;

    public static boolean isAutoUpdateEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(RemoteControlService.PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_AUTO_UPDATE, true);
    }

    public static void setAutoUpdateEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(RemoteControlService.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply();
    }

    /** Schedule both self-heal and auto-update alarms. Safe to call repeatedly. */
    public static void scheduleAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = SystemClock.elapsedRealtime();
        try {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    now + HEAL_INTERVAL_MS, HEAL_INTERVAL_MS,
                    buildPI(ctx, ACTION_HEAL_CHECK, REQ_HEAL));
        } catch (Throwable ignored) {}
        try {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    now + UPDATE_INTERVAL_MS, UPDATE_INTERVAL_MS,
                    buildPI(ctx, ACTION_CHECK_UPDATE, REQ_UPDATE));
        } catch (Throwable ignored) {}
    }

    private static PendingIntent buildPI(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, SelfHealReceiver.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, req, i, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_HEAL_CHECK.equals(action)) {
            handleHeal(context);
        } else if (ACTION_CHECK_UPDATE.equals(action)) {
            handleUpdateCheck(context);
        }
    }

    private void handleHeal(Context ctx) {
        if (RemoteControlService.isRunning) return;
        if (!BootReceiver.isAutoStartEnabled(ctx)) return;
        Intent svc = new Intent(ctx, RemoteControlService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } catch (Throwable ignored) {}
    }

    private void handleUpdateCheck(final Context ctx) {
        if (!isAutoUpdateEnabled(ctx)) return;
        final Context app = ctx.getApplicationContext();
        UpdateChecker.checkForUpdate(app, false, (available, code, releaseName, apkUrl, notes) -> {
            if (!available || apkUrl == null || apkUrl.isEmpty()) return;
            if (!isAutoUpdateEnabled(app)) return;
            UpdateChecker.downloadAndInstall(app, apkUrl);
        });
    }
}
