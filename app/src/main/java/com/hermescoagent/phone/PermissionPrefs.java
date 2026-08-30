package com.hermescoagent.phone;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Per-permission user opt-in state, decoupled from the OS grant. Unchecking a
 * permission here disables the feature even if the OS grant is still in place —
 * feature code consults {@link #wants(Context, String)} before using it.
 */
public final class PermissionPrefs {

    private static final String PREFS = "hermes_perm_prefs";

    public static final String LOC = "loc";
    public static final String BG_LOC = "bg_loc";
    public static final String CAM = "cam";
    public static final String MIC = "mic";
    public static final String PHONE = "phone";
    public static final String POST_NOTIF = "post_notif";
    public static final String DND = "dnd";
    public static final String NOTIF_LISTENER = "notif_listener";
    public static final String BATT = "batt";
    public static final String OVERLAY = "overlay";
    public static final String BT = "bt";

    private PermissionPrefs() {}

    public static boolean wants(Context ctx, String key) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(key, defaultFor(key));
    }

    public static void setWants(Context ctx, String key, boolean value) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply();
    }

    private static boolean defaultFor(String key) {
        // Background location is the most invasive and requires a separate
        // Settings navigation on Android 11+, so it's off by default.
        return !BG_LOC.equals(key);
    }

    public static boolean isApplicable(String key) {
        switch (key) {
            case BG_LOC: return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
            case POST_NOTIF: return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
            case BT: return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            default: return true;
        }
    }

    /** True when the underlying OS grant is currently in place. */
    public static boolean isGranted(Context ctx, String key) {
        switch (key) {
            case LOC:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
            case BG_LOC:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        || ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
            case CAM:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || ctx.checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED;
            case MIC:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
            case PHONE:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                        == PackageManager.PERMISSION_GRANTED;
            case POST_NOTIF:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
            case DND: {
                NotificationManager nm = (NotificationManager)
                        ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null && nm.isNotificationPolicyAccessGranted();
            }
            case NOTIF_LISTENER:
                return isNotificationListenerEnabled(ctx);
            case BATT: {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
            }
            case OVERLAY:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || Settings.canDrawOverlays(ctx);
            case BT:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    /** True if the feature is both wanted by the user AND OS-granted (or n/a on this SDK). */
    public static boolean enabled(Context ctx, String key) {
        if (!isApplicable(key)) return false;
        return wants(ctx, key) && isGranted(ctx, key);
    }

    private static boolean isNotificationListenerEnabled(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
                "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) return false;
        String needle = ctx.getPackageName() + "/"
                + HermesNotificationListener.class.getName();
        for (String entry : flat.split(":")) {
            if (entry.equals(needle)) return true;
        }
        return false;
    }
}
