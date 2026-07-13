package io.jethro.app.indicators;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Market-indicators strip (ADR-0023): major indices/ETFs (US + international) for context on
 * the top bar. Display only — never positions or PnL. Empty when disabled or offline.
 */
@RestController
public final class IndicatorsController {

    public record IndicatorDto(String symbol, String label, String price, double changePercent) {
    }

    private final ObjectProvider<IndicatorsService> service;

    public IndicatorsController(ObjectProvider<IndicatorsService> service) {
        this.service = service;
    }

    @GetMapping("/api/indicators")
    public List<IndicatorDto> indicators() {
        IndicatorsService live = service.getIfAvailable();
        if (live == null) {
            return List.of();
        }
        return live.latest().stream()
                .map(i -> new IndicatorDto(i.symbol(), i.label(), i.price(), i.changePercent()))
                .toList();
    }
}
