package com.hermescoagent.phone;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Full-screen transparent overlay that draws a soft pulsing glow around the
 *  screen edges while the phone is actively being controlled. Touch passthrough
 *  is handled by the WindowManager flags in {@link ControlBanner}, not here. */
public final class GlowBorderView extends View {

    private static final int GLOW_COLOR = 0xFFF85149; // red — matches the live dot + STOP
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float intensity = 0f; // 0..1, driven by the pulse animator

    public GlowBorderView(Context ctx) {
        super(ctx);
        setWillNotDraw(false);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setColor(GLOW_COLOR);
        glowPaint.setStrokeWidth(dp(10));
        glowPaint.setMaskFilter(new BlurMaskFilter(dp(16), BlurMaskFilter.Blur.NORMAL));

        corePaint.setStyle(Paint.Style.STROKE);
        corePaint.setColor(GLOW_COLOR);
        corePaint.setStrokeWidth(dp(3));
    }

    /** 0f = invisible, 1f = full glow. Driven by the pulse animator. */
    public void setIntensity(float v) {
        if (v != intensity) {
            intensity = v;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (intensity <= 0.01f) return;
        int inset = dp(8);
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = dp(24);

        // soft halo — grows + brightens with intensity
        glowPaint.setAlpha((int) (170 * intensity));
        glowPaint.setStrokeWidth(dp((int) (6 + 14 * intensity)));
        canvas.drawRoundRect(rect, radius, radius, glowPaint);

        // crisp inner edge
        corePaint.setAlpha((int) (235 * intensity));
        canvas.drawRoundRect(rect, radius, radius, corePaint);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
