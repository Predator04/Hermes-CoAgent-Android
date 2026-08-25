package com.hermescoagent.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Accessibility service — the input-injection core for remote control.
 * Tap, swipe, type, and global actions (back/home/recents/notifications).
 */
public class HermesAccessibilityService extends AccessibilityService {

    public static HermesAccessibilityService instance;

    // Most-recent foreground state, updated from TYPE_WINDOW_STATE_CHANGED events
    // so `foreground` can answer instantly without walking the accessibility tree.
    private volatile String lastForegroundPackage = "";
    private volatile String lastForegroundActivity = "";
    private volatile String lastForegroundTitle = "";

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
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            try {
                CharSequence pkg = event.getPackageName();
                lastForegroundPackage = pkg == null ? "" : pkg.toString();
            } catch (Throwable ignored) {}
            try {
                CharSequence cls = event.getClassName();
                lastForegroundActivity = cls == null ? "" : cls.toString();
            } catch (Throwable ignored) {}
            String title = "";
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    for (AccessibilityWindowInfo w : getWindows()) {
                        if (w == null) continue;
                        if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                            CharSequence t = w.getTitle();
                            if (t != null) { title = t.toString(); break; }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            lastForegroundTitle = title;
        } catch (Throwable ignored) {}
    }

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

    public boolean longPress(int x, int y, long durationMs) {
        long duration = Math.max(MIN_SWIPE_MS, Math.min(MAX_SWIPE_MS, durationMs));
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, duration)).build();
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
            case "lock": return lockScreen();
            default: return false;
        }
    }

    /** Lock the screen via accessibility. Requires API 28+. */
    public boolean lockScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
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
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;
        // Fallback: set clipboard + paste on focused node.
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("hermes", text));
                return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Scroll the first scrollable node in the tree. dir=true → forward/down. */
    public boolean scroll(boolean forward) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo n = findFirstScrollable(root);
        if (n == null) return false;
        int action = forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        return n.performAction(action);
    }

    private AccessibilityNodeInfo findFirstScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findFirstScrollable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
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

    /** Package name of the app in the active window, or "" if unknown. */
    public String getForegroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "";
            CharSequence pkg = root.getPackageName();
            return pkg == null ? "" : pkg.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public String getForegroundActivity() {
        return lastForegroundActivity;
    }

    public String getForegroundTitle() {
        return lastForegroundTitle;
    }

    // Screenshot rate limit is ~1/sec on many OEM ROMs, but the callback usually
    // completes within a few hundred ms. 5s covers cold-start jank.
    private static final long SCREENSHOT_TIMEOUT_MS = 5000L;
    // Defaults tuned for vision-round-trip latency: ~50% resize, JPEG q60, and
    // a 1080px longest-edge cap keep frames small (~15–30KB) without hurting
    // text legibility. Callers can override each via opts on takeScreenshotToJson.
    private static final float DEFAULT_SCREENSHOT_SCALE = 0.5f;
    private static final int DEFAULT_JPEG_QUALITY = 60;
    private static final int DEFAULT_MAX_EDGE = 1080;

    // Shared executor for takeScreenshot callbacks. Previously we created a new
    // single-thread executor per call — each leaked a live thread forever.
    private static ExecutorService screenshotCallbackExec;
    private static synchronized ExecutorService screenshotCallbackExec() {
        if (screenshotCallbackExec == null) {
            screenshotCallbackExec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hermes-shot-cb");
                t.setDaemon(true);
                return t;
            });
        }
        return screenshotCallbackExec;
    }

    /**
     * Capture the current screen using AccessibilityService.takeScreenshot (API 30+).
     * Saves a JPEG to the app cache dir and returns { ok, path, width, height,
     * orig_width, orig_height, bytes, base64 (optional) }.
     *
     * Options in {@code opts}:
     *   scale         (double, default 0.5)   — resize factor before compression
     *   quality       (int,    default 60)    — JPEG quality 1..100
     *   max_edge      (int,    default 1080)  — cap for the longest edge (0 = no cap)
     *   include_base64(boolean,default true)  — embed base64 JPEG in the response
     *
     * Pass {@code max_edge=0} (and typically {@code scale=1.0}) to bypass the
     * optimized-frame path and get a full-resolution capture.
     */
    public JSONObject takeScreenshotToJson(JSONObject opts) throws JSONException {
        JSONObject resp = new JSONObject();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            resp.put("ok", false);
            resp.put("error", "screenshot requires API 30+");
            return resp;
        }

        final float scale = clampScale((float) opts.optDouble("scale", DEFAULT_SCREENSHOT_SCALE));
        final int quality = Math.max(1, Math.min(100, opts.optInt("quality", DEFAULT_JPEG_QUALITY)));
        final int maxEdge = Math.max(0, opts.optInt("max_edge", DEFAULT_MAX_EDGE));
        final boolean includeBase64 = opts.optBoolean("include_base64", true);

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmapRef = new AtomicReference<>();
        final AtomicInteger errRef = new AtomicInteger(0);
        final AtomicReference<String> errMsgRef = new AtomicReference<>("");

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY,
                    screenshotCallbackExec(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            HardwareBuffer hb = null;
                            Bitmap wrapped = null;
                            try {
                                hb = result.getHardwareBuffer();
                                ColorSpace cs = result.getColorSpace();
                                wrapped = Bitmap.wrapHardwareBuffer(hb, cs);
                                if (wrapped == null) {
                                    errMsgRef.set("wrapHardwareBuffer returned null");
                                    errRef.set(-2);
                                } else {
                                    // Copy to a software bitmap so we can compress.
                                    bitmapRef.set(wrapped.copy(Bitmap.Config.ARGB_8888, false));
                                }
                            } catch (Throwable t) {
                                errMsgRef.set(String.valueOf(t));
                                errRef.set(-2);
                            } finally {
                                if (wrapped != null) wrapped.recycle();
                                if (hb != null) hb.close();
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            errRef.set(errorCode);
                            errMsgRef.set("takeScreenshot error " + errorCode);
                            latch.countDown();
                        }
                    });
        } catch (Throwable t) {
            resp.put("ok", false);
            resp.put("error", "takeScreenshot threw: " + t);
            return resp;
        }

        try {
            if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                resp.put("ok", false);
                resp.put("error", "screenshot timeout");
                return resp;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            resp.put("ok", false);
            resp.put("error", "interrupted");
            return resp;
        }

        Bitmap bmp = bitmapRef.get();
        if (bmp == null) {
            resp.put("ok", false);
            resp.put("error", errMsgRef.get().isEmpty() ? "no bitmap" : errMsgRef.get());
            return resp;
        }

        int origW = bmp.getWidth();
        int origH = bmp.getHeight();

        int targetW = origW;
        int targetH = origH;
        if (scale > 0f && scale < 0.999f) {
            targetW = Math.max(1, Math.round(origW * scale));
            targetH = Math.max(1, Math.round(origH * scale));
        }
        if (maxEdge > 0) {
            int longest = Math.max(targetW, targetH);
            if (longest > maxEdge) {
                float cap = (float) maxEdge / (float) longest;
                targetW = Math.max(1, Math.round(targetW * cap));
                targetH = Math.max(1, Math.round(targetH * cap));
            }
        }

        Bitmap toEncode = bmp;
        if (targetW != origW || targetH != origH) {
            toEncode = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            toEncode.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            byte[] jpeg = baos.toByteArray();

            File dir = new File(getCacheDir(), "screenshots");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            // Purge stale JPEGs so screenshots don't accumulate indefinitely
            // in app-private cache (potential exposure via backup or root).
            File[] existing = dir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            File out = new File(dir, "screenshot-" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(jpeg);
            }

            resp.put("ok", true);
            resp.put("path", out.getAbsolutePath());
            resp.put("width", toEncode.getWidth());
            resp.put("height", toEncode.getHeight());
            resp.put("orig_width", origW);
            resp.put("orig_height", origH);
            resp.put("format", "jpeg");
            resp.put("quality", quality);
            resp.put("scale", scale);
            resp.put("max_edge", maxEdge);
            resp.put("bytes", jpeg.length);
            if (includeBase64) {
                resp.put("base64", Base64.encodeToString(jpeg, Base64.NO_WRAP));
            }
        } catch (Exception e) {
            resp.put("ok", false);
            resp.put("error", "encode/save failed: " + e);
        } finally {
            if (toEncode != bmp) toEncode.recycle();
            bmp.recycle();
        }
        return resp;
    }

    private static float clampScale(float s) {
        if (Float.isNaN(s) || s <= 0f) return DEFAULT_SCREENSHOT_SCALE;
        if (s > 1f) return 1f;
        return s;
    }

    /**
     * Capture the current screen and return raw JPEG bytes, or null on failure.
     * Used by the streaming {@code watch} action to avoid the disk I/O and
     * metadata overhead of {@link #takeScreenshotToJson(JSONObject)}.
     */
    public byte[] captureScreenJpeg(float scale, int quality) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        final float s = clampScale(scale);
        final int q = Math.max(1, Math.min(100, quality));

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmapRef = new AtomicReference<>();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY,
                    screenshotCallbackExec(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            HardwareBuffer hb = null;
                            Bitmap wrapped = null;
                            try {
                                hb = result.getHardwareBuffer();
                                ColorSpace cs = result.getColorSpace();
                                wrapped = Bitmap.wrapHardwareBuffer(hb, cs);
                                if (wrapped != null) {
                                    bitmapRef.set(wrapped.copy(Bitmap.Config.ARGB_8888, false));
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                if (wrapped != null) wrapped.recycle();
                                if (hb != null) hb.close();
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            latch.countDown();
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
        try {
            if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        Bitmap bmp = bitmapRef.get();
        if (bmp == null) return null;

        int origW = bmp.getWidth();
        int origH = bmp.getHeight();
        int targetW = origW;
        int targetH = origH;
        if (s > 0f && s < 0.999f) {
            targetW = Math.max(1, Math.round(origW * s));
            targetH = Math.max(1, Math.round(origH * s));
        }
        int longest = Math.max(targetW, targetH);
        if (longest > DEFAULT_MAX_EDGE) {
            float cap = (float) DEFAULT_MAX_EDGE / (float) longest;
            targetW = Math.max(1, Math.round(targetW * cap));
            targetH = Math.max(1, Math.round(targetH * cap));
        }

        Bitmap toEncode = bmp;
        try {
            if (targetW != origW || targetH != origH) {
                toEncode = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            toEncode.compress(Bitmap.CompressFormat.JPEG, q, baos);
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (toEncode != bmp) toEncode.recycle();
            bmp.recycle();
        }
    }
}
