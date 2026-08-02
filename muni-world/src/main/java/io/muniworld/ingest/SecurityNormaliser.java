package io.muniworld.ingest;

import io.muniworld.domain.Bond;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ADR-0004 normalise stage: maps source-shaped rows into canonical {@link Bond}s via a {@link FieldMap},
 * with robust parsing (money strips $/,/% into exact BigDecimal; dates accept ISO / US / Socrata datetime;
 * tax status normalised). A row missing a required field (cusip, coupon, maturity, price) is skipped and
 * counted — the ADR-0011 quarantine discipline: never force-fit, never silently drop. Source-agnostic, so
 * one normaliser serves Socrata, EMMA/OS-extracted fields, or CSV.
 */
@Component
public final class SecurityNormaliser {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,        // 2035-06-01
            DateTimeFormatter.ofPattern("M/d/uuuu"), // 6/1/2035
            DateTimeFormatter.ofPattern("MM/dd/uuuu"),
            DateTimeFormatter.ofPattern("uuuuMMdd"), // 20350601
    };

    /** The outcome of a normalise pass: the valid bonds, and how many rows were skipped (quarantined). */
    public record Result(List<Bond> bonds, int skipped) {
    }

    public Result normalise(List<Map<String, Object>> rows, FieldMap m) {
        List<Bond> out = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> r : rows) {
            try {
                String cusip = str(r, m.cusip());
                BigDecimal coupon = dec(r, m.coupon());
                LocalDate maturity = date(r, m.maturity());
                BigDecimal price = dec(r, m.price());
                if (cusip == null || coupon == null || maturity == null || price == null) {
                    skipped++;   // required field missing → quarantine (ADR-0011), don't guess
                    continue;
                }
                out.add(new Bond(cusip, str(r, m.issuer()), coupon, maturity, date(r, m.dated()), price,
                        tax(str(r, m.taxStatus())), date(r, m.callDate()), dec(r, m.callPrice()),
                        str(r, m.rating()), str(r, m.geoFips())));
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        return new Result(out, skipped);
    }

    // ---- field parsing ----

    private static String str(Map<String, Object> r, String key) {
        if (key == null) {
            return null;
        }
        Object v = r.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal dec(Map<String, Object> r, String key) {
        String s = str(r, key);
        if (s == null) {
            return null;
        }
        s = s.replaceAll("[$,%\\s]", "");
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(Map<String, Object> r, String key) {
        String s = str(r, key);
        if (s == null) {
            return null;
        }
        if (s.length() > 10 && (s.charAt(10) == 'T' || s.charAt(10) == ' ')) {
            s = s.substring(0, 10); // Socrata "2035-06-01T00:00:00.000"
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (RuntimeException ignore) {
                // try the next format
            }
        }
        return null;
    }

    /** Normalise tax-status text to the canonical set; pass through anything unrecognised (never drop info). */
    private static String tax(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toLowerCase();
        if (s.contains("amt") && !s.contains("non")) {
            return "AMT";
        }
        if (s.contains("bab") || s.contains("build america")) {
            return "BAB";
        }
        if (s.contains("taxable") && !s.contains("exempt")) {
            return "taxable";
        }
        if (s.contains("exempt")) {
            return "tax-exempt";
        }
        return raw;
    }
}
