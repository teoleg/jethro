package io.jethro.app.indicators;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Market-indicators strip: major index/market context on the top bar. Sim runs serve the
 * strip from the sim tape (regime + marks + day-over-day, {@link SimIndicatorsSource});
 * live runs from delayed Yahoo levels ({@link IndicatorsService}). Display only — never
 * positions or PnL. Empty when disabled or offline.
 */
@RestController
public final class IndicatorsController {

    public record IndicatorDto(String symbol, String label, String price, Double changePercent) {
    }

    private final ObjectProvider<IndicatorsSource> source;

    public IndicatorsController(ObjectProvider<IndicatorsSource> source) {
        this.source = source;
    }

    @GetMapping("/api/indicators")
    public List<IndicatorDto> indicators() {
        IndicatorsSource live = source.getIfAvailable();
        if (live == null) {
            return List.of();
        }
        return live.latest().stream()
                .map(i -> new IndicatorDto(i.symbol(), i.label(), i.price(), i.changePercent()))
                .toList();
    }

    /** Diagnostics: raw Yahoo response for one symbol (default ^GSPC) — status + first bytes.
     *  Only meaningful on the Yahoo source; the sim strip has nothing to probe. */
    @GetMapping("/api/indicators/probe")
    public IndicatorsService.ProbeResult probe(
            @org.springframework.web.bind.annotation.RequestParam(name = "symbol", defaultValue = "^GSPC") String symbol) {
        if (source.getIfAvailable() instanceof IndicatorsService yahoo) {
            return yahoo.probe(symbol);
        }
        return new IndicatorsService.ProbeResult("(sim indicators — nothing to probe)", -1, null,
                "the strip is derived from the sim tape, not a remote fetch");
    }
}
