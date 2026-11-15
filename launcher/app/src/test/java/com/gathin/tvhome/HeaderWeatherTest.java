package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeaderWeatherTest {
    @Test public void fullReadingShowsPlaceTemperatureSkyAndAqiPill() {
        HeaderWeather.Glance g = new HeaderWeather.Glance("Karol Bagh", 25, "Clear", 158, 0xFFE53935, 0xFFFFFFFF);
        assertEquals("KAROL BAGH · 25° CLEAR    AQI 158 ", HeaderWeather.text(g));
        assertEquals("KAROL BAGH · 25° CLEAR   ".length(), HeaderWeather.pillStart(g));
    }

    @Test public void unknownAqiLeavesOutThePill() {
        HeaderWeather.Glance g = new HeaderWeather.Glance("Home", -3, "Snow", -1, 0, 0);
        assertEquals("HOME · -3° SNOW", HeaderWeather.text(g));
        assertEquals(-1, HeaderWeather.pillStart(g));
    }

    @Test public void missingPlaceAndSkyStillShowTheTemperature() {
        HeaderWeather.Glance g = new HeaderWeather.Glance(null, 30, null, 42, 0, 0);
        assertEquals("30°    AQI 42 ", HeaderWeather.text(g));
        assertEquals("30°   ".length(), HeaderWeather.pillStart(g));
    }
}
