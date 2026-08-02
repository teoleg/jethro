package io.muniworld.extract;

import io.muniworld.pdf.OcrText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 guardrails: the OCR scanned-vs-native decision, and — the important one — assisted extraction only
 * keeps a model-proposed row whose numbers appear <b>verbatim in the source text</b>. A model that invents a
 * bond is rejected, never indexed (ADR-0012 / ADR-0016).
 */
class AssistedExtractionTest {

    private static Map<String, Object> row(String cusip, String coupon, String maturity) {
        Map<String, Object> m = new HashMap<>();   // mutable — the extractor tags issuer/geoFips
        m.put("cusip", cusip);
        m.put("coupon", coupon);
        m.put("maturity", maturity);
        return m;
    }

    @Test
    void assistedKeepsOnlyRowsGroundedInTheText() {
        String osText = "Maturity schedule: 649122AB1 5.000% due 2035; 649122AC9 4.000% due 2040.";
        ModelExtractor stub = t -> {
            List<Map<String, Object>> out = new ArrayList<>();
            out.add(row("649122AB1", "5.000", "2035-11-01"));   // all three tokens are in the text
            out.add(row("649122AC9", "4.000", "2040-11-01"));   // present too
            out.add(row("999999ZZ9", "9.999", "2099-11-01"));   // INVENTED — not in the text
            return out;
        };

        var verified = new VerifyingAssistedExtractor(Optional.of(stub))
                .extractVerified(osText, "City of New York", "3600000000");

        assertEquals(2, verified.size(), "the invented bond is rejected; the two grounded ones survive");
        assertTrue(verified.stream().noneMatch(r -> "999999ZZ9".equals(r.get("cusip"))));
        assertEquals("City of New York", verified.get(0).get("issuer"), "survivors tagged with the issuer");
    }

    @Test
    void noModelMeansNoAssistedRows() {
        var none = new VerifyingAssistedExtractor(Optional.empty());
        assertFalse(none.available());
        assertTrue(none.extractVerified("any text", "x", "y").isEmpty(), "deterministic-first: no model → nothing");
    }

    @Test
    void ocrSparseDecision() {
        assertTrue(OcrText.isSparse("", 1), "no text → scanned");
        assertTrue(OcrText.isSparse("x".repeat(150), 2), "75 chars/page → scanned");
        assertFalse(OcrText.isSparse("x".repeat(500), 1), "500 chars/page → native text");
    }
}
