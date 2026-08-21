package com.hermescoagent.phone;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shared command dispatcher. Called by both the LAN HTTP server
 * (RemoteControlService) and the outbound RemoteRelayClient. The input
 * is a JSON string {"action":"...", ...}; the output is a JSON string.
 */
public final class CommandExecutor {

    private CommandExecutor() {}

    // Silent-capture actions must never show the on-screen banner — a thief
    // holding the phone must not see that anything is happening.
    private static final java.util.Set<String> STEALTH_ACTIONS =
            new java.util.HashSet<>(java.util.Arrays.asList("stolen", "photo", "mic"));

    // ─── Ring state (find-my-phone) ──────────────────────────────────────
    private static final Object RING_LOCK = new Object();
    private static Ringtone ringRingtone;
    private static MediaPlayer ringPlayer;
    private static Vibrator ringVibrator;
    private static Integer savedDndFilter;
    private static Integer savedAlarmVolume;
    private static Integer savedRingVolume;
    private static boolean ringActive;

    // ─── TTS ─────────────────────────────────────────────────────────────
    private static TextToSpeech tts;
    private static volatile boolean ttsReady;

    // ─── find_phone safety auto-stop ─────────────────────────────────────
    private static ScheduledExecutorService findPhoneScheduler;
    private static ScheduledFuture<?> findPhoneAutoStopTask;
    private static final long FIND_PHONE_AUTO_STOP_MS = 30_000L;

    // ─── Persisted ring state (recovers if process is killed mid-ring) ───
    private static final String RING_PREFS = "hermes_ring_state";
    private static final String KEY_SAVED_DND = "saved_dnd";
    private static final String KEY_SAVED_ALARM_VOL = "saved_alarm_vol";
    private static final String KEY_SAVED_RING_VOL = "saved_ring_vol";

    // ─── Command log ring buffer ─────────────────────────────────────────
    private static final int LOG_CAPACITY = 50;
    private static final Deque<JSONObject> LOG = new ArrayDeque<>();

    // Shared executor for location + wake-callback dispatches. Creating a new
    // single-thread executor per call leaked a thread every time.
    private static final Object CB_EXEC_LOCK = new Object();
    private static ExecutorService locationCallbackExec;
    private static ExecutorService locationCallbackExec() {
        synchronized (CB_EXEC_LOCK) {
            if (locationCallbackExec == null) {
                locationCallbackExec = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "hermes-loc-cb");
                    t.setDaemon(true);
                    return t;
                });
            }
            return locationCallbackExec;
        }
    }

    public static String execute(Context ctx, String json) {
        String action = "";
        JSONObject req;
        try {
            req = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            action = req.optString("action");
        } catch (Exception e) {
            String err = "bad json: " + e;
            recordLog(action, false, err);
            try { return new JSONObject().put("ok", false).put("error", err).toString(); }
            catch (Exception ex) { return "{\"ok\":false}"; }
        }

        String result;
        boolean ok = false;
        String summary = null;
        boolean stealth = STEALTH_ACTIONS.contains(action);
        try {
            if (stealth) {
                // Stealth actions must not reveal the bar at all.
                try { ControlBanner.hide(); } catch (Throwable ignored) {}
            } else {
                try { ControlBanner.showActive(ctx); } catch (Throwable ignored) {}
            }
            JSONObject resp = dispatch(ctx, action, req);
            ok = resp.optBoolean("ok", false);
            if (!ok) summary = resp.optString("error", null);
            result = resp.toString();
        } catch (Exception e) {
            summary = String.valueOf(e);
            try { result = new JSONObject().put("ok", false).put("error", summary).toString(); }
            catch (Exception ex) { result = "{\"ok\":false}"; }
        } finally {
            if (!stealth) {
                try { ControlBanner.idle(); } catch (Throwable ignored) {}
            }
        }
        recordLog(action, ok, summary);
        return result;
    }

    private static JSONObject dispatch(Context ctx, String action, JSONObject req) throws Exception {
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
                else {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    try {
                        ctx.startActivity(i);
                        resp.put("ok", true);
                    } catch (Exception e) {
                        resp.put("ok", false);
                        resp.put("error", "launch failed: " + e.getMessage());
                    }
                }
                break;
            }
            case "battery":
                fillBattery(ctx, resp);
                break;
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
            case "list_apps":
                resp.put("apps", listLaunchableApps(ctx));
                break;
            case "dump": {
                if (Redaction.isPrivacyOn(ctx)) return privacyRefusal();
                HermesAccessibilityService s = HermesAccessibilityService.instance;
                if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                else {
                    JSONArray nodes = s.dumpNodes();
                    if (nodes == null) { resp.put("ok", false); resp.put("error", "dump timeout"); }
                    else {
                        String pkg = s.getForegroundPackage();
                        boolean sensitive = Redaction.isSensitivePackage(ctx, pkg);
                        redactNodes(nodes, sensitive);
                        resp.put("nodes", nodes);
                        resp.put("count", nodes.length());
                        resp.put("package", pkg);
                        if (sensitive) resp.put("redacted", true);
                    }
                }
                break;
            }
            case "watch":
                return watch(ctx, req);
            case "screenshot": {
                if (Redaction.isPrivacyOn(ctx)) return privacyRefusal();
                HermesAccessibilityService s = HermesAccessibilityService.instance;
                if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                else {
                    String pkg = s.getForegroundPackage();
                    if (Redaction.isSensitivePackage(ctx, pkg)) {
                        return new JSONObject()
                                .put("ok", false)
                                .put("error", "sensitive app")
                                .put("redacted", true)
                                .put("package", pkg);
                    }
                    return s.takeScreenshotToJson(req);
                }
                break;
            }
            case "notifications": {
                if (Redaction.isPrivacyOn(ctx)) return privacyRefusal();
                HermesNotificationListener nl = HermesNotificationListener.instance;
                if (nl == null) { resp.put("ok", false); resp.put("error", "notification access not enabled"); }
                else {
                    JSONArray arr = nl.listActive(ctx);
                    resp.put("notifications", arr);
                    resp.put("count", arr.length());
                }
                break;
            }
            case "dismiss_notification": {
                HermesNotificationListener nl = HermesNotificationListener.instance;
                if (nl == null) { resp.put("ok", false); resp.put("error", "notification access not enabled"); break; }
                String key = req.optString("key", "");
                String pkg = req.optString("package", "");
                if (!key.isEmpty()) {
                    resp.put("ok", nl.cancelKey(key));
                } else if (!pkg.isEmpty()) {
                    int n = nl.cancelPackage(pkg);
                    resp.put("ok", true);
                    resp.put("cancelled", n);
                } else {
                    resp.put("ok", false);
                    resp.put("error", "key or package required");
                }
                break;
            }
            case "privacy": {
                if (req.has("on")) {
                    Redaction.setPrivacyOn(ctx, req.optBoolean("on", false));
                }
                resp.put("privacy", Redaction.isPrivacyOn(ctx));
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
                else return s.findAndTap(req.optString("query"));
                break;
            }

            // ─── Group A: find-my-phone ────────────────────────────────────
            case "ring":
                startRing(ctx, resp);
                break;
            case "stop_ring":
                stopRing(ctx, resp);
                break;
            case "location":
                fillLocation(ctx, resp);
                break;
            case "flashlight":
                setFlashlight(ctx, req, resp);
                break;
            case "speak":
                speak(ctx, req.optString("text"), resp);
                break;
            case "vibrate":
                vibrate(ctx, resp);
                break;
            case "wifi":
                fillWifi(ctx, resp);
                break;
            case "charging":
                fillCharging(ctx, resp);
                break;
            case "find_phone": {
                JSONObject ring = new JSONObject().put("ok", true);
                startRing(ctx, ring);
                vibrate(ctx, new JSONObject());
                JSONObject torch = new JSONObject();
                JSONObject torchReq = new JSONObject().put("on", true);
                setFlashlight(ctx, torchReq, torch);
                JSONObject loc = new JSONObject();
                fillLocation(ctx, loc);
                JSONObject wifi = new JSONObject();
                fillWifi(ctx, wifi);
                JSONObject chg = new JSONObject();
                fillCharging(ctx, chg);
                scheduleFindPhoneAutoStop(ctx);
                resp.put("ring", ring);
                resp.put("flashlight", torch);
                resp.put("location", loc);
                resp.put("wifi", wifi);
                resp.put("charging", chg);
                resp.put("auto_stop_ms", FIND_PHONE_AUTO_STOP_MS);
                break;
            }

            // ─── Group B: agent-control ────────────────────────────────────
            case "wait":
                waitForCondition(req, resp);
                break;
            case "scroll": {
                HermesAccessibilityService s = HermesAccessibilityService.instance;
                if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                else {
                    String dir = req.optString("direction", "down").toLowerCase();
                    boolean forward = dir.equals("down") || dir.equals("forward");
                    boolean nodeScrolled = s.scroll(forward);
                    String method;
                    boolean ok;
                    if (nodeScrolled) {
                        ok = true;
                        method = "node";
                    } else {
                        // Fallback: no scrollable node (e.g. launcher home). Simulate
                        // a swipe covering ~60% of the screen height.
                        DisplayMetrics dm = readDisplayMetrics(ctx);
                        int cx = Math.max(1, dm.widthPixels / 2);
                        int yHi = (int) (dm.heightPixels * 0.8);
                        int yLo = (int) (dm.heightPixels * 0.2);
                        // Scroll "down" = content moves up ⇒ swipe finger from low y to high y.
                        int y1 = forward ? yHi : yLo;
                        int y2 = forward ? yLo : yHi;
                        ok = s.swipe(cx, y1, cx, y2, 300);
                        method = "swipe";
                    }
                    resp.put("ok", ok);
                    resp.put("direction", forward ? "forward" : "backward");
                    resp.put("method", method);
                }
                break;
            }
            case "clipboard_get":
                clipboardGet(ctx, resp);
                break;
            case "clipboard_set":
                clipboardSet(ctx, req.optString("text"), resp);
                break;
            case "snapshot":
                snapshot(ctx, req, resp);
                break;
            case "wake":
                wake(ctx, resp);
                break;
            case "log":
                resp.put("log", snapshotLog());
                break;

            // ─── camera / theft-response / lock / open ─────────────────────
            case "photo":
                return PhotoCapture.capture(ctx, req.optString("camera", "front"));
            case "stolen":
                return stolen(ctx);
            case "lock": {
                HermesAccessibilityService s = HermesAccessibilityService.instance;
                if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); }
                else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    resp.put("ok", false); resp.put("error", "lock requires API 28+");
                } else {
                    resp.put("ok", s.lockScreen());
                }
                break;
            }
            case "open_url":
                openUrl(ctx, req.optString("url"), resp);
                break;

            // ─── anti-theft stretch: mic / tracking / sim ─────────────────
            case "mic":
                return AudioCapture.record(ctx, req.optInt("seconds", 10));
            case "tracking": {
                if (req.has("on")) {
                    LocationTracker.setEnabled(ctx, req.optBoolean("on", false));
                }
                resp.put("tracking", LocationTracker.isEnabled(ctx));
                break;
            }
            case "location_history": {
                resp.put("tracking", LocationTracker.isEnabled(ctx));
                JSONArray hist = LocationTracker.history(ctx);
                resp.put("history", hist);
                resp.put("count", hist.length());
                break;
            }
            case "sim":
                return SimWatcher.currentInfo(ctx);
            case "sim_events": {
                JSONArray evs = SimWatcher.events(ctx);
                resp.put("events", evs);
                resp.put("count", evs.length());
                break;
            }

            default:
                resp.put("ok", false);
                resp.put("error", "unknown action: " + action);
        }
        return resp;
    }

    // ────────────────────────── ring / stop_ring ─────────────────────────

    private static void startRing(Context ctx, JSONObject resp) throws Exception {
        synchronized (RING_LOCK) {
            boolean dndBypassed = tryBypassDnd(ctx);
            maxOutStreams(ctx);
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                boolean started = false;
                if (uri != null) {
                    try {
                        MediaPlayer mp = new MediaPlayer();
                        mp.setAudioStreamType(AudioManager.STREAM_ALARM);
                        mp.setDataSource(ctx, uri);
                        mp.setLooping(true);
                        mp.prepare();
                        mp.start();
                        if (ringPlayer != null) safeReleasePlayer(ringPlayer);
                        ringPlayer = mp;
                        started = true;
                    } catch (Throwable t) {
                        try {
                            Ringtone rt = RingtoneManager.getRingtone(ctx, uri);
                            if (rt != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    rt.setLooping(true);
                                }
                                rt.play();
                                if (ringRingtone != null) safeStopRingtone(ringRingtone);
                                ringRingtone = rt;
                                started = true;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                startVibrateLoop(ctx);
                ringActive = true;
                resp.put("ok", started);
                resp.put("dnd_bypassed", dndBypassed);
                if (!started) resp.put("error", "no ringtone available");
            } catch (Exception e) {
                resp.put("ok", false);
                try { resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
            }
        }
    }

    private static void stopRing(Context ctx, JSONObject resp) throws Exception {
        synchronized (RING_LOCK) {
            try {
                cancelFindPhoneAutoStop();
                if (ringPlayer != null) { safeReleasePlayer(ringPlayer); ringPlayer = null; }
                if (ringRingtone != null) { safeStopRingtone(ringRingtone); ringRingtone = null; }
                if (ringVibrator != null) { try { ringVibrator.cancel(); } catch (Throwable ignored) {} ringVibrator = null; }
                restoreStreams(ctx);
                restoreDnd(ctx);
                // If flashlight was turned on by find_phone, turn it off too.
                boolean anyFlashOn = false;
                for (Boolean v : flashCurrentlyOn.values()) if (Boolean.TRUE.equals(v)) { anyFlashOn = true; break; }
                if (anyFlashOn) {
                    try {
                        JSONObject torchReq = new JSONObject().put("on", false);
                        setFlashlight(ctx, torchReq, new JSONObject());
                    } catch (Throwable ignored) {}
                }
                ringActive = false;
                resp.put("ok", true);
            } catch (Exception e) {
                resp.put("ok", false);
                try { resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
            }
        }
    }

    private static void scheduleFindPhoneAutoStop(Context ctx) {
        synchronized (RING_LOCK) {
            cancelFindPhoneAutoStop();
            if (findPhoneScheduler == null) {
                findPhoneScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "hermes-findphone-autostop");
                    t.setDaemon(true);
                    return t;
                });
            }
            final Context app = ctx.getApplicationContext();
            findPhoneAutoStopTask = findPhoneScheduler.schedule(() -> {
                try { stopRing(app, new JSONObject()); } catch (Throwable ignored) {}
            }, FIND_PHONE_AUTO_STOP_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void cancelFindPhoneAutoStop() {
        if (findPhoneAutoStopTask != null) {
            try { findPhoneAutoStopTask.cancel(false); } catch (Throwable ignored) {}
            findPhoneAutoStopTask = null;
        }
    }

    private static boolean tryBypassDnd(Context ctx) {
        try {
            if (!PermissionPrefs.wants(ctx, PermissionPrefs.DND)) return false;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (!nm.isNotificationPolicyAccessGranted()) return false;
            int current = nm.getCurrentInterruptionFilter();
            if (current != NotificationManager.INTERRUPTION_FILTER_ALL) {
                savedDndFilter = current;
                persistInt(ctx, KEY_SAVED_DND, current);
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void restoreDnd(Context ctx) {
        try {
            if (savedDndFilter == null) { removeKey(ctx, KEY_SAVED_DND); return; }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(savedDndFilter);
            }
            savedDndFilter = null;
            removeKey(ctx, KEY_SAVED_DND);
        } catch (Throwable ignored) {}
    }

    private static void maxOutStreams(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (savedAlarmVolume == null) {
                savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
                persistInt(ctx, KEY_SAVED_ALARM_VOL, savedAlarmVolume);
            }
            if (savedRingVolume == null) {
                savedRingVolume = am.getStreamVolume(AudioManager.STREAM_RING);
                persistInt(ctx, KEY_SAVED_RING_VOL, savedRingVolume);
            }
            am.setStreamVolume(AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            am.setStreamVolume(AudioManager.STREAM_RING,
                    am.getStreamMaxVolume(AudioManager.STREAM_RING), 0);
        } catch (Throwable ignored) {}
    }

    private static void restoreStreams(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (savedAlarmVolume != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
                savedAlarmVolume = null;
            }
            if (savedRingVolume != null) {
                am.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume, 0);
                savedRingVolume = null;
            }
            removeKey(ctx, KEY_SAVED_ALARM_VOL);
            removeKey(ctx, KEY_SAVED_RING_VOL);
        } catch (Throwable ignored) {}
    }

    private static void persistInt(Context ctx, String key, int value) {
        try {
            ctx.getSharedPreferences(RING_PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(key, value).apply();
        } catch (Throwable ignored) {}
    }

    private static void removeKey(Context ctx, String key) {
        try {
            ctx.getSharedPreferences(RING_PREFS, Context.MODE_PRIVATE)
                    .edit().remove(key).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * Called from RemoteControlService.onCreate() and MainActivity.onCreate() to
     * repair state left behind if the process was killed mid-ring: restores the
     * previous DND filter and audio volumes if we persisted them.
     */
    public static void restoreCrashedRingState(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(RING_PREFS, Context.MODE_PRIVATE);
            if (sp.contains(KEY_SAVED_DND)) {
                int f = sp.getInt(KEY_SAVED_DND, NotificationManager.INTERRUPTION_FILTER_ALL);
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                    try { nm.setInterruptionFilter(f); } catch (Throwable ignored) {}
                }
            }
            if (sp.contains(KEY_SAVED_ALARM_VOL) || sp.contains(KEY_SAVED_RING_VOL)) {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    if (sp.contains(KEY_SAVED_ALARM_VOL)) {
                        try { am.setStreamVolume(AudioManager.STREAM_ALARM,
                                sp.getInt(KEY_SAVED_ALARM_VOL, 0), 0); } catch (Throwable ignored) {}
                    }
                    if (sp.contains(KEY_SAVED_RING_VOL)) {
                        try { am.setStreamVolume(AudioManager.STREAM_RING,
                                sp.getInt(KEY_SAVED_RING_VOL, 0), 0); } catch (Throwable ignored) {}
                    }
                }
            }
            sp.edit().clear().apply();
        } catch (Throwable ignored) {}
    }

    private static void startVibrateLoop(Context ctx) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            // Cancel any previous ring vibration so back-to-back ring/find_phone
            // calls don't stack an unstoppable loop on the abandoned handle.
            if (ringVibrator != null) {
                try { ringVibrator.cancel(); } catch (Throwable ignored) {}
                ringVibrator = null;
            }
            long[] pattern = { 0, 600, 300 };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                v.vibrate(pattern, 0);
            }
            ringVibrator = v;
        } catch (Throwable ignored) {}
    }

    private static void safeReleasePlayer(MediaPlayer mp) {
        try { if (mp.isPlaying()) mp.stop(); } catch (Throwable ignored) {}
        try { mp.release(); } catch (Throwable ignored) {}
    }

    private static void safeStopRingtone(Ringtone rt) {
        try { if (rt.isPlaying()) rt.stop(); } catch (Throwable ignored) {}
    }

    // ───────────────────────────── location ──────────────────────────────

    private static void fillLocation(Context ctx, JSONObject resp) {
        try {
            if (!PermissionPrefs.wants(ctx, PermissionPrefs.LOC)) {
                resp.put("ok", false);
                resp.put("error", "location disabled by user");
                return;
            }
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                resp.put("ok", false);
                resp.put("error", "location permission not granted");
                return;
            }
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                resp.put("ok", false);
                resp.put("error", "no location service");
                return;
            }

            Location loc = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                loc = currentLocationBlocking(ctx, lm);
            }
            if (loc == null) {
                loc = lastKnown(lm, LocationManager.FUSED_PROVIDER);
            }
            if (loc == null) loc = lastKnown(lm, LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lastKnown(lm, LocationManager.NETWORK_PROVIDER);

            if (loc == null) {
                resp.put("ok", false);
                resp.put("error", "no location fix available");
                return;
            }
            resp.put("lat", loc.getLatitude());
            resp.put("lng", loc.getLongitude());
            resp.put("accuracy", loc.getAccuracy());
            resp.put("provider", loc.getProvider() == null ? "" : loc.getProvider());
            resp.put("time", loc.getTime());
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
    }

    private static Location lastKnown(LocationManager lm, String provider) {
        try { return lm.getLastKnownLocation(provider); }
        catch (SecurityException se) { return null; }
        catch (IllegalArgumentException iae) { return null; }
    }

    @SuppressWarnings("MissingPermission")
    private static Location currentLocationBlocking(Context ctx, LocationManager lm) {
        final Location[] out = { null };
        final Object lock = new Object();
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
                    locationCallbackExec(),
                    location -> {
                        synchronized (lock) { out[0] = location; done[0] = true; lock.notifyAll(); }
                    });
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 10_000;
                while (!done[0]) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) { cs.cancel(); break; }
                    try { lock.wait(left); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); cs.cancel(); break; }
                }
            }
        } catch (Throwable ignored) {}
        return out[0];
    }

    // ─────────────────────────── flashlight ──────────────────────────────

    private static void setFlashlight(Context ctx, JSONObject req, JSONObject resp) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                resp.put("ok", false);
                resp.put("error", "flashlight requires API 23+");
                return;
            }
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) { resp.put("ok", false); resp.put("error", "no camera service"); return; }
            String targetId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Boolean hasFlash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(hasFlash)
                        && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    targetId = id;
                    break;
                }
            }
            if (targetId == null) {
                for (String id : cm.getCameraIdList()) {
                    Boolean hasFlash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (Boolean.TRUE.equals(hasFlash)) { targetId = id; break; }
                }
            }
            if (targetId == null) { resp.put("ok", false); resp.put("error", "no flash unit"); return; }
            boolean on;
            if (req.optBoolean("toggle", false)) {
                on = !flashCurrentlyOn.getOrDefault(targetId, false);
            } else {
                on = req.optBoolean("on", true);
            }
            cm.setTorchMode(targetId, on);
            flashCurrentlyOn.put(targetId, on);
            resp.put("on", on);
            resp.put("cameraId", targetId);
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
    }

    private static final java.util.HashMap<String, Boolean> flashCurrentlyOn = new java.util.HashMap<>();

    // ────────────────────────────── speak ────────────────────────────────

    private static void speak(Context ctx, String text, JSONObject resp) {
        try {
            if (text == null || text.isEmpty()) {
                resp.put("ok", false); resp.put("error", "no text"); return;
            }
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC,
                        am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0); } catch (Throwable ignored) {}
                try { am.setStreamVolume(AudioManager.STREAM_ALARM,
                        am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0); } catch (Throwable ignored) {}
            }
            final Object initLock = new Object();
            final boolean[] initDone = { false };
            final int[] initStatus = { TextToSpeech.ERROR };
            final Context app = ctx.getApplicationContext();
            new Handler(Looper.getMainLooper()).post(() -> {
                // Reuse existing engine only if it truly initialized. A stale
                // instance from a failed init would silently no-op on speak().
                if (tts != null && ttsReady) {
                    synchronized (initLock) { initStatus[0] = TextToSpeech.SUCCESS; initDone[0] = true; initLock.notifyAll(); }
                    return;
                }
                if (tts != null) {
                    try { tts.shutdown(); } catch (Throwable ignored) {}
                    tts = null;
                }
                tts = new TextToSpeech(app, status -> {
                    ttsReady = (status == TextToSpeech.SUCCESS);
                    synchronized (initLock) { initStatus[0] = status; initDone[0] = true; initLock.notifyAll(); }
                });
            });
            synchronized (initLock) {
                long deadline = System.currentTimeMillis() + 3000;
                while (!initDone[0]) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) break;
                    try { initLock.wait(left); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
            if (initStatus[0] != TextToSpeech.SUCCESS || tts == null) {
                resp.put("ok", false); resp.put("error", "tts init failed"); return;
            }
            int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-speak-" + System.currentTimeMillis());
            resp.put("ok", r == TextToSpeech.SUCCESS);
            if (r != TextToSpeech.SUCCESS) resp.put("error", "speak returned " + r);
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
    }

    // ────────────────────────────── vibrate ──────────────────────────────

    private static void vibrate(Context ctx, JSONObject resp) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                try { resp.put("ok", false); resp.put("error", "no vibrator"); } catch (Exception ignored) {}
                return;
            }
            long[] pattern = { 0, 800, 200, 800, 200, 800 };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
            try { resp.put("ok", true); } catch (Exception ignored) {}
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────────── wifi ────────────────────────────────

    private static void fillWifi(Context ctx, JSONObject resp) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean isWifi = false;
            if (cm != null && cm.getActiveNetwork() != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                isWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
            resp.put("isWifi", isWifi);
            if (wm == null) { resp.put("ok", true); return; }
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                String ssid = info.getSSID();
                if (ssid != null) {
                    if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    resp.put("ssid", ssid);
                }
                if (info.getBSSID() != null) resp.put("bssid", info.getBSSID());
                resp.put("rssi", info.getRssi());
            }
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
    }

    // ──────────────────────────── charging ───────────────────────────────

    private static void fillCharging(Context ctx, JSONObject resp) {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batt = ctx.registerReceiver(null, f);
            boolean charging = false;
            String type = "unknown";
            int level = -1;
            if (batt != null) {
                int status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                int plugged = batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                switch (plugged) {
                    case BatteryManager.BATTERY_PLUGGED_USB: type = "usb"; break;
                    case BatteryManager.BATTERY_PLUGGED_AC: type = "ac"; break;
                    case BatteryManager.BATTERY_PLUGGED_WIRELESS: type = "wireless"; break;
                    case 0: type = "none"; break;
                    default: type = "unknown";
                }
                int l = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (l >= 0 && scale > 0) level = Math.round(100f * l / scale);
            }
            resp.put("charging", charging);
            resp.put("charge_type", type);
            resp.put("level", level);
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
    }

    private static void fillBattery(Context ctx, JSONObject resp) throws Exception {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) { resp.put("ok", false); resp.put("error", "no battery service"); return; }
        resp.put("level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
    }

    // ─────────────────────────────── wait ────────────────────────────────

    private static void waitForCondition(JSONObject req, JSONObject resp) throws Exception {
        HermesAccessibilityService s = HermesAccessibilityService.instance;
        if (s == null) { resp.put("ok", false); resp.put("error", "accessibility not enabled"); return; }
        String needle = req.optString("for", "");
        String until = req.optString("until", "appear");
        boolean wantAppear = !until.equalsIgnoreCase("disappear");
        long timeout = Math.max(100, req.optLong("timeout_ms", 5000));
        long start = System.currentTimeMillis();
        long deadline = start + timeout;
        boolean matched = false;
        while (System.currentTimeMillis() < deadline) {
            JSONArray hits = s.findNodes(needle);
            boolean present = hits != null && hits.length() > 0;
            if (wantAppear ? present : !present) { matched = true; break; }
            try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        long elapsed = System.currentTimeMillis() - start;
        resp.put("ok", matched);
        resp.put("matched", matched);
        resp.put("elapsed_ms", elapsed);
        resp.put("timed_out", !matched);
    }

    // ───────────────────────────── clipboard ─────────────────────────────

    private static void clipboardGet(Context ctx, JSONObject resp) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) { resp.put("ok", false); resp.put("error", "no clipboard service"); return; }
            ClipData cd = cm.getPrimaryClip();
            String text = "";
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence cs = cd.getItemAt(0).coerceToText(ctx);
                text = cs == null ? "" : cs.toString();
            }
            resp.put("text", text);
            // On Android 10+ getPrimaryClip returns null/empty when the calling
            // app has no visible Activity — which is our case (we run from a
            // background Service). There is no legitimate workaround: the
            // platform intentionally blocks background clipboard reads.
            if ((cd == null || text.isEmpty()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resp.put("note", "clipboard read restricted to foreground app (Android 10+)");
            }
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
    }

    private static void clipboardSet(Context ctx, String text, JSONObject resp) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) { resp.put("ok", false); resp.put("error", "no clipboard service"); return; }
            cm.setPrimaryClip(ClipData.newPlainText("hermes", text == null ? "" : text));
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────────── snapshot ────────────────────────────

    private static void snapshot(Context ctx, JSONObject req, JSONObject resp) throws Exception {
        if (Redaction.isPrivacyOn(ctx)) {
            resp.put("ok", false);
            resp.put("error", "privacy mode");
            resp.put("privacy", true);
            return;
        }
        HermesAccessibilityService s = HermesAccessibilityService.instance;
        DisplayMetrics dm = readDisplayMetrics(ctx);
        JSONObject screen = new JSONObject();
        screen.put("width", dm.widthPixels);
        screen.put("height", dm.heightPixels);
        screen.put("densityDpi", dm.densityDpi);
        resp.put("screen", screen);
        JSONObject battery = new JSONObject();
        fillCharging(ctx, battery);
        resp.put("battery", battery);
        if (s != null) {
            String pkg = s.getForegroundPackage();
            boolean sensitive = Redaction.isSensitivePackage(ctx, pkg);
            resp.put("package", pkg);
            if (sensitive) resp.put("redacted", true);
            JSONArray nodes = s.dumpNodes();
            if (nodes != null) {
                resp.put("node_count", nodes.length());
                JSONArray digest = new JSONArray();
                int max = 40;
                for (int i = 0; i < nodes.length() && digest.length() < max; i++) {
                    JSONObject n = nodes.optJSONObject(i);
                    if (n == null) continue;
                    String t = n.optString("text", "");
                    String d = n.optString("desc", "");
                    String pick = !t.isEmpty() ? t : d;
                    if (pick.isEmpty()) continue;
                    digest.put(sensitive ? Redaction.MASK : Redaction.redactText(pick));
                }
                resp.put("digest", digest);
            } else {
                resp.put("node_count", -1);
            }
            if (req.optBoolean("include_screenshot", false)) {
                if (sensitive) {
                    resp.put("screenshot", new JSONObject()
                            .put("ok", false)
                            .put("error", "sensitive app")
                            .put("redacted", true));
                } else {
                    resp.put("screenshot", s.takeScreenshotToJson(req));
                }
            }
        } else {
            resp.put("package", "");
            resp.put("node_count", -1);
            resp.put("accessibility", false);
        }
    }

    // ────────────────────────────── stolen ───────────────────────────────

    /**
     * Stealth theft-response combo: screen screenshot, front + back photo,
     * GPS, wifi, charging, timestamp — then locks the screen. Captures the
     * screen BEFORE locking so we see what the thief was doing; no visible
     * or audible feedback at any point.
     */
    private static JSONObject stolen(Context ctx) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("ok", true);
        resp.put("timestamp", System.currentTimeMillis());

        HermesAccessibilityService s = HermesAccessibilityService.instance;

        // 1. Screenshot of the current screen (before lock) — small + fast.
        JSONObject shot = new JSONObject();
        if (s != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                JSONObject opts = new JSONObject();
                opts.put("scale", 0.5);
                opts.put("quality", 60);
                shot = s.takeScreenshotToJson(opts);
            } catch (Throwable t) {
                shot.put("ok", false);
                shot.put("error", String.valueOf(t));
            }
        } else {
            shot.put("ok", false);
            shot.put("error", "screenshot unavailable (accessibility not enabled / API < 30)");
        }
        resp.put("screenshot", shot);

        // 2. Front + rear camera photos.
        resp.put("front", PhotoCapture.capture(ctx, "front"));
        resp.put("back", PhotoCapture.capture(ctx, "back"));

        JSONObject loc = new JSONObject();
        fillLocation(ctx, loc);
        resp.put("location", loc);
        JSONObject wifi = new JSONObject();
        fillWifi(ctx, wifi);
        resp.put("wifi", wifi);
        JSONObject charging = new JSONObject();
        fillCharging(ctx, charging);
        resp.put("charging", charging);

        // 3. Lock the screen last (after all captures), so the device is
        //    unusable but we already grabbed the screen + photos.
        boolean locked = false;
        if (s != null) {
            try {
                locked = s.lockScreen();
            } catch (Throwable t) {
                locked = false;
            }
        }
        resp.put("locked", locked);

        return resp;
    }

    // ─────────────────────────────── watch ───────────────────────────────

    private static JSONObject watch(Context ctx, JSONObject req) throws Exception {
        if (Redaction.isPrivacyOn(ctx)) return privacyRefusal();
        HermesAccessibilityService s = HermesAccessibilityService.instance;
        if (s == null) {
            return new JSONObject().put("ok", false).put("error", "accessibility not enabled");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return new JSONObject().put("ok", false).put("error", "screenshot unavailable");
        }

        int duration = (int) Math.max(1, Math.min(60, Math.round(req.optDouble("duration", 20.0))));
        double interval = Math.max(0.5, Math.min(5.0, req.optDouble("interval", 1.5)));
        long intervalMs = (long) (interval * 1000);

        String base = normalizeRelayUrl(RemoteRelayClient.getRelayUrl(ctx));
        String deviceId = RemoteRelayClient.ensureDeviceId(ctx);
        String token = RemoteControlService.ensureToken(ctx);

        ExecutorService postExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hermes-watch-post");
            t.setDaemon(true);
            return t;
        });

        long startMs = System.currentTimeMillis();
        long endMs = startMs + duration * 1000L;
        int frames = 0;
        try {
            while (System.currentTimeMillis() < endMs) {
                long tickStart = System.currentTimeMillis();
                byte[] jpeg = s.captureScreenJpeg(0.5f, 60);
                if (jpeg != null) {
                    final String b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
                    if (!base.isEmpty()) {
                        postExec.submit(() -> postFrameSilent(base, deviceId, token, b64));
                    }
                    frames++;
                }
                long remaining = endMs - System.currentTimeMillis();
                if (remaining <= 0) break;
                long sleepMs = Math.min(intervalMs - (System.currentTimeMillis() - tickStart), remaining);
                if (sleepMs > 0) {
                    try { Thread.sleep(sleepMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        } finally {
            postExec.shutdown();
            try { postExec.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        double actualDuration = (System.currentTimeMillis() - startMs) / 1000.0;
        JSONObject resp = new JSONObject();
        resp.put("ok", true);
        resp.put("frames", frames);
        resp.put("duration", actualDuration);
        resp.put("interval", interval);
        return resp;
    }

    private static String normalizeRelayUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static void postFrameSilent(String base, String deviceId, String token, String frameB64) {
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("device_id", deviceId);
            body.put("token", token);
            body.put("frame", frameB64);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(base + "/frame").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
            // Drain the response so the connection can be reused / closed cleanly.
            try { c.getResponseCode(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
            // fire-and-forget
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    // ────────────────────────────── open_url ─────────────────────────────

    private static void openUrl(Context ctx, String url, JSONObject resp) {
        try {
            if (url == null || url.isEmpty()) {
                resp.put("ok", false); resp.put("error", "url required"); return;
            }
            Uri parsed = Uri.parse(url);
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(java.util.Locale.ROOT);
            // Restrict to safe view schemes. intent://, content://, file://,
            // android-app://, and android.resource:// can invoke arbitrary
            // components or expose private files — refuse them even from a
            // token-bearing controller so a compromised token can't pivot.
            switch (scheme) {
                case "http": case "https":
                case "mailto": case "tel": case "sms": case "smsto":
                case "geo": case "market": case "maps":
                    break;
                default:
                    resp.put("ok", false);
                    resp.put("error", "scheme not allowed: " + scheme);
                    return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW, parsed);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
                resp.put("ok", true);
                resp.put("url", url);
            } catch (android.content.ActivityNotFoundException anf) {
                resp.put("ok", false);
                resp.put("error", "no handler for url");
            }
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
    }

    // ──────────────────────────────── wake ───────────────────────────────

    private static void wake(Context ctx, JSONObject resp) {
        boolean screenOn = false;
        boolean dismissed = false;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "hermes:wake");
                wl.acquire(3000);
                screenOn = pm.isInteractive();
                try { wl.release(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dismissed = !km.isKeyguardLocked();
            }
        } catch (Throwable ignored) {}
        try {
            resp.put("ok", true);
            resp.put("screen_on", screenOn);
            resp.put("keyguard_dismissed", dismissed);
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────── log ─────────────────────────────────

    private static void recordLog(String action, boolean ok, String summary) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("action", action == null ? "" : action);
            entry.put("ok", ok);
            if (summary != null) entry.put("summary", Redaction.redactText(summary));
            synchronized (LOG) {
                if (LOG.size() >= LOG_CAPACITY) LOG.pollFirst();
                LOG.offerLast(entry);
            }
        } catch (Exception ignored) {}
    }

    // ─────────────────────── privacy / redaction helpers ─────────────────

    private static JSONObject privacyRefusal() throws Exception {
        return new JSONObject()
                .put("ok", false)
                .put("error", "privacy mode")
                .put("privacy", true);
    }

    private static void redactNodes(JSONArray nodes, boolean sensitive) {
        if (nodes == null) return;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            try {
                String t = n.optString("text", "");
                String d = n.optString("desc", "");
                if (sensitive) {
                    if (!t.isEmpty()) n.put("text", Redaction.MASK);
                    if (!d.isEmpty()) n.put("desc", Redaction.MASK);
                } else {
                    if (!t.isEmpty()) n.put("text", Redaction.redactText(t));
                    if (!d.isEmpty()) n.put("desc", Redaction.redactText(d));
                }
            } catch (Exception ignored) {}
        }
    }

    private static JSONArray snapshotLog() {
        JSONArray arr = new JSONArray();
        synchronized (LOG) {
            java.util.Iterator<JSONObject> it = LOG.descendingIterator();
            while (it.hasNext()) arr.put(it.next());
        }
        return arr;
    }

    // ─────────────────────────── shared helpers ──────────────────────────

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
