package io.jethro.app.hedge;

import io.jethro.app.risk.VarService;
import io.jethro.domain.InstrumentId;
import io.jethro.order.LastPriceCache;
import io.jethro.trading.riskpnl.CovMath;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Hedging REST surface (ADR-0038/0039): the live per-axis exposure, band state and sized proposal
 * the landing-page panel renders — the "how the math works" view. Reads exposures + EWMA covariance
 * from {@link VarService}, asset class from reference data, and the proxy price from the shared
 * last-price cache; the decision itself is the deterministic {@link HedgeAdvisor}.
 */
@RestController
public final class HedgeController {

    private final HedgeAdvisor advisor;
    private final ObjectProvider<VarService> varService;
    private final ObjectProvider<InstrumentRefSource> refs;
    private final ObjectProvider<LastPriceCache> prices;
    private final ObjectProvider<io.jethro.trading.riskpnl.RiskProjection> projection;
    private final String hedgeBook;

    public HedgeController(HedgeAdvisor advisor, ObjectProvider<VarService> varService,
                           ObjectProvider<InstrumentRefSource> refs, ObjectProvider<LastPriceCache> prices,
                           ObjectProvider<io.jethro.trading.riskpnl.RiskProjection> projection,
                           @org.springframework.beans.factory.annotation.Value("${jethro.hedge.book:HEDGE}")
                           String hedgeBook) {
        this.advisor = advisor;
        this.varService = varService;
        this.refs = refs;
        this.prices = prices;
        this.projection = projection;
        this.hedgeBook = hedgeBook;
    }

    @GetMapping("/api/hedging")
    public HedgeAdvisor.Snapshot hedging() {
        VarService vs = varService.getIfAvailable();
        if (vs == null) {
            return new HedgeAdvisor.Snapshot(advisor.mode().name(), false, List.of(),
                    "risk service unavailable");
        }
        Optional<CovMath.Covariance> cov = vs.covarianceSnapshot();
        Map<String, BigDecimal> exposures = vs.exposuresUsd();

        InstrumentRefSource rf = refs.getIfAvailable();
        Predicate<String> isEquity = id -> rf != null
                && rf.find(id).map(r -> "EQUITY".equalsIgnoreCase(r.assetClass())).orElse(false);

        LastPriceCache pc = prices.getIfAvailable();
        Function<String, Optional<BigDecimal>> priceOf = id ->
                pc != null ? pc.lastPrice(new InstrumentId(id)) : Optional.empty();

        Function<String, Optional<BigDecimal>> betaOf = id -> rf == null ? Optional.empty()
                : rf.find(id).map(io.jethro.trading.riskpnl.InstrumentRef::hedgeBeta)
                        .filter(b -> b != null);

        var proj = projection.getIfAvailable();
        Map<String, BigDecimal> held = new java.util.HashMap<>();
        for (String proxy : advisor.proxyUniverse()) {
            held.put(proxy, proj != null
                    ? proj.positionQuantity(hedgeBook, proxy) : BigDecimal.ZERO);
        }

        // The panel is read-only, so the price gate suffices here; the executing lifecycle also
        // applies the quarantine gate (ADR-0042).
        return advisor.evaluate(cov, exposures, isEquity, priceOf, betaOf, held, id -> true);
    }

    public record ModeRequest(String mode) {
    }

    /** Set OFF/ADVISE/AUTO from the panel. */
    @PostMapping("/api/hedging/mode")
    public HedgeAdvisor.Snapshot setMode(@RequestBody ModeRequest req) {
        if (req == null || req.mode() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode is required (OFF/ADVISE/AUTO)");
        }
        try {
            advisor.setMode(HedgeAdvisor.Mode.valueOf(req.mode().trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown mode: " + req.mode());
        }
        return hedging();
    }
}
