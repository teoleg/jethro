package io.muniworld.extract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The guardrail that makes model-assisted extraction safe (ADR-0015 Phase 3 / ADR-0012 / ADR-0016). A
 * {@link ModelExtractor} proposes rows for a hard OS layout; this class keeps a proposed row <b>only if its
 * key numbers appear verbatim in the source text</b> — the CUSIP, the coupon, and the maturity year must all
 * be present in the document. So the model may locate and structure, but a number it invents (not in the OS)
 * is rejected, never indexed. Anything unverifiable is dropped (quarantine, ADR-0011).
 *
 * <p>Absent a configured model this is a no-op (empty) — extraction stays deterministic-first.
 */
@Component
public final class VerifyingAssistedExtractor {

    private static final Logger log = LoggerFactory.getLogger(VerifyingAssistedExtractor.class);

    private final Optional<ModelExtractor> model;

    public VerifyingAssistedExtractor(Optional<ModelExtractor> model) {
        this.model = model;
    }

    public boolean available() {
        return model.isPresent();
    }

    /** Propose (via the model) then verify against {@code text}; tag survivors with issuer/geoFips. */
    public List<Map<String, Object>> extractVerified(String text, String issuer, String geoFips) {
        if (model.isEmpty() || text == null || text.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> proposed;
        try {
            proposed = model.get().propose(text);
        } catch (RuntimeException e) {
            log.warn("assisted extraction model failed: {}", e.toString());
            return List.of();
        }
        List<Map<String, Object>> verified = new ArrayList<>();
        int rejected = 0;
        for (Map<String, Object> r : proposed) {
            if (verify(text, r)) {
                r.putIfAbsent("issuer", issuer);
                r.putIfAbsent("geoFips", geoFips);
                verified.add(r);
            } else {
                rejected++;   // a proposed number not present in the document → never trust it
            }
        }
        log.info("assisted extraction: {} proposed, {} verified, {} rejected (not in source)",
                proposed.size(), verified.size(), rejected);
        return verified;
    }

    /** A row is trustworthy only if its CUSIP, coupon, and maturity year all appear verbatim in the OS text. */
    private static boolean verify(String text, Map<String, Object> r) {
        String cusip = str(r.get("cusip"));
        String coupon = str(r.get("coupon"));
        String maturity = str(r.get("maturity"));
        if (cusip == null || coupon == null || maturity == null) {
            return false;
        }
        String year = maturity.length() >= 4 ? maturity.substring(0, 4) : maturity;   // "2035-06-01" → "2035"
        return text.contains(cusip) && text.contains(coupon) && text.contains(year);
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
