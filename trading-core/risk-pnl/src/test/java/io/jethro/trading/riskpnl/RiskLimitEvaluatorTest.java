package io.jethro.trading.riskpnl;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic limit checks at the WARN/ALERT boundaries — exact, no float. */
class RiskLimitEvaluatorTest {

    private final InstrumentRefSource refs = id -> Optional.ofNullable(Map.of(
            "AAPL", new InstrumentRef("AAPL", "EQUITY", "USD", new BigDecimal("1"))
    ).get(id));

    // WARN at 80% of the limit.
    private final RiskLimitEvaluator evaluator = new RiskLimitEvaluator(new BigDecimal("0.80"));

    private static Fill buy(String id, String qty, String price) {
        return new Fill(id, "ord-" + id, new BookId("ALPHA"), new InstrumentId("AAPL"),
                Side.BUY, new BigDecimal(qty), new BigDecimal(price), Instant.EPOCH);
    }

    private RiskLimitSource grossLimit(String max) {
        return book -> new RiskLimits(new BigDecimal(max), null, null);
    }

    @Test
    void noBreachBelowTheWarnRatio() {
        var p = new RiskProjection(refs);
        p.applyFill(buy("f1", "100", "10"));      // gross exposure 1000
        p.applyMark("AAPL", new BigDecimal("10"), 0);

        // limit 2000 → 1000 is 50%, below the 80% warn ratio
        assertTrue(evaluator.evaluate(p.snapshot(0), grossLimit("2000")).isEmpty());
    }

    @Test
    void warnAtEightyPercentOfLimit() {
        var p = new RiskProjection(refs);
        p.applyFill(buy("f1", "100", "10"));      // gross 1000
        p.applyMark("AAPL", new BigDecimal("10"), 0);

        // limit 1250 → 1000/1250 = exactly 80% → WARN
        List<LimitBreach> breaches = evaluator.evaluate(p.snapshot(0), grossLimit("1250"));
        assertEquals(1, breaches.size());
        assertEquals(LimitBreach.Severity.WARN, breaches.get(0).severity());
        assertEquals(LimitBreach.Metric.GROSS_EXPOSURE, breaches.get(0).metric());
        assertEquals("risk:ALPHA:GROSS_EXPOSURE", breaches.get(0).id());
    }

    @Test
    void alertAtOrAboveTheLimit() {
        var p = new RiskProjection(refs);
        p.applyFill(buy("f1", "100", "10"));      // gross 1000
        p.applyMark("AAPL", new BigDecimal("10"), 0);

        // limit 1000 → 100% → ALERT
        List<LimitBreach> breaches = evaluator.evaluate(p.snapshot(0), grossLimit("1000"));
        assertEquals(1, breaches.size());
        assertEquals(LimitBreach.Severity.ALERT, breaches.get(0).severity());
        assertEquals(0, new BigDecimal("1000").compareTo(breaches.get(0).actual()));
        assertEquals(0, new BigDecimal("1000").compareTo(breaches.get(0).limit()));
    }

    @Test
    void lossLimitBreachesOnNegativePnl() {
        var p = new RiskProjection(refs);
        p.applyFill(buy("f1", "100", "10"));       // long 100 @10
        p.applyMark("AAPL", new BigDecimal("7"), 0); // mark 7 → unrealized -300

        RiskLimitSource lossLimit = book -> new RiskLimits(null, null, new BigDecimal("250"));
        List<LimitBreach> breaches = evaluator.evaluate(p.snapshot(0), lossLimit);
        assertEquals(1, breaches.size());
        assertEquals(LimitBreach.Metric.LOSS, breaches.get(0).metric());
        assertEquals(LimitBreach.Severity.ALERT, breaches.get(0).severity()); // 300 loss >= 250
        assertEquals(0, new BigDecimal("300").compareTo(breaches.get(0).actual()));
    }

    @Test
    void unsetLimitsAreSkipped() {
        var p = new RiskProjection(refs);
        p.applyFill(buy("f1", "100", "10"));
        p.applyMark("AAPL", new BigDecimal("10"), 0);

        RiskLimitSource none = book -> RiskLimits.none();
        assertTrue(evaluator.evaluate(p.snapshot(0), none).isEmpty());
    }
}
