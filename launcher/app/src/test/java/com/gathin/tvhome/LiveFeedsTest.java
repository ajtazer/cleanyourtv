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
    private static LiveFeeds.Match match(String name, String desc, String state, String status, String session,
                                         String summary, String a, String aCode, String aScore, String b,
                                         String bCode, String bScore) {
        return new LiveFeeds.Match(name, desc, state, status, session, summary,
                new String[][] {{a, aCode, aScore}, {b, bCode, bScore}});
    }

    /** Afghanistan v Zimbabwe is ESPN's scoreboard on 15 Nov 2026 (2nd Test, stumps on day 3). */
    private static final LiveFeeds.Match AFG_ZIM = match("Afghanistan v Zimbabwe", "2nd Test", "in", "Stumps",
            "Day 3", "Zimbabwe lead by 291 runs", "Afghanistan", "AFG", "217", "Zimbabwe", "ZIM",
            "339 & 169/7 (58 ov)");

    @Test public void ongoingKeepsTestNationsOnlyIndiaFirst() {
        LiveFeeds.Match indWomen = match("India Women v England Women", "1st ODI", "in", "Live", "", "",
                "India Women", "IND-W", "100/2 (20 ov)", "England Women", "ENG-W", "");
        List<LiveFeeds.Match> on = LiveFeeds.ongoing(Arrays.asList(
                match("Zimbabwe A v Ireland A", "2nd unofficial Test", "in", "Live", "", "", "Zimbabwe A", "ZIM-A",
                        "329 & 214/3", "Ireland A", "IRE-A", "551"),
                match("Emirates Eagles v UAE Bulls", "20th Match", "in", "Live", "", "", "Emirates Eagles", "EME",
                        "123/4", "UAE Bulls", "UB", "100/5"),
                AFG_ZIM,
                match("Australia v England", "1st Test", "post", "Final", "", "", "Australia", "AUS", "220",
                        "England", "ENG", "355/6"),
                indWomen));
        assertEquals(2, on.size());
        assertEquals(indWomen, on.get(0));   // India first, then the rest in ESPN's order
        assertEquals(AFG_ZIM, on.get(1));    // A sides, leagues and finished matches are left out
    }

    @Test public void stumpsIsOngoingButNotLive() {
        assertFalse(LiveFeeds.isLive(AFG_ZIM));
        assertEquals("2ND TEST · AFGHANISTAN V ZIMBABWE", LiveFeeds.titleLine(AFG_ZIM));
        assertEquals("AFG 217 v ZIM 339 & 169/7 (58 ov)", LiveFeeds.scoreLine(AFG_ZIM));
        assertEquals("STUMPS · DAY 3 · ZIMBABWE LEAD BY 291 RUNS", LiveFeeds.statusLine(AFG_ZIM));

        LiveFeeds.Match playing = match("India v Australia", "3rd T20I", "in", "Live", "", "India need 42 from 18 balls",
                "India", "IND", "160/4 (17 ov)", "Australia", "AUS", "201/6");
        assertTrue(LiveFeeds.isLive(playing));
        assertEquals("INDIA NEED 42 FROM 18 BALLS", LiveFeeds.statusLine(playing));
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
