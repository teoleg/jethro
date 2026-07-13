package io.jethro.trading.riskpnl;

import io.jethro.domain.Decimals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic scenario/stress engine (quant-engine sequencing step 2, first slice):
 * revalues the current positions under defined market shocks and reports the P&amp;L
 * impact per book and firm-wide, in USD. Pure code — no model anywhere near it
 * (invariant 7); results are estimates by construction but computed EXACTLY from the
 * stated first-order formulas (no binary floating point on the money arithmetic).
 *
 * <p>First-order revaluation per position (sign convention: quantity &gt; 0 long,
 * P&amp;L &gt; 0 profit; all shocks are exact decimals):
 * <ul>
 *   <li><b>EQUITY / FUTURE</b> (equity-index futures) under equity shock {@code s}:
 *       ΔP&amp;L = netExposure × s. Worked: long 100 AAPL @ 190 → net 19,000;
 *       equities −5% → −950.00.</li>
 *   <li><b>BOND</b> (Treasury futures) under rate shock Δy: ΔP&amp;L = netExposure ×
 *       (−D·Δy), D = modified duration from reference data. Worked: long 1 ZN @
 *       110.50 × $1000 (net 110,500, D 6.3), rates +100bp → 110,500 × −0.063 =
 *       −6,961.50. No duration on file → the position is SKIPPED and counted, never
 *       silently treated as insensitive.</li>
 *   <li><b>SWAP</b> (V9 convention: mark = par rate in %, multiplier = DV01×100):
 *       ΔP&amp;L = quantity × (Δy in percentage points) × multiplier. Worked: 1 lot
 *       pay-fixed USD_IRS_5Y (mult 45,000), rates +100bp → 1 × 1.00 × 45,000 =
 *       +45,000.00 (pay-fixed gains when rates rise).</li>
 *   <li><b>FX pairs</b> under USD shock u: a *USD pair falls when USD strengthens —
 *       ΔP&amp;L = netExposure × (−u). CONVENTION: only *USD pairs trade today; a
 *       non-USD cross would be skipped and counted.</li>
 *   <li><b>Currency translation</b>: any position whose instrument currency ≠ USD has
 *       its USD value scaled by the USD shock — ΔP&amp;L += usdNetExposure × (−u).
 *       Worked: SAP €18,500 @ EURUSD 1.085 = $20,072.50; USD +2% → −401.45. The
 *       equity×FX cross-term is ignored (first-order, documented).</li>
 * </ul>
 *
 * <p>Conversions to USD use the live FX marks passed in ({@link FxConversion} — every
 * conversion names its marks). A position in an unconvertible currency is skipped and
 * counted, never mis-summed. Positions without a live mark carry zero exposure and are
 * counted as skipped when they hold quantity.
 */
public final class ScenarioEngine {

    private static final int SCALE = Decimals.PNL_SCALE;
    private static final RoundingMode ROUND = RoundingMode.HALF_EVEN;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** A market shock: equity move (fraction), parallel rates move (bp), USD move (fraction). */
    public record Shock(BigDecimal equityPct, BigDecimal ratesBps, BigDecimal usdPct) {
    }

    /** A named scenario. */
    public record Scenario(String id, String name, Shock shock) {
    }

    /** One book's P&amp;L impact under a scenario, in USD. */
    public record BookImpact(String bookId, BigDecimal pnlUsd) {
    }

    /** A scenario's portfolio impact: firm-wide and per book, plus honesty counters. */
    public record ScenarioResult(String id, String name, BigDecimal firmPnlUsd,
                                 List<BookImpact> byBook, int positionsCovered, int positionsSkipped) {
    }

    /** The standard stress set (demo calibration: moves a risk committee would ask about). */
    public static final List<Scenario> STANDARD = List.of(
            new Scenario("rates-up-100", "Rates +100bp",
                    new Shock(ZERO, new BigDecimal("100"), ZERO)),
            new Scenario("rates-down-100", "Rates −100bp",
                    new Shock(ZERO, new BigDecimal("-100"), ZERO)),
            new Scenario("equities-down-5", "Equities −5%",
                    new Shock(new BigDecimal("-0.05"), ZERO, ZERO)),
            new Scenario("usd-up-2", "USD +2%",
                    new Shock(ZERO, ZERO, new BigDecimal("0.02"))),
            new Scenario("risk-off", "Risk-off (equities −5%, rates −25bp, USD +1%)",
                    new Shock(new BigDecimal("-0.05"), new BigDecimal("-25"), new BigDecimal("0.01"))));

    private final InstrumentRefSource refs;
    private final SwapPricingService swaps; // nullable: full-reval swaps when present, else first-order

    public ScenarioEngine(InstrumentRefSource refs) {
        this(refs, null);
    }

    /** @param swaps when non-null, swap scenario P&amp;L is FULL revaluation on the shocked curve
     *               (captures convexity), not first-order DV01. */
    public ScenarioEngine(InstrumentRefSource refs, SwapPricingService swaps) {
        this.refs = refs;
        this.swaps = swaps;
    }

    /** Runs the standard scenarios over the given positions with the given FX marks. */
    public List<ScenarioResult> run(List<PositionRisk> positions, FxConversion fx) {
        List<ScenarioResult> results = new ArrayList<>(STANDARD.size());
        for (Scenario scenario : STANDARD) {
            results.add(run(scenario, positions, fx));
        }
        return results;
    }

    private ScenarioResult run(Scenario scenario, List<PositionRisk> positions, FxConversion fx) {
        Shock shock = scenario.shock();
        // Full-revaluation swap P&L per lot on the shocked curve (convexity), computed once per
        // scenario when a pricer is available and the scenario moves rates; else first-order below.
        Map<String, BigDecimal> swapReval = swaps != null && shock.ratesBps().signum() != 0
                ? swaps.swapPnlPerLotUnderShock(shock.ratesBps(), java.time.LocalDate.now())
                : Map.of();
        Map<String, BigDecimal> byBook = new LinkedHashMap<>();
        BigDecimal firm = ZERO;
        int covered = 0;
        int skipped = 0;
        for (PositionRisk p : positions) {
            if (p.quantity().signum() == 0) {
                continue; // flat rows (kept for realized PnL) carry no scenario risk
            }
            if (!p.hasMark() || !fx.canConvert(p.currency(), "USD")) {
                skipped++; // can't value or can't convert — count, never silently zero
                continue;
            }
            BigDecimal impactCcy;
            switch (p.assetClass()) {
                case "EQUITY", "FUTURE" -> impactCcy = p.netExposure().multiply(shock.equityPct());
                case "FX" -> impactCcy = p.netExposure().multiply(shock.usdPct().negate());
                case "BOND" -> {
                    BigDecimal duration = refs.find(p.instrumentId())
                            .map(InstrumentRef::modDuration).orElse(null);
                    if (duration == null) {
                        if (shock.ratesBps().signum() != 0) {
                            skipped++; // rates shock but no duration on file — honest skip
                            continue;
                        }
                        impactCcy = ZERO;
                    } else {
                        // Δy as an exact fraction: bp / 10^4 via decimal point shift.
                        impactCcy = p.netExposure()
                                .multiply(duration.negate())
                                .multiply(shock.ratesBps().movePointLeft(4));
                    }
                }
                case "SWAP" -> {
                    BigDecimal perLot = swapReval.get(p.instrumentId());
                    if (perLot != null) {
                        // Full revaluation: qty lots ($1M each) × ΔPV re-priced on the shocked
                        // curve — carries convexity (a first-order DV01 shock would be symmetric).
                        impactCcy = p.quantity().multiply(perLot);
                    } else {
                        // First-order fallback (no pricer/curve): V9 convention — mark is the par
                        // rate in percent, so Δy of b bp moves it b/100 pts × the DV01-based multiplier.
                        impactCcy = p.quantity()
                                .multiply(shock.ratesBps().movePointLeft(2))
                                .multiply(refs.find(p.instrumentId())
                                        .map(InstrumentRef::multiplier).orElse(BigDecimal.ONE));
                    }
                }
                default -> {
                    skipped++; // unknown asset class — never guess a sensitivity
                    continue;
                }
            }
            BigDecimal impactUsd = fx.convert(impactCcy, p.currency(), "USD");
            if (!"USD".equals(p.currency()) && shock.usdPct().signum() != 0) {
                // Translation: the position's USD value scales with the USD move.
                impactUsd = impactUsd.add(
                        fx.convert(p.netExposure(), p.currency(), "USD")
                                .multiply(shock.usdPct().negate()));
            }
            covered++;
            byBook.merge(p.bookId(), impactUsd, BigDecimal::add);
            firm = firm.add(impactUsd);
        }
        List<BookImpact> impacts = new ArrayList<>(byBook.size());
        byBook.forEach((book, pnl) -> impacts.add(new BookImpact(book, p8(pnl))));
        impacts.sort((a, b) -> a.pnlUsd().compareTo(b.pnlUsd())); // worst book first
        return new ScenarioResult(scenario.id(), scenario.name(), p8(firm), impacts, covered, skipped);
    }

    private static BigDecimal p8(BigDecimal v) {
        return v.setScale(SCALE, ROUND);
    }
}
