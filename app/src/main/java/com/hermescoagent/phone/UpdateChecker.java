package com.hermescoagent.phone;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class UpdateChecker {

    public interface Callback {
        void onResult(boolean available, int latestVersionCode, String releaseName, String apkUrl, String notes);
    }

    private static final String PREFS = "hermes_updates";
    private static final String KEY_LAST_CHECK = "last_update_check_ms";
    private static final String KEY_SKIPPED = "skipped_version_code";
    private static final long TWELVE_HOURS_MS = 12L * 60L * 60L * 1000L;
    private static final String RELEASES_URL =
            "https://api.github.com/repos/Predator04/Hermes-CoAgent-Android/releases/latest";

    private UpdateChecker() {}

    public static void checkForUpdate(Context ctx, Callback cb) {
        checkForUpdate(ctx, false, cb);
    }

    public static void checkForUpdate(Context ctx, boolean manual, Callback cb) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                long now = System.currentTimeMillis();
                long last = sp.getLong(KEY_LAST_CHECK, 0L);
                if (!manual && (now - last) < TWELVE_HOURS_MS) {
                    main.post(() -> cb.onResult(false, 0, null, null, null));
                    return;
                }

                String body = httpGet(RELEASES_URL);
                if (body == null) {
                    main.post(() -> cb.onResult(false, 0, null, null, null));
                    return;
                }

                JSONObject json = new JSONObject(body);
                String tag = json.optString("tag_name", "");
                String name = json.optString("name", "");
                if (name == null || name.isEmpty()) name = tag;
                String notes = json.optString("body", "");

                int latestCode = 0;
                try {
                    String numeric = tag.startsWith("v") ? tag.substring(1) : tag;
                    latestCode = Integer.parseInt(numeric.trim());
                } catch (Exception ignored) {}

                sp.edit().putLong(KEY_LAST_CHECK, now).apply();

                int skipped = sp.getInt(KEY_SKIPPED, -1);
                int currentCode = BuildConfig.VERSION_CODE;

                if (latestCode <= currentCode || latestCode == skipped) {
                    final int lc = latestCode;
                    final String rn = name;
                    main.post(() -> cb.onResult(false, lc, rn, null, notes));
                    return;
                }

                String apkUrl = null;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a == null) continue;
                        String assetName = a.optString("name", "");
                        if (assetName.toLowerCase().endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }

                final int lc = latestCode;
                final String rn = name;
                final String url = apkUrl;
                main.post(() -> cb.onResult(true, lc, rn, url, notes));
            } catch (Exception e) {
                main.post(() -> cb.onResult(false, 0, null, null, null));
            }
        }, "UpdateChecker").start();
    }

    public static void skipVersion(Context ctx, int code) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SKIPPED, code)
                .apply();
    }

    public static void downloadAndInstall(Context ctx, String apkUrl) {
        if (apkUrl == null || apkUrl.isEmpty()) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                File dir = new File(app.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) return;
                File apk = new File(dir, "app-release.apk");
                if (apk.exists()) apk.delete();

                URL u = new URL(apkUrl);
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "HermesCoAgent-Android");
                conn.connect();

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) return;

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                }

                Uri uri = FileProvider.getUriForFile(
                        app, app.getPackageName() + ".fileprovider", apk);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(intent);
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }, "UpdateDownloader").start();
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "HermesCoAgent-Android");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }
}
