package com.hermescoagent.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that runs a minimal HTTP command server on the phone.
 * Hermes sends JSON commands to http://<phone-ip>:8765/cmd and gets JSON back.
 *
 * Every request must carry X-Hermes-Token matching the token in SharedPreferences.
 */
public class RemoteControlService extends Service {

    public static final int PORT = 8765;
    public static final String PREFS = "hermes_prefs";
    public static final String KEY_TOKEN = "auth_token";
    private static final String TOKEN_HEADER = "x-hermes-token";
    private static final int HTTP_WORKERS = 4;
    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private ServerSocket server;
    private ExecutorService workers;
    private volatile boolean running;
    private String authToken;
    /** True while the foreground service + HTTP server are up (for UI status). */
    public static volatile boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        authToken = ensureToken(this);
        // If a previous process was killed mid-ring, our DND filter and audio
        // volumes may be stuck in the "ringing" state. Restore them now.
        CommandExecutor.restoreCrashedRingState(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground();
        startServer();
        isRunning = true;
        return START_STICKY;
    }

    /** Returns the persisted token, generating one on first use. */
    public static String ensureToken(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = sp.getString(KEY_TOKEN, null);
        if (t != null && !t.isEmpty()) return t;
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        t = sb.toString();
        sp.edit().putString(KEY_TOKEN, t).apply();
        return t;
    }

    private void startForeground() {
        String channelId = "hermes_remote";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    channelId, "Hermes CoAgent", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n;
        // Notification.Builder(Context, channelId) only exists on API 26+.
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, channelId)
                    .setContentTitle("Hermes CoAgent")
                    .setContentText("Remote control active on port " + PORT)
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("Hermes CoAgent")
                    .setContentText("Remote control active on port " + PORT)
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_LOW)
                    .build();
        }
        startForeground(1, n);
    }

    private synchronized void startServer() {
        // START_STICKY redelivers onStartCommand; don't bind the port twice.
        if (running && server != null && !server.isClosed()) return;
        running = true;
        if (workers == null || workers.isShutdown()) {
            workers = Executors.newFixedThreadPool(HTTP_WORKERS);
        }
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                while (running) {
                    final Socket sock = server.accept();
                    try {
                        workers.execute(() -> handle(sock));
                    } catch (Throwable t) {
                        closeQuietly(sock);
                    }
                }
            } catch (Exception ignored) {
                // socket closed on shutdown, or bind failed
            }
        }, "hermes-accept").start();
    }

    private void handle(Socket sock) {
        BufferedReader br = null;
        OutputStream out = null;
        try {
            sock.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = sock.getInputStream();
            br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // Request line: e.g. "POST /cmd HTTP/1.1"
            String requestLine = br.readLine();
            if (requestLine == null) return;
            String[] rlParts = requestLine.split(" ");
            String method = rlParts.length > 0 ? rlParts[0] : "";
            String path = rlParts.length > 1 ? rlParts[1] : "";

            // Parse headers
            int contentLength = 0;
            String headerToken = null;
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    try { contentLength = Integer.parseInt(value); } catch (NumberFormatException nfe) { contentLength = 0; }
                } else if (name.equals(TOKEN_HEADER)) {
                    headerToken = value;
                }
            }

            out = sock.getOutputStream();

            if (!"POST".equalsIgnoreCase(method) || !"/cmd".equals(path)) {
                writeStatus(out, 405, "Method Not Allowed",
                        "{\"ok\":false,\"error\":\"use POST /cmd\"}");
                return;
            }

            if (authToken == null || headerToken == null || !constantTimeEquals(authToken, headerToken)) {
                writeStatus(out, 401, "Unauthorized",
                        "{\"ok\":false,\"error\":\"missing or invalid X-Hermes-Token\"}");
                return;
            }

            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                writeStatus(out, 413, "Payload Too Large",
                        "{\"ok\":false,\"error\":\"body too large\"}");
                return;
            }

            char[] body = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int r = br.read(body, read, contentLength - read);
                if (r == -1) break;
                read += r;
            }
            String json = new String(body, 0, read);
            String response = CommandExecutor.execute(getApplicationContext(), json);
            writeStatus(out, 200, "OK", response);
        } catch (Exception ignored) {
        } finally {
            closeQuietly(br);
            closeQuietly(out);
            closeQuietly(sock);
        }
    }

    private static void writeStatus(OutputStream out, int code, String reason, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        int r = 0;
        for (int i = 0; i < ab.length; i++) r |= ab[i] ^ bb[i];
        return r == 0;
    }

    private static void closeQuietly(Object c) {
        if (c instanceof Closeable) {
            try { ((Closeable) c).close(); } catch (Exception ignored) {}
        } else if (c instanceof Socket) {
            try { ((Socket) c).close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        isRunning = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        if (workers != null) {
            workers.shutdownNow();
            try { workers.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            workers = null;
        }
        super.onDestroy();
    }
}
