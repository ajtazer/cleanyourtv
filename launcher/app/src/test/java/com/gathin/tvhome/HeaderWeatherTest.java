package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class HeaderWeatherTest {
    private static HeaderWeather.Glance glance(String city, int temp, String sky, int aqi) {
        return new HeaderWeather.Glance(city, temp, sky, aqi, 0xFFE53935, 0xFFFFFFFF, 0, true, "");
    }

    @Test public void fullReadingShowsPlaceTemperatureSkyAndAqiPill() {
        HeaderWeather.Glance g = glance("Karol Bagh", 25, "Clear", 158);
        assertEquals("KAROL BAGH · 25° CLEAR    AQI 158 ", HeaderWeather.text(g));
        assertEquals("KAROL BAGH · 25° CLEAR   ".length(), HeaderWeather.pillStart(g));
    }

    @Test public void unknownAqiLeavesOutThePill() {
        HeaderWeather.Glance g = glance("Home", -3, "Snow", -1);
        assertEquals("HOME · -3° SNOW", HeaderWeather.text(g));
        assertEquals(-1, HeaderWeather.pillStart(g));
    }

    @Test public void missingPlaceAndSkyStillShowTheTemperature() {
        HeaderWeather.Glance g = glance(null, 30, null, 42);
        assertEquals("30°    AQI 42 ", HeaderWeather.text(g));
        assertEquals("30°   ".length(), HeaderWeather.pillStart(g));
    }

    @Test public void forecastRowsAreParsedAndBadRowsSkipped() {
        List<String[]> days = HeaderWeather.forecastDays("MON|0|27|15;TUE|3|26|14;bad;WED|x|1|2;THU|61|-1|-5");
        assertEquals(3, days.size());
        assertArrayEquals(new String[] {"MON", "0", "27", "15"}, days.get(0));
        assertArrayEquals(new String[] {"TUE", "3", "26", "14"}, days.get(1));
        assertArrayEquals(new String[] {"THU", "61", "-1", "-5"}, days.get(2));
        assertEquals(0, HeaderWeather.forecastDays("").size());
        assertEquals(0, HeaderWeather.forecastDays(null).size());
    }
}
