package com.gathin.tvhome;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Xml;

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
 * <li>cricket from ESPNcricinfo's keyless live-scores RSS, every 3 minutes: live internationals between the
 * 12 Test nations, India first;</li>
 * <li>headlines from an RSS feed (clock_news_feed, Google News India by default), every 15 minutes;</li>
 * <li>the next festival from Google's public "Holidays in India" calendar (iCal), once a day: no dates are
 * written in this code.</li>
 * </ul>
 * The parsing is plain Java (LiveFeedsTest); only {@link #refresh} touches the network.
 */
final class LiveFeeds {
    static final String CRICKET_URL = "https://static.cricinfo.com/rss/livescores.xml";
    static final String FESTIVALS_URL =
            "https://calendar.google.com/calendar/ical/en.indian%23holiday%40group.v.calendar.google.com/public/basic.ics";
    private static final long CRICKET_MS = 3 * 60_000L;
    private static final long NEWS_MS = 15 * 60_000L;
    private static final long FESTIVALS_MS = 24 * 60 * 60_000L;
    private static final long RETRY_MS = 60_000L;
    private static final int MAX_EVENTS = 200;

    /** The 12 Test nations as the feed names them, and the short code shown. */
    private static final String[][] NATIONS = {
            {"India", "IND"}, {"Australia", "AUS"}, {"England", "ENG"}, {"Pakistan", "PAK"},
            {"South Africa", "SA"}, {"New Zealand", "NZ"}, {"Sri Lanka", "SL"}, {"Bangladesh", "BAN"},
            {"Afghanistan", "AFG"}, {"West Indies", "WI"}, {"Ireland", "IRE"}, {"Zimbabwe", "ZIM"}};

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

    static volatile List<String> cricket = Collections.emptyList();
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
                        cricket = cricketLines(rssTitles(CRICKET_URL));
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

    /** "Australia 220/10 * v England 355/6" as "AUS 220/10* v ENG 355/6", Test nations only, India first. */
    static List<String> cricketLines(List<String> titles) {
        List<String> india = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String raw : titles) {
            String t = raw.replace("&amp;", "&").trim();
            int v = t.indexOf(" v ");
            if (v < 0) continue;
            String a = side(t.substring(0, v));
            String b = side(t.substring(v + 3));
            if (a == null || b == null) continue;
            (a.startsWith("IND") || b.startsWith("IND") ? india : rest).add(a + " v " + b);
        }
        india.addAll(rest);
        return india;
    }

    /** "India Women 245/3 *" as "IND W 245/3*"; null for a side that is not one of the 12 Test nations. */
    static String side(String s) {
        s = s.trim();
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '*') i++;
        String team = s.substring(0, i).trim();
        String score = s.substring(i).trim().replaceAll("\\s+", " ").replace(" *", "*");
        boolean women = team.endsWith(" Women");
        if (women) team = team.substring(0, team.length() - " Women".length()).trim();
        for (String[] n : NATIONS) {
            if (n[0].equals(team)) return n[1] + (women ? " W" : "") + (score.isEmpty() ? "" : " " + score);
        }
        return null;
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
