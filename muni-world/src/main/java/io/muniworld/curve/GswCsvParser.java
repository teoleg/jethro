package io.muniworld.curve;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for the Federal Reserve's GSW Treasury curve file ({@code feds200628.csv}, FEDS 2006-28,
 * Gurkaynak-Sack-Wright) — the free daily zero-coupon Treasury curve, 1961 to the present, published by the
 * Board as a plain CSV with no key and no registration (ADR-0017).
 *
 * <p><b>The header is FOUND, not counted.</b> The file opens with a preamble of explanatory rows whose
 * length the Fed has changed over the years; every third-party snippet hard-codes "skip 9 rows", which
 * breaks silently the next time the preamble grows — and a silently shifted column set is exactly the kind
 * of quiet data corruption ADR-0011 exists to prevent. This parser scans for the row that names
 * {@code BETA0} and takes column positions from it by NAME.
 *
 * <p><b>Early history is Nelson-Siegel, not Svensson.</b> The Fed fits only four parameters (no BETA3/TAU2)
 * for the earlier decades and leaves those cells blank. A blank fourth term is the NS model exactly, so it
 * is read as zero — that is not a guess, it is the same curve.
 */
public final class GswCsvParser {

    /** One published day: the six NSS parameters, plus whatever SVENYnn zero yields the row carries. */
    public record Row(LocalDate date, double beta0, double beta1, double beta2, double beta3,
                      double tau1, double tau2, Map<Integer, Double> svenYieldsPercent) {

        /** The reconstructed curve for this day. */
        public NelsonSiegelSvensson curve() {
            return new NelsonSiegelSvensson(beta0, beta1, beta2, beta3, tau1, tau2);
        }
    }

    /** Dates appear as ISO in the current file; older vintages used US format. Both are accepted. */
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
    };

    private GswCsvParser() {
    }

    /**
     * Parse the whole file. Rows without a usable date or without BETA0/BETA1/TAU1 are skipped and counted
     * by the caller through the returned size — a partial file yields the days it can support rather than
     * failing whole, but never invents a parameter it did not read.
     */
    public static List<Row> parse(byte[] csv) {
        String text = new String(csv, StandardCharsets.UTF_8);
        String[] lines = text.split("\r?\n");

        int headerIdx = -1;
        String[] header = null;
        for (int i = 0; i < lines.length; i++) {
            String[] cells = split(lines[i]);
            for (String c : cells) {
                if (c.trim().equalsIgnoreCase("BETA0")) {
                    headerIdx = i;
                    header = cells;
                    break;
                }
            }
            if (headerIdx >= 0) {
                break;
            }
        }
        if (header == null) {
            throw new IllegalArgumentException(
                    "not a GSW curve file: no header row naming BETA0 in " + lines.length + " line(s)");
        }

        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            col.put(header[i].trim().toUpperCase(java.util.Locale.ROOT), i);
        }
        // Column 0 is the date; the Fed has labelled it "Date" and left it blank across vintages, so it is
        // taken positionally rather than by a name that may not be there.
        Integer b0 = col.get("BETA0");
        Integer b1 = col.get("BETA1");
        Integer b2 = col.get("BETA2");
        Integer b3 = col.get("BETA3");
        Integer t1 = col.get("TAU1");
        Integer t2 = col.get("TAU2");
        if (b0 == null || b1 == null || t1 == null) {
            throw new IllegalArgumentException("GSW header missing BETA0/BETA1/TAU1: " + col.keySet());
        }

        List<Row> rows = new ArrayList<>();
        for (int i = headerIdx + 1; i < lines.length; i++) {
            String[] cells = split(lines[i]);
            if (cells.length <= b0) {
                continue;
            }
            LocalDate date = parseDate(cells[0]);
            Double beta0 = num(cells, b0);
            Double beta1 = num(cells, b1);
            Double tau1 = num(cells, t1);
            if (date == null || beta0 == null || beta1 == null || tau1 == null || tau1 <= 0) {
                continue;   // an unusable row is omitted, never patched with a default
            }
            Double beta2 = num(cells, b2);
            Double beta3 = num(cells, b3);
            Double tau2 = num(cells, t2);
            Map<Integer, Double> svens = new java.util.LinkedHashMap<>();
            for (int n = 1; n <= 30; n++) {
                Integer idx = col.get(String.format("SVENY%02d", n));
                Double v = idx == null ? null : num(cells, idx);
                if (v != null) {
                    svens.put(n, v);
                }
            }
            rows.add(new Row(date, beta0, beta1,
                    beta2 == null ? 0 : beta2,
                    beta3 == null ? 0 : beta3,          // blank BETA3 == Nelson-Siegel, exactly
                    tau1,
                    tau2 == null || tau2 <= 0 ? 1 : tau2,   // unused when beta3 == 0
                    svens));
        }
        return rows;
    }

    /** Split on commas, tolerating simple double-quoted cells (the Fed's preamble rows contain commas). */
    private static String[] split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static Double num(String[] cells, Integer idx) {
        if (idx == null || idx >= cells.length) {
            return null;
        }
        String s = cells[idx].trim();
        if (s.isEmpty() || s.equalsIgnoreCase("NA") || s.equalsIgnoreCase("NaN")) {
            return null;
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        String v = s.trim();
        if (v.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f);
            } catch (java.time.format.DateTimeParseException ignored) {
                // try the next vintage's format
            }
        }
        return null;
    }
}
