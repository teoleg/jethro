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
 * Hedge advisor (ADR-0039): turns live exposures into per-axis hedge proposals under the
 * target-flat deadband. Firm-level for v1 (per-book is the ADR target — a follow-up). The
 * EQUITY axis is live now: sum the book's single-name equity USD exposure, and when |net|
 * exceeds the cap, size the minimum-variance index-future hedge back to flat ({@link HedgeMath}),
 * carrying the effectiveness ρ² so a weak proxy is flagged "reduce, don't hedge".
 *
 * <p>Pure evaluation — all live state is passed in, so the whole decision is unit-testable and,
 * being deterministic, reproducible from the same inputs (invariant 7). Mode is OFF/ADVISE/AUTO;
 * AUTO order submission and the FX/RATES axes are the next increments.
 */
public final class HedgeAdvisor {

    public enum Mode { OFF, ADVISE, AUTO }

    /** One hedge axis with its exposure, band state, and (when breached) the sized proposal. */
    public record Axis(String axis, String proxyId, BigDecimal netExposureUsd, BigDecimal capUsd,
                       double utilization, boolean breached, boolean hedgeRecommended,
                       String hedgeSide, BigDecimal hedgeQuantity, BigDecimal hedgeNotionalUsd,
                       Double effectiveness, BigDecimal grossSigmaUsd, BigDecimal residualSigmaUsd,
                       String status, String rationale) {
    }

    public record Snapshot(String mode, boolean covarianceReady, List<Axis> axes, String note) {
    }

    private volatile Mode mode;
    private final BigDecimal equityCapUsd;
    private final double effectivenessFloor;
    private final String equityProxyId;
    private final BigDecimal equityProxyMultiplier;

    public HedgeAdvisor(Mode mode, BigDecimal equityCapUsd, double effectivenessFloor,
                        String equityProxyId, BigDecimal equityProxyMultiplier) {
        this.mode = mode;
        this.equityCapUsd = equityCapUsd;
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
        double utilization = equityCapUsd.signum() > 0
                ? net.abs().doubleValue() / equityCapUsd.doubleValue() : 0.0;
        boolean breached = net.abs().compareTo(equityCapUsd) > 0;

        Axis axis;
        if (mode == Mode.OFF) {
            axis = plain(net, utilization, breached, "hedging OFF for this axis");
        } else if (!breached) {
            axis = plain(net, utilization, false,
                    "within the deadband — net equity " + money(net) + " under the "
                            + money(equityCapUsd) + " cap, no hedge needed");
        } else {
            Optional<BigDecimal> proxyPrice = priceOf.apply(equityProxyId);
            if (covariance.isEmpty() || proxyPrice.isEmpty()) {
                axis = plain(net, utilization, true, covariance.isEmpty()
                        ? "cap breached, but the covariance is still warming up — cannot size a hedge yet"
                        : "cap breached, but no " + equityProxyId + " price yet — cannot size a hedge");
            } else {
                Optional<HedgeMath.HedgeProposal> p = HedgeMath.betaHedge(
                        covariance.get(), equityExposures, equityProxyId,
                        proxyPrice.get(), equityProxyMultiplier, effectivenessFloor);
                axis = p.map(hp -> sized(net, utilization, hp))
                        .orElse(plain(net, utilization, true,
                                "cap breached, but " + equityProxyId
                                        + " is not in the covariance window yet — cannot size a hedge"));
            }
        }
        String note = mode == Mode.AUTO
                ? "AUTO — a breached, recommended axis auto-submits its hedge (sim-gated, ADR-0019)"
                : "ADVISE — proposals surface here; execute from the ticket";
        return new Snapshot(mode.name(), covariance.isPresent(), List.of(axis), note);
    }

    private Axis sized(BigDecimal net, double utilization, HedgeMath.HedgeProposal p) {
        String side = p.signedQuantity().signum() < 0 ? "SELL" : "BUY";
        return new Axis("EQUITY", equityProxyId, net.setScale(2, RoundingMode.HALF_UP), equityCapUsd,
                utilization, true, p.recommended(), side, p.signedQuantity().abs(),
                p.hedgeNotionalUsd().abs(), p.effectiveness(), p.grossSigmaUsd(), p.residualSigmaUsd(),
                p.recommended() ? "HEDGE" : "REDUCE", p.rationale());
    }

    private Axis plain(BigDecimal net, double utilization, boolean breached, String status) {
        return new Axis("EQUITY", equityProxyId, net.setScale(2, RoundingMode.HALF_UP), equityCapUsd,
                utilization, breached, false, null, null, null, null, null, null,
                breached ? "BREACHED" : "OK", status);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
