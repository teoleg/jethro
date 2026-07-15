package io.jethro.app.risk;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bucketed DV01 per book (quant-engine step 4) — supersedes the static key-tenor
 * {@code RatesRiskService} view at the same path: buckets are now the CURVE NODES, swap
 * legs come from the trade-dated book's Strata sensitivities (roll-down included) and
 * futures key-rate-split at their CTD maturity. {@code curveLive=false} means swap legs
 * are unvalued this cycle (futures still carry their duration-based DV01); {@code skipped}
 * counts rates positions omitted for missing data — disclosed, never guessed.
 */
@RestController
@RequestMapping("/api/dv01")
public class Dv01Controller {

    private final Dv01Service service;

    public Dv01Controller(Dv01Service service) {
        this.service = service;
    }

    @GetMapping
    public Dv01Service.Dv01View dv01() {
        return service.view(System.currentTimeMillis());
    }
}
