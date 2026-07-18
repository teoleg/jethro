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
 * the book's single-name equity USD exposure and size the index-future TARGET hedge
 * ({@link HedgeMath}) in two tiers (ADR-0040): the STATISTICAL min-variance hedge when the
 * covariance is ready and the measured ρ² clears the floor, otherwise the STRUCTURAL hedge from
 * assigned fundamental betas — so the book is hedged even with no return history.
 *
 * <p><b>The proposal is a DELTA, not the full hedge</b> (ADR-0039's {@code net = e + h}): the
 * advisor takes the hedge book's CURRENT proxy position and proposes {@code target − held}.
 * On-target books propose nothing; a book whose equities go flat gets its residual hedge UNWOUND
 * (no underlying → no hedge). A delta smaller than the ADR-0039 min-trade notional ($10k) is
 * suppressed as churn — without this feedback, AUTO would re-submit the full hedge every cooldown
 * and compound the position without bound (2026-07-18 math review, P1-1).
 *
 * <p>Pure evaluation — all live state is passed in, so the whole decision is unit-testable and,
 * being deterministic, reproducible from the same inputs (invariant 7). Mode is OFF/ADVISE/AUTO;
 * AUTO order submission is {@link HedgeLifecycle}, the FX/RATES axes are the next increments.
 */
public final class HedgeAdvisor {

    public enum Mode { OFF, ADVISE, AUTO }

    /** One hedge axis: the pure exposure, held/target hedge, and (when acting) the sized DELTA.
     *  {@code tier} is STATISTICAL (ADR-0038, measured ρ²), STRUCTURAL (ADR-0040, assigned beta,
     *  effectiveness asserted so {@code effectiveness} is null), or "—" when not sizing. */
    public record Axis(String axis, String proxyId, BigDecimal netExposureUsd, BigDecimal floorUsd,
                       double utilization, boolean hedging, boolean hedgeRecommended,
                       String hedgeSide, BigDecimal hedgeQuantity, BigDecimal hedgeNotionalUsd,
                       Double effectiveness, BigDecimal grossSigmaUsd, BigDecimal residualSigmaUsd,
                       BigDecimal heldProxyQty, BigDecimal targetProxyQty,
                       String status, String tier, String rationale) {
    }

    public record Snapshot(String mode, boolean covarianceReady, List<Axis> axes, String note) {
    }

    private volatile Mode mode;
    private final BigDecimal rebalanceFloorUsd;
    private final BigDecimal minTradeNotionalUsd;
    private final double effectivenessFloor;
    private final String equityProxyId;
    private final BigDecimal equityProxyMultiplier;

    public HedgeAdvisor(Mode mode, BigDecimal rebalanceFloorUsd, BigDecimal minTradeNotionalUsd,
                        double effectivenessFloor, String equityProxyId, BigDecimal equityProxyMultiplier) {
        this.mode = mode;
        this.rebalanceFloorUsd = rebalanceFloorUsd == null ? BigDecimal.ZERO : rebalanceFloorUsd.abs();
        this.minTradeNotionalUsd = minTradeNotionalUsd == null ? BigDecimal.ZERO : minTradeNotionalUsd.abs();
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

    /** The configured equity hedge proxy (e.g. ES) — callers read the held position of THIS id. */
    public String equityProxyId() {
        return equityProxyId;
    }

    /** The sized target from whichever tier could produce one this cycle. */
    private record Target(BigDecimal signedQty, String tier, Double effectiveness,
                          BigDecimal grossSigmaUsd, BigDecimal residualSigmaUsd, String rationale) {
    }

    /**
     * Evaluate the equity hedge axis from live inputs.
     *
     * @param covariance the EWMA return covariance (empty during warm-up)
     * @param exposuresUsd USD exposure per instrument (firm)
     * @param isEquity     true for single-name equities (the axis members; NOT the index proxy)
     * @param priceOf      current price of an instrument (for the proxy)
     * @param betaOf       assigned fundamental beta of an instrument (ADR-0040 structural tier)
     * @param heldProxyQty the hedge book's CURRENT signed proxy position (negative = short) —
     *                     the {@code h} in ADR-0039's {@code net = e + h}; the proposal is the delta
     */
    public Snapshot evaluate(Optional<CovMath.Covariance> covariance,
                             Map<String, BigDecimal> exposuresUsd,
                             Predicate<String> isEquity,
                             Function<String, Optional<BigDecimal>> priceOf,
                             Function<String, Optional<BigDecimal>> betaOf,
                             BigDecimal heldProxyQty) {
        Map<String, BigDecimal> equityExposures = exposuresUsd.entrySet().stream()
                .filter(e -> isEquity.test(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        BigDecimal net = equityExposures.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal held = heldProxyQty == null ? BigDecimal.ZERO : heldProxyQty;

        Axis axis;
        if (mode == Mode.OFF) {
            axis = idle(net, held, "OFF", "hedging OFF for this axis");
        } else {
            Optional<BigDecimal> proxyPrice = priceOf.apply(equityProxyId);
            if (proxyPrice.isEmpty()) {
                axis = idle(net, held, "WARMING", "no " + equityProxyId
                        + " price yet — cannot size or adjust the hedge");
            } else {
                axis = act(net, held, equityExposures, covariance, proxyPrice.get(), betaOf);
            }
        }
        String note = mode == Mode.AUTO
                ? "AUTO — the book is held target-flat: the hedge DELTA (target − held) auto-submits (sim-gated, ADR-0019)"
                : "ADVISE — the sized hedge-to-flat surfaces here; execute from the ticket";
        return new Snapshot(mode.name(), covariance.isPresent(), List.of(axis), note);
    }

    /**
     * Target-then-delta (the P1-1 fix): compute the signed TARGET proxy quantity from the pure
     * equity book (zero when |net| is inside the rebalance floor — which drives the unwind of any
     * residual hedge), then propose {@code target − held}. Deltas under the min-trade notional are
     * suppressed (ADR-0039 churn guard) and reported as on-target.
     */
    private Axis act(BigDecimal net, BigDecimal held, Map<String, BigDecimal> equityExposures,
                     Optional<CovMath.Covariance> covariance, BigDecimal proxyPrice,
                     Function<String, Optional<BigDecimal>> betaOf) {
        boolean flatTarget = net.abs().compareTo(rebalanceFloorUsd) <= 0;
        Target target;
        if (flatTarget) {
            target = new Target(BigDecimal.ZERO, "—", null, null, null,
                    "net equity |" + money(net) + "| ≤ " + money(rebalanceFloorUsd)
                            + " floor — target hedge is zero");
        } else {
            target = sizeTarget(equityExposures, covariance, proxyPrice, betaOf);
            if (target == null) {
                return idle(net, held, "WARMING", "net equity " + money(net) + " to hedge, but neither "
                        + "a ready covariance nor assigned betas — cannot size a target yet"
                        + (held.signum() != 0 ? " (holding " + plain(held) + " " + equityProxyId + ")" : ""));
            }
            if (target.signedQty() == null) {
                // Statistical tier sized it but ρ² is below the floor and no structural fallback:
                // the honest desk call is to REDUCE the position, not pretend the proxy hedges it.
                return idle(net, held, "REDUCE", target.rationale());
            }
        }
        BigDecimal delta = target.signedQty().subtract(held);
        BigDecimal deltaNotional = delta.abs().multiply(proxyPrice).multiply(equityProxyMultiplier);
        String heldVsTarget = "held " + plain(held) + " → target " + plain(target.signedQty())
                + " " + equityProxyId;
        if (delta.signum() == 0 || deltaNotional.compareTo(minTradeNotionalUsd) < 0) {
            String status = target.signedQty().signum() == 0 && held.signum() == 0 ? "FLAT" : "ON-TARGET";
            return new Axis("EQUITY", equityProxyId, money(net), rebalanceFloorUsd,
                    eff(target), false, false, null, null, null,
                    target.effectiveness(), target.grossSigmaUsd(), target.residualSigmaUsd(),
                    plain(held), plain(target.signedQty()), status, target.tier(),
                    heldVsTarget + " — delta " + money(deltaNotional)
                            + " under the " + money(minTradeNotionalUsd) + " min trade, holding");
        }
        String side = delta.signum() < 0 ? "SELL" : "BUY";
        String status = target.signedQty().signum() == 0 ? "UNWIND" : "HEDGE";
        return new Axis("EQUITY", equityProxyId, money(net), rebalanceFloorUsd,
                eff(target), true, true, side, delta.abs(),
                delta.abs().multiply(proxyPrice).multiply(equityProxyMultiplier).setScale(2, RoundingMode.HALF_UP),
                target.effectiveness(), target.grossSigmaUsd(), target.residualSigmaUsd(),
                plain(held), plain(target.signedQty()), status, target.tier(),
                heldVsTarget + " → " + side + " " + plain(delta.abs()) + " · " + target.rationale());
    }

    /**
     * The signed target proxy quantity for the pure equity book — STATISTICAL (measured β̂/ρ²,
     * ADR-0038) when the covariance is ready and clears the effectiveness floor, else STRUCTURAL
     * (assigned betas, ADR-0040). Effectiveness/tier are judged on the PURE book, never the
     * residual after hedging (a well-hedged residual is ~uncorrelated with the proxy by
     * construction — judging it would flap the tiers). Null = cannot size; a Target with null
     * quantity = sized but below the ρ² floor with no structural fallback (REDUCE).
     */
    private Target sizeTarget(Map<String, BigDecimal> equityExposures,
                              Optional<CovMath.Covariance> covariance, BigDecimal proxyPrice,
                              Function<String, Optional<BigDecimal>> betaOf) {
        Optional<HedgeMath.HedgeProposal> statistical = covariance.flatMap(cov ->
                HedgeMath.betaHedge(cov, equityExposures, equityProxyId, proxyPrice,
                        equityProxyMultiplier, effectivenessFloor));
        if (statistical.isPresent() && statistical.get().recommended()) {
            var p = statistical.get();
            return new Target(p.signedQuantity(), "STATISTICAL", p.effectiveness(),
                    p.grossSigmaUsd(), p.residualSigmaUsd(), p.rationale());
        }
        Map<String, BigDecimal> betas = new java.util.HashMap<>();
        for (String id : equityExposures.keySet()) {
            betaOf.apply(id).ifPresent(b -> betas.put(id, b));
        }
        Optional<HedgeMath.StructuralHedge> structural = HedgeMath.structuralBetaHedge(
                equityExposures, betas, equityProxyId, proxyPrice, equityProxyMultiplier);
        if (structural.isPresent()) {
            return new Target(structural.get().signedQuantity(), "STRUCTURAL", null, null, null,
                    structural.get().rationale());
        }
        if (statistical.isPresent()) {
            return new Target(null, "STATISTICAL", statistical.get().effectiveness(),
                    statistical.get().grossSigmaUsd(), statistical.get().residualSigmaUsd(),
                    statistical.get().rationale());
        }
        return null;
    }

    /** Nothing to trade this cycle (OFF, warming, or reduce-don't-hedge). Held is still shown. */
    private Axis idle(BigDecimal net, BigDecimal held, String status, String rationale) {
        return new Axis("EQUITY", equityProxyId, money(net), rebalanceFloorUsd,
                0.0, false, false, null, null, null, null, null, null,
                plain(held), null, status, "—", rationale);
    }

    private static double eff(Target t) {
        return t.effectiveness() != null ? t.effectiveness() : 0.0;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Quantities in messages: 6dp, trailing zeros stripped (0E-7 noise never reaches the UI). */
    private static BigDecimal plain(BigDecimal v) {
        return v == null ? null : v.setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }
}
