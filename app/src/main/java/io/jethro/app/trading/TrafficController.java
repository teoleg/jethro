package io.jethro.app.trading;

import io.jethro.trading.runtime.TradingCoreRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-path traffic state for the Ops screen (ADR-0014/0032): how much data the feed is
 * pushing through the ring buffer — ticks consumed, drops (never silent, invariant 9), marks
 * flushed, instruments live — plus the active provider and regime. Cumulative counters; the UI
 * derives a ticks/sec rate from successive polls.
 */
@RestController
public final class TrafficController {

    public record TrafficDto(boolean up, String provider, String regime, long instruments,
                             long ticksIn, long ticksDropped, long marksFlushed,
                             long warmLoadedMarks, long timestampMillis) {
    }

    private final ObjectProvider<TradingCoreLifecycle> tradingCore;

    public TrafficController(ObjectProvider<TradingCoreLifecycle> tradingCore) {
        this.tradingCore = tradingCore;
    }

    @GetMapping("/api/traffic")
    public TrafficDto traffic() {
        long now = System.currentTimeMillis();
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        TradingCoreRuntime rt = core != null ? core.runtime() : null;
        if (rt == null) {
            return new TrafficDto(false, "none", "n/a", 0, 0, 0, 0, 0, now);
        }
        var stats = rt.stats();
        String provider = core.feedStatuses().stream().findFirst()
                .map(io.jethro.trading.marketdata.FeedStatus::provider).orElse("n/a");
        return new TrafficDto(true, provider, core.regime(), rt.markCache().size(),
                stats.ticksIn(), stats.ticksDropped(), stats.marksFlushedTotal(),
                stats.warmLoadedMarks(), now);
    }
}
