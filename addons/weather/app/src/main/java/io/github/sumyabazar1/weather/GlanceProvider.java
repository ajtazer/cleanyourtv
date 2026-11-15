package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The weather and the air quality as one row for the Clean Your TV launcher's header (its header_weather key):
 * the launcher stays offline and only reads this. A reading is fetched here only when the launcher asks and the
 * last one is older than {@link Forecast#REFRESH_MS} (or {@link Forecast#RETRY_MS} after a failure), or the place
 * changed: no background service, no alarms. Read-only, and it holds only what Home shows anyway.
 */
public final class GlanceProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.sumyabazar1.weather.glance";
    static final Uri NOW = Uri.parse("content://" + AUTHORITY + "/now");
    private static final String[] COLUMNS = {"city", "temp", "sky", "aqi", "aqi_colour", "aqi_text_colour", "at_ms"};
    private static final String CACHE = "glance";
    /** A reading older than this is not shown at all, rather than shown wrong. */
    private static final long STALE_MS = 3 * 60 * 60_000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetching = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        if (!"now".equals(uri.getLastPathSegment())) return null;
        Context ctx = getContext();
        if (ctx == null) return null;
        MatrixCursor c = new MatrixCursor(COLUMNS);
        c.setNotificationUri(ctx.getContentResolver(), NOW);
        SharedPreferences place = ctx.getSharedPreferences("place", Context.MODE_PRIVATE);
        String name = place.getString("name", "");
        if (name.isEmpty() || !place.contains("lat")) return c;   // no place chosen yet: no line
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        String key = String.format(Locale.ROOT, "%.4f,%.4f", lat, lon);

        SharedPreferences cache = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long at = key.equals(cache.getString("key", "")) ? cache.getLong("at", 0) : 0;
        if (elapsedOver(now, at, Forecast.REFRESH_MS) && elapsedOver(now, cache.getLong("tried", 0), Forecast.RETRY_MS)) {
            fetch(ctx, lat, lon, key);
        }
        if (at == 0 || Math.abs(now - at) > STALE_MS) return c;

        boolean fahrenheit = ctx.getSharedPreferences("options", Context.MODE_PRIVATE).getBoolean("fahrenheit",
                "f".equalsIgnoreCase(ctx.getString(R.string.temperature_unit).trim()));
        int aqi = cache.getInt("aqi", -1);
        c.addRow(new Object[] {
                name,
                Forecast.degrees(Double.longBitsToDouble(cache.getLong("temp", 0)), fahrenheit),
                ctx.getString(Forecast.sky(cache.getInt("code", 3), cache.getBoolean("day", true))),
                aqi,
                aqi < 0 ? 0 : Forecast.aqiColour(aqi),
                aqi < 0 ? 0 : Forecast.aqiTextColour(aqi),
                at});
        return c;
    }

    /** More than {@code limit} ms since {@code then}, or {@code then} is ahead of a clock that moved back. */
    static boolean elapsedOver(long now, long then, long limit) {
        return now < then || now - then > limit;
    }

    private void fetch(Context ctx, double lat, double lon, String key) {
        if (!fetching.compareAndSet(false, true)) return;
        ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().putLong("tried", System.currentTimeMillis()).apply();
        io.execute(() -> {
            try {
                Forecast.Report r = Forecast.fetch(lat, lon, Forecast.MIN_DAYS);
                ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
                        .putString("key", key)
                        .putLong("at", System.currentTimeMillis())
                        .putLong("temp", Double.doubleToRawLongBits(r.tempC))
                        .putInt("code", r.code)
                        .putBoolean("day", r.day)
                        .putInt("aqi", r.aqi)
                        .commit();
                ctx.getContentResolver().notifyChange(NOW, null);
            } catch (Exception offline) {
                // Keep the last reading; the next query after RETRY_MS tries again.
            } finally {
                fetching.set(false);
            }
        });
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
