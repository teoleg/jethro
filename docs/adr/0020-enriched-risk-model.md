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
