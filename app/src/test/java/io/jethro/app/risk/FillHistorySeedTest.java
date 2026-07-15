package io.jethro.app.risk;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Side;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.RiskProjection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restart-survival fix (review BLOCKER-1): the projection rebuilds from the fills
 * TABLE at boot, not a topic replay. Verifies the seed reconstructs positions from the
 * history source, fires the fill tap (swap registry etc.), and is idempotent so a fill
 * that also arrives later on the topic is applied once.
 */
class FillHistorySeedTest {

    private static final InstrumentRefSource REFS = id -> Optional.of(
            new InstrumentRef(id, "EQUITY", "USD", BigDecimal.ONE));

    private static Fill fill(String id, String instrument, Side side, String qty, String px) {
        return new Fill(id, "ord-" + id, new BookId("ALPHA"), new InstrumentId(instrument),
                side, new BigDecimal(qty), new BigDecimal(px), Instant.EPOCH);
    }

    private static RiskDataConsumer consumer(RiskProjection projection, List<Fill> history,
                                             List<String> tapped) {
        return new RiskDataConsumer("localhost:0", projection, null, null,
                f -> tapped.add(f.fillId()), () -> history);
    }

    @Test
    void seedRebuildsPositionsFromTheFillsTable() {
        var projection = new RiskProjection(REFS);
        var tapped = new ArrayList<String>();
        List<Fill> history = List.of(
                fill("f1", "AAPL", Side.BUY, "100", "10"),
                fill("f2", "AAPL", Side.SELL, "40", "12"));

        consumer(projection, history, tapped).seedFromFillsTable();
        projection.applyMark("AAPL", new BigDecimal("12"), 1_000);

        var pos = projection.snapshot(1_000).positions();
        assertEquals(1, pos.size());
        assertEquals(0, new BigDecimal("60").compareTo(pos.get(0).quantity()), "100 bought − 40 sold");
        assertEquals(0, new BigDecimal("80").compareTo(pos.get(0).realizedPnl()), "(12−10)×40");
        assertEquals(List.of("f1", "f2"), tapped, "the tap fired for every seeded fill");
    }

    @Test
    void seedIsIdempotentSoTopicRedeliveryOfASeededFillIsANoOp() {
        var projection = new RiskProjection(REFS);
        var tapped = new ArrayList<String>();
        Fill f = fill("f1", "AAPL", Side.BUY, "100", "10");

        // Seed from the table, then the SAME fill arrives again (as it would live on the topic).
        consumer(projection, List.of(f), tapped).seedFromFillsTable();
        projection.applyFill(f); // topic redelivery of a fill already in the seed
        projection.applyMark("AAPL", new BigDecimal("11"), 1_000);

        var pos = projection.snapshot(1_000).positions();
        assertEquals(0, new BigDecimal("100").compareTo(pos.get(0).quantity()), "not 200 — deduped");
        assertEquals(0, new BigDecimal("100").compareTo(pos.get(0).unrealizedPnl()), "100×(11−10)");
    }

    @Test
    void emptyHistorySeedsNothingAndDoesNotThrow() {
        var projection = new RiskProjection(REFS);
        consumer(projection, List.of(), new ArrayList<>()).seedFromFillsTable();
        assertTrue(projection.snapshot(1_000).positions().isEmpty());
    }
}
