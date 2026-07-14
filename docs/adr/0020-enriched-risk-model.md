# ADR-0020: Multi-asset quant foundation — OpenGamma Strata as the analytics substrate, exact money ledger on top

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** Oleg
- **Tags:** risk, data, backend, architecture

## Context

Jethro is a **multi-asset** platform (CLAUDE.md): equities and index futures today, FX,
rates, options and credit ahead. Risk today is notional exposure + average-cost PnL +
static caps — no volatility, VaR, curves, Greeks, or scenario, and portfolio-blind.

The decision is the **analytics foundation** for a multi-asset risk/pricing engine, not a
one-off statistic. Building that layer out of general-purpose math (std-dev, matrices)
means re-implementing finance conventions — day counts, calendars, curve interpolation,
Greeks, discounting — which is exactly where hand-rolled systems get subtly wrong numbers
and "look like a toy." Constraints: JVM on ARM/Pi, no JNI embedded (ADR-0002), exact money
(invariant 1), auditable finance math.

## Decision

We will adopt **OpenGamma Strata** as the multi-asset quant/analytics substrate — a
pure-Java institutional library whose domain model already spans the layers we need:
reference data (calendars, day counts), market data (curves, vol surfaces), products
across asset classes, pricers (PV + Greeks), and a **measures + scenario/stress**
framework. We model Jethro instruments onto Strata products and build curves/surfaces from
market data; PV, Greeks, VaR and scenario come from Strata measures.

Hard boundary: the **exact money ledger** — positions, average-cost PnL, cash, exposure —
stays in `common-domain` `BigDecimal` (invariant 1) as the record of truth; Strata's
`double` analytics run at the pricing layer and convert back at the money boundary. A
general-purpose math library is at most a low-level transitive detail, never "the risk
library." QuantLib stays reserved as an **out-of-process** pricing service (ADR-0002/0010)
for exotics Strata can't cover — never JNI-embedded on the Pi.

## Alternatives considered

**General-purpose math library as the risk layer (Commons Math et al.).** Rejected as the
foundation: it's statistics, not a finance engine — assembling curves, day counts, Greeks
and scenario from primitives is the toy path. (Supersedes this ADR's earlier draft framing.)

**QuantLib embedded via JNI.** Rejected: native C++/JNI, ARM/Pi build pain, against the
JVM/lean posture (ADR-0002). Reachable only as a separate service, for exotics.

**Hand-rolled finance framework.** Rejected: reinvents years of error-prone market
conventions; unauditable at the pace we need.

**Defer a foundation, keep bolting on ad-hoc measures.** Rejected: that is the tunnel-
vision path — a pile of inconsistent numbers instead of one coherent multi-asset engine.

## Consequences

- Positive: one coherent multi-asset substrate — curves, pricing, Greeks, VaR, scenario
  across asset classes; credible, not toy; exact money preserved; a defined home for every
  future measure. QuantLib still reachable as a service when exotics demand it.
- Negative: Strata is a substantial dependency with its own domain model — real
  integration cost (mapping instruments, sourcing market data); `double` analytics beside
  exact money means the boundary must be policed in review; strongest for rates/FX/credit,
  so equities/futures use a thinner slice at first; more build weight (pure-Java, Pi-fine).
- Follow-ups: verify the Strata artifact/version resolves; map instruments→products and
  marks→curves per asset class; wire VaR/sensitivities/scenario into the risk snapshot;
  feed the enriched snapshot to the strategy and limits; QuantLib service for exotics.

## Implementation note — live swap economics + dynamic bond-future DV01 (2026-07-14)

Two deliberately-deferred first-order conventions became live analytics:

**Swap ledger multiplier is now dynamic.** The V9 quoting convention (mark = par rate in
percent, P&L = qty × Δpar-points × multiplier, multiplier = DV01 × 100) held the multiplier
at its inception constant (45,000/pt 5Y, 80,000/pt 10Y). The ledger (`RiskProjection`) now
reads the multiplier from the LIVE Strata per-lot DV01 via `SwapDv01Source` (1s memo in the
wiring, off the tick path): the swap annuity drifts with the curve, so a constant multiplier
mis-states P&L exactly when rates move most. Worked: BUY 2 USD_IRS_5Y @ 4.04, par now 4.14
(+10bp), live DV01 $430/bp/lot → unrealized = 2 × 0.10 × 43,000 = **$8,600** (the static
45,000 would say $9,000). Realized P&L on closing fills monetizes at the same live
multiplier. No curve yet → static refdata multiplier (the disclosed V9 approximation),
never zero. Remaining approximation, stated: the avg-cost ledger has no per-trade maturity
dates, so the annuity is the fresh reference-tenor annuity — no roll-down; a trade-dated
swap book is the refinement.

**Bond-future DV01 breathes with the curve.** `BondFutureDurations` computes the closed-form
modified duration of a semiannual PAR bond at the LIVE Treasury yield for the future's key
tenor — D(y,T) = (1/y)(1 − (1+y/2)^(−2T)); worked: 10Y @ 4.5% → 7.9819, @ 1.5% → 9.254 —
replacing the static refdata duration in both `RatesRiskService` (bucketed DV01) and
`ScenarioEngine` (rates shocks). Falls back to the static duration until the curve quotes;
skipped (counted) when neither exists. CONVENTION: par-bond proxy for the CTD — a delivery
basket/conversion-factor model is the tracked refinement, not faked here.

## Implementation note — rates refinements (2026-07-14, task #16)

Three refinements. (1) **CTD-window bond-future duration**: the future's duration now uses
the published CME deliverable maturity window plus the conversion-factor 6% rule (CFs price
every deliverable at 6%: below 6% market yields the SHORT window end is cheapest-to-deliver,
above it the LONG end), with the par yield interpolated at that CTD maturity. Worked: ZN's
window is 6.5–10y; at 4.5% the CTD sits at 6.5y → D = 5.58, not the naive 10y figure 7.98 —
the error real CTD selection removes. Per-ISSUE selection (actual basket, repo) still needs
real bond reference data — stated, not faked. (2) **Swap gross-notional exposure** (V22):
a swap position's exposure is now qty × $1M notional per lot (`notional_per_lot` refdata
attribute) instead of qty × par × multiplier (which understated a $1M lot ~5×); P&L math is
unchanged; MACRO book limits recalibrated with the convention. (3) **Trade-dated swap book**
(V23): every SWAP fill is registered as a dated trade (idempotent on fill_id; session-day
dated, so compressed sim days age trades in minutes) and priced as a SEASONED swap — fixed
leg at its OWN entry par, REMAINING schedule from its own trade day — via full Strata reval
on the live curve: PV carries roll-down and per-trade DV01 shrinks with age (3y into a 5y
swap ≲ 2/5 of the annuity remains). Elapsed SOFR fixings are approximated flat at the current
short rate (no fixing archive; touches only the in-progress accrual period — stated). The
fills-projected ledger remains the P&L source of truth (invariant 3); `/api/swaps/book` and
the Rates page's "Swap book — trade-dated" panel expose the precise view beside it.

## Implementation note — key-rate DV01 as risk state (2026-07-14, task #22)

Curve sensitivities are now first-class risk state (quant-engine step 4): `/api/dv01`
reports each book's DV01 PER CURVE NODE (1Y/2Y/5Y/10Y/30Y), so a 2s10s steepener shows its
offsetting legs where a single total nets to nearly nothing. Swap legs are the trade-dated
book's **Strata parameter sensitivities** partitioned per node
(`SwapPricingService.bucketedDv01Seasoned`) — the buckets are the same vector the total
DV01 sums, so they add back to it exactly, and an aged trade's risk visibly rolls down the
curve (a 10Y traded 6y ago buckets at ~4y, nothing left at the 10Y node). Treasury futures
book DV01 = netExposure × (−D) × 10⁻⁴ (the ScenarioEngine sign convention), allocated
across the two curve nodes bracketing the CTD maturity by linear key-rate weights — worked:
long 2 ZN @ 110.50 × $1000, D 6.3 → DV01 −139.23; CTD 6.5y ⇒ w(10Y) = (6.5−5)/(10−5) = 0.3
→ 10Y −41.769, 5Y −97.461 (remainder, so the split sums exactly). This SUPERSEDES the
static key-tenor `RatesRiskService` (fresh-tenor swap DV01, fixed instrument→bucket map),
which is deleted. Positions with missing data are skipped AND counted; a dead curve is
disclosed (`curveLive=false`), never zeroed silently.

## Implementation note — full-revaluation scenarios (2026-07-14, task #24)

The scenario engine's rates legs are now FULL revaluation on the shifted curve
(quant-engine step 2 remainder), replacing the first-order extrapolations:

**Bond futures** re-price the CTD par bond at the shocked yield —
P(y′) = (y/y′)(1 − (1+y′/2)^(−2T)) + (1+y′/2)^(−2T), the same CTD window + 6% CF rule the
duration model uses — so scenarios carry convexity. Worked (ZN, CTD 6.5y @ 4.5%, net
110,500): +100bp → −5,970.87 and −100bp → +6,374.55, where linear said ±6,168 — long
bonds gain more on the way down than they lose on the way up, which −D·Δy cannot show; on
ZB (D≈10.8) the linear error at ±100bp is ~7 points of the move. A shift that would take
the yield non-positive refuses to reprice and falls back to first-order (disclosed, never
guessed).

**Swaps** re-price the trade-dated book's seasoned trades on base vs shifted curve
(`seasonedPnlUnderShock` via the `SeasonedSwapReval` hook): an aged trade responds like
its REMAINING tenor (a 10Y traded 6y ago shocks like a 4y), payer convexity comes through
(gains less than DV01×Δ on +Δ, loses more on −Δ — asserted as pricing identities), and
past SOFR fixings are pinned to the BASE curve in both worlds — history is a fact a
scenario must not rewrite. Fallback order: seasoned book → fresh-tenor per-lot reval →
first-order V9 convention.

Stated: this is direct shifted-curve repricing through the same Strata pricers, not the
`ScenarioMarketData` scenario-set wrapper — identical math at this scale; adopt the
wrapper if scenario sets grow. Equity×FX cross-terms remain first-order (documented).
