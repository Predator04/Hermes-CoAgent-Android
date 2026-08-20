package com.hermescoagent.phone;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Shared command dispatcher. Called by both the LAN HTTP server
 * (RemoteControlService) and the outbound RemoteRelayClient. The input
 * is a JSON string {"action":"...", ...}; the output is a JSON string.
 */
public final class CommandExecutor {

    private CommandExecutor() {}

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

    // ─── Command log ring buffer ─────────────────────────────────────────
    private static final int LOG_CAPACITY = 50;
    private static final Deque<JSONObject> LOG = new ArrayDeque<>();

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
        try {
            JSONObject resp = dispatch(ctx, action, req);
            ok = resp.optBoolean("ok", false);
            if (!ok) summary = resp.optString("error", null);
            result = resp.toString();
        } catch (Exception e) {
            summary = String.valueOf(e);
            try { result = new JSONObject().put("ok", false).put("error", summary).toString(); }
            catch (Exception ex) { result = "{\"ok\":false}"; }
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
                else { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); }
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
                else return s.takeScreenshotToJson(req);
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
                resp.put("ring", ring);
                resp.put("flashlight", torch);
                resp.put("location", loc);
                resp.put("wifi", wifi);
                resp.put("charging", chg);
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
                    resp.put("ok", s.scroll(forward));
                    resp.put("direction", forward ? "forward" : "backward");
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
                if (ringPlayer != null) { safeReleasePlayer(ringPlayer); ringPlayer = null; }
                if (ringRingtone != null) { safeStopRingtone(ringRingtone); ringRingtone = null; }
                if (ringVibrator != null) { try { ringVibrator.cancel(); } catch (Throwable ignored) {} ringVibrator = null; }
                restoreStreams(ctx);
                restoreDnd(ctx);
                ringActive = false;
                resp.put("ok", true);
            } catch (Exception e) {
                resp.put("ok", false);
                try { resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean tryBypassDnd(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (!nm.isNotificationPolicyAccessGranted()) return false;
            int current = nm.getCurrentInterruptionFilter();
            if (current != NotificationManager.INTERRUPTION_FILTER_ALL) {
                savedDndFilter = current;
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void restoreDnd(Context ctx) {
        try {
            if (savedDndFilter == null) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(savedDndFilter);
            }
            savedDndFilter = null;
        } catch (Throwable ignored) {}
    }

    private static void maxOutStreams(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (savedAlarmVolume == null) savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            if (savedRingVolume == null) savedRingVolume = am.getStreamVolume(AudioManager.STREAM_RING);
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
        } catch (Throwable ignored) {}
    }

    private static void startVibrateLoop(Context ctx) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
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
                    Executors.newSingleThreadExecutor(),
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
                if (tts == null) {
                    tts = new TextToSpeech(app, status -> {
                        synchronized (initLock) { initStatus[0] = status; initDone[0] = true; initLock.notifyAll(); }
                    });
                } else {
                    synchronized (initLock) { initStatus[0] = TextToSpeech.SUCCESS; initDone[0] = true; initLock.notifyAll(); }
                }
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
            resp.put("package", s.getForegroundPackage());
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
                    if (!t.isEmpty()) digest.put(t);
                    else if (!d.isEmpty()) digest.put(d);
                }
                resp.put("digest", digest);
            } else {
                resp.put("node_count", -1);
            }
            if (req.optBoolean("include_screenshot", false)) {
                resp.put("screenshot", s.takeScreenshotToJson(req));
            }
        } else {
            resp.put("package", "");
            resp.put("node_count", -1);
            resp.put("accessibility", false);
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
            if (summary != null) entry.put("summary", summary);
            synchronized (LOG) {
                if (LOG.size() >= LOG_CAPACITY) LOG.pollFirst();
                LOG.offerLast(entry);
            }
        } catch (Exception ignored) {}
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
