package io.jethro.app.trading;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operator surface for mark quarantines (corporate action / bad print): list what's
 * quarantined and clear one after human review — clearing accepts the NEXT mark as the
 * new baseline (the split-adjusted level). Decimals as strings (invariant 1).
 */
@RestController
public final class MarkQuarantineController {

    public record QuarantineDto(String instrumentId, String lastGoodPrice, String suspectPrice) {
    }

    public record ClearResult(String instrumentId, boolean cleared, String note) {
    }

    private final ObjectProvider<TradingCoreLifecycle> tradingCore;

    public MarkQuarantineController(ObjectProvider<TradingCoreLifecycle> tradingCore) {
        this.tradingCore = tradingCore;
    }

    @GetMapping("/api/marks/quarantined")
    public List<QuarantineDto> quarantined() {
        var core = tradingCore.getIfAvailable();
        var runtime = core != null ? core.runtime() : null;
        if (runtime == null) {
            return List.of();
        }
        return runtime.markCache().quarantined().stream()
                .map(q -> new QuarantineDto(q.instrumentId(),
                        q.lastGoodPrice().toPlainString(), q.suspectPrice().toPlainString()))
                .toList();
    }

    @PostMapping("/api/marks/{instrumentId}/clear-quarantine")
    public ClearResult clear(@PathVariable String instrumentId) {
        var core = tradingCore.getIfAvailable();
        var runtime = core != null ? core.runtime() : null;
        if (runtime == null) {
            return new ClearResult(instrumentId, false, "trading core not running");
        }
        boolean cleared = runtime.markCache().clearQuarantine(instrumentId);
        return new ClearResult(instrumentId, cleared,
                cleared ? "next mark accepted as the new baseline" : "instrument was not quarantined");
    }
}
