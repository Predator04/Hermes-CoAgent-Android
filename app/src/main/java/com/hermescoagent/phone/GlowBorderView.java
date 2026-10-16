package com.hermescoagent.phone;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Full-screen transparent overlay that draws a soft pulsing glow around the
 *  screen edges while the phone is actively being controlled. Supports a solid
 *  color theme or a cycling rainbow theme (the default). Touch passthrough is
 *  handled by the WindowManager flags in {@link ControlBanner}, not here. */
public final class GlowBorderView extends View {

    private static final int DEFAULT_COLOR = 0xFFF85149;
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float intensity = 0f; // 0..1, driven by the pulse animator
    private float hue = 0f;      // 0..360, driven by the rainbow animator
    private boolean rainbow = true;
    private int solidColor = DEFAULT_COLOR;

    public GlowBorderView(Context ctx) {
        super(ctx);
        setWillNotDraw(false);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setMaskFilter(new BlurMaskFilter(dp(16), BlurMaskFilter.Blur.NORMAL));
        corePaint.setStyle(Paint.Style.STROKE);
        corePaint.setStrokeWidth(dp(3));
    }

    /** Set the color theme: "rainbow" (cycling) or a #RRGGBB hex. */
    public void setTheme(String key) {
        if (key == null || key.isEmpty() || "rainbow".equals(key)) {
            rainbow = true;
        } else {
            rainbow = false;
            try { solidColor = Color.parseColor(key); }
            catch (Exception e) { solidColor = DEFAULT_COLOR; }
        }
        invalidate();
    }

    public boolean isRainbow() { return rainbow; }

    /** 0f = invisible, 1f = full glow. Driven by the pulse animator. */
    public void setIntensity(float v) {
        if (v != intensity) {
            intensity = v;
            invalidate();
        }
    }

    public void setHue(float h) {
        hue = h;
        invalidate();
    }

    private int currentColor() {
        if (rainbow) return Color.HSVToColor(new float[]{hue, 0.9f, 1f});
        return solidColor;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (intensity <= 0.01f) return;
        int color = currentColor();
        int inset = dp(8);
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = dp(24);

        // soft halo — grows + brightens with intensity
        glowPaint.setColor(color);
        glowPaint.setAlpha((int) (170 * intensity));
        glowPaint.setStrokeWidth(dp((int) (6 + 14 * intensity)));
        canvas.drawRoundRect(rect, radius, radius, glowPaint);

        // crisp inner edge
        corePaint.setColor(color);
        corePaint.setAlpha((int) (235 * intensity));
        canvas.drawRoundRect(rect, radius, radius, corePaint);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
