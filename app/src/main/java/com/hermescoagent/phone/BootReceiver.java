package com.hermescoagent.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Starts the remote-control service after the device boots, so the phone is
 * drivable again without the user having to open the app. Controlled by the
 * "Auto-start on boot" toggle (default ON).
 *
 * BOOT_COMPLETED fires after the first unlock, which is exactly when the
 * credential-encrypted prefs (the toggle) are readable. QUICKBOOT_POWERON is
 * the OEM-equivalent on some HTC/ASUS-style firmwares.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String KEY_AUTO_START = "auto_start_on_boot";

    public static boolean isAutoStartEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(RemoteControlService.PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_AUTO_START, true);
    }

    public static void setAutoStartEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(RemoteControlService.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_START, enabled).apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!boot) return;
        if (!isAutoStartEnabled(context)) return;
        if (RemoteControlService.isRunning) return;

        Intent i = new Intent(context, RemoteControlService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }
}
