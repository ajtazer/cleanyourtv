package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * header_weather: the weather and the air quality on a line under the date, from an add-on that serves one row
 * (the Weather add-on's GlanceProvider). The launcher only reads and never goes online: the add-on does the
 * fetching. The owner opts in by naming the add-on's package, which must name a provider of its own in a
 * {@value #META} meta-data. Same lifecycle as {@link GameDay}: watched only while Home is on screen.
 */
final class HeaderWeather {
    static final String META = "com.gathin.tvhome.glance";

    /** One reading, worded by the add-on (its sky in the TV's language, its AQI colours). */
    static final class Glance {
        final String city;
        final int temp;
        final String sky;
        /** US AQI, or -1 when unknown. */
        final int aqi;
        final int aqiColour;
        final int aqiTextColour;
        /** The WMO weather code now (-1 when not sent) and day or night: the screensaver's icon and sky effect. */
        final int code;
        final boolean day;
        /** The next days as "MON|0|27|15;TUE|3|26|14", or "": the corners screensaver's forecast. */
        final String forecast;

        Glance(String city, int temp, String sky, int aqi, int aqiColour, int aqiTextColour, int code, boolean day,
               String forecast) {
            this.city = city == null ? "" : city;
            this.temp = temp;
            this.sky = sky == null ? "" : sky;
            this.aqi = aqi;
            this.aqiColour = aqiColour;
            this.aqiTextColour = aqiTextColour;
            this.code = code;
            this.day = day;
            this.forecast = forecast == null ? "" : forecast;
        }
    }

    /** The add-on's "MON|0|27|15;TUE|3|26|14" as {day, code, high, low} rows; a malformed row is skipped. */
    static java.util.List<String[]> forecastDays(String forecast) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (forecast == null || forecast.isEmpty()) return out;
        for (String row : forecast.split(";")) {
            String[] f = row.split("\\|");
            if (f.length != 4 || f[0].trim().isEmpty()) continue;
            try {
                Integer.parseInt(f[1].trim());
                Integer.parseInt(f[2].trim());
                Integer.parseInt(f[3].trim());
                out.add(new String[] {f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim()});
            } catch (NumberFormatException skip) {
                // not a forecast row
            }
        }
        return out;
    }

    private final Context ctx;
    private final Uri uri;
    private final Handler main;
    private final Runnable onChange;
    private final ContentObserver observer;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean watching;
    private Glance glance;

    private HeaderWeather(Context ctx, Uri uri, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.uri = uri;
        this.main = main;
        this.onChange = onChange;
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                read();
            }
        };
    }

    /** Null when the key is empty, the package is not installed, or it names no provider of its own. */
    static HeaderWeather create(Context ctx, String pkg, Handler main, Runnable onChange) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg.trim(), PackageManager.GET_META_DATA);
            String authority = info.metaData == null ? null : info.metaData.getString(META);
            if (authority == null || authority.trim().isEmpty()) return null;
            // The provider must be the named package's own, not another app's that claims the authority.
            ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
            if (provider == null || !pkg.trim().equals(provider.packageName)) return null;
            Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority.trim()).appendPath("now").build();
            return new HeaderWeather(ctx, uri, main, onChange);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Home is on screen: read now and watch for changes. */
    void start() {
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(uri, false, observer);
                watching = true;
            } catch (SecurityException e) {
                // The add-on's provider went away; the line keeps what it had.
            }
        }
        read();
    }

    /** Home is off screen: nothing runs until it is back. */
    void stop() {
        if (watching) ctx.getContentResolver().unregisterContentObserver(observer);
        watching = false;
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    /** The last reading, or null: no place chosen yet, a stale reading, or no answer at all. */
    Glance glance() {
        return glance;
    }

    /** One query off the main thread (the provider lives in the add-on's process), then onChange on the main thread. */
    void read() {
        try {
            io.execute(() -> {
                Glance g = null;
                try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int temp = c.getColumnIndex("temp");
                        if (temp >= 0 && !c.isNull(temp)) {
                            g = new Glance(str(c, c.getColumnIndex("city")), c.getInt(temp), str(c, c.getColumnIndex("sky")),
                                    num(c, c.getColumnIndex("aqi"), -1), num(c, c.getColumnIndex("aqi_colour"), 0),
                                    num(c, c.getColumnIndex("aqi_text_colour"), 0xFFFFFFFF),
                                    num(c, c.getColumnIndex("code"), -1), num(c, c.getColumnIndex("day"), 1) != 0,
                                    str(c, c.getColumnIndex("forecast")));
                        }
                    }
                } catch (RuntimeException gone) {
                    // Uninstalled or crashed mid-query: keep what is on screen.
                    return;
                }
                Glance result = g;
                main.post(() -> {
                    glance = result;
                    onChange.run();
                });
            });
        } catch (RejectedExecutionException shutDown) {
            // onDestroy ran first.
        }
    }

    private static String str(Cursor c, int i) {
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static int num(Cursor c, int i, int fallback) {
        return i < 0 || c.isNull(i) ? fallback : c.getInt(i);
    }

    /** "KAROL BAGH · 25° CLEAR   AQI 158": the place and sky in capitals like the date above; no AQI when unknown. */
    static String text(Glance g) {
        StringBuilder s = new StringBuilder();
        if (!g.city.isEmpty()) s.append(g.city.toUpperCase(Locale.getDefault())).append(" · ");
        s.append(g.temp).append('°');
        if (!g.sky.isEmpty()) s.append(' ').append(g.sky.toUpperCase(Locale.getDefault()));
        if (g.aqi >= 0) s.append("   ").append(PILL_PAD).append("AQI ").append(g.aqi).append(PILL_PAD);
        return s.toString();
    }

    /** Where the AQI pill starts in {@link #text}, or -1 when there is none. */
    static int pillStart(Glance g) {
        if (g.aqi < 0) return -1;
        String t = text(g);
        return t.length() - (PILL_PAD + "AQI " + g.aqi + PILL_PAD).length();
    }

    /** {@link #text} with the AQI drawn as a pill in its US AQI band's colours. */
    static CharSequence format(Glance g) {
        String t = text(g);
        int start = pillStart(g);
        if (start < 0) return t;
        SpannableString s = new SpannableString(t);
        s.setSpan(new BackgroundColorSpan(g.aqiColour), start, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(g.aqiTextColour), start, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** A thin space each side of the pill's text, so the colour does not touch the letters. */
    private static final String PILL_PAD = " ";
}
