package com.hermescoagent.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Full-screen transparent overlay that draws a cursor/pointer at the exact
 * coordinates of the last tap/swipe so the owner can SEE where the agent is
 * touching. RGB ring cycles through hues; the pointer fades out ~1s after the
 * last touch. Touch passthrough via FLAG_NOT_TOUCHABLE. Silent no-op when the
 * overlay permission is missing.
 */
public final class PointerOverlay extends View {

    private static final long FADE_MS = 1100L;
    private static final long HIDE_DELAY_MS = 1400L;

    private static PointerOverlay instance;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideTask = () -> { shownAt = 0L; invalidate(); };

    private WindowManager wm;
    private float cx, cy;
    private long shownAt;
    private float hue;
    private boolean attached;

    private PointerOverlay(Context ctx) {
        super(ctx);
        setWillNotDraw(false);
        ringPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStyle(Paint.Style.STROKE);
    }

    /** Show the pointer at (x, y). Attaches the overlay lazily on first use. */
    public static void show(Context ctx, int x, int y) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(app)) {
            return;
        }
        if (instance == null) instance = new PointerOverlay(app);
        instance.showInternal(app, x, y);
    }

    private void showInternal(Context app, int x, int y) {
        main.post(() -> {
            try {
                attachIfNeeded(app);
                cx = x;
                cy = y;
                shownAt = System.currentTimeMillis();
                hue = (hue + 40f) % 360f;
                invalidate();
                main.removeCallbacks(hideTask);
                main.postDelayed(hideTask, HIDE_DELAY_MS);
            } catch (Throwable ignored) {}
        });
    }

    private void attachIfNeeded(Context app) {
        if (attached) return;
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(this, lp);
        attached = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (shownAt == 0L) return;
        long elapsed = System.currentTimeMillis() - shownAt;
        if (elapsed > HIDE_DELAY_MS) return;
        float t = Math.min(1f, elapsed / (float) FADE_MS);
        int alpha = (int) (235 * (1f - t));

        int color = Color.HSVToColor(new float[]{hue, 0.85f, 1f});
        float r = dp(22) + dp(10) * t; // expanding ring

        fillPaint.setColor(color);
        fillPaint.setAlpha(Math.min(alpha + 40, 255));
        canvas.drawCircle(cx, cy, dp(8), fillPaint);

        ringPaint.setColor(color);
        ringPaint.setAlpha(alpha);
        ringPaint.setStrokeWidth(dp(3));
        canvas.drawCircle(cx, cy, r, ringPaint);

        // crosshair arms
        crossPaint.setColor(color);
        crossPaint.setAlpha(alpha);
        crossPaint.setStrokeWidth(dp(2));
        float arm = dp(14);
        canvas.drawLine(cx - arm, cy, cx - dp(9), cy, crossPaint);
        canvas.drawLine(cx + dp(9), cy, cx + arm, cy, crossPaint);
        canvas.drawLine(cx, cy - arm, cx, cy - dp(9), crossPaint);
        canvas.drawLine(cx, cy + dp(9), cx, cy + arm, crossPaint);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
