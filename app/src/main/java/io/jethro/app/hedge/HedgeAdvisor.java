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

    /** One hedge axis: the pure exposure, held/target hedge, and (when acting) the sized DELTA on
     *  {@code proxyId} (the instrument being traded this cycle — selected, or an old proxy being
     *  unwound). {@code tier} is STATISTICAL (measured ρ² on the daily-close series),
     *  STATISTICAL-STREAM (measured ρ² on the mark stream, ADR-0095 — σ figures absent because their
     *  unit is one sampling interval), STRUCTURAL (assigned beta, {@code effectiveness} null —
     *  asserted, never a fake ρ²), or "—" when not sizing. */
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
    private final BigDecimal noTradeBandFraction;
    private final double effectivenessFloor;
    private final int minCovarianceDays;
    private final List<String> proxyCandidates;
    private final double proxySwitchMargin;
    private final String structuralProxyId;
    private final Function<String, Optional<BigDecimal>> multiplierOf;

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
     * @param covariance  the daily-close EWMA return covariance (empty during warm-up)
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
        return evaluate(covariance, Optional.empty(), exposuresUsd, isEquity, priceOf, betaOf,
                heldByProxy, tradable);
    }

    /**
     * Evaluate the equity hedge axis, with the ADR-0095 mark-stream covariance as the second
     * statistical basis.
     *
     * @param streamCovariance the covariance measured on the mark stream ({@link
     *                         HedgeStreamCovariance}) — consulted only when the daily-close estimate
     *                         cannot measure this book at all, warm-gated by its own estimator (so
     *                         the ADR-0041 day gate, whose unit is sessions, does not apply to it),
     *                         and reported without absolute σ because its unit is one sampling
     *                         interval rather than a day
     */
    public Snapshot evaluate(Optional<CovMath.Covariance> covariance,
                             Optional<CovMath.Covariance> streamCovariance,
                             Map<String, BigDecimal> exposuresUsd,
                             Predicate<String> isEquity,
                             Function<String, Optional<BigDecimal>> priceOf,
                             Function<String, Optional<BigDecimal>> betaOf,
                             Map<String, BigDecimal> heldByProxy,
                             Predicate<String> tradable) {
        Map<String, BigDecimal> equityExposures = exposuresUsd.entrySet().stream()
                .filter(e -> isEquity.test(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        BigDecimal net = equityExposures.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> held = heldByProxy == null ? Map.of() : heldByProxy;

        Axis axis = mode == Mode.OFF
                ? idle(net, held, "OFF", "hedging OFF for this axis")
                : act(net, held, equityExposures, covariance, streamCovariance, priceOf, betaOf, tradable);
        String note = mode == Mode.AUTO
                ? "AUTO — the book is held target-flat: the hedge DELTA (target − held) auto-submits (sim-gated, ADR-0019)"
                : "ADVISE — the sized hedge-to-flat surfaces here; execute from the ticket";
        return new Snapshot(mode.name(), covariance.isPresent() || streamCovariance.isPresent(),
                List.of(axis), note);
    }

    private Axis act(BigDecimal net, Map<String, BigDecimal> held,
                     Map<String, BigDecimal> equityExposures, Optional<CovMath.Covariance> covariance,
                     Optional<CovMath.Covariance> streamCovariance,
                     Function<String, Optional<BigDecimal>> priceOf,
                     Function<String, Optional<BigDecimal>> betaOf, Predicate<String> tradable) {
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
            target = sizeTarget(equityExposures, covariance, streamCovariance, candidates, priceOf,
                    betaOf, held);
        }
        if (target == null) {
            return idle(net, held, "WARMING", "net equity " + money(net) + " to hedge, but no "
                    + "tradable proxy can be sized yet (price/covariance/betas missing)"
                    + heldNote(held));
        }
        if (target.signedQty() == null) {
            return idle(net, held, "REDUCE", target.rationale());
        }

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
                            + " no-trade band, holding");
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
                plain(targets.get(bestProxy)), status, target.tier(), story);
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

    /** What one covariance estimate had to say: the sized target when a candidate cleared the ρ²
     *  floor, and — separately — the best proposal it could measure at all, so "no measurement" and
     *  "measured, and it says this proxy does not hedge you" stay distinguishable. */
    private record Reading(Target passing, HedgeMath.HedgeProposal belowFloor, String tier,
                           boolean sigmaIsDaily) {
        static final Reading NONE = new Reading(null, null, null, false);

        boolean measured() {
            return passing != null || belowFloor != null;
        }
    }

    /**
     * ADR-0042 selection over three tiers of evidence, strongest first:
     * <ol>
     *   <li><b>STATISTICAL</b> — the daily-close covariance, past the ADR-0041 session gate;</li>
     *   <li><b>STATISTICAL-STREAM</b> — the ADR-0095 mark-stream covariance, consulted only when the
     *       daily estimate could not measure this book at all (it warm-gates itself, so the session
     *       gate — whose unit is sessions — does not apply, and its absolute σ is withheld because
     *       its unit is one sampling interval);</li>
     *   <li><b>STRUCTURAL</b> — assigned fundamental betas (ADR-0040), the history-free floor.</li>
     * </ol>
     *
     * <p><b>A measurement that says no is an answer, not a gap (ADR-0095).</b> When an estimate can
     * measure this book and no candidate clears the ρ² floor, the target is <b>flat</b> — the residual
     * hedge unwinds through the ordinary delta path — rather than falling through to assigned betas.
     * Falling through answers "measured not to hedge this book" with "assumed to hedge it", which
     * makes the effectiveness floor unreachable by construction and leaves the firm carrying proxy
     * exposure that is, on its own evidence, not a hedge. The structural tier stays exactly what
     * ADR-0040 built it for: the answer when there is <em>no</em> measurement.
     *
     * @return null = cannot size at all (WARMING).
     */
    private Target sizeTarget(Map<String, BigDecimal> equityExposures,
                              Optional<CovMath.Covariance> covariance,
                              Optional<CovMath.Covariance> streamCovariance, List<Candidate> candidates,
                              Function<String, Optional<BigDecimal>> priceOf,
                              Function<String, Optional<BigDecimal>> betaOf,
                              Map<String, BigDecimal> held) {
        Reading daily = read(covariance, true, "STATISTICAL", true, equityExposures, candidates, held);
        if (daily.passing() != null) {
            return daily.passing();
        }
        Reading stream = daily.measured() ? Reading.NONE
                : read(streamCovariance, false, "STATISTICAL-STREAM", false, equityExposures,
                        candidates, held);
        if (stream.passing() != null) {
            return stream.passing();
        }
        Reading refused = daily.measured() ? daily : stream;
        if (refused.belowFloor() != null) {
            HedgeMath.HedgeProposal p = refused.belowFloor();
            Candidate c = candidates.stream().filter(x -> x.id().equals(p.proxyInstrumentId()))
                    .findFirst().orElse(null);
            if (c != null) {
                return new Target(c, BigDecimal.ZERO, refused.tier(), p.effectiveness(),
                        refused.sigmaIsDaily() ? p.grossSigmaUsd() : null,
                        refused.sigmaIsDaily() ? p.residualSigmaUsd() : null,
                        p.rationale() + " — target flat, the hedge is not carried on assumption");
            }
        }
        // Structural fallback on the configured proxy (assigned betas are quoted against it) — only
        // reached when NOTHING could measure this book.
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
        return null;
    }

    /**
     * One covariance estimate's verdict on every tradable candidate: the best proposal clearing the
     * ρ² floor (with ADR-0042 switch hysteresis versus the held proxy), plus whatever it could
     * measure at all.
     *
     * @param applyDaysGate the ADR-0041 min-sessions gate — for the daily-close series only; the
     *                      stream estimator counts sampling intervals, not sessions, and enforces its
     *                      own warm-up before it reports a pair at all
     * @param sigmaIsDaily  false when the estimate's absolute σ is per sampling interval and must
     *                      therefore not be surfaced as a daily σ (ADR-0095); ρ² and the hedge ratio
     *                      are homogeneous of degree zero in Σ and carry across unchanged
     */
    private Reading read(Optional<CovMath.Covariance> covariance, boolean applyDaysGate, String tier,
                         boolean sigmaIsDaily, Map<String, BigDecimal> equityExposures,
                         List<Candidate> candidates, Map<String, BigDecimal> held) {
        if (covariance.isEmpty()
                || (applyDaysGate && covariance.get().observations() < minCovarianceDays)) {
            return Reading.NONE;
        }
        Map<String, HedgeMath.HedgeProposal> passing = new LinkedHashMap<>();
        HedgeMath.HedgeProposal anyMeasured = null;
        for (Candidate c : candidates) {
            Optional<HedgeMath.HedgeProposal> p = HedgeMath.betaHedge(covariance.get(),
                    equityExposures, c.id(), c.price(), c.multiplier(), effectivenessFloor);
            if (p.isPresent()) {
                if (anyMeasured == null || p.get().effectiveness() > anyMeasured.effectiveness()) {
                    anyMeasured = p.get(); // the best-fitting proxy this estimate could measure
                }
                if (p.get().recommended()) {
                    passing.put(c.id(), p.get());
                }
            }
        }
        if (passing.isEmpty()) {
            return new Reading(null, anyMeasured, tier, sigmaIsDaily);
        }
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
        Target target = new Target(c, p.signedQuantity(), tier, p.effectiveness(),
                sigmaIsDaily ? p.grossSigmaUsd() : null, sigmaIsDaily ? p.residualSigmaUsd() : null,
                comparison + p.rationale());
        return new Reading(target, anyMeasured, tier, sigmaIsDaily);
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
                plain(heldQty), null, status, "—", rationale);
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
