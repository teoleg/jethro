# ADR-0038: Minimum-variance proxy hedging — beta to index futures, DV01 to Treasury futures, direct FX

- **Status:** Proposed
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** risk, hedging, quant

## Context

The platform measures risk well (EWMA covariance, parametric + historical VaR, bucketed
DV01, scenario engine) but can only *reduce* it by closing positions — which contaminates
strategy P&L and hypothesis outcome scoring (ADR-0027) with risk-management noise. What is
missing is the standard desk capability: offset an exposure with a **liquid proxy** so the
strategy's position stays on while the book's risk comes down. The universe already contains
the canonical proxies (ES/NQ index futures, ZT/ZF/ZN/ZB Treasury futures, EURUSD/GBPUSD
spot), and `CovMath` already computes the EWMA covariance the ratio math needs. Whatever is
chosen must be deterministic (invariant 7), exact-decimal at the money boundary
(invariant 1), and testable against the sim's known factor loadings.

## Decision

We will hedge with the **minimum-variance hedge ratio** (Ederington): for position S and
proxy F, `h* = Cov(ΔS, ΔF) / Var(ΔF)`, estimated from the existing EWMA covariance
(λ = 0.94, minimum-observation gate), specialized per asset class:

1. **Equities — beta-hedge onto index futures.** Systematic exposure = β̂ × position
   notional; hedge = opposite position in ES (default) or NQ sized
   `systematicNotional / (price × multiplier)`. Worked: long 2,000 AAPL @ 190.00
   ($380,000), β̂ = 1.20 → $456,000 systematic; ES @ 5,600 × 50 = $280,000/contract →
   short 456,000/280,000 = **1.628571 ES**. Fractional contracts are a disclosed sim
   simplification; whole-contract rounding is a live-broker follow-up.
2. **Rates — DV01-neutral onto Treasury futures per bucket.** For each curve bucket, hedge
   quantity = − bucketDV01 / contractDV01 of the bucket's future (ZT 2y, ZF 5y, ZN 10y,
   ZB long), contract DV01 from the existing CTD model. Worked: +$450/bp in the 10y bucket,
   ZN DV01 $64/bp → short 450/64 = **7.03 ZN**.
3. **FX — direct hedge in the pair.** Hedge target = the book's net non-USD value (the
   ADR-0037 translation driver, open positions + un-repatriated realized). Net €150,000 →
   sell 150,000 EURUSD; `fxTranslationPnl` is pinned while the hedge is on.

Every proposal carries its **measured effectiveness** `e = ρ²` (variance fraction removed)
and the residual σ; a proxy with `ρ²` below a configured floor (default 0.25) is not
proposed — closing the position is then the only honest lever, and the advisor says so.
Ratio math lives in risk-pnl beside `CovMath`; execution goes through the ordinary order
path. When to hedge, and who pulls the trigger, is ADR-0039.

## Alternatives considered

**Options-based protection (protective puts / collars).** The other canonical hedge — caps
downside without capping variance symmetrically. Rejected now: no options instruments,
refdata, or vol-surface pricing exist; a large infra buy. Deferred — trigger: options added
to the instrument universe with a pricing source.

**De-risking by trimming the position.** Not a hedge: it changes the strategy's position,
realizes P&L and costs, and corrupts hypothesis outcome scoring. Remains the fallback the
advisor recommends when no proxy passes the `ρ²` floor.

**Single-name pairs hedge (e.g. short MSFT against AAPL).** Cross-name correlation is less
stable than name-to-index, `ρ²` typically lower, and borrow/carry is unmodelled. Rejected
versus the index proxy.

**Full covariance-QP overlay** (optimizer picks a weight vector across all instruments
minimizing portfolio σ). More powerful, but per-trade rationale is opaque and the answer is
noise-sensitive in the covariance tails. Deferred — trigger: measured residual VaR staying
above target after single-proxy hedging.

## Consequences

- Positive: risk comes down without touching strategy positions; every hedge has an
  auditable formula, a stated effectiveness, and (in sim) a ground truth — measured β̂ must
  converge to the configured factor loading, an exact-tolerance test.
- Negative: **basis risk is accepted and displayed** — a β-hedge at ρ = 0.85 still leaves
  ~53% of daily σ (√(1−0.72)); β̂ and DV01 drift, so hedges must be rebalanced (ADR-0039);
  hedge legs consume gross-exposure budget under the ADR-0018 guardrail (a book at its
  gross cap cannot hedge — follow-up: hedge-aware gross headroom or net/beta-adjusted
  limits).
- Follow-ups: ADR-0039 (lifecycle/triggers); whole-contract rounding at a real broker;
  hedge-aware guardrail headroom; effectiveness telemetry (realized variance reduction vs
  promised `ρ²`).
