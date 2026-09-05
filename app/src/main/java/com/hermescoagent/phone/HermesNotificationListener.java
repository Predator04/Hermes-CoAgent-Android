package com.hermescoagent.phone;

import android.app.Notification;
import android.app.PendingIntent;
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
                if (n != null && n.actions != null && n.actions.length > 0) {
                    JSONArray actions = new JSONArray();
                    for (int i = 0; i < n.actions.length; i++) {
                        Notification.Action action = n.actions[i];
                        if (action == null) continue;
                        JSONObject a = new JSONObject();
                        a.put("index", i);
                        a.put("title", Redaction.redactText(ctx, String.valueOf(action.title)));
                        actions.put(a);
                    }
                    if (actions.length() > 0) o.put("actions", actions);
                }
                out.put(o);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /**
     * Fire a notification's contentIntent (opens it) or one of its action
     * button intents. actionIndex < 0 means "tap the notification body".
     */
    public JSONObject tap(Context ctx, String key, String pkg, int actionIndex) {
        JSONObject resp = new JSONObject();
        try {
            StatusBarNotification[] active;
            try {
                active = getActiveNotifications();
            } catch (Throwable t) {
                active = null;
            }
            if (active == null || active.length == 0) {
                resp.put("ok", false);
                resp.put("error", "no active notifications");
                return resp;
            }

            StatusBarNotification target = null;
            if (key != null && !key.isEmpty()) {
                for (StatusBarNotification sbn : active) {
                    if (sbn != null && key.equals(sbn.getKey())) { target = sbn; break; }
                }
            } else if (pkg != null && !pkg.isEmpty()) {
                for (StatusBarNotification sbn : active) {
                    if (sbn != null && pkg.equals(sbn.getPackageName())) { target = sbn; break; }
                }
            }
            if (target == null) {
                resp.put("ok", false);
                resp.put("error", "notification not found");
                return resp;
            }

            Notification n = target.getNotification();
            if (n == null) {
                resp.put("ok", false);
                resp.put("error", "notification has no payload");
                return resp;
            }

            PendingIntent pi;
            String what;
            if (actionIndex >= 0) {
                if (n.actions == null || actionIndex >= n.actions.length) {
                    resp.put("ok", false);
                    resp.put("error", "action index out of range");
                    return resp;
                }
                pi = n.actions[actionIndex].actionIntent;
                if (pi == null) {
                    resp.put("ok", false);
                    resp.put("error", "action intent missing");
                    return resp;
                }
                what = "action";
            } else {
                pi = n.contentIntent;
                if (pi == null) {
                    resp.put("ok", false);
                    resp.put("error", "no content intent");
                    return resp;
                }
                what = "content";
            }

            try {
                pi.send();
                resp.put("ok", true);
                resp.put("package", target.getPackageName());
                resp.put("key", target.getKey());
                resp.put("tapped", what);
                resp.put("action_index", actionIndex);
            } catch (Throwable t) {
                resp.put("ok", false);
                resp.put("error", String.valueOf(t));
            }
        } catch (Throwable t) {
            try {
                resp.put("ok", false);
                resp.put("error", String.valueOf(t));
            } catch (Throwable ignored) {}
        }
        return resp;
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
