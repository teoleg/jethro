package io.jethro.trading.algo.agent;

import java.math.BigDecimal;
import java.util.List;

/**
 * The computed market state an agent reasons about — plain values, produced by
 * deterministic code (mark cache, later risk snapshots). The agent narrates this
 * data; it never computes it (ADR-0016).
 */
public record MarketView(List<InstrumentMark> marks, long ticksIn, long ticksDropped) {

    public record InstrumentMark(String instrumentId, BigDecimal price, boolean stale, long ageMillis) {
    }
}
