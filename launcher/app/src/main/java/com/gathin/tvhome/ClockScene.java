package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in; a
 * ClockRefresh keeps its TextClocks current where Android would not.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final ClockRefresh clockRefresh;
    private final ImageView ambient;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    /** clock_wallpaper or clock_glow false: something else (a picture, or plain black) replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** One number per decode request: a result whose number is no longer the current one is dropped. Main thread only. */
    private int wallpaperGeneration;
    /** clock_wallpaper with a collection: the pictures, how long each lasts, and how the next one is picked. */
    private List<File> rotation;
    private long rotationMs;
    private long shownMs;
    private String order;
    private Map<String, Set<String>> moods;
    private String latitude, longitude;
    /** header_weather: Home's weather line under the date; a reader lives from start() to stop() only. */
    private final TextView weather;
    private Context weatherCtx;
    private String weatherPkg = "";
    private HeaderWeather headerWeather;
    private int weatherTicks;
    /** clock_layout "corners" (layout dream_clock_corners): fixed corners instead of a drifting block. */
    private final boolean corners;
    private final TextView place, now, aqi, cricketView, cricketTitle, cricketStatus, festivalView, newsView, newsSource;
    private final LinearLayout forecastBox;
    private final SkyEffectView sky;
    /** clock_extras, clock_sky_effect, clock_news_feed and clock_festival_types, read by useExtras(). */
    private boolean showForecast, showFestival, showCricket, showNews, skyEffect, allFestivals;
    private String newsFeed = "";
    private ExecutorService feedsIo;
    private int cricketIndex, newsIndex;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!corners) moveClock();
            shiftGlow();
            advanceWallpaper();
            // A screensaver can stay up for hours: ask the add-on every 10 minutes, as Home does.
            if (headerWeather != null && ++weatherTicks % 10 == 0) headerWeather.read();
            if (corners) {
                showFestival();
                cricketIndex++;
                newsIndex++;
                showFeeds(true);
                refreshFeeds();
            }
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        clockRefresh = new ClockRefresh(content);
        ambient = root.findViewById(R.id.dreamAmbient);
        weather = root.findViewById(R.id.dreamWeather);
        place = root.findViewById(R.id.dreamPlace);
        now = root.findViewById(R.id.dreamNow);
        aqi = root.findViewById(R.id.dreamAqi);
        forecastBox = root.findViewById(R.id.dreamForecast);
        cricketView = root.findViewById(R.id.dreamCricket);
        cricketTitle = root.findViewById(R.id.dreamCricketTitle);
        cricketStatus = root.findViewById(R.id.dreamCricketStatus);
        festivalView = root.findViewById(R.id.dreamFestival);
        newsView = root.findViewById(R.id.dreamNews);
        newsSource = root.findViewById(R.id.dreamNewsSource);
        sky = root.findViewById(R.id.dreamSky);
        corners = place != null;
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            if (!corners) placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        clockRefresh.start();
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
        if (weatherCtx != null && headerWeather == null) {
            headerWeather = HeaderWeather.create(weatherCtx, weatherPkg, handler, this::showWeather);
            if (headerWeather != null) headerWeather.start();
        }
        if (corners) {
            showFestival();
            showFeeds(false);
            if (sky != null && skyEffect) sky.start();
            if ((showCricket || showNews || showFestival) && feedsIo == null) {
                feedsIo = Executors.newSingleThreadExecutor();
                refreshFeeds();
            }
        }
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        if (headerWeather != null) {
            headerWeather.shutdown();   // its thread goes with it: Home opens this scene again on every idle
            headerWeather = null;
        }
        if (feedsIo != null) {
            feedsIo.shutdownNow();
            feedsIo = null;
        }
        if (sky != null) sky.stop();
        clockRefresh.stop();
        content.animate().cancel();
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
    }

    /**
     * clock_wallpaper: put the picture Home is showing behind the clock, darkened by clock_wallpaper_dim, in
     * place of the glow, and carry a collection's rotation on while the screensaver is up. Decoded off the main
     * thread at screen size in RGB_565 (4 MB at 1080p), like Home's own; until then, or if it cannot be read,
     * the glow stays.
     * Call before start(), which starts the decode (again after a stop() that dropped an unfinished one).
     */
    void useHomeWallpaper(Context ctx) {
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        if (!cfg.clockWallpaper) {
            // clock_glow false: plain black behind the clock (OLED); the clock itself still moves every minute.
            if (!cfg.clockGlow) {
                wallpaperShown = true;   // also stops the glow's colour cycle
                ambient.setBackground(null);
            }
            return;
        }
        // What Home plays, and Config puts the single wallpaper in it when a theme has no collection.
        // Reading cfg.wallpaper first held the screensaver on wallpaper.jpg while Home rotated.
        if (cfg.wallpapers.isEmpty()) {
            wallpaperFile = null;
        } else {
            // Start on the picture Home is showing. WallpaperPlayer records it under the same
            // preferences and the screensaver runs in the same process, so this is just a read.
            rotation = new ArrayList<>(cfg.wallpapers);
            String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                    .getString("image", "");
            File current = WallpaperRotation.find(rotation, shown);
            wallpaperFile = current != null ? current : rotation.get(0);
            // Home only rotates while it is visible, so the screensaver carries the rotation on
            // itself; without this it holds one picture for as long as it is up.
            rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
            order = cfg.wallpaperOrder;
            moods = cfg.wallpaperMoods;
            latitude = moods.isEmpty() ? "" : ctx.getString(R.string.latitude);
            longitude = moods.isEmpty() ? "" : ctx.getString(R.string.longitude);
        }
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = ++wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();   // stopped meanwhile (or already shown): this picture was never attached
                    return;
                }
                wallpaperShown = true;
                // As the image, not the background: a background is stretched to the view, so a picture of
                // another shape than the screen would be distorted. centerCrop scales and crops it like Home.
                ambient.setBackground(null);
                ambient.setImageDrawable(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
            });
        }, "clock-wallpaper").start();
    }

    /** WallpaperPlayer's decode: sampled and scaled down to cover the screen, never bigger than 4K. */
    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Step to the next picture once its turn is up; the tick already runs every minute. */
    private void advanceWallpaper() {
        if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
        shownMs += MOVE_MS;
        if (shownMs < rotationMs) return;
        shownMs = 0;
        // The same pick as Home's, so a shuffled collection stays shuffled and wallpaper_moods still
        // decides what may show at this hour.
        File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
        if (next.equals(wallpaperFile)) return;   // a mood group of one
        wallpaperFile = next;
        wallpaperShown = false;
        loadWallpaper();
    }

    /** WallpaperPlayer's filter: the pictures wallpaper_moods allows at this hour, or all of them. */
    private List<File> playable() {
        if (moods.isEmpty()) return rotation;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
        return WallpaperMood.select(rotation, moods,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    /** header_weather: show Home's weather and AQI line under the date as well. Call before start(). */
    void useWeather(Context ctx) {
        try {
            weatherPkg = Config.load(ctx).headerWeather;
            weatherCtx = ctx;
        } catch (RuntimeException e) {
            weatherCtx = null;
        }
    }

    /** clock_extras and its companions, for the corners layout. Call before start(). */
    void useExtras(Context ctx) {
        try {
            Config cfg = Config.load(ctx);
            showForecast = cfg.clockExtras.contains("forecast");
            showFestival = cfg.clockExtras.contains("festival");
            showCricket = cfg.clockExtras.contains("cricket");
            showNews = cfg.clockExtras.contains("news");
            skyEffect = cfg.clockSkyEffect;
            newsFeed = cfg.clockNewsFeed;
            allFestivals = "all".equals(cfg.clockFestivalTypes);
        } catch (RuntimeException e) {
            // nothing extra
        }
    }

    /** Top-left: place, the weather now and the AQI pill; top-right: the forecast; and the sky effect for now. */
    private void showCorners(HeaderWeather.Glance g) {
        Locale locale = Locale.getDefault();
        boolean has = g != null;
        place.setText(has ? g.city.toUpperCase(locale) : "");
        place.setVisibility(has && !g.city.isEmpty() ? View.VISIBLE : View.GONE);
        now.setText(has ? (g.temp + "° " + g.sky).trim().toUpperCase(locale) : "");
        now.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has && g.aqi >= 0) {
            aqi.setText("AQI " + g.aqi);
            aqi.setTextColor(g.aqiTextColour);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(g.aqiColour);
            pill.setCornerRadius(4 * aqi.getResources().getDisplayMetrics().density);
            aqi.setBackground(pill);
            aqi.setVisibility(View.VISIBLE);
        } else {
            aqi.setVisibility(View.GONE);
        }
        forecastBox.removeAllViews();
        List<String[]> days = has && showForecast ? HeaderWeather.forecastDays(g.forecast)
                : Collections.<String[]>emptyList();
        for (String[] d : days) forecastBox.addView(forecastRow(d));
        forecastBox.setVisibility(days.isEmpty() ? View.GONE : View.VISIBLE);
        if (sky != null && skyEffect) {
            sky.setMode(has && g.code >= 0 ? SkyEffectView.modeFor(g.code, g.day) : SkyEffectView.NONE);
        }
    }

    /** One forecast row: "MON", its weather icon, the high, and the low dimmer. */
    private View forecastRow(String[] d) {
        Context ctx = forecastBox.getContext();
        float dp = ctx.getResources().getDisplayMetrics().density;
        Typeface medium = ctx.getResources().getFont(R.font.outfit_medium);
        Typeface regular = ctx.getResources().getFont(R.font.outfit_regular);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setPadding(0, (int) (2 * dp), 0, (int) (2 * dp));
        row.addView(label(ctx, d[0], 13, 0xB3FFFFFF, medium, 0.18f));
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconFor(Integer.parseInt(d[1])));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams((int) (20 * dp), (int) (20 * dp));
        ip.setMarginStart((int) (12 * dp));
        ip.setMarginEnd((int) (10 * dp));
        row.addView(icon, ip);
        TextView hi = label(ctx, d[2] + "°", 18, 0xFFFFFFFF, regular, 0.04f);
        hi.setMinEms(2);
        hi.setGravity(Gravity.END);
        row.addView(hi);
        TextView lo = label(ctx, d[3] + "°", 18, 0x99FFFFFF, regular, 0.04f);
        lo.setMinEms(2);
        lo.setGravity(Gravity.END);
        row.addView(lo);
        return row;
    }

    private static TextView label(Context ctx, String text, int sp, int colour, Typeface tf, float spacing) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(tf);
        t.setLetterSpacing(spacing);
        t.setSingleLine(true);
        return t;
    }

    /** A forecast day's icon from its WMO code. */
    private static int iconFor(int code) {
        if (code >= 95) return R.drawable.ic_wx_storm;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_wx_rain;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if (code == 3) return R.drawable.ic_wx_cloud;
        if (code == 1 || code == 2) return R.drawable.ic_wx_partly;
        return R.drawable.ic_wx_sun;
    }

    /** Bottom-left, second line: the next festival from Google's India holidays calendar. */
    private void showFestival() {
        if (festivalView == null) return;
        LocalDate today = LocalDate.now();
        LiveFeeds.Event e = showFestival ? LiveFeeds.nextFestival(LiveFeeds.festivals, today, allFestivals) : null;
        festivalView.setText(e == null ? "" : LiveFeeds.festivalText(e, today));
        festivalView.setVisibility(e == null ? View.GONE : View.VISIBLE);
    }

    /** Bottom-left: a live score; bottom-right: a headline and its source. Several take turns, a minute each. */
    private void showFeeds(boolean fade) {
        if (cricketView != null) {
            List<LiveFeeds.Match> on = showCricket ? LiveFeeds.matches : Collections.<LiveFeeds.Match>emptyList();
            LiveFeeds.Match m = on.isEmpty() ? null : on.get(cricketIndex % on.size());
            int shown = m == null ? View.GONE : View.VISIBLE;
            cricketView.setText(m == null ? "" : LiveFeeds.scoreLine(m));
            // The red dot means play is on right now, not just that the match is under way (stumps, tea...).
            cricketView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    m != null && LiveFeeds.isLive(m) ? R.drawable.dot_live : 0, 0, 0, 0);
            cricketView.setVisibility(shown);
            cricketTitle.setText(m == null ? "" : LiveFeeds.titleLine(m));
            cricketTitle.setVisibility(shown);
            String status = m == null ? "" : LiveFeeds.statusLine(m);
            cricketStatus.setText(status);
            cricketStatus.setVisibility(status.isEmpty() ? View.GONE : shown);
        }
        if (newsView == null) return;
        List<String[]> news = showNews ? LiveFeeds.headlines : Collections.<String[]>emptyList();
        if (news.isEmpty()) {
            newsView.setVisibility(View.GONE);
            newsSource.setVisibility(View.GONE);
            return;
        }
        String[] h = news.get(newsIndex % news.size());
        Runnable put = () -> {
            newsView.setText(h[0]);
            newsSource.setText(h[1].toUpperCase(Locale.getDefault()));
            newsView.setVisibility(View.VISIBLE);
            newsSource.setVisibility(h[1].isEmpty() ? View.GONE : View.VISIBLE);
        };
        View box = (View) newsView.getParent();
        if (!fade || newsView.getVisibility() != View.VISIBLE) {
            put.run();
            return;
        }
        box.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            put.run();
            box.animate().alpha(1f).setDuration(600).start();
        }).start();
    }

    private void refreshFeeds() {
        if (feedsIo == null) return;
        LiveFeeds.refresh(feedsIo, handler, showCricket, showNews ? newsFeed : "", showFestival, () -> {
            showFestival();
            showFeeds(false);
        });
    }

    private void showWeather() {
        HeaderWeather.Glance g = headerWeather == null ? null : headerWeather.glance();
        if (corners) {
            showCorners(g);
            return;
        }
        int was = weather.getVisibility();
        if (g == null) {
            weather.setVisibility(View.GONE);
        } else {
            weather.setText(HeaderWeather.format(g));
            weather.setVisibility(View.VISIBLE);
        }
        // The block just grew or shrank: place it again so the new line stays on screen.
        if (was != weather.getVisibility()) content.post(this::placeClock);
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        if (wallpaperShown) return;
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
