package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * clock_sky_effect: a light, continuous weather layer over the screensaver's wallpaper, chosen from the Weather
 * add-on's WMO code and day or night: rain streaks (with a dim flash now and then in a thunderstorm), snowflakes,
 * slow fog bands, a few drifting clouds, or twinkling stars on a clear night; nothing on a clear day. At most
 * {@value #MAX} particles, redrawn at a rate that suits the effect (rain 30 fps, stars 10), a hardware layer only
 * while running, and nothing allocated per frame. Takes no focus and no accessibility.
 */
public final class SkyEffectView extends View {
    static final int NONE = 0, RAIN = 1, STORM = 2, SNOW = 3, FOG = 4, CLOUDS = 5, STARS = 6;
    private static final int MAX = 60;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float[] px = new float[MAX];
    private final float[] py = new float[MAX];
    private final float[] speed = new float[MAX];
    private final float[] size = new float[MAX];
    private final float[] phase = new float[MAX];
    private final Runnable frame = this::invalidate;
    private int mode = NONE;
    private int count;
    private boolean running;
    private long last;
    private long nextFlash;
    private float flash;

    public SkyEffectView(Context context) {
        this(context, null);
    }

    public SkyEffectView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** The effect for an Open-Meteo WMO weather code, by day or by night. */
    static int modeFor(int code, boolean day) {
        if (code >= 95) return STORM;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code == 45 || code == 48) return FOG;
        if (code == 2 || code == 3) return CLOUDS;
        if (code == 0 || code == 1) return day ? NONE : STARS;
        return NONE;
    }

    void setMode(int m) {
        if (m == mode) return;
        mode = m;
        seed();
        setVisibility(m == NONE ? GONE : VISIBLE);
        if (running && m != NONE) kick();
    }

    void start() {
        running = true;
        if (mode != NONE) kick();
    }

    void stop() {
        running = false;
        removeCallbacks(frame);
        setLayerType(LAYER_TYPE_NONE, null);
    }

    private void kick() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        removeCallbacks(frame);
        last = 0;
        invalidate();
    }

    private long frameMs() {
        switch (mode) {
            case STARS: return 100;
            case FOG: case CLOUDS: return 66;
            default: return 33;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        seed();
    }

    private void seed() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        switch (mode) {
            case RAIN: case STORM: count = 50; break;
            case SNOW: count = 45; break;
            case FOG: case CLOUDS: count = 4; break;
            case STARS: count = MAX; break;
            default: count = 0;
        }
        for (int i = 0; i < count; i++) {
            px[i] = random.nextFloat() * w;
            py[i] = random.nextFloat() * h;
            phase[i] = random.nextFloat() * 6.283f;
            switch (mode) {
                case RAIN: case STORM:   // speed in px/s, size = streak length
                    speed[i] = h * (0.9f + random.nextFloat() * 0.6f);
                    size[i] = h * (0.025f + random.nextFloat() * 0.03f);
                    break;
                case SNOW:
                    speed[i] = h * (0.04f + random.nextFloat() * 0.06f);
                    size[i] = 1.5f + random.nextFloat() * 2.5f;
                    break;
                case FOG:
                    speed[i] = w * (0.004f + random.nextFloat() * 0.006f);
                    size[i] = h * (0.06f + random.nextFloat() * 0.08f);
                    py[i] = h * (0.25f + 0.6f * random.nextFloat());
                    break;
                case CLOUDS:
                    speed[i] = w * (0.003f + random.nextFloat() * 0.004f);
                    size[i] = h * (0.10f + random.nextFloat() * 0.08f);
                    py[i] = h * (0.05f + 0.35f * random.nextFloat());
                    break;
                case STARS:   // speed = twinkle rate, only in the upper sky
                    speed[i] = 0.6f + random.nextFloat() * 1.6f;
                    size[i] = 0.8f + random.nextFloat() * 1.4f;
                    py[i] = random.nextFloat() * h * 0.65f;
                    break;
                default:
                    break;
            }
        }
        nextFlash = SystemClock.uptimeMillis() + 6000 + random.nextInt(8000);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (mode == NONE || count == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(0.12f, (now - last) / 1000f);
        last = now;
        int w = getWidth();
        int h = getHeight();
        float t = now / 1000f;
        switch (mode) {
            case RAIN: case STORM:
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f);
                paint.setColor(0x59FFFFFF);
                for (int i = 0; i < count; i++) {
                    py[i] += speed[i] * dt;
                    px[i] -= speed[i] * dt * 0.12f;
                    if (py[i] > h) {
                        py[i] = -size[i];
                        px[i] = random.nextFloat() * (w + h * 0.15f);
                    }
                    c.drawLine(px[i], py[i], px[i] - size[i] * 0.12f, py[i] + size[i], paint);
                }
                if (mode == STORM) {
                    if (now >= nextFlash) {
                        flash = 0.16f;
                        nextFlash = now + 7000 + random.nextInt(9000);
                    }
                    if (flash > 0) {
                        c.drawColor(((int) (flash * 255) << 24) | 0xFFFFFF);
                        flash = Math.max(0f, flash - dt * 0.6f);
                    }
                }
                break;
            case SNOW:
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xCCFFFFFF);
                for (int i = 0; i < count; i++) {
                    py[i] += speed[i] * dt;
                    px[i] += (float) Math.sin(t * 0.8f + phase[i]) * 12f * dt;
                    if (py[i] > h + 4) {
                        py[i] = -4;
                        px[i] = random.nextFloat() * w;
                    }
                    c.drawCircle(px[i], py[i], size[i], paint);
                }
                break;
            case FOG:
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x14FFFFFF);
                for (int i = 0; i < count; i++) {
                    px[i] += speed[i] * dt;
                    if (px[i] - w * 0.3f > w) px[i] = -w * 0.3f;
                    c.drawRoundRect(px[i] - w * 0.3f, py[i], px[i] + w * 0.3f, py[i] + size[i],
                            size[i] / 2, size[i] / 2, paint);
                }
                break;
            case CLOUDS:
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x10FFFFFF);
                for (int i = 0; i < count; i++) {
                    float r = size[i];
                    px[i] += speed[i] * dt;
                    if (px[i] - r * 2.2f > w) px[i] = -r * 2.2f;
                    c.drawCircle(px[i], py[i], r, paint);
                    c.drawCircle(px[i] + r * 1.1f, py[i] + r * 0.2f, r * 0.8f, paint);
                    c.drawCircle(px[i] - r * 1.0f, py[i] + r * 0.3f, r * 0.7f, paint);
                }
                break;
            case STARS:
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < count; i++) {
                    float a = 0.35f + 0.45f * (0.5f + 0.5f * (float) Math.sin(t * speed[i] + phase[i]));
                    paint.setColor(((int) (a * 255) << 24) | 0xFFFFFF);
                    c.drawCircle(px[i], py[i], size[i], paint);
                }
                break;
            default:
                break;
        }
        if (running) postDelayed(frame, frameMs());
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
