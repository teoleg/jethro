package io.muniworld.bond;

import io.muniworld.curve.CurveRepository;
import io.muniworld.domain.Bond;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The OAS gates that protect the calculation from bad inputs (ADR-0020) and from silent assumptions
 * (owner directive: "filter out all your assumptions"): curve staleness, curve sanity at read time, the
 * per-result assumption ledger, and strict (facts-only) mode.
 */
class OasServiceGatesTest {

    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 6, 30);

    /** A callable bond whose OS stated a call DATE but no PRICE — the par-call assumption case. */
    private static SecurityRepository.Row bondAssumedParCall() {
        Bond b = new Bond("TESTGATE1", "Gate Test Issuer", new BigDecimal("5.0"),
                LocalDate.of(2036, 6, 15), null, null, "tax-exempt",
                LocalDate.of(2031, 6, 15), null, null, null);
        SecurityRepository.Detail d = new SecurityRepository.Detail(
                "Fixed", false, false, 3, new BigDecimal("2500000"),
                new BigDecimal("102.5"), PRICE_DATE, "os-test");
        return new SecurityRepository.Row(b, d);
    }

    private static Map<String, Object> fit(LocalDate asOf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", String.valueOf(asOf));
        m.put("beta0", new BigDecimal("4.2"));
        m.put("beta1", new BigDecimal("-0.8"));
        m.put("beta2", new BigDecimal("-0.5"));
        m.put("beta3", BigDecimal.ZERO);
        m.put("tau1", new BigDecimal("1.4"));
        m.put("tau2", new BigDecimal("6.0"));
        return m;
    }

    private static Map<String, Object> vol() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", String.valueOf(PRICE_DATE));
        m.put("windowDays", 252);
        m.put("sigma", new BigDecimal("0.151030"));
        m.put("p10", new BigDecimal("0.09"));
        m.put("p50", new BigDecimal("0.14"));
        m.put("p90", new BigDecimal("0.22"));
        return m;
    }

    private static OasService service(SecurityRepository sec, CurveRepository curves) {
        return new OasService(sec, curves, 14);
    }

    @Test
    void staleCurveIsRefusedByName() {
        SecurityRepository sec = mock(SecurityRepository.class);
        CurveRepository curves = mock(CurveRepository.class);
        when(sec.findDetailed(anyString())).thenReturn(Optional.of(bondAssumedParCall()));
        when(curves.fitOnOrBefore(anyString(), any())).thenReturn(fit(PRICE_DATE.minusDays(45)));
        Map<String, Object> out = service(sec, curves).oas("TESTGATE1");
        assertEquals(false, out.get("available"));
        String reason = String.valueOf(out.get("reason"));
        assertTrue(reason.contains("too stale") && reason.contains("45 day(s)"), reason);
    }

    @Test
    void aCorruptStoredCurveIsRefusedAtReadTime() {
        SecurityRepository sec = mock(SecurityRepository.class);
        CurveRepository curves = mock(CurveRepository.class);
        when(sec.findDetailed(anyString())).thenReturn(Optional.of(bondAssumedParCall()));
        Map<String, Object> insane = fit(PRICE_DATE);
        insane.put("beta0", new BigDecimal("400"));   // a unit slip that somehow reached the DB
        when(curves.fitOnOrBefore(anyString(), any())).thenReturn(insane);
        Map<String, Object> out = service(sec, curves).oas("TESTGATE1");
        assertEquals(false, out.get("available"));
        assertTrue(String.valueOf(out.get("reason")).contains("failed validation"),
                String.valueOf(out.get("reason")));
    }

    @Test
    void assumptionsAreListedOnEveryResultAndStrictModeRefusesOnThem() {
        SecurityRepository sec = mock(SecurityRepository.class);
        CurveRepository curves = mock(CurveRepository.class);
        when(sec.findDetailed(anyString())).thenReturn(Optional.of(bondAssumedParCall()));
        when(curves.fitOnOrBefore(anyString(), any())).thenReturn(fit(PRICE_DATE));
        when(curves.latestVol(anyString(), anyString())).thenReturn(vol());

        // default mode: computed, with the par-call assumption on the ledger
        Map<String, Object> out = service(sec, curves).oas("TESTGATE1", false);
        assertEquals(true, out.get("available"));
        assertEquals(false, out.get("assumptionFree"));
        @SuppressWarnings("unchecked")
        List<String> assumptions = (List<String>) out.get("assumptions");
        assertTrue(assumptions.stream().anyMatch(a -> a.contains("par call assumed")),
                String.valueOf(assumptions));

        // strict mode: the same bond REFUSES, naming the assumption it will not make
        Map<String, Object> strict = service(sec, curves).oas("TESTGATE1", true);
        assertEquals(false, strict.get("available"));
        assertTrue(String.valueOf(strict.get("reason")).contains("par call assumed"),
                String.valueOf(strict.get("reason")));
    }

    @Test
    void aFactsOnlyBondIsAssumptionFreeInBothModes() {
        SecurityRepository sec = mock(SecurityRepository.class);
        CurveRepository curves = mock(CurveRepository.class);
        Bond b = new Bond("TESTGATE2", "Facts Only Issuer", new BigDecimal("5.0"),
                LocalDate.of(2036, 6, 15), null, null, "tax-exempt",
                LocalDate.of(2031, 6, 15), new BigDecimal("100"), null, null);   // OS-stated call PRICE
        SecurityRepository.Detail d = new SecurityRepository.Detail(
                "Fixed", false, false, 3, new BigDecimal("2500000"),
                new BigDecimal("102.5"), PRICE_DATE, "os-test");
        when(sec.findDetailed(anyString())).thenReturn(Optional.of(new SecurityRepository.Row(b, d)));
        when(curves.fitOnOrBefore(anyString(), any())).thenReturn(fit(PRICE_DATE));
        when(curves.latestVol(anyString(), anyString())).thenReturn(vol());

        Map<String, Object> strict = service(sec, curves).oas("TESTGATE2", true);
        assertEquals(true, strict.get("available"));
        assertEquals(true, strict.get("assumptionFree"));
        assertFalse(((List<?>) strict.get("assumptions")).iterator().hasNext());
    }
}
