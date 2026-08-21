package com.hermescoagent.phone;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Reads/dismisses active notifications on behalf of the remote agent. The
 * OS binds this service once the user grants "Notification access" from
 * system settings.
 */
public class HermesNotificationListener extends NotificationListenerService {

    public static volatile HermesNotificationListener instance;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /**
     * Returns a JSON array of active notifications, newest first. Applies
     * {@link Redaction#redactText} to title/text, and blanks the text
     * fields entirely for {@link Redaction#isSensitivePackage sensitive apps}.
     */
    public JSONArray listActive(Context ctx) {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (Throwable t) {
            return new JSONArray();
        }
        if (active == null) return new JSONArray();

        StatusBarNotification[] sorted = Arrays.copyOf(active, active.length);
        Arrays.sort(sorted, new Comparator<StatusBarNotification>() {
            @Override public int compare(StatusBarNotification a, StatusBarNotification b) {
                return Long.compare(b.getPostTime(), a.getPostTime());
            }
        });

        PackageManager pm = ctx.getPackageManager();
        JSONArray out = new JSONArray();
        for (StatusBarNotification sbn : sorted) {
            try {
                JSONObject o = new JSONObject();
                String pkg = sbn.getPackageName() == null ? "" : sbn.getPackageName();
                o.put("package", pkg);
                o.put("app_label", appLabel(pm, pkg));
                o.put("time", sbn.getPostTime());
                o.put("is_ongoing", sbn.isOngoing());
                o.put("is_clearable", sbn.isClearable());
                o.put("key", sbn.getKey() == null ? "" : sbn.getKey());

                Notification n = sbn.getNotification();
                String title = "";
                String text = "";
                if (n != null && n.extras != null) {
                    Bundle e = n.extras;
                    CharSequence t = e.getCharSequence(Notification.EXTRA_TITLE);
                    if (t == null) t = e.getCharSequence(Notification.EXTRA_TITLE_BIG);
                    if (t != null) title = t.toString();
                    CharSequence x = e.getCharSequence(Notification.EXTRA_TEXT);
                    if (x == null) x = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
                    if (x != null) text = x.toString();
                }
                if (Redaction.isSensitivePackage(ctx, pkg)) {
                    o.put("title", Redaction.MASK);
                    o.put("text", Redaction.MASK);
                    o.put("redacted", true);
                } else {
                    o.put("title", Redaction.redactText(ctx, title));
                    o.put("text", Redaction.redactText(ctx, text));
                }
                out.put(o);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Cancel by exact SBN key. */
    public boolean cancelKey(String key) {
        if (key == null || key.isEmpty()) return false;
        try {
            cancelNotification(key);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cancel every notification currently posted by a package. */
    public int cancelPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        int n = 0;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return 0;
            for (StatusBarNotification sbn : active) {
                if (pkg.equals(sbn.getPackageName())) {
                    try { cancelNotification(sbn.getKey()); n++; } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return n;
    }

    private static String appLabel(PackageManager pm, String pkg) {
        if (pm == null || pkg == null || pkg.isEmpty()) return pkg;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label == null ? pkg : label.toString();
        } catch (Throwable ignored) {
            return pkg;
        }
    }
}
