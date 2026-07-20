package io.jethro.app.social;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the instruments a post refers to (ADR-0050). Deliberately NOT restricted to the configured
 * universe — social discusses whatever it discusses, and we want to SEE it. A discovered ticker is
 * merely TAGGED tracked-or-not downstream; what to do with an untracked one (a suggestion to add it,
 * or ignore and focus on the configured list) is a later choice, not a filter here.
 */
public final class Cashtags {

    private static final Pattern CASHTAG = Pattern.compile("\\$([A-Za-z]{1,6})");

    // News prose almost never uses $cashtags; it writes the ticker exchange-qualified, e.g.
    // "Nvidia (NASDAQ: NVDA)" or "(NYSE: JPM)". Match that so news items yield real ticker mentions.
    // Deliberately exchange-QUALIFIED only — a bare "(GDP)"/"(FOMC)"/"(CEO)" in a press release is not a
    // ticker, and requiring the exchange prefix avoids that false-positive flood.
    private static final Pattern EXCHANGE_TICKER = Pattern.compile(
            "(?i)\\b(?:NYSE(?:\\s+American|\\s+Arca)?|NASDAQ|AMEX|OTC(?:MKTS)?|CBOE|BATS|LON|LSE|ETR|FRA|EPA|"
                    + "TSX|TSXV|ASX|HKG|TYO|SIX|BME)\\s*:\\s*([A-Za-z]{1,6}(?:\\.[A-Za-z]{1,2})?)");

    private Cashtags() {
    }

    /** Every {@code $CASHTAG} in the text (uppercased), unrestricted. Used both as the primary
     *  subject extraction and for the spam "too many tickers" count. */
    public static Set<String> extractCashtags(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = CASHTAG.matcher(text);
        while (m.find()) {
            out.add(m.group(1).toUpperCase());
        }
        return out;
    }

    /** Ticker mentions in NEWS prose: any {@code $CASHTAG} (rare in news) PLUS every exchange-qualified
     *  ticker like {@code (NASDAQ: NVDA)} / {@code NYSE: JPM}. Exchange-qualified only, so common
     *  non-ticker abbreviations in parentheses (GDP, FOMC, CEO…) are not mistaken for symbols. */
    public static Set<String> extractNewsTickers(String text) {
        Set<String> out = extractCashtags(text);
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = EXCHANGE_TICKER.matcher(text);
        while (m.find()) {
            out.add(m.group(1).toUpperCase());
        }
        return out;
    }

    /** All cashtags PLUS bare whole-word mentions of a TRACKED id (e.g. "AAPL" without the $) — the
     *  tracked set only helps catch un-tagged mentions of names we already know; it is not a filter. */
    public static Set<String> extract(String text, Set<String> tracked) {
        Set<String> out = extractCashtags(text);
        if (text != null && tracked != null) {
            for (String id : tracked) {
                if (!out.contains(id) && Pattern.compile("\\b" + Pattern.quote(id) + "\\b").matcher(text).find()) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
