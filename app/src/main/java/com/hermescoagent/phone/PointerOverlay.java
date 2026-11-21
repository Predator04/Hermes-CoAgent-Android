package com.hermescoagent.phone;

import android.animation.ValueAnimator;
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
 * Full-screen transparent overlay that shows a persistent RGB cursor at the
 * exact coordinates of the last tap/swipe so the owner can always SEE where the
 * agent is. The cursor stays on-screen (breathing ring) and jumps to each new
 * touch point; {@link #hide()} tears it down when the control session ends.
 * Touch passthrough via FLAG_NOT_TOUCHABLE. Silent no-op without the overlay
 * permission.
 */
public final class PointerOverlay extends View {

    private static final Object LOCK = new Object();
    private static PointerOverlay instance;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private float cx, cy;
    private float hue;
    private float pulse = 0.6f; // breathing ring, 0..1
    private boolean attached;
    private ValueAnimator pulseAnim;
    private ValueAnimator moveAnim;

    private PointerOverlay(Context ctx) {
        super(ctx);
        setWillNotDraw(false);
        ringPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStyle(Paint.Style.STROKE);
    }

    /** Move the persistent cursor to (x, y). Attaches lazily on first use. */
    public static void show(Context ctx, int x, int y) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(app)) {
            return;
        }
        PointerOverlay inst;
        synchronized (LOCK) {
            if (instance == null) instance = new PointerOverlay(app);
            inst = instance;
        }
        inst.moveTo(app, x, y);
    }

    /** Remove the cursor overlay entirely (control session ended). */
    public static void hide() {
        PointerOverlay inst;
        synchronized (LOCK) { inst = instance; }
        if (inst != null) inst.detach();
    }

    private void moveTo(Context app, int x, int y) {
        main.post(() -> {
            try {
                attachIfNeeded(app);
                final float fromX = cx, fromY = cy;
                final float toX = x, toY = y;
                if (moveAnim != null) { try { moveAnim.cancel(); } catch (Throwable ignored) {} }
                moveAnim = ValueAnimator.ofFloat(0f, 1f);
                moveAnim.setDuration(140);
                moveAnim.addUpdateListener(a -> {
                    float t = (float) a.getAnimatedValue();
                    float ease = t * t * (3f - 2f * t); // smoothstep
                    cx = fromX + (toX - fromX) * ease;
                    cy = fromY + (toY - fromY) * ease;
                    invalidate();
                });
                moveAnim.start();
                hue = (hue + 40f) % 360f;
                startPulse();
            } catch (Throwable ignored) {}
        });
    }

    private void attachIfNeeded(Context app) {
        synchronized (LOCK) {
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
            try {
                wm.addView(this, lp);
                attached = true;
            } catch (Throwable ignored) {
                attached = false;
            }
        }
    }

    private void detach() {
        main.post(() -> {
            stopPulse();
            synchronized (LOCK) {
                if (attached && wm != null) {
                    try { wm.removeView(this); } catch (Throwable ignored) {}
                }
                attached = false;
                if (instance == this) instance = null;
            }
        });
    }

    private void startPulse() {
        if (pulseAnim != null) return;
        try {
            pulseAnim = ValueAnimator.ofFloat(0.35f, 1f);
            pulseAnim.setDuration(700);
            pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnim.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); invalidate(); });
            pulseAnim.start();
        } catch (Throwable ignored) {}
    }

    private void stopPulse() {
        if (pulseAnim != null) {
            try { pulseAnim.cancel(); } catch (Throwable ignored) {}
            pulseAnim = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!attached) return;
        int color = Color.HSVToColor(new float[]{hue, 0.85f, 1f});

        // Mouse-pointer arrow (white fill, black outline) with tip at (cx, cy).
        android.graphics.Path arrow = new android.graphics.Path();
        float u = dp(1);
        arrow.moveTo(cx, cy);                                   // tip
        arrow.lineTo(cx + 14 * u, cy + 10 * u);                 // right edge
        arrow.lineTo(cx + 6 * u, cy + 9 * u);                   // notch
        arrow.lineTo(cx + 6 * u, cy + 22 * u);                  // tail down
        arrow.lineTo(cx + 3.5f * u, cy + 22 * u);               // tail left
        arrow.lineTo(cx + 3.5f * u, cy + 8 * u);                // tail up
        arrow.close();

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.WHITE);
        fillPaint.setAlpha(240);
        canvas.drawPath(arrow, fillPaint);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.BLACK);
        ringPaint.setAlpha(210);
        ringPaint.setStrokeWidth(dp(2));
        canvas.drawPath(arrow, ringPaint);

        // Breathing ring around the tip marks the exact tap point.
        float r = dp(14) + dp(8) * pulse;
        ringPaint.setColor(color);
        ringPaint.setAlpha((int) (210 * pulse));
        ringPaint.setStrokeWidth(dp(3));
        canvas.drawCircle(cx, cy, r, ringPaint);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
