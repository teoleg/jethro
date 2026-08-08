package io.muniworld.extract;

import io.muniworld.ingest.FieldMap;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ADR-0015 deterministic Official-Statement parser: reads the OS **maturity schedule** and
 * **optional-redemption** paragraph out of extracted text into canonical <b>term rows</b> (CUSIP, coupon,
 * maturity, call, tax, par). It is the tested heart of the flagship pipeline and it obeys the two hard rules:
 *
 * <ul>
 *   <li><b>Terms only, never a current price.</b> The schedule's yield is the <em>reoffering</em> level at
 *       issuance — captured as {@code reofferingYield} but NOT emitted as {@code price}. A bond lands with
 *       its terms and blank market economics until a real current-price source exists (ADR-0015).</li>
 *   <li><b>Quarantine, never guess.</b> A line that looks like a schedule row but can't be fully parsed
 *       (missing a confident maturity/coupon/CUSIP) is counted as quarantined, not force-fit (ADR-0011).</li>
 * </ul>
 *
 * <p>Scope (v1): the common columnar schedule "Year | Principal | Coupon | Yield | CUSIP" with a schedule
 * month/day header, and a single optional-call clause. Messier layouts escalate to assisted extraction
 * later (ADR-0015 Phase 3) — this parser stays deterministic and conservative.
 */
public final class OfficialStatementParser {

    /** The column names this parser emits — point a {@link FieldMap} at these to normalise the rows. */
    public static final FieldMap FIELD_MAP = new FieldMap(
            "cusip", "issuer", "coupon", "maturity", null /*dated*/, null /*price: terms-only*/,
            "tax", "callDate", "callPrice", null /*rating*/, "geoFips");

    private static final Pattern PERCENT = Pattern.compile("(\\d{1,2}(?:\\.\\d{1,3})?)\\s*%");
    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern FULL_CUSIP = Pattern.compile("\\b([0-9]{3}[0-9A-Z]{5}[0-9])\\b");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern US_DATE = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b");
    // "(November 1)" / "Due November 1" / "maturing November 1" → the schedule's month + day for year-only rows
    private static final Pattern SCHED_MONTHDAY = Pattern.compile(
            "(?:\\(|Due |maturing )\\s*([A-Z][a-z]+)\\s+(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASE_CUSIP = Pattern.compile(
            "(?:Base\\s*CUSIP|CUSIP\\s*Base)[^0-9A-Z]{0,4}([0-9]{3}[0-9A-Z]{3})", Pattern.CASE_INSENSITIVE);
    // optional-redemption: "... on or after <date> ... at <price>%" and a "maturing on or after <date>" gate
    private static final Pattern CALL_ON_OR_AFTER = Pattern.compile(
            "on or after\\s+([A-Z][a-z]+\\s+\\d{1,2},\\s*\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALL_PRICE = Pattern.compile(
            "at\\s+(?:a\\s+redemption\\s+price\\s+of\\s+)?(\\d{2,3}(?:\\.\\d+)?)\\s*%", Pattern.CASE_INSENSITIVE);

    /** Parse outcome: the term rows, how many likely-rows were quarantined, and a pass-level confidence. */
    public record Result(List<Map<String, Object>> rows, int quarantined, double confidence) {
    }

    /**
     * Parse OS text into term rows. {@code issuer}/{@code geoFips} tag every row (known for the OS being
     * ingested); {@code fallbackBase} is a CUSIP-6 to use if the document doesn't state one.
     */
    public static Result parse(String text, String issuer, String geoFips, String fallbackBase) {
        if (text == null || text.isBlank()) {
            return new Result(List.of(), 0, 0.0);
        }
        String[] lines = text.split("\\r?\\n");

        int[] schedMonthDay = findScheduleMonthDay(text);   // [month, day] or null
        // A base is a CUSIP-6 or it is NOT A BASE. Callers pass "" when they have none (the folder loader
        // and the upload endpoint both do), and "" is non-null — which used to reach the suffix branch and
        // emit "" + "AB1" = "AB1" as a security's identity: a fabricated 3-character key, indexed as if it
        // were real. Normalise here so the only thing that can ever be prefixed is a genuine CUSIP-6.
        String base = normaliseBase(findBaseCusip(text, fallbackBase));
        String tax = detectTax(text);
        Call call = detectCall(text);

        List<Map<String, Object>> rows = new ArrayList<>();
        int quarantined = 0;
        for (String raw : lines) {
            String line = raw.strip();
            // A bond row is gated on a CUSIP — that's what distinguishes a schedule row from prose/headers
            // (e.g. the redemption paragraph has a percent but no CUSIP, so it's never a "failed row").
            String cusip = findCusip(line, base);
            if (cusip == null) {
                // A row that carries a coupon, a year AND a trailing suffix is a schedule row we simply
                // cannot KEY, because the document's base CUSIP-6 was never found. Count it as quarantined
                // instead of skipping it silently: a suffix-style schedule otherwise reports "0 rows, 0
                // quarantined", which reads as "there was no schedule" rather than "we could not key it".
                if (base == null && suffixOf(line) != null
                        && !allMatches(PERCENT, line).isEmpty() && YEAR.matcher(line).find()) {
                    quarantined++;
                }
                continue;
            }
            List<String> pcts = allMatches(PERCENT, line);
            LocalDate maturity = parseMaturity(line, schedMonthDay);
            String coupon = pcts.isEmpty() ? null : pcts.get(0);

            if (maturity != null && coupon != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("cusip", cusip);
                r.put("issuer", issuer);
                r.put("coupon", coupon);
                r.put("maturity", maturity.toString());
                r.put("tax", tax);
                r.put("geoFips", geoFips);
                if (pcts.size() > 1) {
                    r.put("reofferingYield", pcts.get(1));   // captured, NOT emitted as price (terms-only)
                }
                if (call != null && !maturity.isBefore(call.callableFromMaturity())) {
                    r.put("callDate", call.date().toString());
                    r.put("callPrice", call.price());
                }
                rows.add(r);
            } else {
                quarantined++;                               // a CUSIP row we couldn't complete → don't guess
            }
        }
        double confidence = rows.isEmpty() ? 0.0 : (double) rows.size() / (rows.size() + quarantined);
        return new Result(rows, quarantined, confidence);
    }

    // ---- section detectors ----

    static int[] findScheduleMonthDay(String text) {
        Matcher m = SCHED_MONTHDAY.matcher(text);
        while (m.find()) {
            Integer mon = monthOf(m.group(1));
            if (mon != null) {
                return new int[] {mon, Integer.parseInt(m.group(2))};
            }
        }
        return null;
    }

    static String findBaseCusip(String text, String fallback) {
        Matcher m = BASE_CUSIP.matcher(text);
        return m.find() ? m.group(1) : fallback;
    }

    private static String detectTax(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("alternative minimum tax") && !t.contains("not") ) {
            // "interest ... is a preference item for the alternative minimum tax"
            if (t.contains("preference") || t.contains("subject to the alternative minimum")) {
                return "AMT";
            }
        }
        if (t.contains("exempt from federal income tax") || t.contains("excluded from gross income")
                || t.contains("excludable from gross income") || t.contains("not includable in gross income")) {
            return "tax-exempt";
        }
        if (t.contains("subject to federal income tax") || t.contains("federally taxable")) {
            return "taxable";
        }
        return null;
    }

    private record Call(LocalDate date, String price, LocalDate callableFromMaturity) {
    }

    private static Call detectCall(String text) {
        // Need a "maturing on or after <date>" gate AND a "redeemable on or after <date> at <price>%".
        Matcher gate = Pattern.compile("maturing on or after\\s+([A-Z][a-z]+\\s+\\d{1,2},\\s*\\d{4})",
                Pattern.CASE_INSENSITIVE).matcher(text);
        LocalDate callableFrom = gate.find() ? parseLongDate(gate.group(1)) : null;

        Matcher when = CALL_ON_OR_AFTER.matcher(text);
        Matcher price = CALL_PRICE.matcher(text);
        // skip the gate's own "on or after" match so we read the redemption date, not the maturity gate
        LocalDate callDate = null;
        while (when.find()) {
            LocalDate d = parseLongDate(when.group(1));
            if (d != null && (callableFrom == null || !d.equals(callableFrom))) {
                callDate = d;
                break;
            }
        }
        String callPrice = price.find() ? price.group(1) : null;
        if (callDate == null || callPrice == null || callableFrom == null) {
            return null;                                     // any piece missing → no guessed call
        }
        return new Call(callDate, callPrice, callableFrom);
    }

    // ---- field parsers ----

    private static LocalDate parseMaturity(String line, int[] schedMonthDay) {
        Matcher iso = ISO_DATE.matcher(line);
        if (iso.find()) {
            return safeDate(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        }
        Matcher us = US_DATE.matcher(line);
        if (us.find()) {
            return safeDate(Integer.parseInt(us.group(3)), Integer.parseInt(us.group(1)), Integer.parseInt(us.group(2)));
        }
        // "November 1, 2026"
        Matcher longd = Pattern.compile("([A-Z][a-z]+)\\s+(\\d{1,2}),\\s*(\\d{4})").matcher(line);
        if (longd.find()) {
            Integer mon = monthOf(longd.group(1));
            if (mon != null) {
                return safeDate(Integer.parseInt(longd.group(3)), mon, Integer.parseInt(longd.group(2)));
            }
        }
        // bare year + schedule month/day header (don't guess the month if the header wasn't found)
        if (schedMonthDay != null) {
            Matcher y = YEAR.matcher(line);
            if (y.find()) {
                return safeDate(Integer.parseInt(y.group()), schedMonthDay[0], schedMonthDay[1]);
            }
        }
        return null;
    }

    /** Full 9-char CUSIP if present; else base(6)+suffix(3) when a base is known and a trailing token fits. */
    private static String findCusip(String line, String base) {
        Matcher full = FULL_CUSIP.matcher(line);
        String last = null;
        while (full.find()) {
            last = full.group(1);                            // CUSIP is the last column — take the last match
        }
        if (last != null) {
            return last;
        }
        // Suffix assembly requires a REAL base (CUSIP-6). normaliseBase guarantees that upstream; the shape
        // is asserted here too, so no future caller can reintroduce a partial key.
        if (base != null && base.length() == 6) {
            String s = suffixOf(line);
            if (s != null) {
                return base + s;
            }
        }
        return null;
    }

    /** A CUSIP suffix at end of line, or null. Mixed alnum only (e.g. "AB1") — rejects all-letter words
     *  ("AMT") and the all-digit tail of a dollar amount ("...000"); neither is a security. */
    private static String suffixOf(String line) {
        Matcher suf = Pattern.compile("\\b([0-9A-Z]{3})\\b\\s*$").matcher(line);
        if (!suf.find()) {
            return null;
        }
        String s = suf.group(1);
        boolean hasLetter = s.chars().anyMatch(Character::isLetter);
        boolean hasDigit = s.chars().anyMatch(Character::isDigit);
        return hasLetter && hasDigit ? s : null;
    }

    /** A base is a CUSIP-6 or nothing — blank/short/malformed input is NOT a base (see parse()). */
    private static String normaliseBase(String base) {
        if (base == null) {
            return null;
        }
        String b = base.strip().toUpperCase(Locale.ROOT);
        return b.matches("[0-9]{3}[0-9A-Z]{3}") ? b : null;
    }

    private static List<String> allMatches(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static LocalDate parseLongDate(String s) {
        Matcher m = Pattern.compile("([A-Z][a-z]+)\\s+(\\d{1,2}),\\s*(\\d{4})").matcher(s);
        if (m.find()) {
            Integer mon = monthOf(m.group(1));
            if (mon != null) {
                return safeDate(Integer.parseInt(m.group(3)), mon, Integer.parseInt(m.group(2)));
            }
        }
        return null;
    }

    private static Integer monthOf(String name) {
        for (Month mo : Month.values()) {
            if (mo.getDisplayName(TextStyle.FULL, Locale.US).equalsIgnoreCase(name)) {
                return mo.getValue();
            }
        }
        return null;
    }

    private static LocalDate safeDate(int y, int m, int d) {
        try {
            return LocalDate.of(y, m, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private OfficialStatementParser() {
    }
}
