package com.gathin.tvhome;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * clock_extras' live lines on the corners screensaver, fetched by the launcher (this fork has INTERNET) only while
 * the screensaver is up, and kept for the process so the next one shows them at once:
 * <ul>
 * <li>cricket from ESPN's public scoreboard JSON (no key), every 3 minutes: ongoing matches between the 12 Test
 * nations, India first, with the match's name, its real state (play on, or stumps, lunch, tea...) and summary;</li>
 * <li>headlines from an RSS feed (clock_news_feed, Google News India by default), every 15 minutes;</li>
 * <li>the next festival from Google's public "Holidays in India" calendar (iCal), once a day: no dates are
 * written in this code.</li>
 * </ul>
 * The parsing is plain Java (LiveFeedsTest); only {@link #refresh} touches the network.
 */
final class LiveFeeds {
    static final String CRICKET_URL = "https://site.api.espn.com/apis/site/v2/sports/cricket/scorepanel";
    static final String FESTIVALS_URL =
            "https://calendar.google.com/calendar/ical/en.indian%23holiday%40group.v.calendar.google.com/public/basic.ics";
    private static final long CRICKET_MS = 3 * 60_000L;
    private static final long NEWS_MS = 15 * 60_000L;
    private static final long FESTIVALS_MS = 24 * 60 * 60_000L;
    private static final long RETRY_MS = 60_000L;
    private static final int MAX_EVENTS = 200;

    /** The 12 Test nations as ESPN names them; a women's side is the same name plus " Women". */
    private static final List<String> NATIONS = java.util.Arrays.asList("India", "Australia", "England", "Pakistan",
            "South Africa", "New Zealand", "Sri Lanka", "Bangladesh", "Afghanistan", "West Indies", "Ireland", "Zimbabwe");
    /** ESPN's status words for an ongoing match with no play just now: no live dot for these. */
    private static final String[] PAUSES = {"stumps", "lunch", "tea", "drinks", "break", "rain", "delay", "interrupt",
            "bad light", "stopped", "suspend", "abandon"};

    /** One match from ESPN's scoreboard: names, state ("pre", "in", "post"), status words and the two sides. */
    static final class Match {
        final String name;
        final String description;
        final String state;
        final String status;
        final String session;
        final String summary;
        /** {display name, abbreviation, score} for each side, in ESPN's order. */
        final String[][] sides;

        Match(String name, String description, String state, String status, String session, String summary,
              String[][] sides) {
            this.name = name;
            this.description = description;
            this.state = state;
            this.status = status;
            this.session = session;
            this.summary = summary;
            this.sides = sides;
        }
    }

    /** One calendar day: its date, its name, and whether Google marks it a public holiday. */
    static final class Event {
        final LocalDate date;
        final String name;
        final boolean isPublic;

        Event(LocalDate date, String name, boolean isPublic) {
            this.date = date;
            this.name = name;
            this.isPublic = isPublic;
        }
    }

    static volatile List<Match> matches = Collections.emptyList();
    static volatile List<String[]> headlines = Collections.emptyList();
    static volatile List<Event> festivals = Collections.emptyList();
    private static volatile long cricketAt, newsAt, festivalsAt, cricketTried, newsTried, festivalsTried;

    private LiveFeeds() {
    }

    /** Fetches whatever is due on io, then runs done on main if anything changed. A failed fetch keeps the old lines. */
    static void refresh(ExecutorService io, Handler main, boolean wantCricket, String newsUrl, boolean wantFestivals,
                        Runnable done) {
        long now = SystemClock.elapsedRealtime();
        boolean c = wantCricket && due(now, cricketAt, cricketTried, CRICKET_MS);
        boolean n = newsUrl != null && !newsUrl.isEmpty() && due(now, newsAt, newsTried, NEWS_MS);
        boolean f = wantFestivals && due(now, festivalsAt, festivalsTried, FESTIVALS_MS);
        if (!c && !n && !f) return;
        if (c) cricketTried = now;
        if (n) newsTried = now;
        if (f) festivalsTried = now;
        try {
            io.execute(() -> {
                boolean changed = false;
                if (c) {
                    try {
                        matches = ongoing(espnMatches(readAll(CRICKET_URL)));
                        cricketAt = SystemClock.elapsedRealtime();
                        changed = true;
                    } catch (Exception offline) {
                        // keep the last score
                    }
                }
                if (n) {
                    try {
                        headlines = headlines(rssTitles(newsUrl));
                        newsAt = SystemClock.elapsedRealtime();
                        changed = true;
                    } catch (Exception offline) {
                        // keep the last headlines
                    }
                }
                if (f) {
                    try (BufferedReader in = open(FESTIVALS_URL)) {
                        festivals = upcoming(icsEvents(in), LocalDate.now());
                        festivalsAt = SystemClock.elapsedRealtime();
                        changed = true;
                    } catch (Exception offline) {
                        // keep the last calendar
                    }
                }
                if (changed) main.post(done);
            });
        } catch (RejectedExecutionException shutDown) {
            // the screensaver stopped first
        }
    }

    private static boolean due(long now, long at, long tried, long every) {
        return (at == 0 || now - at > every) && (tried == 0 || now - tried > RETRY_MS);
    }

    private static InputStream get(String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(10_000);
        http.setReadTimeout(10_000);
        http.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)");
        if (http.getResponseCode() != 200) {
            http.disconnect();
            throw new IOException("HTTP " + http.getResponseCode());
        }
        return http.getInputStream();
    }

    private static BufferedReader open(String url) throws IOException {
        return new BufferedReader(new InputStreamReader(get(url), StandardCharsets.UTF_8));
    }

    /** The titles of an RSS feed's items (not the channel's own title). */
    private static List<String> rssTitles(String url) throws Exception {
        List<String> out = new ArrayList<>();
        try (InputStream in = get(url)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            boolean inItem = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    if ("item".equals(p.getName())) inItem = true;
                    else if (inItem && "title".equals(p.getName())) out.add(p.nextText());
                } else if (e == XmlPullParser.END_TAG && "item".equals(p.getName())) {
                    inItem = false;
                }
            }
        }
        return out;
    }

    private static String readAll(String url) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader in = open(url)) {
            char[] buf = new char[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.append(buf, 0, n);
        }
        return out.toString();
    }

    /** Every two-sided match on ESPN's cricket scoreboard JSON (scores[].events[]). */
    static List<Match> espnMatches(String json) throws JSONException {
        List<Match> out = new ArrayList<>();
        JSONArray scores = new JSONObject(json).optJSONArray("scores");
        for (int b = 0; scores != null && b < scores.length(); b++) {
            JSONArray events = scores.getJSONObject(b).optJSONArray("events");
            for (int i = 0; events != null && i < events.length(); i++) {
                JSONObject e = events.getJSONObject(i);
                JSONObject status = e.optJSONObject("status");
                JSONObject type = status == null ? null : status.optJSONObject("type");
                JSONArray comps = e.optJSONArray("competitions");
                if (type == null || comps == null || comps.length() == 0) continue;
                JSONObject c = comps.getJSONObject(0);
                JSONArray sides = c.optJSONArray("competitors");
                if (sides == null || sides.length() != 2) continue;
                String[][] s = new String[2][];
                for (int k = 0; k < 2; k++) {
                    JSONObject side = sides.getJSONObject(k);
                    JSONObject team = side.optJSONObject("team");
                    s[k] = new String[] {team == null ? "" : team.optString("displayName"),
                            team == null ? "" : team.optString("abbreviation"), side.optString("score")};
                }
                out.add(new Match(e.optString("name"), c.optString("description"), type.optString("state"),
                        type.optString("description"), status.optString("session"), status.optString("summary"), s));
            }
        }
        return out;
    }

    /** Ongoing matches (live, or paused at stumps, lunch...) between the 12 Test nations, India first. */
    static List<Match> ongoing(List<Match> all) {
        List<Match> india = new ArrayList<>();
        List<Match> rest = new ArrayList<>();
        for (Match m : all) {
            if (!"in".equals(m.state) || !nation(m.sides[0][0]) || !nation(m.sides[1][0])) continue;
            boolean ind = m.sides[0][0].startsWith("India") || m.sides[1][0].startsWith("India");
            (ind ? india : rest).add(m);
        }
        india.addAll(rest);
        return india;
    }

    private static boolean nation(String name) {
        String n = name.endsWith(" Women") ? name.substring(0, name.length() - " Women".length()) : name;
        return NATIONS.contains(n.trim());
    }

    /** Play is on right now: ongoing and not at stumps, lunch, tea, a break or a delay. Drives the red dot. */
    static boolean isLive(Match m) {
        if (!"in".equals(m.state)) return false;
        String s = m.status.toLowerCase(Locale.ROOT);
        for (String p : PAUSES) if (s.contains(p)) return false;
        return true;
    }

    /** "2ND TEST · AFGHANISTAN V ZIMBABWE": which match this is. */
    static String titleLine(Match m) {
        String t = m.description.isEmpty() ? m.name : m.description + " · " + m.name;
        return t.toUpperCase(Locale.ROOT);
    }

    /** "AFG 217 v ZIM 339 & 169/7 (58 ov)": both sides, in ESPN's order. */
    static String scoreLine(Match m) {
        return side(m.sides[0]) + " v " + side(m.sides[1]);
    }

    private static String side(String[] s) {
        String code = s[1].isEmpty() ? s[0] : s[1];
        return s[2].isEmpty() ? code : code + " " + s[2];
    }

    /** "STUMPS · DAY 3 · ZIMBABWE LEAD BY 291 RUNS"; the pause word only when play is not on. */
    static String statusLine(Match m) {
        List<String> parts = new ArrayList<>();
        if (!isLive(m) && !m.status.isEmpty()) parts.add(m.status);
        if (!m.session.isEmpty()) parts.add(m.session);
        if (!m.summary.isEmpty()) parts.add(m.summary);
        return String.join(" · ", parts).toUpperCase(Locale.ROOT);
    }

    /** "Headline - Source" as {headline, source}; a title with no source keeps an empty one. */
    static List<String[]> headlines(List<String> titles) {
        List<String[]> out = new ArrayList<>();
        for (String raw : titles) {
            String t = raw.replace("&amp;", "&").trim();
            if (t.isEmpty() || t.endsWith("- Google News")) continue;
            int dash = t.lastIndexOf(" - ");
            out.add(dash > 0 ? new String[] {t.substring(0, dash).trim(), t.substring(dash + 3).trim()}
                    : new String[] {t, ""});
        }
        return out;
    }

    /** Every all-day VEVENT in an iCal stream: DTSTART, SUMMARY, and "Public holiday" in DESCRIPTION. */
    static List<Event> icsEvents(BufferedReader in) throws IOException {
        List<Event> out = new ArrayList<>();
        String date = null, summary = null;
        boolean isPublic = false;
        String line = in.readLine();
        while (line != null) {
            // RFC 5545 folding: a line that starts with a space or tab continues the one before
            StringBuilder logical = new StringBuilder(line);
            String next;
            while ((next = in.readLine()) != null && (next.startsWith(" ") || next.startsWith("\t"))) {
                logical.append(next, 1, next.length());
            }
            String l = logical.toString();
            if (l.equals("BEGIN:VEVENT")) {
                date = null;
                summary = null;
                isPublic = false;
            } else if (l.startsWith("DTSTART")) {
                date = l.substring(l.indexOf(':') + 1).trim();
            } else if (l.startsWith("SUMMARY:")) {
                summary = unescape(l.substring(8)).trim();
            } else if (l.startsWith("DESCRIPTION:")) {
                isPublic = l.substring(12).startsWith("Public holiday");
            } else if (l.equals("END:VEVENT") && date != null && date.length() >= 8 && summary != null
                    && !summary.isEmpty()) {
                try {
                    out.add(new Event(LocalDate.of(Integer.parseInt(date.substring(0, 4)),
                            Integer.parseInt(date.substring(4, 6)), Integer.parseInt(date.substring(6, 8))),
                            summary.replace(" (tentative)", ""), isPublic));
                } catch (RuntimeException badDate) {
                    // skip it
                }
            }
            line = next;
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").replace("\\N", " ").replace("\\\\", "\\");
    }

    /** Events from today on, by date, a public holiday before an observance on the same day. */
    static List<Event> upcoming(List<Event> events, LocalDate today) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) if (!e.date.isBefore(today)) out.add(e);
        out.sort(Comparator.comparing((Event e) -> e.date).thenComparing(e -> !e.isPublic));
        return out.size() > MAX_EVENTS ? new ArrayList<>(out.subList(0, MAX_EVENTS)) : out;
    }

    /** The first event from today on: public holidays only, or every observance too with all. */
    static Event nextFestival(List<Event> events, LocalDate today, boolean all) {
        for (Event e : events) if (!e.date.isBefore(today) && (all || e.isPublic)) return e;
        return null;
    }

    /** "TODAY · DIWALI", "HOLI · TOMORROW" or "GURU NANAK JAYANTI · IN 9 DAYS". */
    static String festivalText(Event e, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, e.date);
        String name = e.name.toUpperCase(Locale.ROOT);
        if (days <= 0) return "TODAY · " + name;
        if (days == 1) return name + " · TOMORROW";
        return name + " · IN " + days + " DAYS";
    }
}
