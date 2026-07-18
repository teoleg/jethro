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
 * size the index-future hedge back to flat ({@link HedgeMath}) in two tiers (ADR-0040): the
 * STATISTICAL min-variance hedge when the covariance is ready and the measured ρ² clears the floor,
 * otherwise the STRUCTURAL hedge from assigned fundamental betas — so the book is hedged even with
 * no return history. Only when neither tier can size does the advisor wait.
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

    /** One hedge axis with its exposure, hedge state, and (when acting) the sized proposal.
     *  {@code tier} is STATISTICAL (ADR-0038, measured ρ²), STRUCTURAL (ADR-0040, assigned beta,
     *  effectiveness asserted so {@code effectiveness} is null), or "—" when not hedging. */
    public record Axis(String axis, String proxyId, BigDecimal netExposureUsd, BigDecimal floorUsd,
                       double utilization, boolean hedging, boolean hedgeRecommended,
                       String hedgeSide, BigDecimal hedgeQuantity, BigDecimal hedgeNotionalUsd,
                       Double effectiveness, BigDecimal grossSigmaUsd, BigDecimal residualSigmaUsd,
                       String status, String tier, String rationale) {
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
     * @param betaOf       assigned fundamental beta of an instrument (ADR-0040 structural tier)
     */
    public Snapshot evaluate(Optional<CovMath.Covariance> covariance,
                             Map<String, BigDecimal> exposuresUsd,
                             Predicate<String> isEquity,
                             Function<String, Optional<BigDecimal>> priceOf,
                             Function<String, Optional<BigDecimal>> betaOf) {
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
            axis = hedge(net, equityExposures, covariance, priceOf.apply(equityProxyId), betaOf);
        }
        String note = mode == Mode.AUTO
                ? "AUTO — the book is held target-flat: any net equity auto-submits its hedge (sim-gated, ADR-0019)"
                : "ADVISE — the sized hedge-to-flat surfaces here; execute from the ticket";
        return new Snapshot(mode.name(), covariance.isPresent(), List.of(axis), note);
    }

    /**
     * Two-tier hedge selection (ADR-0040): use the STATISTICAL min-variance hedge (ADR-0038) when
     * the covariance is ready, the proxy is in its window, and the measured ρ² clears the floor;
     * otherwise fall back to the STRUCTURAL hedge sized from assigned fundamental betas — no history.
     * Only when neither can size (no proxy price, or no covariance AND no assigned betas) do we wait.
     */
    private Axis hedge(BigDecimal net, Map<String, BigDecimal> equityExposures,
                       Optional<CovMath.Covariance> covariance, Optional<BigDecimal> proxyPrice,
                       Function<String, Optional<BigDecimal>> betaOf) {
        if (proxyPrice.isEmpty()) {
            return warming(net, "net equity " + money(net) + " to hedge to flat, but no " + equityProxyId
                    + " price yet — cannot size a hedge");
        }
        if (covariance.isPresent()) {
            Optional<HedgeMath.HedgeProposal> p = HedgeMath.betaHedge(covariance.get(), equityExposures,
                    equityProxyId, proxyPrice.get(), equityProxyMultiplier, effectivenessFloor);
            if (p.isPresent() && p.get().recommended()) {
                return statistical(net, p.get()); // measured ρ² clears the floor — the better tier
            }
        }
        Map<String, BigDecimal> betas = new java.util.HashMap<>();
        for (String id : equityExposures.keySet()) {
            betaOf.apply(id).ifPresent(b -> betas.put(id, b));
        }
        Optional<HedgeMath.StructuralHedge> s = HedgeMath.structuralBetaHedge(
                equityExposures, betas, equityProxyId, proxyPrice.get(), equityProxyMultiplier);
        if (s.isPresent()) {
            return structural(net, s.get());
        }
        return warming(net, "net equity " + money(net) + " to hedge, but neither a ready covariance nor "
                + "assigned betas for " + equityProxyId + " — cannot size a hedge yet");
    }

    private Axis statistical(BigDecimal net, HedgeMath.HedgeProposal p) {
        String side = p.signedQuantity().signum() < 0 ? "SELL" : "BUY";
        return new Axis("EQUITY", equityProxyId, net.setScale(2, RoundingMode.HALF_UP), rebalanceFloorUsd,
                p.effectiveness(), true, true, side, p.signedQuantity().abs(),
                p.hedgeNotionalUsd().abs(), p.effectiveness(), p.grossSigmaUsd(), p.residualSigmaUsd(),
                "HEDGE", "STATISTICAL", p.rationale());
    }

    private Axis structural(BigDecimal net, HedgeMath.StructuralHedge s) {
        String side = s.signedQuantity().signum() < 0 ? "SELL" : "BUY";
        // effectiveness is ASSERTED by the assigned betas, not measured — reported as null, never ρ².
        return new Axis("EQUITY", equityProxyId, net.setScale(2, RoundingMode.HALF_UP), rebalanceFloorUsd,
                0.0, true, true, side, s.signedQuantity().abs(), s.hedgeNotionalUsd().abs(),
                null, null, null, "HEDGE", "STRUCTURAL", s.rationale());
    }

    /** Nothing to do on this axis: flat, or hedging OFF. */
    private Axis flat(BigDecimal net, String status, String rationale) {
        return new Axis("EQUITY", equityProxyId, net.setScale(2, RoundingMode.HALF_UP), rebalanceFloorUsd,
                0.0, false, false, null, null, null, null, null, null, status, "—", rationale);
    }

    /** Net exposure to hedge, but we can't size it yet (no proxy price, or no covariance and no betas). */
    private Axis warming(BigDecimal net, String rationale) {
        return new Axis("EQUITY", equityProxyId, net.setScale(2, RoundingMode.HALF_UP), rebalanceFloorUsd,
                0.0, true, false, null, null, null, null, null, null, "WARMING", "—", rationale);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
