package io.jethro.app.trading;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the Rates UI whether the curve it's drawing is the LIVE Treasury curve or the factor
 * sim (ADR-0024), so it can label honestly ("US Treasury · live" vs "SOFR · sim") — the same
 * curve pipeline drives both, only the source differs.
 */
@RestController
public final class CurveSourceController {

    public record CurveSourceDto(String source, boolean live, String label) {
    }

    private final ObjectProvider<TradingCoreLifecycle> tradingCore;

    public CurveSourceController(ObjectProvider<TradingCoreLifecycle> tradingCore) {
        this.tradingCore = tradingCore;
    }

    @GetMapping("/api/curve/source")
    public CurveSourceDto source() {
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        String source = core != null ? core.curveSource() : "sim";
        boolean live = "treasury-live".equals(source);
        return new CurveSourceDto(source, live, live ? "US Treasury · live" : "SOFR curve · sim");
    }
}
