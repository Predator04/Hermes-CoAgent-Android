package com.hermescoagent.phone;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
 * Singleton overlay that shows a small "being controlled" pill at the top of
 * the screen while a remote command is actively executing. Auto-hidden for
 * stealth actions (see {@link CommandExecutor}).
 */
public final class ControlBanner {

    private static final ControlBanner INSTANCE = new ControlBanner();

    private final Handler main = new Handler(Looper.getMainLooper());
    private View view;
    private TextView dotView;
    private ObjectAnimator dotAnim;
    private WindowManager wm;
    private boolean attached;

    private ControlBanner() {}

    public static ControlBanner get() { return INSTANCE; }

    public static void show(Context ctx) { INSTANCE.showInternal(ctx); }
    public static void hide() { INSTANCE.hideInternal(); }
    public static boolean isShowing() { return INSTANCE.attached; }

    private void showInternal(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(app)) {
            return;
        }
        main.post(() -> {
            try {
                if (attached) return;
                wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;

                view = buildPill(app);

                int type;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                } else {
                    type = WindowManager.LayoutParams.TYPE_PHONE;
                }
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = dp(app, 28);

                wm.addView(view, lp);
                attached = true;
                startPulse();
            } catch (Throwable ignored) {
                attached = false;
                view = null;
            }
        });
    }

    private void hideInternal() {
        main.post(() -> {
            try {
                if (dotAnim != null) { try { dotAnim.cancel(); } catch (Throwable ignored) {} dotAnim = null; }
                if (attached && view != null && wm != null) {
                    try { wm.removeView(view); } catch (Throwable ignored) {}
                }
            } finally {
                attached = false;
                view = null;
                dotView = null;
            }
        });
    }

    private View buildPill(Context ctx) {
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(ctx, 12);
        int padV = dp(ctx, 6);
        pill.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.parseColor("#CC0D1117"));
        bg.setCornerRadius(dp(ctx, 20));
        bg.setStroke(dp(ctx, 1), Color.parseColor("#3358A6FF"));
        pill.setBackground(bg);

        dotView = new TextView(ctx);
        dotView.setText("●");
        dotView.setTextColor(Color.parseColor("#F85149"));
        dotView.setTextSize(10);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dotLp.rightMargin = dp(ctx, 6);
        pill.addView(dotView, dotLp);

        TextView label = new TextView(ctx);
        label.setText("Hermes CoAgent");
        label.setTextColor(Color.parseColor("#E6EDF3"));
        label.setTextSize(12);
        pill.addView(label);

        return pill;
    }

    private void startPulse() {
        if (dotView == null) return;
        try {
            dotAnim = ObjectAnimator.ofFloat(dotView, "alpha", 1f, 0.25f);
            dotAnim.setDuration(700);
            dotAnim.setRepeatCount(ValueAnimator.INFINITE);
            dotAnim.setRepeatMode(ValueAnimator.REVERSE);
            dotAnim.start();
        } catch (Throwable ignored) {}
    }

    private static int dp(Context ctx, int value) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}
