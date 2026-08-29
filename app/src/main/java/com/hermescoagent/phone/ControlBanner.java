package com.hermescoagent.phone;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Persistent overlay control bar. Shown for the entire lifetime of the
 * remote-control foreground service so the owner always knows the phone is
 * being controlled and can kill it instantly via the STOP button. Auto-hidden
 * for stealth actions (see {@link CommandExecutor}).
 *
 * Uses two overlay windows so touches pass through the info pill and only the
 * STOP button intercepts. Silent no-op when SYSTEM_ALERT_WINDOW is not granted.
 */
public final class ControlBanner {

    private static final ControlBanner INSTANCE = new ControlBanner();

    private static final String COLOR_BG        = "#E60D1117";
    private static final String COLOR_STROKE    = "#3358A6FF";
    private static final String COLOR_LABEL     = "#E6EDF3";
    private static final String COLOR_DOT_IDLE  = "#6E7681";
    private static final String COLOR_DOT_LIVE  = "#F85149";
    private static final String COLOR_STOP_BG   = "#F85149";
    private static final String COLOR_STOP_EDGE = "#33FFFFFF";

    private static final long IDLE_HIDE_MS = 15000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideTask = this::hideInternal;
    private WindowManager wm;

    private View pillView;
    private View stopView;
    private TextView dotView;
    private ObjectAnimator dotAnim;

    private boolean attached;
    private boolean hiddenForStealth;
    private boolean active;

    private ControlBanner() {}

    public static ControlBanner get() { return INSTANCE; }

    /** Show the bar + red pulsing dot for an active command. Cancels any
     *  pending idle-hide so a burst of commands keeps the bar steady. */
    public static void showActive(Context ctx) {
        INSTANCE.main.removeCallbacks(INSTANCE.hideTask);
        INSTANCE.showInternal(ctx);
        INSTANCE.setActiveInternal(true);
    }

    /** Dot back to gray, then hide after {@link #IDLE_HIDE_MS} of no further
     *  commands. Called at the end of a non-stealth command so the bar stays
     *  up through a burst of activity but disappears once the agent goes idle. */
    public static void idle() {
        INSTANCE.main.removeCallbacks(INSTANCE.hideTask);
        INSTANCE.setActiveInternal(false);
        INSTANCE.main.postDelayed(INSTANCE.hideTask, IDLE_HIDE_MS);
    }

    /** Immediately tear the bar down (stealth actions, service stop). */
    public static void hide() {
        INSTANCE.main.removeCallbacks(INSTANCE.hideTask);
        INSTANCE.hideInternal();
    }

    public static boolean isShowing() { return INSTANCE.attached; }

    private void showInternal(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(app)) {
            return;
        }
        main.post(() -> attachViews(app));
    }

    private void attachViews(Context app) {
        try {
            if (attached) return;
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            pillView = buildPill(app);
            stopView = buildStop(app);

            int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams pillLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);
            pillLp.gravity = Gravity.TOP | Gravity.START;
            pillLp.x = dp(app, 12);
            pillLp.y = dp(app, 28);

            WindowManager.LayoutParams stopLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);
            stopLp.gravity = Gravity.TOP | Gravity.END;
            stopLp.x = dp(app, 12);
            stopLp.y = dp(app, 28);

            wm.addView(pillView, pillLp);
            wm.addView(stopView, stopLp);
            attached = true;
            hiddenForStealth = false;
            applyActive();
        } catch (Throwable ignored) {
            attached = false;
            pillView = null;
            stopView = null;
        }
    }

    private void hideInternal() {
        main.post(this::detachViews);
    }

    private void detachViews() {
        try {
            cancelPulse();
            if (attached && wm != null) {
                if (pillView != null) { try { wm.removeView(pillView); } catch (Throwable ignored) {} }
                if (stopView != null) { try { wm.removeView(stopView); } catch (Throwable ignored) {} }
            }
        } finally {
            attached = false;
            hiddenForStealth = false;
            active = false;
            pillView = null;
            stopView = null;
            dotView = null;
        }
    }

    private void setActiveInternal(boolean on) {
        main.post(() -> {
            active = on;
            applyActive();
        });
    }

    private void applyActive() {
        if (dotView == null) return;
        if (active) {
            dotView.setTextColor(Color.parseColor(COLOR_DOT_LIVE));
            startPulse();
        } else {
            cancelPulse();
            dotView.setAlpha(1f);
            dotView.setTextColor(Color.parseColor(COLOR_DOT_IDLE));
        }
    }

    private void stealthHideInternal() {
        main.post(() -> {
            if (!attached || hiddenForStealth) return;
            if (pillView != null) pillView.setVisibility(View.GONE);
            if (stopView != null) stopView.setVisibility(View.GONE);
            hiddenForStealth = true;
        });
    }

    private void stealthShowInternal() {
        main.post(() -> {
            if (!attached || !hiddenForStealth) return;
            if (pillView != null) pillView.setVisibility(View.VISIBLE);
            if (stopView != null) stopView.setVisibility(View.VISIBLE);
            hiddenForStealth = false;
        });
    }

    private View buildPill(Context ctx) {
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(ctx, 14);
        int padV = dp(ctx, 8);
        pill.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.parseColor(COLOR_BG));
        bg.setCornerRadius(dp(ctx, 22));
        bg.setStroke(dp(ctx, 1), Color.parseColor(COLOR_STROKE));
        pill.setBackground(bg);

        dotView = new TextView(ctx);
        dotView.setText("●");
        dotView.setTextColor(Color.parseColor(COLOR_DOT_IDLE));
        dotView.setTextSize(12);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dotLp.rightMargin = dp(ctx, 8);
        pill.addView(dotView, dotLp);

        TextView label = new TextView(ctx);
        label.setText("Hermes CoAgent");
        label.setTextColor(Color.parseColor(COLOR_LABEL));
        label.setTextSize(13);
        pill.addView(label);

        return pill;
    }

    private View buildStop(Context ctx) {
        LinearLayout btn = new LinearLayout(ctx);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        int padH = dp(ctx, 14);
        int padV = dp(ctx, 8);
        btn.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.parseColor(COLOR_STOP_BG));
        bg.setCornerRadius(dp(ctx, 22));
        bg.setStroke(dp(ctx, 1), Color.parseColor(COLOR_STOP_EDGE));
        btn.setBackground(bg);

        TextView label = new TextView(ctx);
        label.setText("■ STOP");
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        btn.addView(label);

        final Context app = ctx.getApplicationContext();
        btn.setOnClickListener(v -> emergencyStop(app));
        return btn;
    }

    private void emergencyStop(Context app) {
        // Disable the outbound relay first so a live poll can't hand us a new
        // command in the moment between stopping the service and the process
        // being torn down.
        try {
            RemoteRelayClient.setEnabled(app, false);
            try { RemoteRelayClient.get(app).stop(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        try {
            app.stopService(new Intent(app, RemoteControlService.class));
        } catch (Throwable ignored) {}
        // Immediate UI feedback: onDestroy is async.
        RemoteControlService.isRunning = false;
        hideInternal();
    }

    private void startPulse() {
        cancelPulse();
        if (dotView == null) return;
        try {
            dotAnim = ObjectAnimator.ofFloat(dotView, "alpha", 1f, 0.25f);
            dotAnim.setDuration(700);
            dotAnim.setRepeatCount(ValueAnimator.INFINITE);
            dotAnim.setRepeatMode(ValueAnimator.REVERSE);
            dotAnim.start();
        } catch (Throwable ignored) {}
    }

    private void cancelPulse() {
        if (dotAnim != null) {
            try { dotAnim.cancel(); } catch (Throwable ignored) {}
            dotAnim = null;
        }
        if (dotView != null) dotView.setAlpha(1f);
    }

    private static int dp(Context ctx, int value) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}
