package io.jethro.app.trading;

import io.jethro.trading.marketdata.FeedStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Feed connection status for the UI's per-feed indicators (ADR-0023). Reports the live
 * market-data feed honestly: which provider is active, whether it's connected, and how stale
 * the DATA is at source (a real, ~15-min-delayed provider is "connected but delayed", not
 * "down"). Structured as a list so more feed types slot in as real health signals exist.
 */
@RestController
public final class FeedsController {

    /** delaySeconds beyond this counts the data as materially delayed (amber, not green). */
    private static final long DELAYED_THRESHOLD_SECONDS = 60;

    public record FeedDto(String type, String provider, boolean connected,
                          long lastUpdateAgeMillis, boolean delayed, long delaySeconds) {
    }

    private final ObjectProvider<TradingCoreLifecycle> tradingCore;

    public FeedsController(ObjectProvider<TradingCoreLifecycle> tradingCore) {
        this.tradingCore = tradingCore;
    }

    @GetMapping("/api/feeds")
    public List<FeedDto> feeds() {
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        if (core == null) {
            return List.of(new FeedDto("market-data", "none", false, -1, false, 0));
        }
        FeedStatus s = core.feedStatus();
        long ageMillis = s.lastUpdateMillis() > 0 ? System.currentTimeMillis() - s.lastUpdateMillis() : -1;
        return List.of(new FeedDto("market-data", s.provider(), s.connected(), ageMillis,
                s.dataDelaySeconds() > DELAYED_THRESHOLD_SECONDS, s.dataDelaySeconds()));
    }
}
