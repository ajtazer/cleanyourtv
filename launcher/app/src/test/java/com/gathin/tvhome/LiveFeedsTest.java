package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class LiveFeedsTest {
    /** Titles as ESPNcricinfo's live-scores RSS printed them on 15 Nov 2026, plus two India lines. */
    @Test public void cricketKeepsTestNationsOnlyIndiaFirst() {
        List<String> lines = LiveFeeds.cricketLines(Arrays.asList(
                "Zimbabwe A 329/10 &amp;  214/3 * v Ireland A 551/10 ",
                "Afghanistan 217/10  v Zimbabwe 339/10 &amp;  169/7 *",
                "Hong Kong Cricket Club 293/7  v United Services Recreation Club 285/9 *",
                "Australia 220/10 * v England 355/6 ",
                "Pakistan Women 126/7  v Netherlands Women 127/2 *",
                "South Africa v Bangladesh 319/5 *",
                "India Women 100/2 * v England Women 150/5",
                "India 245/3 * v Australia 301/8"));
        assertEquals(Arrays.asList(
                "IND W 100/2* v ENG W 150/5",
                "IND 245/3* v AUS 301/8",
                "AFG 217/10 v ZIM 339/10 & 169/7*",
                "AUS 220/10* v ENG 355/6",
                "SA v BAN 319/5*"), lines);
    }

    @Test public void headlinesSplitTheSource() {
        List<String[]> h = LiveFeeds.headlines(Arrays.asList(
                "‘Abandoned tribals to Naxal violence’: PM Modi takes aim at previous govts - The Times of India",
                "Top stories - Google News",
                "A headline with no source",
                ""));
        assertEquals(2, h.size());
        assertArrayEquals(new String[] {"‘Abandoned tribals to Naxal violence’: PM Modi takes aim at previous govts",
                "The Times of India"}, h.get(0));
        assertArrayEquals(new String[] {"A headline with no source", ""}, h.get(1));
    }

    private static final String ICS = "BEGIN:VCALENDAR\r\n"
            + "BEGIN:VEVENT\r\nDTSTART;VALUE=DATE:20261124\r\nSUMMARY:Guru Tegh Bahadur's Martyrdom Day\r\n"
            + "DESCRIPTION:Observance\\nTo hide observances\\, go to Google Calendar Settings\r\nEND:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nDTSTART;VALUE=DATE:20261124\r\nSUMMARY:Guru Nanak Jayanti\r\n"
            + "DESCRIPTION:Public holiday\r\nEND:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nDTSTART;VALUE=DATE:20261115\r\nSUMMARY:Chhat Puja (Pratihar Sashthi/Surya\r\n"
            + "  Sashthi)\r\nDESCRIPTION:Observance\r\nEND:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nDTSTART;VALUE=DATE:20270310\r\nSUMMARY:Ramzan Id (tentative)\r\n"
            + "DESCRIPTION:Public holiday\\nThis is a tentative date\r\nEND:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nDTSTART;VALUE=DATE:20250101\r\nSUMMARY:Old Year\r\nDESCRIPTION:Public holiday\r\n"
            + "END:VEVENT\r\nEND:VCALENDAR\r\n";

    @Test public void icsEventsUnfoldUnescapeAndSortPublicFirst() throws Exception {
        List<LiveFeeds.Event> all = LiveFeeds.icsEvents(new BufferedReader(new StringReader(ICS)));
        assertEquals(5, all.size());
        assertEquals("Chhat Puja (Pratihar Sashthi/Surya Sashthi)", all.get(2).name);
        assertEquals("Ramzan Id", all.get(3).name);
        assertTrue(all.get(3).isPublic);

        LocalDate today = LocalDate.of(2026, 11, 15);
        List<LiveFeeds.Event> next = LiveFeeds.upcoming(all, today);
        assertEquals(4, next.size());   // 2025 is gone
        assertEquals("Chhat Puja (Pratihar Sashthi/Surya Sashthi)", next.get(0).name);
        assertEquals("Guru Nanak Jayanti", next.get(1).name);   // public first on the same day
        assertFalse(next.get(2).isPublic);
    }

    @Test public void nextFestivalAndItsWording() throws Exception {
        List<LiveFeeds.Event> next = LiveFeeds.upcoming(
                LiveFeeds.icsEvents(new BufferedReader(new StringReader(ICS))), LocalDate.of(2026, 11, 15));
        LocalDate today = LocalDate.of(2026, 11, 15);
        LiveFeeds.Event publicOnly = LiveFeeds.nextFestival(next, today, false);
        assertEquals("GURU NANAK JAYANTI · IN 9 DAYS", LiveFeeds.festivalText(publicOnly, today));
        LiveFeeds.Event any = LiveFeeds.nextFestival(next, today, true);
        assertEquals("TODAY · CHHAT PUJA (PRATIHAR SASHTHI/SURYA SASHTHI)", LiveFeeds.festivalText(any, today));
        assertEquals("GURU NANAK JAYANTI · TOMORROW",
                LiveFeeds.festivalText(publicOnly, LocalDate.of(2026, 11, 23)));
        assertNull(LiveFeeds.nextFestival(next, LocalDate.of(2028, 1, 1), true));
    }
}
