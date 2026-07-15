package io.jethro.trading.riskpnl;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Latency guardrail for the risk projection (see docs/architecture/perf-budgets.md):
 * {@code snapshot()} is the boundary call the UI/API/guardrail path makes, BigDecimal by
 * design (invariant 1 — it is NOT the tick path), so it will never be free; the guard is
 * that it stays interactive at a realistic book size. 240 positions across 4 books is ~15×
 * the seeded instrument universe. Budgets are 20–50× above healthy measurements on a
 * 2-vCPU CI runner so scheduler noise cannot flake the build, while an accidental O(n²)
 * grouping or per-snapshot re-pricing still fails loudly. Measured numbers are printed
 * for trend-watching.
 */
class RiskSnapshotPerfTest {

    private static final int BOOKS = 4;
    private static final int INSTRUMENTS_PER_BOOK = 60; // 240 positions
    private static final int WARMUP_SNAPSHOTS = 50;
    private static final int MEASURED_SNAPSHOTS = 200;
    private static final double SNAPSHOT_BUDGET_MILLIS = 50.0;
    private static final long FILL_FLOOR_PER_SEC = 2_000;

    private static final InstrumentRefSource REFS = id -> Optional.of(
            new InstrumentRef(id, id.hashCode() % 2 == 0 ? "EQUITY" : "FUTURE", "USD",
                    new BigDecimal(id.hashCode() % 2 == 0 ? "1" : "50")));

    private static RiskProjection loadedProjection() {
        var p = new RiskProjection(REFS);
        int f = 0;
        for (int b = 0; b < BOOKS; b++) {
            for (int i = 0; i < INSTRUMENTS_PER_BOOK; i++) {
                String instrument = "SYM" + i;
                p.applyFill(new Fill("f" + (f++), "ord-" + f, new BookId("BOOK" + b),
                        new InstrumentId(instrument), i % 2 == 0 ? Side.BUY : Side.SELL,
                        new BigDecimal(100 + i), new BigDecimal("100.123456"), Instant.EPOCH));
            }
        }
        for (int i = 0; i < INSTRUMENTS_PER_BOOK; i++) {
            p.applyMark("SYM" + i, new BigDecimal("101.654321"), 1_000);
        }
        return p;
    }

    @Test
    void snapshotOfARealisticBookStaysInteractive() {
        var p = loadedProjection();
        assertEquals(BOOKS * INSTRUMENTS_PER_BOOK, p.snapshot(1_000).positions().size(),
                "fixture must actually hold the advertised book");

        long sink = 0;
        for (int i = 0; i < WARMUP_SNAPSHOTS; i++) {
            sink += p.snapshot(1_000).positions().size();
        }
        long start = System.nanoTime();
        for (int i = 0; i < MEASURED_SNAPSHOTS; i++) {
            sink += p.snapshot(1_000).positions().size();
        }
        long nanos = System.nanoTime() - start;
        assertTrue(sink > 0, "blackhole");

        double avgMillis = nanos / 1e6 / MEASURED_SNAPSHOTS;
        System.out.printf("[perf] risk snapshot (%d positions, %d books): %.3f ms avg (budget %.0f ms)%n",
                BOOKS * INSTRUMENTS_PER_BOOK, BOOKS, avgMillis, SNAPSHOT_BUDGET_MILLIS);
        assertTrue(avgMillis < SNAPSHOT_BUDGET_MILLIS,
                "risk snapshot averaged " + avgMillis + " ms for "
                        + BOOKS * INSTRUMENTS_PER_BOOK + " positions — budget "
                        + SNAPSHOT_BUDGET_MILLIS + " ms is already 20–50× a healthy build");
    }

    @Test
    void fillApplicationKeepsPaceWithABusySimTape() {
        var p = loadedProjection();
        int warmup = 2_000;
        int measured = 20_000;
        for (int i = 0; i < warmup; i++) {
            p.applyFill(new Fill("w" + i, "ord-w" + i, new BookId("BOOK0"),
                    new InstrumentId("SYM" + (i % INSTRUMENTS_PER_BOOK)), Side.BUY,
                    BigDecimal.ONE, new BigDecimal("100.123456"), Instant.EPOCH));
        }
        long start = System.nanoTime();
        for (int i = 0; i < measured; i++) {
            p.applyFill(new Fill("m" + i, "ord-m" + i, new BookId("BOOK0"),
                    new InstrumentId("SYM" + (i % INSTRUMENTS_PER_BOOK)), Side.BUY,
                    BigDecimal.ONE, new BigDecimal("100.123456"), Instant.EPOCH));
        }
        long nanos = System.nanoTime() - start;

        long fillsPerSec = measured * 1_000_000_000L / Math.max(1, nanos);
        System.out.printf("[perf] risk applyFill: %,d fills/s (floor %,d)%n",
                fillsPerSec, FILL_FLOOR_PER_SEC);
        assertTrue(fillsPerSec > FILL_FLOOR_PER_SEC,
                "applyFill degraded to " + fillsPerSec
                        + " fills/s — the floor is orders of magnitude under a healthy build");
    }
}
