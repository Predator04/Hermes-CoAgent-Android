package com.hermescoagent.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Outbound long-poll client. Dials a relay server, registers, then loops
 * calling GET /poll (which blocks up to ~25s server-side). Each command it
 * receives goes through {@link CommandExecutor} and the result is POSTed
 * back on /result.
 *
 * All work runs on a single daemon thread. Errors trigger a short backoff
 * then a re-register — the loop never dies.
 */
public final class RemoteRelayClient {

    public static final String KEY_RELAY_URL = "relay_url";
    public static final String KEY_RELAY_ENABLED = "relay_enabled";
    public static final String KEY_DEVICE_ID = "relay_device_id";

    public enum Status { OFF, CONNECTING, CONNECTED, ERROR }

    public interface StatusListener {
        void onStatus(Status s, String detail);
    }

    private static final int POLL_READ_TIMEOUT_MS = 35_000;
    private static final int SHORT_TIMEOUT_MS = 15_000;
    private static final long BACKOFF_MS = 3_000L;

    private static RemoteRelayClient INSTANCE;

    private final Context appCtx;
    private Thread thread;
    private volatile boolean running;
    private volatile Status status = Status.OFF;
    private volatile String statusDetail = "";
    private volatile StatusListener listener;

    private RemoteRelayClient(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public static synchronized RemoteRelayClient get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new RemoteRelayClient(ctx);
        return INSTANCE;
    }

    public void setStatusListener(StatusListener l) {
        this.listener = l;
        // Fire once so a fresh listener sees current state.
        if (l != null) l.onStatus(status, statusDetail);
    }

    public Status status() { return status; }
    public String statusDetail() { return statusDetail; }

    /** Persisted, generated once. */
    public static String ensureDeviceId(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(RemoteControlService.PREFS, Context.MODE_PRIVATE);
        String id = sp.getString(KEY_DEVICE_ID, null);
        if (id != null && !id.isEmpty()) return id;
        id = UUID.randomUUID().toString();
        sp.edit().putString(KEY_DEVICE_ID, id).apply();
        return id;
    }

    public static String getRelayUrl(Context ctx) {
        return ctx.getSharedPreferences(RemoteControlService.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RELAY_URL, "");
    }

    public static void setRelayUrl(Context ctx, String url) {
        ctx.getSharedPreferences(RemoteControlService.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RELAY_URL, url == null ? "" : url).apply();
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(RemoteControlService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RELAY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(RemoteControlService.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RELAY_ENABLED, enabled).apply();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::runLoop, "hermes-relay");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
        setStatus(Status.OFF, "");
    }

    public boolean isRunning() { return running; }

    private void setStatus(Status s, String detail) {
        this.status = s;
        this.statusDetail = detail == null ? "" : detail;
        StatusListener l = this.listener;
        if (l != null) {
            try { l.onStatus(s, statusDetail); } catch (Throwable ignored) {}
        }
    }

    private void runLoop() {
        String deviceId = ensureDeviceId(appCtx);
        String token = RemoteControlService.ensureToken(appCtx);

        while (running) {
            String base = normalize(getRelayUrl(appCtx));
            if (base.isEmpty()) {
                setStatus(Status.ERROR, "no relay URL");
                sleep(BACKOFF_MS);
                continue;
            }
            setStatus(Status.CONNECTING, base);

            try {
                register(base, deviceId, token);
                setStatus(Status.CONNECTED, base);

                while (running) {
                    // Re-read in case user changed the URL / disabled.
                    String currentBase = normalize(getRelayUrl(appCtx));
                    if (!currentBase.equals(base)) break;

                    JSONArray commands = poll(base, deviceId, token);
                    if (commands == null) {
                        // network hiccup; back off and re-register
                        break;
                    }
                    for (int i = 0; i < commands.length(); i++) {
                        JSONObject c = commands.optJSONObject(i);
                        if (c == null) continue;
                        String commandId = c.optString("command_id");
                        JSONObject action = c.optJSONObject("action");
                        String actionJson = action == null ? "{}" : action.toString();
                        String result = CommandExecutor.execute(appCtx, actionJson);
                        try {
                            postResult(base, deviceId, token, commandId, result);
                        } catch (Exception postErr) {
                            // If posting result fails, keep going — poll loop
                            // will recover on next iteration.
                        }
                    }
                }
            } catch (Exception e) {
                setStatus(Status.ERROR, String.valueOf(e.getMessage()));
                sleep(BACKOFF_MS);
            }
        }
        setStatus(Status.OFF, "");
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static String normalize(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private void register(String base, String deviceId, String token) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_id", deviceId);
        body.put("token", token);
        httpJson("POST", base + "/register", body.toString(), SHORT_TIMEOUT_MS);
    }

    private JSONArray poll(String base, String deviceId, String token) {
        try {
            String url = base + "/poll?device_id=" + enc(deviceId) + "&token=" + enc(token);
            String resp = httpJson("GET", url, null, POLL_READ_TIMEOUT_MS);
            if (resp == null) return new JSONArray();
            JSONObject o = new JSONObject(resp);
            JSONArray a = o.optJSONArray("commands");
            return a == null ? new JSONArray() : a;
        } catch (Exception e) {
            setStatus(Status.ERROR, "poll: " + e.getMessage());
            return null;
        }
    }

    private void postResult(String base, String deviceId, String token,
                            String commandId, String resultJson) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_id", deviceId);
        body.put("token", token);
        body.put("command_id", commandId);
        // resultJson is itself a JSON object string — embed as parsed object.
        try {
            body.put("result", new JSONObject(resultJson));
        } catch (Exception parse) {
            body.put("result", resultJson);
        }
        httpJson("POST", base + "/result", body.toString(), SHORT_TIMEOUT_MS);
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String httpJson(String method, String urlStr, String body, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload);
                }
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(in);
            if (code < 200 || code >= 400) {
                throw new RuntimeException("HTTP " + code + ": " + resp);
            }
            return resp;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString("UTF-8");
    }
}
