package io.jethro.app.hedge;

import io.jethro.trading.riskpnl.CovMath;
import io.jethro.trading.riskpnl.HedgeMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Hedge advisor (ADR-0039): turns live exposures into per-axis hedge proposals. The book is kept
 * <b>target-flat</b> — any net equity exposure is hedged back to zero, not run naked up to a cap.
 * Firm-level for v1 (per-book is the ADR target — a follow-up).
 *
 * <p><b>Proxy selection (ADR-0042):</b> each cycle the equity proxy is chosen from the candidate
 * list — tradable candidates only (live price, not quarantined), highest measured ρ² wins
 * (per-candidate multiplier from refdata), with a switch-hysteresis margin so a held proxy isn't
 * flipped on estimation noise. No candidate clearing the statistical gate (ADR-0038 ρ² floor +
 * ADR-0041 min-covariance-days) → the STRUCTURAL tier (ADR-0040, assigned betas vs the configured
 * proxy) carries the book.
 *
 * <p><b>The proposal is a DELTA, not the full hedge</b> (ADR-0039's {@code net = e + h}): per-proxy
 * targets are the selected proxy's sized target and ZERO for every other held proxy; the cycle
 * executes the largest delta above the no-trade band — so on-target books propose nothing, flat
 * books unwind their residual hedge, and a proxy switch unwinds the old instrument before building
 * the new. Without this feedback, AUTO re-submits the full hedge every cooldown and compounds the
 * position without bound (2026-07-18 math review, P1-1).
 *
 * <p><b>The no-trade band is scale-relative</b> (ADR-0069): a delta trades when it clears the
 * absolute ADR-0039 churn guard <b>or</b> a fraction of the hedge's own scale
 * ({@code max(|target|, |held|)} notional) — i.e. the threshold is the SMALLER of the two. An
 * absolute-dollar guard alone is an absolute barrier once the hedged book shrinks below it: the
 * whole hedge becomes untradable, including unwinding it, so a stale proxy leg is stranded on the
 * book forever and ADR-0039's "no underlying, no hedge" promise silently fails. Because the band
 * fraction is at most 1, {@code |delta| = scale} whenever the target is zero, so a full unwind is
 * always executable at any book size.
 *
 * <p>Pure evaluation — all live state is passed in, so the whole decision is unit-testable and,
 * being deterministic, reproducible from the same inputs (invariant 7). Mode is OFF/ADVISE/AUTO;
 * AUTO order submission is {@link HedgeLifecycle}, the FX/RATES axes are the next increments.
 */
public final class HedgeAdvisor {

    public enum Mode { OFF, ADVISE, AUTO }

    /** The one axis this advisor sizes today, and the key its ADR-0098 churn series is filed under. */
    private static final String EQUITY_AXIS = "EQUITY";

    /** One hedge axis: the pure exposure, held/target hedge, and (when acting) the sized DELTA on
     *  {@code proxyId} (the instrument being traded this cycle — selected, or an old proxy being
     *  unwound). {@code tier} is STATISTICAL (measured ρ²), STRUCTURAL (assigned beta,
     *  {@code effectiveness} null — asserted, never a fake ρ²), or "—" when not sizing.
     *  {@code rawTargetNotionalUsd} is the tier's target BEFORE the ADR-0098 churn shrinkage — the
     *  series {@link HedgeTargetChurn} samples — and {@code churnSigmaUsd} is the σ that was
     *  subtracted (null while warming). {@code trackingRate} is the ADR-0100 directional efficiency
     *  of that same series, the fraction of the remaining gap the hedge closes when it is GROWING
     *  the overlay (null while warming = closes it in full, the pre-ADR-0100 behaviour).
     *  {@code habitualNetUsd} is the ADR-0105 band — the median |net exposure| of this axis's own
     *  sampled history (null while warming) — and {@code excessFraction} the share of the current
     *  net standing above it, which is the fraction of the sized hedge actually worn. */
    public record Axis(String axis, String proxyId, BigDecimal netExposureUsd, BigDecimal floorUsd,
                       double utilization, boolean hedging, boolean hedgeRecommended,
                       String hedgeSide, BigDecimal hedgeQuantity, BigDecimal hedgeNotionalUsd,
                       Double effectiveness, BigDecimal grossSigmaUsd, BigDecimal residualSigmaUsd,
                       BigDecimal heldProxyQty, BigDecimal targetProxyQty,
                       String status, String tier, String rationale,
                       BigDecimal rawTargetNotionalUsd, BigDecimal churnSigmaUsd,
                       BigDecimal trackingRate, BigDecimal habitualNetUsd,
                       BigDecimal excessFraction) {
    }

    public record Snapshot(String mode, boolean covarianceReady, List<Axis> axes, String note) {
    }

    private volatile Mode mode;
    private final BigDecimal rebalanceFloorUsd;
    private final BigDecimal minTradeNotionalUsd;
    private final BigDecimal noTradeBandFraction;
    private final double effectivenessFloor;
    private final int minCovarianceDays;
    private final List<String> proxyCandidates;
    private final double proxySwitchMargin;
    private final String structuralProxyId;
    private final Function<String, Optional<BigDecimal>> multiplierOf;
    private final BigDecimal churnSigmaMultiple;

    /**
     * @param minTradeNotionalUsd  absolute churn guard on the hedge delta (ADR-0039 Decision 2)
     * @param noTradeBandFraction  the delta also trades at this fraction of the hedge's own scale
     *                             (ADR-0069) — clamped to [0,1]; 0 disables the relative leg and
     *                             restores the pure-absolute ADR-0039 behaviour
     * @param proxyCandidates    equity proxies evaluated each cycle (ADR-0042), e.g. [ES, NQ]
     * @param proxySwitchMargin  ρ² edge a challenger needs over the HELD proxy to switch
     * @param structuralProxyId  the proxy assigned betas are quoted against (structural tier)
     * @param multiplierOf       contract multiplier per proxy (refdata; config fallback wired
     *                           by the caller)
     */
    public HedgeAdvisor(Mode mode, BigDecimal rebalanceFloorUsd, BigDecimal minTradeNotionalUsd,
                        BigDecimal noTradeBandFraction,
                        double effectivenessFloor, int minCovarianceDays,
                        List<String> proxyCandidates, double proxySwitchMargin,
                        String structuralProxyId, Function<String, Optional<BigDecimal>> multiplierOf) {
        this(mode, rebalanceFloorUsd, minTradeNotionalUsd, noTradeBandFraction, effectivenessFloor,
                minCovarianceDays, proxyCandidates, proxySwitchMargin, structuralProxyId,
                multiplierOf, BigDecimal.ONE);
    }

    /**
     * @param churnSigmaMultiple how many σ of the target's own step between hedge evaluations is
     *                           subtracted from the target before it is traded (ADR-0098) — clamped
     *                           at ≥ 0; 0 restores the pre-ADR-0098 hedge-to-exactly-flat behaviour
     */
    public HedgeAdvisor(Mode mode, BigDecimal rebalanceFloorUsd, BigDecimal minTradeNotionalUsd,
                        BigDecimal noTradeBandFraction,
                        double effectivenessFloor, int minCovarianceDays,
                        List<String> proxyCandidates, double proxySwitchMargin,
                        String structuralProxyId, Function<String, Optional<BigDecimal>> multiplierOf,
                        BigDecimal churnSigmaMultiple) {
        this.churnSigmaMultiple = churnSigmaMultiple == null ? BigDecimal.ONE
                : churnSigmaMultiple.max(BigDecimal.ZERO);
        this.mode = mode;
        this.rebalanceFloorUsd = rebalanceFloorUsd == null ? BigDecimal.ZERO : rebalanceFloorUsd.abs();
        this.minTradeNotionalUsd = minTradeNotionalUsd == null ? BigDecimal.ZERO : minTradeNotionalUsd.abs();
        this.noTradeBandFraction = noTradeBandFraction == null ? BigDecimal.ZERO
                : noTradeBandFraction.abs().min(BigDecimal.ONE);
        this.effectivenessFloor = effectivenessFloor;
        this.minCovarianceDays = Math.max(2, minCovarianceDays);
        this.proxyCandidates = proxyCandidates == null || proxyCandidates.isEmpty()
                ? List.of(structuralProxyId) : List.copyOf(proxyCandidates);
        this.proxySwitchMargin = Math.max(0, proxySwitchMargin);
        this.structuralProxyId = structuralProxyId;
        this.multiplierOf = multiplierOf;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** Every proxy the advisor may hold or trade — callers read held positions for THESE ids. */
    public List<String> proxyUniverse() {
        List<String> ids = new ArrayList<>(proxyCandidates);
        if (!ids.contains(structuralProxyId)) {
            ids.add(structuralProxyId);
        }
        return ids;
    }

    /** One tradable candidate with its market inputs. */
    private record Candidate(String id, BigDecimal price, BigDecimal multiplier) {
    }

    /** The sized target on a chosen proxy from whichever tier could produce one. */
    private record Target(Candidate proxy, BigDecimal signedQty, String tier, Double effectiveness,
                          BigDecimal grossSigmaUsd, BigDecimal residualSigmaUsd, String rationale) {
    }

    /**
     * Evaluate the equity hedge axis from live inputs.
     *
     * @param covariance  the EWMA return covariance (empty during warm-up)
     * @param exposuresUsd USD exposure per instrument (firm)
     * @param isEquity    true for single-name equities (the axis members; NOT index proxies)
     * @param priceOf     current price of an instrument
     * @param betaOf      assigned fundamental beta (ADR-0040 structural tier)
     * @param heldByProxy the hedge book's CURRENT signed position per proxy (ADR-0039's h)
     * @param tradable    false = the instrument is in no shape to trade (stale/quarantined) —
     *                    it is neither selected nor unwound this cycle (ADR-0042 gate)
     */
    public Snapshot evaluate(Optional<CovMath.Covariance> covariance,
                             Map<String, BigDecimal> exposuresUsd,
                             Predicate<String> isEquity,
                             Function<String, Optional<BigDecimal>> priceOf,
                             Function<String, Optional<BigDecimal>> betaOf,
                             Map<String, BigDecimal> heldByProxy,
                             Predicate<String> tradable) {
        return evaluate(covariance, exposuresUsd, isEquity, priceOf, betaOf, heldByProxy, tradable,
                id -> Optional.empty());
    }

    /**
     * @param churnSigmaOf σ of the raw hedge target's own step between hedge evaluations on the axis
     *                     ({@link HedgeTargetChurn}), empty while warming. The target is shrunk
     *                     toward flat by {@code churnSigmaMultiple × σ} before it is traded
     *                     (ADR-0098) — strictly one-way: the magnitude can only fall and the sign
     *                     can never flip, so an estimated σ can never lever the hedge up.
     */
    public Snapshot evaluate(Optional<CovMath.Covariance> covariance,
                             Map<String, BigDecimal> exposuresUsd,
                             Predicate<String> isEquity,
                             Function<String, Optional<BigDecimal>> priceOf,
                             Function<String, Optional<BigDecimal>> betaOf,
                             Map<String, BigDecimal> heldByProxy,
                             Predicate<String> tradable,
                             Function<String, Optional<BigDecimal>> churnSigmaOf) {
        return evaluate(covariance, exposuresUsd, isEquity, priceOf, betaOf, heldByProxy, tradable,
                churnSigmaOf, id -> Optional.empty());
    }

    /**
     * @param efficiencyOf directional efficiency of the raw hedge target's own path (ADR-0100)
     */
    public Snapshot evaluate(Optional<CovMath.Covariance> covariance,
                             Map<String, BigDecimal> exposuresUsd,
                             Predicate<String> isEquity,
                             Function<String, Optional<BigDecimal>> priceOf,
                             Function<String, Optional<BigDecimal>> betaOf,
                             Map<String, BigDecimal> heldByProxy,
                             Predicate<String> tradable,
                             Function<String, Optional<BigDecimal>> churnSigmaOf,
                             Function<String, Optional<BigDecimal>> efficiencyOf) {
        return evaluate(covariance, exposuresUsd, isEquity, priceOf, betaOf, heldByProxy, tradable,
                churnSigmaOf, efficiencyOf, id -> Optional.empty());
    }

    /**
     * @param efficiencyOf directional efficiency of the raw hedge target's own path on the axis
     *                     ({@link HedgeTargetChurn#efficiencyRatio}), empty while warming. The
     *                     hedge closes this fraction of the gap to its target per evaluation when
     *                     it is GROWING the overlay (ADR-0100); reductions — including a full
     *                     unwind — always trade in one cycle, so the rate can only ever leave the
     *                     hedge smaller than it would otherwise have been.
     */
    public Snapshot evaluate(Optional<CovMath.Covariance> covariance,
                             Map<String, BigDecimal> exposuresUsd,
                             Predicate<String> isEquity,
                             Function<String, Optional<BigDecimal>> priceOf,
                             Function<String, Optional<BigDecimal>> betaOf,
                             Map<String, BigDecimal> heldByProxy,
                             Predicate<String> tradable,
                             Function<String, Optional<BigDecimal>> churnSigmaOf,
                             Function<String, Optional<BigDecimal>> efficiencyOf,
                             Function<String, Optional<BigDecimal>> habitualNetOf) {
        Map<String, BigDecimal> equityExposures = exposuresUsd.entrySet().stream()
                .filter(e -> isEquity.test(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        BigDecimal net = equityExposures.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> held = heldByProxy == null ? Map.of() : heldByProxy;

        Axis axis = mode == Mode.OFF
                ? idle(net, held, "OFF", "hedging OFF for this axis")
                : act(net, held, equityExposures, covariance, priceOf, betaOf, tradable, churnSigmaOf,
                        efficiencyOf, habitualNetOf);
        String note = mode == Mode.AUTO
                ? "AUTO — the book is held target-flat: the hedge DELTA (target − held) auto-submits (sim-gated, ADR-0019)"
                : "ADVISE — the sized hedge-to-flat surfaces here; execute from the ticket";
        return new Snapshot(mode.name(), covariance.isPresent(), List.of(axis), note);
    }

    private Axis act(BigDecimal net, Map<String, BigDecimal> held,
                     Map<String, BigDecimal> equityExposures, Optional<CovMath.Covariance> covariance,
                     Function<String, Optional<BigDecimal>> priceOf,
                     Function<String, Optional<BigDecimal>> betaOf, Predicate<String> tradable,
                     Function<String, Optional<BigDecimal>> churnSigmaOf,
                     Function<String, Optional<BigDecimal>> efficiencyOf,
                     Function<String, Optional<BigDecimal>> habitualNetOf) {
        List<Candidate> candidates = candidates(priceOf, tradable);
        boolean flatTarget = net.abs().compareTo(rebalanceFloorUsd) <= 0;
        Target target;
        if (flatTarget) {
            Candidate anchor = candidates.isEmpty() ? null : incumbentOr(candidates, held, candidates.get(0));
            target = anchor == null ? null
                    : new Target(anchor, BigDecimal.ZERO, "—", null, null, null,
                            "net equity |" + money(net) + "| ≤ " + money(rebalanceFloorUsd)
                                    + " floor — target hedge is zero");
        } else {
            target = sizeTarget(equityExposures, covariance, candidates, priceOf, betaOf, held);
        }
        if (target == null) {
            return idle(net, held, "WARMING", "net equity " + money(net) + " to hedge, but no "
                    + "tradable proxy can be sized yet (price/covariance/betas missing)"
                    + heldNote(held));
        }
        if (target.signedQty() == null) {
            return idle(net, held, "REDUCE", target.rationale());
        }

        // ADR-0098: neutralize only the systematic exposure that stands clear of its own churn.
        BigDecimal targetContractUsd = target.proxy().price().multiply(target.proxy().multiplier());
        BigDecimal rawTargetNotional = target.signedQty().multiply(targetContractUsd)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal churnSigma = churnSigmaOf.apply(EQUITY_AXIS).orElse(null);
        target = shrinkToChurnBoundary(target, targetContractUsd, rawTargetNotional, churnSigma);

        // ADR-0105: wear only the part of the hedge that covers exposure above the level this desk
        // habitually carries — hedge back to the edge of its own band, not to the centre.
        BigDecimal habitualNet = habitualNetOf.apply(EQUITY_AXIS).orElse(null);
        BigDecimal excessFraction = excessFraction(net, habitualNet);
        target = hedgeOnlyTheExcess(target, net, habitualNet, excessFraction);

        // ADR-0100: approach that target at the rate its own path earns — full speed when the
        // target is going somewhere, barely at all when it is only churning. Growing the overlay
        // only; reductions and unwinds still trade in one cycle.
        BigDecimal trackingRate = efficiencyOf.apply(EQUITY_AXIS).orElse(null);
        target = trackTowardTarget(target, held.getOrDefault(target.proxy().id(), BigDecimal.ZERO),
                trackingRate);

        // Per-proxy targets: the chosen proxy gets the sized target; every other held proxy
        // targets zero. Execute the largest delta above the min-trade notional (one order per
        // cycle — a switch unwinds the old proxy before the new one builds).
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        targets.put(target.proxy().id(), target.signedQty());
        held.forEach((proxy, qty) -> targets.putIfAbsent(proxy, BigDecimal.ZERO));

        String bestProxy = null;
        BigDecimal bestDelta = null;
        BigDecimal bestNotional = null;
        BigDecimal bestScaleNotional = null;
        Candidate bestCandidate = null;
        for (var e : targets.entrySet()) {
            BigDecimal have = held.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal delta = e.getValue().subtract(have);
            if (delta.signum() == 0 || !tradable.test(e.getKey())) {
                continue; // never trade an untradable instrument — not even to unwind (ADR-0042)
            }
            Candidate c = e.getKey().equals(target.proxy().id()) ? target.proxy()
                    : candidate(e.getKey(), priceOf);
            if (c == null) {
                continue;
            }
            BigDecimal contractUsd = c.price().multiply(c.multiplier());
            BigDecimal notional = delta.abs().multiply(contractUsd);
            // The hedge's own scale on this proxy — what the band is measured against (ADR-0069).
            BigDecimal scaleNotional = e.getValue().abs().max(have.abs()).multiply(contractUsd);
            if (bestNotional == null || notional.compareTo(bestNotional) > 0) {
                bestProxy = e.getKey();
                bestDelta = delta;
                bestNotional = notional;
                bestScaleNotional = scaleNotional;
                bestCandidate = c;
            }
        }

        BigDecimal heldSelected = held.getOrDefault(target.proxy().id(), BigDecimal.ZERO);
        String heldVsTarget = "held " + plain(heldSelected) + " → target " + plain(target.signedQty())
                + " " + target.proxy().id();
        BigDecimal band = noTradeBand(bestScaleNotional);
        if (bestProxy == null || bestNotional.compareTo(band) < 0) {
            String status = target.signedQty().signum() == 0 && allFlat(held) ? "FLAT" : "ON-TARGET";
            return new Axis("EQUITY", target.proxy().id(), money(net), rebalanceFloorUsd,
                    eff(target), false, false, null, null, null,
                    target.effectiveness(), target.grossSigmaUsd(), target.residualSigmaUsd(),
                    plain(heldSelected), plain(target.signedQty()), status, target.tier(),
                    heldVsTarget + " — largest delta under the " + money(band)
                            + " no-trade band, holding",
                    rawTargetNotional, churnSigma, trackingRate, habitualNet, excessFraction);
        }
        boolean unwindingOther = !bestProxy.equals(target.proxy().id());
        String side = bestDelta.signum() < 0 ? "SELL" : "BUY";
        String status = unwindingOther || target.signedQty().signum() == 0 ? "UNWIND" : "HEDGE";
        String story = unwindingOther
                ? "unwinding " + plain(held.getOrDefault(bestProxy, BigDecimal.ZERO)) + " " + bestProxy
                        + " before building the " + target.proxy().id() + " hedge · " + target.rationale()
                : heldVsTarget + " → " + side + " " + plain(bestDelta.abs()) + " · " + target.rationale();
        return new Axis("EQUITY", bestProxy, money(net), rebalanceFloorUsd,
                eff(target), true, true, side, bestDelta.abs(),
                bestNotional.setScale(2, RoundingMode.HALF_UP),
                target.effectiveness(), target.grossSigmaUsd(), target.residualSigmaUsd(),
                plain(held.getOrDefault(bestProxy, BigDecimal.ZERO)),
                plain(targets.get(bestProxy)), status, target.tier(), story,
                rawTargetNotional, churnSigma, trackingRate, habitualNet, excessFraction);
    }

    /**
     * ADR-0105 — the share of the axis's current net exposure that stands above the level the desk
     * habitually carries: {@code f = max(0, |N| − H) / |N|}, where {@code H} is the median of this
     * axis's own |net| history ({@link HedgeExposureLevel}).
     *
     * <p>Null while the level is warming (the caller then hedges exactly as it did before), and
     * {@code f ∈ [0,1]} by construction: the numerator is clamped at zero and never exceeds
     * {@code |N|}. Exact decimal at 8dp — the quotient of two USD figures, rounded once, HALF_EVEN.
     */
    private static BigDecimal excessFraction(BigDecimal net, BigDecimal habitualNet) {
        if (habitualNet == null || habitualNet.signum() < 0) {
            return null;
        }
        BigDecimal absNet = net.abs();
        if (absNet.signum() == 0) {
            return BigDecimal.ZERO; // nothing to hedge either way
        }
        BigDecimal excess = absNet.subtract(habitualNet).max(BigDecimal.ZERO);
        return excess.divide(absNet, 8, RoundingMode.HALF_EVEN).min(BigDecimal.ONE);
    }

    /**
     * ADR-0105 — hedge back to the edge of the desk's own band rather than to flat: scale the sized
     * target by {@link #excessFraction}, so the overlay neutralizes {@code |N| − H} of exposure and
     * leaves exactly {@code H} — the exposure the book habitually runs — standing.
     *
     * <p>The sized hedge is homogeneous of degree 1 in the exposure it covers (it is a beta- or
     * ρ-weighted proportional hedge), so {@code q' = f·q} neutralizes the fraction {@code f} of the
     * net and leaves {@code (1−f)·|N| = H} unhedged, in the same beta-adjusted sense as the full
     * hedge it scales. This is the standard transaction-cost result — Leland (<i>JF</i> 1985),
     * Whalley &amp; Wilmott (<i>Math. Finance</i> 1997), Zakamouline (<i>JBF</i> 2006): with costs,
     * the optimal policy is a no-hedge band around the target, and a breach trades back to the band
     * edge, never to the centre. Hedging to the centre from a floor of zero — the desk's behaviour
     * until now — is the one policy that pays the round trip on every oscillation of an exposure it
     * always carries.
     *
     * <p><b>Strictly one-way.</b> {@code f ∈ [0,1]} and the multiplier is positive, so
     * {@code |q'| ≤ |q|} and {@code sign(q') ∈ {sign(q), 0}}: an estimated band can only ever leave
     * the overlay smaller, never larger and never on the other side. {@code f = 1} (net far above
     * the band) reproduces the previous behaviour exactly; a null band leaves the target untouched.
     */
    private Target hedgeOnlyTheExcess(Target t, BigDecimal net, BigDecimal habitualNet,
                                      BigDecimal fraction) {
        if (fraction == null || t.signedQty() == null || t.signedQty().signum() == 0
                || fraction.compareTo(BigDecimal.ONE) == 0) {
            return t;
        }
        BigDecimal q = t.signedQty();
        BigDecimal worn = q.multiply(fraction).setScale(6, RoundingMode.HALF_EVEN);
        if (worn.abs().compareTo(q.abs()) > 0) {
            worn = q; // belt and braces: rounding may never carry the hedge past its own target
        }
        if (worn.compareTo(q) == 0) {
            return t;
        }
        String note = worn.signum() == 0
                ? " · inside the desk's own band (ADR-0105): |" + money(net) + "| net ≤ "
                        + money(habitualNet) + " habitual — no overlay worn"
                : " · banded at the desk's habitual net (ADR-0105): (|" + money(net) + "| − "
                        + money(habitualNet) + ")/|" + money(net) + "| = "
                        + fraction.stripTrailingZeros().toPlainString() + " of " + plain(q) + " → "
                        + plain(worn) + " " + t.proxy().id();
        return new Target(t.proxy(), worn, t.tier(), t.effectiveness(), t.grossSigmaUsd(),
                t.residualSigmaUsd(), t.rationale() + note);
    }

    /**
     * ADR-0100 — close only the fraction {@code a} of the gap to the target that the target's own
     * path has earned, where {@code a} is its directional efficiency
     * ({@link HedgeTargetChurn#efficiencyRatio}): the share of the distance the target travels that
     * is net displacement rather than round trip.
     *
     * <p>Asymmetric, exactly as ADR-0080 established on the strategy side: the rate applies only to
     * the part of the move that <b>grows</b> the overlay. Reducing the proxy position — including a
     * full unwind, and including the free leg of a move that crosses flat — trades in one cycle, so
     * ADR-0069's "an unwind is always executable" promise is untouched.
     *
     * <ul>
     *   <li>same sign (or from flat) and {@code |d| ≤ |h|} — a reduction: {@code q = d}</li>
     *   <li>same sign (or from flat) and {@code |d| > |h|} — growing: {@code q = h + a·(d − h)}</li>
     *   <li>opposite signs — cut to flat free, rebuild slowed: {@code q = a·d}</li>
     * </ul>
     *
     * <p>In every branch {@code q} lies on the segment between {@code h} and {@code d}, so
     * {@code |q| ≤ max(|h|,|d|)} and {@code |q| ≤ |d|} whenever the move grows the hedge: a rate
     * estimated from the stream can only ever leave the overlay smaller than ADR-0098 already
     * allows, never larger. {@code a = 1} reproduces the previous behaviour exactly.
     */
    private Target trackTowardTarget(Target t, BigDecimal heldQty, BigDecimal rate) {
        if (rate == null || t.signedQty() == null) {
            return t;
        }
        BigDecimal a = rate.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        if (a.compareTo(BigDecimal.ONE) == 0) {
            return t;
        }
        BigDecimal d = t.signedQty();
        BigDecimal h = heldQty == null ? BigDecimal.ZERO : heldQty;
        BigDecimal tracked;
        if (h.signum() != 0 && d.signum() != 0 && h.signum() != d.signum()) {
            tracked = d.multiply(a);
        } else if (d.abs().compareTo(h.abs()) <= 0) {
            return t; // a reduction (incl. a full unwind) trades in full
        } else {
            tracked = h.add(d.subtract(h).multiply(a));
        }
        tracked = tracked.setScale(6, RoundingMode.HALF_EVEN);
        if (tracked.abs().compareTo(d.abs()) > 0) {
            tracked = d; // belt and braces: rounding may never carry the hedge past its target
        }
        if (tracked.compareTo(d) == 0) {
            return t;
        }
        String note = " · tracked at its own efficiency (ADR-0100): " + plain(h) + " + "
                + a.stripTrailingZeros().toPlainString() + "·(" + plain(d) + " − " + plain(h)
                + ") → " + plain(tracked) + " " + t.proxy().id();
        return new Target(t.proxy(), tracked, t.tier(), t.effectiveness(), t.grossSigmaUsd(),
                t.residualSigmaUsd(), t.rationale() + note);
    }

    /**
     * ADR-0098 — shrink the sized target toward flat by {@code churnSigmaMultiple × σ} of the
     * target's own step between hedge evaluations:
     * {@code T' = sign(T) · max(0, |T| − k·σ)}, in USD of proxy notional, then re-divided by the
     * contract's money value to a quantity.
     *
     * <p>Strictly one-way: {@code |T'| ≤ |T|} and {@code sign(T') ∈ {sign(T), 0}} by construction,
     * so an estimated σ can only ever make the hedge smaller. A target that is large relative to
     * its own churn (a real, persistent systematic exposure) is essentially untouched; a target
     * inside its own churn is set flat and the residual hedge is unwound by the ordinary
     * per-proxy delta path.
     */
    private Target shrinkToChurnBoundary(Target t, BigDecimal contractUsd, BigDecimal rawNotional,
                                         BigDecimal churnSigma) {
        if (churnSigmaMultiple.signum() <= 0 || churnSigma == null || churnSigma.signum() <= 0
                || rawNotional.signum() == 0 || contractUsd.signum() <= 0) {
            return t;
        }
        BigDecimal boundary = churnSigma.multiply(churnSigmaMultiple);
        BigDecimal keep = rawNotional.abs().subtract(boundary).max(BigDecimal.ZERO);
        if (keep.compareTo(rawNotional.abs()) == 0) {
            return t;
        }
        BigDecimal shrunk = rawNotional.signum() < 0 ? keep.negate() : keep;
        BigDecimal qty = shrunk.divide(contractUsd, 6, RoundingMode.HALF_EVEN);
        String note = " · churn-shrunk (ADR-0098): |" + money(rawNotional) + "| − "
                + money(boundary) + " σ-step → " + money(shrunk) + " → "
                + plain(qty) + " " + t.proxy().id();
        return new Target(t.proxy(), qty, t.tier(), t.effectiveness(), t.grossSigmaUsd(),
                t.residualSigmaUsd(), t.rationale() + note);
    }

    /**
     * The no-trade band on the hedge delta (ADR-0069): the SMALLER of the absolute ADR-0039 churn
     * guard and {@code fraction × scale}, where {@code scale = max(|target|,|held|)} notional on
     * the proxy being traded. Equivalently: trade when the delta is material in absolute terms OR
     * material relative to the hedge itself.
     *
     * <p>Taking the minimum (never the maximum) is what keeps the absolute guard from becoming an
     * absolute barrier: on a book far larger than the guard the relative leg is the looser of the
     * two and the ADR-0039 $10k behaviour is unchanged, while on a book smaller than the guard the
     * relative leg governs and the hedge can still be trimmed or unwound. With the fraction clamped
     * at 1 and a zero target, {@code delta == scale ≥ band}, so an unwind is always executable.
     */
    private BigDecimal noTradeBand(BigDecimal scaleNotional) {
        if (scaleNotional == null || scaleNotional.signum() <= 0
                || noTradeBandFraction.signum() <= 0) {
            return minTradeNotionalUsd;
        }
        return minTradeNotionalUsd.min(scaleNotional.multiply(noTradeBandFraction));
    }

    /**
     * ADR-0042 selection: statistical per-candidate (highest ρ² clearing the floor + the
     * ADR-0041 covariance gate), with switch hysteresis versus the currently-held proxy;
     * structural fallback on the configured proxy. Null = cannot size at all; Target with null
     * quantity = sized but below the ρ² floor with no structural fallback (REDUCE).
     */
    private Target sizeTarget(Map<String, BigDecimal> equityExposures,
                              Optional<CovMath.Covariance> covariance, List<Candidate> candidates,
                              Function<String, Optional<BigDecimal>> priceOf,
                              Function<String, Optional<BigDecimal>> betaOf,
                              Map<String, BigDecimal> held) {
        Map<String, HedgeMath.HedgeProposal> passing = new LinkedHashMap<>();
        Optional<HedgeMath.HedgeProposal> anyStatistical = Optional.empty();
        if (covariance.isPresent() && covariance.get().observations() >= minCovarianceDays) {
            for (Candidate c : candidates) {
                Optional<HedgeMath.HedgeProposal> p = HedgeMath.betaHedge(covariance.get(),
                        equityExposures, c.id(), c.price(), c.multiplier(), effectivenessFloor);
                if (p.isPresent()) {
                    anyStatistical = p;
                    if (p.get().recommended()) {
                        passing.put(c.id(), p.get());
                    }
                }
            }
        }
        if (!passing.isEmpty()) {
            String bestId = passing.entrySet().stream()
                    .max(Map.Entry.comparingByValue(
                            java.util.Comparator.comparingDouble(HedgeMath.HedgeProposal::effectiveness)))
                    .orElseThrow().getKey();
            // Switch hysteresis: keep a held proxy unless the challenger's ρ² beats it by the margin.
            String incumbent = incumbentId(held);
            String chosen = bestId;
            if (incumbent != null && !incumbent.equals(bestId) && passing.containsKey(incumbent)
                    && passing.get(bestId).effectiveness()
                            - passing.get(incumbent).effectiveness() < proxySwitchMargin) {
                chosen = incumbent;
            }
            HedgeMath.HedgeProposal p = passing.get(chosen);
            Candidate c = candidates.stream().filter(x -> x.id().equals(p.proxyInstrumentId()))
                    .findFirst().orElseThrow();
            String comparison = passing.size() > 1
                    ? passing.entrySet().stream()
                            .map(e -> e.getKey() + " ρ²=" + String.format("%.2f", e.getValue().effectiveness()))
                            .collect(Collectors.joining(" vs ")) + " → " + chosen + " · "
                    : "";
            return new Target(c, p.signedQuantity(), "STATISTICAL", p.effectiveness(),
                    p.grossSigmaUsd(), p.residualSigmaUsd(), comparison + p.rationale());
        }
        // Structural fallback on the configured proxy (assigned betas are quoted against it).
        Candidate structural = candidates.stream()
                .filter(c -> c.id().equals(structuralProxyId)).findFirst()
                .orElse(candidate(structuralProxyId, priceOf));
        if (structural != null) {
            Map<String, BigDecimal> betas = new HashMap<>();
            for (String id : equityExposures.keySet()) {
                betaOf.apply(id).ifPresent(b -> betas.put(id, b));
            }
            Optional<HedgeMath.StructuralHedge> s = HedgeMath.structuralBetaHedge(
                    equityExposures, betas, structural.id(), structural.price(), structural.multiplier());
            if (s.isPresent()) {
                return new Target(structural, s.get().signedQuantity(), "STRUCTURAL", null, null, null,
                        s.get().rationale());
            }
        }
        if (anyStatistical.isPresent()) {
            var p = anyStatistical.get();
            Candidate c = candidates.stream().filter(x -> x.id().equals(p.proxyInstrumentId()))
                    .findFirst().orElse(null);
            if (c != null) {
                return new Target(c, null, "STATISTICAL", p.effectiveness(),
                        p.grossSigmaUsd(), p.residualSigmaUsd(), p.rationale());
            }
        }
        return null;
    }

    /** Tradable candidates with a live price and a known multiplier, in configured order. */
    private List<Candidate> candidates(Function<String, Optional<BigDecimal>> priceOf,
                                       Predicate<String> tradable) {
        List<Candidate> out = new ArrayList<>();
        for (String id : proxyCandidates) {
            if (!tradable.test(id)) {
                continue;
            }
            Candidate c = candidate(id, priceOf);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private Candidate candidate(String id, Function<String, Optional<BigDecimal>> priceOf) {
        BigDecimal price = priceOf.apply(id).orElse(null);
        BigDecimal mult = multiplierOf.apply(id).orElse(null);
        return price == null || price.signum() <= 0 || mult == null || mult.signum() <= 0
                ? null : new Candidate(id, price, mult);
    }

    /** The proxy currently held (largest |position|), or null when flat everywhere. */
    private static String incumbentId(Map<String, BigDecimal> held) {
        return held.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().signum() != 0)
                .max(Map.Entry.comparingByValue(java.util.Comparator.comparing(BigDecimal::abs)))
                .map(Map.Entry::getKey).orElse(null);
    }

    private static Candidate incumbentOr(List<Candidate> candidates, Map<String, BigDecimal> held,
                                         Candidate fallback) {
        String incumbent = incumbentId(held);
        return candidates.stream().filter(c -> c.id().equals(incumbent)).findFirst().orElse(fallback);
    }

    private static boolean allFlat(Map<String, BigDecimal> held) {
        return held.values().stream().allMatch(q -> q == null || q.signum() == 0);
    }

    private static String heldNote(Map<String, BigDecimal> held) {
        String s = held.entrySet().stream().filter(e -> e.getValue().signum() != 0)
                .map(e -> plain(e.getValue()) + " " + e.getKey())
                .collect(Collectors.joining(", "));
        return s.isEmpty() ? "" : " (holding " + s + ")";
    }

    /** Nothing to trade this cycle (OFF, warming, or reduce-don't-hedge). Held is still shown. */
    private Axis idle(BigDecimal net, Map<String, BigDecimal> held, String status, String rationale) {
        String incumbent = incumbentId(held);
        BigDecimal heldQty = incumbent == null ? BigDecimal.ZERO : held.get(incumbent);
        return new Axis("EQUITY", incumbent != null ? incumbent : structuralProxyId, money(net),
                rebalanceFloorUsd, 0.0, false, false, null, null, null, null, null, null,
                plain(heldQty), null, status, "—", rationale, null, null, null, null, null);
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
