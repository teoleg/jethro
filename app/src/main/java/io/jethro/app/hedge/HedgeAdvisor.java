package io.jethro.app.hedge;

import io.jethro.trading.riskpnl.CovMath;
import io.jethro.trading.riskpnl.HedgeMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Hedge advisor (ADR-0039): turns live exposures into per-axis hedge proposals. The book is kept
 * <b>target-flat</b> — any net equity exposure is hedged back to zero, not run naked up to a cap.
 * Firm-level for v1 (per-book is the ADR target — a follow-up). The EQUITY axis is live now: sum
 * the book's single-name equity USD exposure and, whenever |net| is above a small anti-churn floor,
 * size the minimum-variance index-future hedge back to flat ({@link HedgeMath}), carrying the
 * effectiveness ρ² so a weak proxy is flagged "reduce, don't hedge".
 *
 * <p>The floor exists only so a residual smaller than one worth trading isn't churned every cycle;
 * default it to 0 and the book is hedged to flat on any net exposure. There is no exposure band
 * inside which the book is left unhedged — that was rejected: a live book always carries a hedge.
 *
 * <p>Pure evaluation — all live state is passed in, so the whole decision is unit-testable and,
 * being deterministic, reproducible from the same inputs (invariant 7). Mode is OFF/ADVISE/AUTO;
 * AUTO order submission is {@link HedgeLifecycle}, the FX/RATES axes are the next increments.
 */
public final class HedgeAdvisor {

    public enum Mode { OFF, ADVISE, AUTO }

    /** One hedge axis with its exposure, hedge state, and (when acting) the sized proposal. */
    public record Axis(String axis, String proxyId, BigDecimal netExposureUsd, BigDecimal floorUsd,
                       double utilization, boolean hedging, boolean hedgeRecommended,
                       String hedgeSide, BigDecimal hedgeQuantity, BigDecimal hedgeNotionalUsd,
                       Double effectiveness, BigDecimal grossSigmaUsd, BigDecimal residualSigmaUsd,
                       String status, String rationale) {
    }

    public record Snapshot(String mode, boolean covarianceReady, List<Axis> axes, String note) {
    }

    private volatile Mode mode;
    private final BigDecimal rebalanceFloorUsd;
    private final double effectivenessFloor;
    private final String equityProxyId;
    private final BigDecimal equityProxyMultiplier;

    public HedgeAdvisor(Mode mode, BigDecimal rebalanceFloorUsd, double effectivenessFloor,
                        String equityProxyId, BigDecimal equityProxyMultiplier) {
        this.mode = mode;
        this.rebalanceFloorUsd = rebalanceFloorUsd == null ? BigDecimal.ZERO : rebalanceFloorUsd.abs();
        this.effectivenessFloor = effectivenessFloor;
        this.equityProxyId = equityProxyId;
        this.equityProxyMultiplier = equityProxyMultiplier;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /**
     * Evaluate the equity hedge axis from live inputs.
     *
     * @param covariance the EWMA return covariance (empty during warm-up)
     * @param exposuresUsd USD exposure per instrument (firm)
     * @param isEquity     true for single-name equities (the axis members; NOT the index proxy)
     * @param priceOf      current price of an instrument (for the proxy)
     */
    public Snapshot evaluate(Optional<CovMath.Covariance> covariance,
                             Map<String, BigDecimal> exposuresUsd,
                             Predicate<String> isEquity,
                             Function<String, Optional<BigDecimal>> priceOf) {
        Map<String, BigDecimal> equityExposures = exposuresUsd.entrySet().stream()
                .filter(e -> isEquity.test(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        BigDecimal net = equityExposures.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Axis axis;
        if (mode == Mode.OFF) {
            axis = flat(net, "OFF", "hedging OFF for this axis");
        } else if (net.abs().compareTo(rebalanceFloorUsd) <= 0) {
            axis = flat(net, "FLAT", "net equity flat: |" + money(net) + "| ≤ " + money(rebalanceFloorUsd)
                    + " rebalance floor — nothing to hedge");
        } else {
            Optional<BigDecimal> proxyPrice = priceOf.apply(equityProxyId);
            if (covariance.isEmpty() || proxyPrice.isEmpty()) {
                axis = warming(net, covariance.isEmpty()
                        ? "net equity " + money(net) + " to hedge to flat, but the covariance is still "
                                + "warming up — cannot size a hedge yet"
                        : "net equity " + money(net) + " to hedge to flat, but no " + equityProxyId
                                + " price yet — cannot size a hedge");
            } else {
                Optional<HedgeMath.HedgeProposal> p = HedgeMath.betaHedge(
                        covariance.get(), equityExposures, equityProxyId,
                        proxyPrice.get(), equityProxyMultiplier, effectivenessFloor);
                axis = p.map(hp -> sized(net, hp))
                        .orElse(warming(net, "net equity " + money(net) + " to hedge, but " + equityProxyId
                                + " is not in the covariance window yet — cannot size a hedge"));
            }
        }
        String note = mode == Mode.AUTO
                ? "AUTO — the book is held target-flat: any net equity auto-submits its hedge (sim-gated, ADR-0019)"
                : "ADVISE — the sized hedge-to-flat surfaces here; execute from the ticket";
        return new Snapshot(mode.name(), covariance.isPresent(), List.of(axis), note);
    }

    private Axis sized(BigDecimal net, HedgeMath.HedgeProposal p) {
        String side = p.signedQuantity().signum() < 0 ? "SELL" : "BUY";
        return new Axis("EQUITY", equityProxyId, net.setScale(2, RoundingMode.HALF_UP), rebalanceFloorUsd,
                p.effectiveness(), true, p.recommended(), side, p.signedQuantity().abs(),
                p.hedgeNotionalUsd().abs(), p.effectiveness(), p.grossSigmaUsd(), p.residualSigmaUsd(),
                p.recommended() ? "HEDGE" : "REDUCE", p.rationale());
    }

    /** Nothing to do on this axis: flat, or hedging OFF. */
    private Axis flat(BigDecimal net, String status, String rationale) {
        return new Axis("EQUITY", equityProxyId, net.setScale(2, RoundingMode.HALF_UP), rebalanceFloorUsd,
                0.0, false, false, null, null, null, null, null, null, status, rationale);
    }

    /** Net exposure to hedge, but we can't size it yet (covariance/price warming up). */
    private Axis warming(BigDecimal net, String rationale) {
        return new Axis("EQUITY", equityProxyId, net.setScale(2, RoundingMode.HALF_UP), rebalanceFloorUsd,
                0.0, true, false, null, null, null, null, null, null, "WARMING", rationale);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
