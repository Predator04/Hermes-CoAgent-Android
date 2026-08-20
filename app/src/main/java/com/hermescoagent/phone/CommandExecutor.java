package com.hermescoagent.phone;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Shared command dispatcher. Called by both the LAN HTTP server
 * (RemoteControlService) and the outbound RemoteRelayClient. The input
 * is a JSON string {"action":"...", ...}; the output is a JSON string.
 */
public final class CommandExecutor {

    private CommandExecutor() {}

    public static String execute(Context ctx, String json) {
        try {
            JSONObject req = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            String action = req.optString("action");
            JSONObject resp = new JSONObject();
            resp.put("ok", true);

            switch (action) {
                case "ping":
                    resp.put("pong", true);
                    break;
                case "tap": {
                    HermesAccessibilityService s = HermesAccessibilityService.instance;
                    if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                    else resp.put("ok", s.tap(req.getInt("x"), req.getInt("y")));
                    break;
                }
                case "swipe": {
                    HermesAccessibilityService s = HermesAccessibilityService.instance;
                    if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                    else resp.put("ok", s.swipe(req.getInt("x1"), req.getInt("y1"),
                            req.getInt("x2"), req.getInt("y2"), req.optLong("duration", 300)));
                    break;
                }
                case "type": {
                    HermesAccessibilityService s = HermesAccessibilityService.instance;
                    if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                    else resp.put("ok", s.type(req.optString("text")));
                    break;
                }
                case "key": {
                    HermesAccessibilityService s = HermesAccessibilityService.instance;
                    if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                    else resp.put("ok", s.globalAction(req.optString("code")));
                    break;
                }
                case "launch": {
                    Intent i = ctx.getPackageManager().getLaunchIntentForPackage(req.optString("package"));
                    if (i == null) { resp.put("ok", false); resp.put("error", "package not found"); }
                    else { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); }
                    break;
                }
                case "battery": {
                    BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                    if (bm == null) { resp.put("ok", false); resp.put("error", "no battery service"); }
                    else resp.put("level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                    break;
                }
                case "info": {
                    resp.put("model", Build.MODEL);
                    resp.put("manufacturer", Build.MANUFACTURER);
                    resp.put("android", Build.VERSION.RELEASE);
                    resp.put("sdk", Build.VERSION.SDK_INT);
                    break;
                }
                case "screen_size": {
                    DisplayMetrics dm = readDisplayMetrics(ctx);
                    resp.put("width", dm.widthPixels);
                    resp.put("height", dm.heightPixels);
                    resp.put("densityDpi", dm.densityDpi);
                    break;
                }
                case "list_apps": {
                    resp.put("apps", listLaunchableApps(ctx));
                    break;
                }
                case "dump": {
                    HermesAccessibilityService s = HermesAccessibilityService.instance;
                    if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                    else {
                        JSONArray nodes = s.dumpNodes();
                        if (nodes == null) { resp.put("ok", false); resp.put("error", "dump timeout"); }
                        else {
                            resp.put("nodes", nodes);
                            resp.put("count", nodes.length());
                            resp.put("package", s.getForegroundPackage());
                        }
                    }
                    break;
                }
                case "screenshot": {
                    HermesAccessibilityService s = HermesAccessibilityService.instance;
                    if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                    else return s.takeScreenshotToJson(req).toString();
                    break;
                }
                case "find": {
                    HermesAccessibilityService s = HermesAccessibilityService.instance;
                    if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                    else {
                        JSONArray matches = s.findNodes(req.optString("query"));
                        if (matches == null) { resp.put("ok", false); resp.put("error", "dump timeout"); }
                        else { resp.put("matches", matches); resp.put("count", matches.length()); }
                    }
                    break;
                }
                case "find_tap": {
                    HermesAccessibilityService s = HermesAccessibilityService.instance;
                    if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                    else return s.findAndTap(req.optString("query")).toString();
                    break;
                }
                default:
                    resp.put("ok", false);
                    resp.put("error", "unknown action: " + action);
            }
            return resp.toString();
        } catch (Exception e) {
            try { return new JSONObject().put("ok", false).put("error", String.valueOf(e)).toString(); }
            catch (Exception ex) { return "{\"ok\":false}"; }
        }
    }

    private static DisplayMetrics readDisplayMetrics(Context ctx) {
        final DisplayMetrics dm = new DisplayMetrics();
        final Object lock = new Object();
        final boolean[] done = { false };
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && wm.getDefaultDisplay() != null) {
                    wm.getDefaultDisplay().getRealMetrics(dm);
                } else {
                    DisplayMetrics r = ctx.getResources().getDisplayMetrics();
                    dm.setTo(r);
                }
            } catch (Exception e) {
                DisplayMetrics r = ctx.getResources().getDisplayMetrics();
                dm.setTo(r);
            } finally {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 2000;
            while (!done[0]) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try { lock.wait(left); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (!done[0]) {
            DisplayMetrics r = ctx.getResources().getDisplayMetrics();
            dm.setTo(r);
        }
        return dm;
    }

    private static JSONArray listLaunchableApps(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
        List<ResolveInfo> list = new ArrayList<>(resolved);
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                CharSequence la = a.loadLabel(pm);
                CharSequence lb = b.loadLabel(pm);
                String sa = la == null ? "" : la.toString();
                String sb = lb == null ? "" : lb.toString();
                return sa.compareToIgnoreCase(sb);
            }
        });
        JSONArray out = new JSONArray();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            JSONObject o = new JSONObject();
            o.put("package", ri.activityInfo.packageName);
            CharSequence label = ri.loadLabel(pm);
            o.put("label", label == null ? ri.activityInfo.packageName : label.toString());
            out.put(o);
        }
        return out;
    }
}
