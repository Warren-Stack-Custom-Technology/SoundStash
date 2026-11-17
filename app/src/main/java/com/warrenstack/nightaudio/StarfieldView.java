package com.warrenstack.nightaudio;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

final class StarfieldView extends View {
    static final String KEY = "twinkling_background";
    private static final int STARS = 92;
    private static final long FRAME_MS = 83;
    private static final long SHOOTING_STAR_MS = 1_250;

    private record Star(float x, float y, float radius, double period, double phase,
                        int minimum, int range, int tint) {}

    private final Star[] stars = new Star[STARS];
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean light;
    private final int haloColor;
    private final float density;
    private long lastFrame;
    private long elapsed;
    private long nextShootingStar;
    private float shootingX;
    private float shootingY;
    private float shootingDx;
    private float shootingDy;

    StarfieldView(Context context, boolean light, int tint) {
        super(context);
        this.light = light;
        haloColor = tint;
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        for (int i = 0; i < stars.length; i++) {
            boolean bright = random.nextFloat() < 0.16f;
            float radius = (bright ? 0.72f + random.nextFloat() * 0.5f
                    : 0.26f + random.nextFloat() * 0.56f) * density;
            int minimum = light ? 85 + random.nextInt(43) : 27 + random.nextInt(45);
            int range = (bright ? 105 : 52) + random.nextInt(24);
            int color = light ? Color.WHITE : random.nextBoolean() ? 0xfff7e7ce : 0xffe9efff;
            stars[i] = new Star(random.nextFloat(), random.nextFloat(), radius,
                    2_800 + random.nextInt(8_400), random.nextDouble() * Math.PI * 2,
                    minimum, range, color);
        }
        nextShootingStar = 16_000 + random.nextInt(27_000);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        lastFrame = 0;
        if (visibility == VISIBLE) invalidate();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        lastFrame = 0;
        if (focused) invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean animate = hasWindowFocus() && getWindowVisibility() == VISIBLE
                && isShown() && ValueAnimator.areAnimatorsEnabled();
        long now = SystemClock.uptimeMillis();
        if (animate && lastFrame > 0) elapsed += Math.min(now - lastFrame, 2 * FRAME_MS);
        lastFrame = animate ? now : 0;

        float width = getWidth(), height = getHeight();
        for (Star star : stars) {
            float x = width * star.x(), y = height * star.y();
            double phase = elapsed * Math.PI * 2 / star.period() + star.phase();
            double pulse = (1.0 + Math.sin(phase)) / 2.0;
            int alpha = star.minimum() + (int) (star.range() * pulse * pulse);
            if (star.radius() >= density * 0.69f) {
                paint.setColor(haloColor);
                paint.setAlpha(light ? alpha / 15 : alpha / 7);
                canvas.drawCircle(x, y, star.radius() * 2.25f, paint);
            }
            paint.setColor(star.tint());
            paint.setAlpha(alpha);
            canvas.drawCircle(x, y, star.radius(), paint);
        }

        if (animate) {
            drawShootingStar(canvas, width, height);
            postInvalidateDelayed(FRAME_MS);
        }
    }

    private void drawShootingStar(Canvas canvas, float width, float height) {
        long age = elapsed - nextShootingStar;
        if (age < 0) return;
        if (age >= SHOOTING_STAR_MS) {
            nextShootingStar = elapsed + 35_000 + random.nextInt(80_000);
            shootingDx = 0;
            return;
        }
        if (shootingDx == 0) {
            boolean rightward = random.nextBoolean();
            shootingX = (rightward ? 0.04f : 0.69f) + random.nextFloat() * 0.22f;
            shootingY = 0.06f + random.nextFloat() * 0.76f;
            shootingDx = (rightward ? 1 : -1) * (0.23f + random.nextFloat() * 0.13f);
            shootingDy = 0.018f + random.nextFloat() * 0.042f;
        }
        float t = age / (float) SHOOTING_STAR_MS;
        float visible = (float) Math.sin(t * Math.PI);
        float dx = width * shootingDx, dy = height * shootingDy;
        float x = width * shootingX + t * dx, y = height * shootingY + t * dy;
        paint.setColor(light ? Color.WHITE : 0xfff5e8d7);
        paint.setStrokeWidth(0.62f * density);
        int pieces = 12;
        float trail = 0.65f * Math.min(1f, t * 7);
        for (int i = pieces - 1; i >= 0; i--) {
            float a = trail * i / pieces, b = trail * (i + 1) / pieces;
            float strength = 1f - i / (float) pieces;
            paint.setAlpha((int) (190 * visible * strength * strength));
            canvas.drawLine(x - a * dx, y - a * dy, x - b * dx, y - b * dy, paint);
        }
        paint.setAlpha((int) (218 * visible));
        canvas.drawCircle(x, y, 1.0f * density, paint);
    }
}
