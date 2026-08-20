package com.hermescoagent.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Accessibility service — the input-injection core for remote control.
 * Tap, swipe, type, and global actions (back/home/recents/notifications).
 */
public class HermesAccessibilityService extends AccessibilityService {

    public static HermesAccessibilityService instance;

    // StrokeDescription rejects durations <=0 and (implementation-defined) very
    // large values. Clamp to a sensible interactive range.
    private static final long MIN_SWIPE_MS = 50L;
    private static final long MAX_SWIPE_MS = 2000L;

    // Runaway-guard for tree dumps. 400 nodes covers dense UIs; 24 depth exceeds
    // any real Android view hierarchy but stops pathological cases.
    private static final int MAX_DUMP_NODES = 400;
    private static final int MAX_DUMP_DEPTH = 24;
    private static final int MAX_FIND_MATCHES = 20;
    private static final long DUMP_TIMEOUT_MS = 3000L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public boolean tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 80)).build();
        return dispatchGesture(g, null, null);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
        long duration = Math.max(MIN_SWIPE_MS, Math.min(MAX_SWIPE_MS, durationMs));
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, duration)).build();
        return dispatchGesture(g, null, null);
    }

    public boolean globalAction(String code) {
        if (code == null) return false;
        switch (code) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            case "quick_settings": return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            case "power": return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            default: return false;
        }
    }

    public boolean type(String text) {
        if (text == null) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo node = findFocusedEditable(root);
        if (node == null) node = findFirstEditable(root);
        if (node == null) return false;
        // ACTION_SET_TEXT silently no-ops on unfocused nodes on some OEM ROMs.
        if (!node.isFocused()) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isFocused()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findFocusedEditable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findFirstEditable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /**
     * Walk the active-window accessibility tree and produce a flat JSONArray of
     * node objects (id, text, desc, cls, clickable, scrollable, bounds). The
     * root is always id 0. Marshals the accessibility calls to the main thread.
     *
     * @return array of nodes (possibly empty when no root is available), or
     *         null when the main-thread call times out.
     */
    public JSONArray dumpNodes() {
        final JSONArray[] result = { new JSONArray() };
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return;
                JSONArray arr = new JSONArray();
                walkNode(root, 0, arr);
                result[0] = arr;
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(DUMP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }

    private void walkNode(AccessibilityNodeInfo n, int depth, JSONArray out) throws JSONException {
        if (n == null || out.length() >= MAX_DUMP_NODES) return;
        JSONObject obj = new JSONObject();
        obj.put("id", out.length());
        CharSequence text = n.getText();
        obj.put("text", text == null ? "" : text.toString());
        CharSequence desc = n.getContentDescription();
        obj.put("desc", desc == null ? "" : desc.toString());
        CharSequence cls = n.getClassName();
        obj.put("cls", cls == null ? "" : cls.toString());
        obj.put("clickable", n.isClickable());
        obj.put("scrollable", n.isScrollable());
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        JSONArray bounds = new JSONArray();
        bounds.put(r.left); bounds.put(r.top); bounds.put(r.right); bounds.put(r.bottom);
        obj.put("bounds", bounds);
        out.put(obj);
        if (depth >= MAX_DUMP_DEPTH) return;
        int cc = n.getChildCount();
        for (int i = 0; i < cc; i++) {
            if (out.length() >= MAX_DUMP_NODES) return;
            walkNode(n.getChild(i), depth + 1, out);
        }
    }

    /**
     * Substring search (case-insensitive) over dumped node text + desc. Returns
     * up to MAX_FIND_MATCHES matches, or null if the tree dump failed.
     */
    public JSONArray findNodes(String query) throws JSONException {
        JSONArray all = dumpNodes();
        if (all == null) return null;
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length() && out.length() < MAX_FIND_MATCHES; i++) {
            JSONObject n = all.optJSONObject(i);
            if (n == null) continue;
            if (matches(n, q)) out.put(n);
        }
        return out;
    }

    /**
     * Find the best matching node for the query and dispatch a tap on its
     * center. Returns a JSON response ready to send back to the client.
     */
    public JSONObject findAndTap(String query) throws JSONException {
        JSONObject resp = new JSONObject();
        JSONArray all = dumpNodes();
        if (all == null) {
            resp.put("ok", false);
            resp.put("error", "dump timeout");
            return resp;
        }
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        JSONObject best = null;
        JSONObject firstMatch = null;
        long bestArea = Long.MAX_VALUE;
        boolean bestClickable = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject n = all.optJSONObject(i);
            if (n == null || !matches(n, q)) continue;
            if (firstMatch == null) firstMatch = n;
            JSONArray b = n.optJSONArray("bounds");
            if (b == null || b.length() < 4) continue;
            long area = (long) (b.optInt(2) - b.optInt(0)) * (long) (b.optInt(3) - b.optInt(1));
            if (area <= 0) continue;
            boolean clickable = n.optBoolean("clickable", false);
            boolean better;
            if (best == null) better = true;
            else if (clickable && !bestClickable) better = true;
            else if (clickable == bestClickable && area < bestArea) better = true;
            else better = false;
            if (better) {
                best = n;
                bestArea = area;
                bestClickable = clickable;
            }
        }
        if (best == null) best = firstMatch;
        if (best == null) {
            resp.put("ok", false);
            resp.put("error", "no match");
            return resp;
        }
        JSONArray b = best.getJSONArray("bounds");
        int cx = (b.getInt(0) + b.getInt(2)) / 2;
        int cy = (b.getInt(1) + b.getInt(3)) / 2;
        boolean tapped = tap(cx, cy);
        JSONObject info = new JSONObject();
        info.put("text", best.optString("text", ""));
        info.put("desc", best.optString("desc", ""));
        info.put("bounds", b);
        info.put("cx", cx);
        info.put("cy", cy);
        resp.put("ok", tapped);
        resp.put("tapped", info);
        return resp;
    }

    private static boolean matches(JSONObject node, String lowerQuery) {
        String text = node.optString("text", "").toLowerCase(Locale.ROOT);
        if (text.contains(lowerQuery)) return true;
        String desc = node.optString("desc", "").toLowerCase(Locale.ROOT);
        return desc.contains(lowerQuery);
    }
}
