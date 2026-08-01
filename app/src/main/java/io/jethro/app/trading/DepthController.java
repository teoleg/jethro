package io.jethro.app.trading;

import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.runtime.QuoteCache;
import io.jethro.trading.runtime.TradingCoreRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Top-of-book depth per instrument (ADR-0033): best bid/ask and the synthesized size resting at
 * each touch (from real volume — a model of depth, NOT a real L2 book). Read-only; decimals as
 * strings (invariant 1). Populated for the sim feed; a real feed without quote sizes shows blanks.
 */
@RestController
public final class DepthController {

    public record DepthDto(String instrumentId, String name, String bid, String ask,
                           String bidSize, String askSize) {
    }

    private final ObjectProvider<TradingCoreLifecycle> tradingCore;
    private final ObjectProvider<RefDataRepository> refData;
    private final TradingCoreProperties properties;

    public DepthController(ObjectProvider<TradingCoreLifecycle> tradingCore,
                           ObjectProvider<RefDataRepository> refData, TradingCoreProperties properties) {
        this.tradingCore = tradingCore;
        this.refData = refData;
        this.properties = properties;
    }

    @GetMapping("/api/depth")
    public List<DepthDto> depth() {
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        TradingCoreRuntime rt = core != null ? core.runtime() : null;
        if (rt == null) {
            return List.of();
        }
        Map<String, String> names = instrumentNames();
        List<DepthDto> out = new ArrayList<>();
        for (String id : universeIds()) { // the DYNAMIC refdata master (invariant 9), not a hardcoded list
            QuoteCache.QuoteHolder q = rt.quoteCache().get(id);
            if (q == null || q.bidSizeScaled() <= 0) {
                continue; // no quoted depth for this instrument (e.g. curve pseudo-quotes)
            }
            out.add(new DepthDto(id, names.getOrDefault(id, id),
                    q.bid().toPlainString(), q.ask().toPlainString(),
                    q.bidSize().toPlainString(), q.askSize().toPlainString()));
        }
        return out;
    }

    private Map<String, String> instrumentNames() {
        RefDataRepository rd = refData.getIfAvailable();
        return rd != null ? rd.instrumentAttribute("name") : Map.of();
    }

    /** The current tradable universe from the refdata master (invariant 9) — dynamic, so a discovery-
     *  promoted or seeded name shows up here with no edit. Empty when refdata isn't wired. */
    private List<String> universeIds() {
        RefDataRepository rd = refData.getIfAvailable();
        return rd == null ? List.of()
                : rd.findAllInstruments().stream().map(i -> i.id().value()).toList();
    }
}
