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
    private static final String COLOR_LABEL_MUTED = "#99C7D4";
    private static final String COLOR_DOT_IDLE  = "#6E7681";
    private static final String COLOR_DOT_LIVE  = "#F85149";
    private static final String COLOR_STOP_BG   = "#F85149";
    private static final String COLOR_STOP_EDGE = "#33FFFFFF";

    private static final long IDLE_HIDE_MS = 15000L;
    /** Safety net: if the agent never sends "done", hide after this long. */
    private static final long DONE_FALLBACK_MS = 300000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideTask = this::hideInternal;
    private WindowManager wm;

    private View pillView;
    private View stopView;
    private TextView dotView;
    private ObjectAnimator dotAnim;
    private GlowBorderView glowView;
    private ValueAnimator glowAnim;
    private ValueAnimator hueAnim;
    private long glowHueMs = 6000L;

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

    /** Command ended but the task is NOT complete: keep the glow + dot pulsing
     *  so the owner knows the agent is still working. Hide is deferred to the
     *  explicit {@link #done()} signal or a long safety-net timeout. */
    public static void idle() {
        INSTANCE.main.removeCallbacks(INSTANCE.hideTask);
        INSTANCE.main.postDelayed(INSTANCE.hideTask, DONE_FALLBACK_MS);
    }

    /** Immediately tear the bar down (stealth actions, service stop). */
    public static void hide() {
        INSTANCE.main.removeCallbacks(INSTANCE.hideTask);
        INSTANCE.hideInternal();
    }

    /** Explicit "task complete" signal from the agent: stop pulsing + hide
     *  immediately so the owner knows for sure the agent is finished. */
    public static void done() {
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
            glowView = new GlowBorderView(app);
            glowView.setTheme(app.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                    .getString("glow_color", "rainbow"));
            String speed = app.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                    .getString("glow_speed", "slow");
            glowHueMs = "fast".equals(speed) ? 1500L : "normal".equals(speed) ? 3000L : 6000L;

            int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams glowLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);
            glowLp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(glowView, glowLp);

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
            cancelGlow();
            if (attached && wm != null) {
                if (pillView != null) { try { wm.removeView(pillView); } catch (Throwable ignored) {} }
                if (stopView != null) { try { wm.removeView(stopView); } catch (Throwable ignored) {} }
                if (glowView != null) { try { wm.removeView(glowView); } catch (Throwable ignored) {} }
            }
        } finally {
            attached = false;
            hiddenForStealth = false;
            active = false;
            pillView = null;
            stopView = null;
            dotView = null;
            glowView = null;
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
            startGlow();
        } else {
            cancelPulse();
            cancelGlow();
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
        label.setTypeface(Typeface.DEFAULT_BOLD);

        TextView sub = new TextView(ctx);
        sub.setText("is currently using this phone");
        sub.setTextColor(Color.parseColor(COLOR_LABEL_MUTED));
        sub.setTextSize(11);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.addView(label);
        textCol.addView(sub);
        pill.addView(textCol);

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
            BootReceiver.setAutoStartEnabled(app, false);
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

    private void startGlow() {
        cancelGlow();
        if (glowView == null) return;
        try {
            glowAnim = ValueAnimator.ofFloat(0.25f, 1f);
            glowAnim.setDuration(850);
            glowAnim.setRepeatCount(ValueAnimator.INFINITE);
            glowAnim.setRepeatMode(ValueAnimator.REVERSE);
            glowAnim.addUpdateListener(a -> glowView.setIntensity((float) a.getAnimatedValue()));
            glowAnim.start();
            if (glowView.isRainbow()) {
                hueAnim = ValueAnimator.ofFloat(0f, 360f);
                hueAnim.setDuration(glowHueMs);
                hueAnim.setRepeatCount(ValueAnimator.INFINITE);
                hueAnim.setRepeatMode(ValueAnimator.RESTART);
                hueAnim.addUpdateListener(a -> glowView.setHue((float) a.getAnimatedValue()));
                hueAnim.start();
            }
        } catch (Throwable ignored) {}
    }

    private void cancelGlow() {
        if (glowAnim != null) {
            try { glowAnim.cancel(); } catch (Throwable ignored) {}
            glowAnim = null;
        }
        if (hueAnim != null) {
            try { hueAnim.cancel(); } catch (Throwable ignored) {}
            hueAnim = null;
        }
        if (glowView != null) glowView.setIntensity(0f);
    }

    private static int dp(Context ctx, int value) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}
