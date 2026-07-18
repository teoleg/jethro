# Fin-expert math review — full quant walkthrough (2026-07-18)

**Scope:** every math-bearing path, read fresh this session line by line — no findings carried
from earlier reviews. Files: `CovMath`, `VarMath`, `VolMath`, `HedgeMath`, `BondFutureDurations`,
`CurveService`, `SwapPricingService`, `Dv01Service`, `RiskProjection`, `FxConversion`,
`ScenarioEngine`, `VarService`, `VolTargeting`, `SimulatedExecutor` (spread/fee/impact/ADV),
`Tca`, `CorrelatedFactorSimulator` (Cholesky/Student-t/regimes), `MomentumStrategy`,
`HedgeAdvisor`/`HedgeLifecycle` (incl. today's target-flat + two-tier changes).

**Method:** trader/desk-quant lens. Each formula was re-derived by hand, its worked example
recomputed, and the implementation measured against the best documented reference for that
technique (list at the bottom). Findings ranked P1 (wrong money under plausible conditions) /
P2 (methodology debt a desk would schedule) / P3 (polish).

## Verdict

The math is unusually honest for a system at this stage: formulas carry their sources, worked
examples are asserted in tests to the cent, estimates disclose their assumptions, and "can't
measure" is consistently reported instead of guessed. Most implementations match the documented
reference exactly (table below). But the review found **one genuine P1 trading-logic defect in
the newest code — the auto-hedger has no feedback of the hedge it already holds**, which after
today's always-hedge change would have compounded the hedge every cooldown cycle in AUTO. It is
fixed in the same change as this review. The other findings are estimation-quality and
sim-vs-prod-parity items with clear triggers.

## Verified correct against the documented reference

| Technique | Implementation | Reference it matches | Check |
|---|---|---|---|
| EWMA vol/covariance, λ=0.94, zero-mean daily | `VolMath`, `CovMath` | RiskMetrics Technical Document (J.P. Morgan, 1996) | recursion + λ exact; zero-mean convention is the RiskMetrics daily standard |
| Parametric VaR quantiles | `CovMath` z₉₅=1.645, z₉₉=2.326, ES₉₅ factor 2.0627 | Normal quantiles; ES = σ·φ(z)/α | φ(1.6449)/0.05 = 2.0627 ✓ |
| Historical-simulation VaR + ES | `VarMath` | Standard hist-sim (delta form); convention ⌊αK⌋ stated | conservative index choice, disclosed |
| Min-variance hedge ratio + effectiveness | `HedgeMath.betaHedge` | Ederington (1979), h\* = Cov/Var, e = ρ² | E\_F\* = −Σᵢ Eᵢ·Σ[i,F]/Σ[F,F]; residual σ = σ√(1−ρ²) ✓ hand-recomputed |
| Fundamental-beta dollar hedge | `HedgeMath.structuralBetaHedge` | Standard desk β-dollar hedge; effectiveness asserted, flagged as such | Σβᵢ·Eᵢ worked example recomputed ✓ |
| Implementation shortfall | `Tca` | Perold (1988) arrival-price shortfall | sign conventions + worked example ✓ |
| Square-root market impact | `SimulatedExecutor.withImpact` | Empirical square-root law (Grinold–Kahn; Almgren et al.; Gatheral) impact = σ·√(Q/ADV), Y=1 stated | ✓; Y=1 disclosed as convention, not fitted |
| Half-spread crossing + cash fee split | `SimulatedExecutor` | Standard microstructure accounting; enables TCA decomposition | ✓ |
| Par-bond modified duration | `BondFutureDurations` D=(1/y)(1−(1+y/2)^(−2T)) | Closed form for semiannual par bond (Fabozzi) | (0.045, 10) → 7.9819 recomputed ✓ |
| CTD selection by 6% CF pivot | `BondFutureDurations` | CME conversion-factor mechanics: yields < 6% → short-end CTD | real contract behaviour; issue-level basket honestly deferred |
| Full-reval convexity | `parBondPriceChange`, Strata swap reval | Standard price function; ±100bp asymmetry | +100bp −0.05404 / −100bp +0.05769 recomputed ✓ |
| Swap pricing / DV01 / key-rate buckets | `SwapPricingService` via OpenGamma Strata | Industry OSS analytics; USD_FIXED_1Y_SOFR_OIS conventions, ACT/360 float, calibrated parameter sensitivity | buckets partition the same sensitivity vector (sum = total exactly) ✓ |
| Multivariate Student-t innovations | `CorrelatedFactorSimulator` | Correct construction: ONE shared χ²_ν divisor across all factors + idio (independent divisors would not give joint fat tails); unit-variance scale √((ν−2)/ν) ✓ | this is the subtle one most sims get wrong — it is right here |
| Cholesky + PSD jitter escalation | same | standard numerical practice, fails fast at boot | ✓ |
| Idio vol decomposition | idio² = total² − systematic² (with cross-term 2·βe·βu·σeσu·ρ) | one-factor-plus residual variance identity | includes the eq–usd covariance term ✓ (commonly forgotten) |
| Overnight gap ≈ 0.3 of daily variance | `overnightGap` | stylized US-equity close-to-open variance share (~20–40% in the literature) | single correlated draw — right shape |
| Vol targeting + marginal sizing | `VolTargeting` | notional = budget/σ; marginal = budget/(σ·ρ) is the Euler/MCR allocation (∂σp/∂w = ρσ) | ρ floored at 0.25 both sides — conservative, stated |
| Momentum z-score | `MomentumStrategy` | t-statistic of window mean return, √n scaling | correct; see P2-3 on self-contamination |
| Avg-cost P&L, locked-FX realized | `RiskProjection` | ADR-0037 clean vs comprehensive split | fee booked immediately; swap valued at live annuity — right monetization convention |

## P1 — wrong money under plausible conditions

### P1-1. The auto-hedger has NO feedback of the hedge it already holds — unbounded re-hedge loop (FOUND AND FIXED)

`HedgeAdvisor.evaluate` sizes the hedge from the single-name equity book only; `HedgeLifecycle`
submits `axis.hedgeQuantity()` as a new MARKET order whenever the axis is actionable, gated only
by a 60s cooldown. **Nothing anywhere reads the ES position the previous hedge created** (the
proxy is FUTURE-class, so it never enters the equity exposures; no `positionQuantity` call exists
in the hedge package). Consequence: in AUTO with net equity ≠ 0, the engine sells the FULL hedge
again every cooldown period — −6.4 ES, −12.9, −19.3 … an unbounded short that compounds until a
book cap or the breaker stops it. ADR-0039 explicitly specifies `net = e + h` (exposure PLUS
current hedge); the implementation dropped the `h`. Under the old $250k deadband the loop was
usually dormant; today's always-hedge change would have armed it on any live book — found by this
review before it reached a running system.

**Fix (in this change):** the advisor now takes the hedge book's current proxy position; the
target quantity is computed from the pure equity book (statistical tier preferred, structural
fallback), and the ORDER is the delta `target − held`. On-target books propose nothing; a book
whose equities go flat gets its residual hedge UNWOUND automatically (no underlying → no hedge,
ADR-0039). Trades below the ADR-0039 min-trade notional ($10k) are suppressed as churn. The hedge
books in a dedicated `HEDGE` book (was: MACRO, which the strategy also trades futures in — the
feedback would have fought the strategy's own ES/NQ positions; separate books also give the clean
strategy-vs-hedge P&L attribution ADR-0039 wants).

### P1-2. EWMA covariance at MIN_OBSERVATIONS=20 leaves ~31% of the estimate on the day-1 seed

`CovMath.ewmaCovariance` seeds Σ with the first day's outer product (rank-1, one day of noise) and
requires only 20 observations. With λ=0.94, the seed still carries λ¹⁹ ≈ 0.309 of total weight at
day 20 — a third of the hedge ratio β̂ and effectiveness ρ² is one arbitrary day. RiskMetrics' own
document puts the effective memory of λ=0.94 at ≈74 days (1% tolerance). Consequences: β̂ noise
directly mis-sizes the statistical hedge, and ρ² noise around the 0.25 floor can flap the advisor
between STATISTICAL and STRUCTURAL tiers. Recommendation (not implemented here — dial change is
Oleg's): raise the covariance gate for the hedge path toward 40–60 obs, or seed with the
equal-weight sample covariance of the first ~10 days; keep VaR's 20-day gate with its existing
disclosure. The 60-day window truncation itself is benign (drops λ⁶⁰ ≈ 2.4% of weight).

### P1-3. `CurveService` loads quoted tenor rates directly as ZERO rates — no bootstrap

The curve is built as `Curves.zeroRates(...)` straight from the streamed `USD.SOFR.<tenor>`
quotes. Internally consistent while the sim defines those quotes as zeros — but live SOFR swap
quotes (and FRED series) are PAR rates. Par ≠ zero: on an upward-sloping curve the 10Y–30Y zero
sits meaningfully above par (order 10–30bp at 2022-style slopes), so PV/DV01 of the long tenors
would be systematically biased the day a real curve feed lands. This is exactly the sim=prod
parity trap. Recommendation: bootstrap par → zero at ingestion (Strata's curve calibration does
this natively) behind the same interface; trigger = first real curve source. Until then the
convention "quotes ARE zeros" should be stated where the sim publishes them.

### P1-4. VaR99 on a 20–60 day window IS the sample worst day

`VarMath.lossAt(pnl, 0.01)`: ⌊0.01·K⌋ = 0 for K < 100 — the 99% VaR reported is literally the
single worst day in the window, the noisiest estimator there is (and ES beyond it averages one
point). Basel/FRTB practice uses ≥250 days (often 500) precisely so the 1% tail has ≥2–5 points.
Not a bug — the convention is stated — but the UI shows VaR99 with the same typography as VaR95.
Recommendation: extend the window once the real-history seed fills `daily_close` (the Yahoo/FRED
seed makes 250+ days available), and until then badge VaR99 as "worst day in window" so nobody
reads a sample minimum as a calibrated quantile.

## P2 — methodology debt a desk would schedule

1. **Index-future delta is outside the equity hedge axis.** The axis sums only EQUITY-class
   names; a strategy position in ES/NQ (FUTURE-class, routed to MACRO) is equity beta the hedger
   neither sees nor hedges. A desk's equity-beta axis includes index futures at their β. Needs
   β(NQ→ES) refdata; natural follow-up to ADR-0040. (The P1-1 fix deliberately reads the HEDGE
   book only, so strategy futures no longer collide with the hedge — this item is about them
   being *unhedged*, not double-traded.)
2. **Momentum fires on the in-window z, which the signal move itself inflates.** σ in
   `windowZScore` includes the move being scored, so a one-tick jump both creates the move and
   dampens its own z — the code even computes the honest `baselineZ` (pre-move EWMA vol) but only
   reports it. Documented choice; a desk would eventually act on the lagged-σ z. Revisit with
   backtest evidence, not by decree.
3. **Impact model has no temporary/permanent split.** The full square-root impact prints into the
   fill price and stays there; Almgren–Chriss decomposes temporary (decays) vs permanent. At the
   2% ADV participation cap this is second-order; matters if participation caps ever loosen.
4. **Seasoned swap flat-fixings approximation.** Elapsed SOFR fixings priced flat at the current
   short rate (disclosed). Correct forward-looking PV; the accrued-carry leg is approximate. Fine
   until fixings are archived; the disclosure is in the right place.
5. **Historical VaR window (60d) vs the 250d+ documented standard** — same root as P1-4;
   the real-history seed is the unlock.
6. **`correlationToPortfolio` floors negative ρ at +0.25 for sizing** — a true diversifier/hedge
   sizes as if ρ=0.25. Deliberately conservative (never super-size on an estimated negative
   correlation) and documented; keep, but revisit when the covariance window grows.
7. **EWMA + hist-VaR treat sim-compressed "days" as days.** Internally consistent (returns are
   per recorded day-row) but a 23400s sim day at real-time pace means the "daily" history accrues
   at wall-day speed — fine now that sim runs real-time; would silently rescale if
   `sim-seconds-per-day` were ever shortened again. Guard: the ADR-0029 mode separation already
   prevents sim/live mixing; add a note where the recorder writes `daily_close`.

## P3 — polish

- `CovMath.ES_95_FACTOR` hardcodes 2.0627 with z=1.645 nearby; deriving it from φ(z)/α in code
  would keep them consistent if z ever changes precision.
- `VarMath.tailMean` divides by idx+1 (includes the VaR point) — standard, but one line of
  javadoc saying "ES includes the quantile observation" would save the next reviewer the check.
- `SimulatedExecutor` LIMIT fills ignore impact (fills exactly at limit) — right for resting
  orders; a marketable limit that crosses a thin book would in reality still pay some impact.
  Disclosed as "no partial fills yet"; fine at this stage.
- Student-t `studentTScale` draws ν Gaussians per tick — clean and deterministic; a Gamma draw
  would be cheaper if the sim ever needs the throughput (it doesn't today).

## Measured against documented systems and references

The benchmark set used for this review — each is the canonical documented source for the
technique it anchors:

- **RiskMetrics Technical Document, 4th ed. (J.P. Morgan/Reuters, 1996)** — EWMA λ=0.94 daily,
  zero-mean convention, effective-memory analysis (basis of P1-2).
- **Ederington, "The Hedging Performance of the New Futures Markets" (J. Finance, 1979)** —
  minimum-variance hedge ratio and ρ² effectiveness — `HedgeMath` matches exactly.
- **Perold, "The Implementation Shortfall" (JPM, 1988)** — arrival-price TCA — `Tca` matches.
- **Almgren, Thum, Hauptmann, Li, "Direct Estimation of Equity Market Impact" (2005)**; Gatheral's
  square-root-law literature; Grinold–Kahn *Active Portfolio Management* — the σ·√(Q/ADV) impact
  form and the participation-cap discipline.
- **Basel FRTB / supervisory VaR standards** — ≥250-day windows, ES at 97.5 — the documented gap
  behind P1-4/P2-5 (Jethro's 60d is a data-availability constraint, now unlockable).
- **CME Treasury futures contract specs + conversion-factor documentation** — the 6% CF pivot and
  deliverable-window CTD behaviour in `BondFutureDurations` are the real mechanics; issue-level
  basket selection honestly deferred until real bond refdata exists.
- **OpenGamma Strata** — swap pricing/DV01/key-rate machinery is delegated to the industry OSS
  analytics library rather than hand-rolled (the right call; ADR-0020), with the one ingestion
  caveat in P1-3.
- **Barra/Axioma fundamental factor models** — the documented home of assigned-beta (structural)
  hedging; ADR-0040's single-beta tier is the honest small version, with the full factor model
  correctly deferred behind a stated trigger.
- **RiskMetrics/Hull on hist-sim VaR conventions** — percentile-index and ES conventions are
  stated in code where they bite.

**Overall placement:** against these references, Jethro's quant layer is a faithful small-desk
implementation: the formulas are the documented ones, the shortcuts are disclosed rather than
hidden, and the two places it diverges from best practice (short estimation windows, par-as-zero
curve) are data-availability constraints with clear triggers, not modeling errors. The one true
defect (P1-1) was a control-loop omission, not a formula error — and the fix restores exactly the
`net = e + h` semantics its ADR already specified.
