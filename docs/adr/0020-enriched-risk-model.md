# ADR-0020: Enriched risk model on real quant libraries — Commons Math now, Strata for derivatives; exact money ledger preserved

- **Status:** Proposed
- **Date:** 2026-07-12
- **Deciders:** Oleg
- **Tags:** risk, data, backend

## Context

Risk today is notional exposure + PnL + static caps (ADR-0017). It treats every dollar as
equally risky and is portfolio-blind — no volatility, VaR, or concentration — and the
strategy's signals never see book state. The owner's concern: hand-rolled risk math "looks
like a toy," and rightly so — reinventing volatility estimators, covariance, and (later)
curve/day-count/Greeks conventions is where amateur systems get subtly wrong numbers.

Forces: invariant 1 (money is exact decimal, never `double`); a JVM/ARM/Pi target;
finance math must be auditable; ADR-0002 (native code only behind a measured need). The
decision: which quant library, and where does it stop touching the money ledger?

## Decision

We will build risk analytics on **established libraries, not hand-rolled math**, matched
to the instruments:

- **Apache Commons Math** (pure Java) now, for the statistics we need — volatility (σ of
  log returns), covariance/correlation, and parametric VaR (`z · σ · |exposure|`). Real,
  respected, trivial to integrate.
- **OpenGamma Strata** (pure Java, institutional — no JNI) reserved for when we add
  **derivatives pricing / Greeks / curves / day counts** (rates, options). Trigger:
  trading those instruments. Adopting it early would force its swap/curve domain model
  onto an equities book for no payoff.

Hard boundary: the money ledger (positions, PnL, exposure) stays exact `BigDecimal`
(invariant 1); library analytics run in `double` and cross back to `BigDecimal` only at
the money boundary (VaR in currency). Portfolio VaR starts as the conservative
undiversified sum.

Worked example (95%, z=1.645): AAPL exposure 19,000, daily σ=1.5% → VaR ≈ 1.645 · 0.015 ·
19,000 = **468.83**, encoded as an exact-value boundary test.

## Alternatives considered

**Hand-rolled primitives (no library).** Rejected: reinvents error-prone conventions and,
as the owner put it, looks like a toy. Acceptable only for the most trivial glue.

**QuantLib (JNI/C++).** Rejected: best-known, but native — JNI + ARM/Pi build pain,
against the JVM/lean posture (ADR-0002). Revisit only for a model that exists only there.

**Strata now, for everything.** Deferred, not rejected: it's the right JVM library for
rates/FX/derivatives, but its trade/curve/market-data model is heavy and a poor fit for
today's equity + index-future + FX-spot book. Adopt when derivatives pricing lands.

## Consequences

- Positive: risk numbers are library-backed and defensible, not toy; VaR gives a dollar
  risk budget; concentration exposes pile-ups; the enriched snapshot is the input signals,
  commentary, and any trained model need. A clear upgrade path to Strata for derivatives.
- Negative: parametric VaR assumes normal returns and an undiversified sum — it
  **understates tail risk and ignores diversification** (documented; scenario/stress is the
  real hedge, deferred). Two representations (`double` analytics beside exact money) — the
  boundary must be enforced in review. A returns window needs warm-up before it means
  anything. New third-party dependency to vet and keep current.
- Follow-ups: feed the enriched snapshot into the strategy (vol-scaled sizing,
  concentration damping) and into limits (VaR/concentration caps); correlation-based VaR +
  scenario/stress; Strata when Greeks/curves arrive.
