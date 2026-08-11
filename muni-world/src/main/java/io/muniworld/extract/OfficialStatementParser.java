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
    // "(November 1)" / "Due November 1" / "Due: December 15, as shown on inside cover" / "maturing
    // November 1". The COLON form is the standard OS cover-page phrasing, and without it a schedule of
    // bare years has no month/day to attach — so every row's maturity would be unknown and dropped.
    private static final Pattern SCHED_MONTHDAY = Pattern.compile(
            "(?:\\(|Due:?\\s+|maturing\\s+)\\s*([A-Z][a-z]+)\\s+(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    // "Base CUSIP(1): 64971X" / "CUSIP Base: 649122" / "Base CUSIP® No. 64971X". The footnote marker is
    // the trap: it contains a DIGIT, so a separator class of [^0-9A-Z] stops dead at the "1" in "(1)" and
    // the base is missed entirely — which is how a top NY issuer's OS reported "no base CUSIP detected"
    // while printing one in its own column header.
    private static final Pattern BASE_CUSIP = Pattern.compile(
            "(?:Base\\s*CUSIP|CUSIP\\s*Base)\\s*(?:\\u00ae|\\u2122)?\\s*(?:\\(\\d{1,2}\\)|\\*|\\u2020)?"
            + "\\s*(?:Nos?\\.?|Number)?\\s*[:.\\-]?\\s*([0-9]{3}[0-9A-Z]{3})", Pattern.CASE_INSENSITIVE);
    // The far commoner real-world form: the schedule's CUSIP column header carries the base in parentheses
    // with a footnote marker — "... Yield Price (681725)*". Accepted only when the word CUSIP appears close
    // by, so an ordinary parenthesised token can never be mistaken for a security's identity.
    private static final Pattern BASE_CUSIP_HEADER = Pattern.compile("\\(([0-9]{3}[0-9A-Z]{3})\\)\\s*\\*");
    /** One serial-maturity entry: year, principal, coupon, yield, price, CUSIP suffix. A schedule line
     *  routinely carries TWO of these side by side (the OS prints the table in two columns), so this is
     *  matched repeatedly per line rather than once. %-signs and the yield-to-call dagger are optional
     *  because the OS prints them on the first row of a column and omits them after. */
    /**
     * One serial-maturity entry: year, principal, coupon, then ONE OR TWO reoffering columns (yield and/or
     * price, per the table's header), then the CUSIP suffix. A schedule line routinely carries TWO of these
     * side by side, so it is matched repeatedly per line.
     *
     * <p>Every numeric shape here is drawn from real documents: rates print as "5.000%", "5 %", "3.4%" or
     * bare "3.6"; prices as "100%" or "106.930%"; a yield may carry a dagger. Demanding decimals (as the
     * first cut did) rejected an entire NYC TFA schedule whose coupons print as "5 %".
     */
    private static final Pattern ENTRY = Pattern.compile(
            "\\b((?:19|20)\\d{2})\\s+\\$?\\s*([\\d,]{3,})\\s+(\\d{1,2}(?:\\.\\d{1,4})?)\\s*%?\\s*[\u2020\u2021*]?"
            + "\\s+((?:\\d{1,3}(?:\\.\\d{1,4})?\\s*%?\\s*[\u2020\u2021*]?\\s+){1,2})"
            + "([0-9A-Z]{2,3})(?![0-9A-Z])");
    /** A number inside the reoffering columns captured by {@link #ENTRY}. */
    private static final Pattern REOFFER_NUM = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,4})?)");
    /** The schedule's column header — it names whether the reoffering columns are Yield, Price, or both. */
    private static final Pattern COLUMN_HEADER = Pattern.compile(
            "Rate\\s+(Yield\\s+Price|Price\\s+Yield|Yield|Price)", Pattern.CASE_INSENSITIVE);
    /** A term bond, printed as prose under the serial table with its own full base+suffix CUSIP. */
    private static final Pattern TERM_BOND = Pattern.compile(
            "\\$([\\d,]+)\\s+(\\d{1,2}\\.\\d{1,3})\\s*%?\\s+Term\\s+Bonds?\\s+due\\s+"
            + "([A-Z][a-z]+\\s+\\d{1,2},\\s*\\d{4}).{0,120}?CUSIP\\s*Number\\s*\\*?\\s*"
            + "([0-9]{3}[0-9A-Z]{3})\\s+([0-9A-Z]{2,3})(?![0-9A-Z])", Pattern.CASE_INSENSITIVE);
    /** "†Yield to first optional call date of December 15, 2036." — the call DATE stated without a price. */
    private static final Pattern FIRST_OPTIONAL_CALL = Pattern.compile(
            "first\\s+optional\\s+(?:call|redemption)\\s+date\\s+of\\s+([A-Z][a-z]+\\s+\\d{1,2},\\s*\\d{4})",
            Pattern.CASE_INSENSITIVE);
    /** Series section headings that state the tax treatment of the maturities that follow them. */
    private static final Pattern SERIES_HEADING = Pattern.compile("\\((Non-AMT|AMT)\\)", Pattern.CASE_INSENSITIVE);
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
        String seriesTax = null;        // set by a "(AMT)" / "(Non-AMT)" heading as the scan walks the doc
        Columns columns = Columns.YIELD;   // until a column header says otherwise (the commonest shape)
        for (String raw : lines) {
            String line = raw.strip();

            // A series heading states the tax treatment of the maturities BELOW it. One OS routinely
            // prices two series together (an AMT and a Non-AMT tranche), so a single document-level tax
            // status would mislabel half the bonds. Read it from the heading instead of averaging.
            Matcher sh = SERIES_HEADING.matcher(line);
            if (sh.find() && line.length() < 120) {
                seriesTax = sh.group(1).equalsIgnoreCase("AMT") ? "AMT" : "tax-exempt";
            }
            String rowTax = seriesTax != null ? seriesTax : tax;

            // The schedule's own column header tells us what the reoffering columns mean. One document
            // prints "Rate Yield Price", another "Rate Yield", another "Rate Price" — reading it beats
            // assuming, and the assumption would silently mislabel a price as a yield.
            Matcher ch = COLUMN_HEADER.matcher(line);
            if (ch.find()) {
                String cols = ch.group(1).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                columns = switch (cols) {
                    case "yield price" -> Columns.YIELD_THEN_PRICE;
                    case "price yield" -> Columns.PRICE_THEN_YIELD;
                    case "price" -> Columns.PRICE;
                    default -> Columns.YIELD;
                };
            }

            // COLUMNAR SERIAL MATURITIES. The schedule is printed as two side-by-side columns, so one
            // text line carries TWO bonds: "2027 $2,395,000 5.000% 2.930% 102.777% NM5 2037 ... NX1".
            // Reading one CUSIP per line (the old behaviour) dropped half the schedule and mis-paired the
            // other half's coupon, so every entry on the line is matched.
            List<Map<String, Object>> entries = parseEntries(line, base, schedMonthDay, issuer, geoFips,
                    rowTax, call, columns);
            if (!entries.isEmpty()) {
                rows.addAll(entries);
                continue;
            }

            // TERM BONDS, printed as prose beneath the serial table with a full base+suffix CUSIP.
            List<Map<String, Object>> terms = parseTermBonds(line, issuer, geoFips, rowTax, call);
            if (!terms.isEmpty()) {
                rows.addAll(terms);
                continue;
            }

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
                r.put("tax", rowTax);
                r.put("geoFips", geoFips);
                if (pcts.size() > 1) {
                    r.put("reofferingYield", pcts.get(1));   // captured, NOT emitted as price (terms-only)
                }
                applyCall(r, maturity, call);
                rows.add(r);
            } else {
                quarantined++;                               // a CUSIP row we couldn't complete → don't guess
            }
        }
        double confidence = rows.isEmpty() ? 0.0 : (double) rows.size() / (rows.size() + quarantined);
        return new Result(rows, quarantined, confidence);
    }


    /**
     * Every serial-maturity entry on one schedule line. Requires a real base CUSIP-6: the schedule prints
     * only 2–3 character suffixes, and a suffix alone is not a security's identity (ADR-0011 — a partial
     * key is never fabricated into a whole one).
     */
    /** Which reoffering columns this schedule prints, read from its own header row. */
    enum Columns { YIELD, PRICE, YIELD_THEN_PRICE, PRICE_THEN_YIELD }

    private static List<Map<String, Object>> parseEntries(String line, String base, int[] schedMonthDay,
                                                          String issuer, String geoFips, String tax,
                                                          Call call, Columns columns) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (base == null || base.length() != 6) {
            return out;
        }
        Matcher m = ENTRY.matcher(line);
        while (m.find()) {
            String suffix = m.group(5);
            if (!isSuffix(suffix)) {
                continue;                 // all-letters or all-digits is a word or a number, not a CUSIP
            }
            LocalDate maturity = schedMonthDay == null ? null
                    : safeDate(Integer.parseInt(m.group(1)), schedMonthDay[0], schedMonthDay[1]);
            if (maturity == null) {
                continue;                 // no schedule month/day header → the date would be a guess
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("cusip", base + suffix);
            r.put("issuer", issuer);
            r.put("coupon", m.group(3));
            r.put("maturity", maturity.toString());
            r.put("tax", tax);
            r.put("geoFips", geoFips);
            // The reoffering columns are whatever the table's header SAYS they are. Labelling a price
            // "yield" (or the reverse) would be a wrong number wearing a right name — and the original
            // issue PRICE is exactly the input the de-minimis/OID analytics need later.
            List<String> nums = new ArrayList<>();
            Matcher n = REOFFER_NUM.matcher(m.group(4));
            while (n.find()) {
                nums.add(n.group(1));
            }
            if (nums.size() >= 2) {
                r.put(columns == Columns.PRICE_THEN_YIELD ? "reofferingPrice" : "reofferingYield", nums.get(0));
                r.put(columns == Columns.PRICE_THEN_YIELD ? "reofferingYield" : "reofferingPrice", nums.get(1));
            } else if (nums.size() == 1) {
                r.put(columns == Columns.PRICE ? "reofferingPrice" : "reofferingYield", nums.get(0));
            }
            applyCall(r, maturity, call);
            out.add(r);
        }
        return out;
    }

    /** Term bonds printed as prose: "$35,925,000 5.500% Term Bond due December 15, 2051, ... 681725 PH4". */
    private static List<Map<String, Object>> parseTermBonds(String line, String issuer, String geoFips,
                                                            String tax, Call call) {
        List<Map<String, Object>> out = new ArrayList<>();
        Matcher m = TERM_BOND.matcher(line);
        while (m.find()) {
            if (!isSuffix(m.group(5))) {
                continue;
            }
            LocalDate maturity = parseLongDate(m.group(3));
            if (maturity == null) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("cusip", m.group(4) + m.group(5));
            r.put("issuer", issuer);
            r.put("coupon", m.group(2));
            r.put("maturity", maturity.toString());
            r.put("tax", tax);
            r.put("geoFips", geoFips);
            applyCall(r, maturity, call);
            out.add(r);
        }
        return out;
    }

    /**
     * Attach the optional call to a row when this maturity is actually callable.
     *
     * <p>A bond maturing on or before the first call date cannot be called — it has already matured. That
     * single rule reproduces the OS's own dagger marks exactly (in the Omaha 2026 schedule the daggered
     * yields are precisely the 2037+ maturities against a 2036-12-15 call), so the callability comes from
     * the document's stated dates rather than from reading a footnote symbol.
     *
     * <p>The call PRICE is only written when the document states one. A first-optional-call sentence gives
     * a date and no price; assuming par there would be inventing a money number (ADR-0015).
     */
    private static void applyCall(Map<String, Object> row, LocalDate maturity, Call call) {
        if (call == null || !maturity.isAfter(call.date())) {
            return;
        }
        if (call.callableFromMaturity() != null && maturity.isBefore(call.callableFromMaturity())) {
            return;
        }
        row.put("callDate", call.date().toString());
        if (call.price() != null) {
            row.put("callPrice", call.price());
        }
    }

    /** A CUSIP suffix is mixed alphanumeric — "NM5" yes, "AMT" no, "000" no. */
    private static boolean isSuffix(String s) {
        return s != null && s.chars().anyMatch(Character::isLetter)
                && s.chars().anyMatch(Character::isDigit);
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
        if (m.find()) {
            return m.group(1);
        }
        // "... Yield Price (681725)*" — the base sits in the CUSIP column header. Require the word CUSIP
        // within 400 chars before it: the header line itself often wraps, so same-line matching misses it,
        // but an unrelated parenthesised token is never that close to a CUSIP heading.
        Matcher h = BASE_CUSIP_HEADER.matcher(text);
        while (h.find()) {
            String before = text.substring(Math.max(0, h.start() - 400), h.start());
            if (before.toUpperCase(Locale.ROOT).contains("CUSIP")) {
                return h.group(1);
            }
        }
        return fallback;
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
        if (callDate != null && callPrice != null && callableFrom != null) {
            return new Call(callDate, callPrice, callableFrom);
        }
        // Commoner form: the schedule's own footnote — "†Yield to first optional call date of December 15,
        // 2036." That states the DATE with no price, which is still the option's most important term. Emit
        // it with a null price rather than discarding a real call (or inventing par for it).
        Matcher first = FIRST_OPTIONAL_CALL.matcher(text);
        if (first.find()) {
            LocalDate d = parseLongDate(first.group(1));
            if (d != null) {
                return new Call(d, callPrice, null);
            }
        }
        return null;                                         // nothing stated → no guessed call
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
