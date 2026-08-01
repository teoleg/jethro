# ADR-0132: Reorient the objective — deploy capital to make money, not preserve the status quo

- **Status:** Implemented
- **Date:** 2026-07-30
- **Deciders:** Oleg
- **Tags:** objective, risk, sizing, capital, loop

## Context

The owner observed the book turning **1,222 orders into ~$122 of PnL** on the day and asked, correctly,
whether *"something is limiting this entire system to make money, just to keep the status quo."* It is —
and it is by design, not by defect. Three configured limiters compound:

1. **The optimizer's objective is capital-preservation.** The continuous-improvement loop's mission
   (`docs/loop-playbook.md`) reads *"grow firm total PnL **while holding or reducing total exposure**,"*
   and the scorer auto-reverts a change whose exposure rises without PnL. The loop is **told to keep the
   book small.** That is the status-quo enforcer.
2. **The daily risk budget is $250.** `jethro.strategy.risk-budget-daily=250` sizes every order to
   `risk-budget-daily / σ_daily`. A book that may risk **$250/day cannot make $1,000/day** — the ceiling
   is arithmetic, independent of signal quality.
3. **Deployed gross is a rounding error of the allowance.** With `unit-notional-usd=50000` and the stacked
   conviction floor / edge gate / agreement-scaling, the whole book builds **~$39k of gross against a
   $500k cap — 8% used.** Nothing external caps the book; it throttles itself.

The result — $122 on $39k — is ~0.31% of gross on the day, which is a *fine risk-adjusted* result on a
microscopic book. The problem is scale, and scale is a money/risk decision only the owner can make.

## Decision

Owner-set parameters (2026-07-30): **deployable capital $200k gross**, **target $1,000/day**, **moderate
risk**. Reorient the system from preserving capital to deploying it:

- **Objective (loop mission + CLAUDE.md).** "Better" now means **grow firm total PnL by deploying capital
  up to the $200k gross budget at moderate volatility** — exposure inside that budget is a resource to
  *use*, not a quantity to minimize. Only **dead exposure** (risk earning nothing) is cut. The concrete
  target is a **$1,000/day run-rate to build toward**; the floor the loop must not fall below is **positive
  PnL expectancy over a rolling 3-day window.**
- **Sizing dials scaled ~5× to deploy ~$200k** (from the ~$39k the same dials build today):
  | dial | was | now | why |
  |---|---|---|---|
  | `jethro.strategy.risk-budget-daily` | 250 | **1250** | daily PnL-vol budget; on $200k ≈ 0.63%/day ≈ ~10% annualized = moderate |
  | `jethro.fusion.unit-notional-usd` | 50000 | **250000** | deterministic cash-at-risk per name at a typical forecast |
  | `jethro.strategy.target-notional` | 25000 | **125000** | pre-vol-history fallback size |
- **Hard risk controls unchanged.** `max-gross-exposure=500000` (the $200k budget sits at 40% of the cap,
  so the cap and the firm drawdown breaker still bind), `max-net-exposure=300000`,
  `max-instrument-exposure=200000`, the conviction floor, edge gate and pre-trade guardrail all stand.
  Moderate means *bigger book, same safety floor* — not "gates off."

## Worked example (why these numbers)

- **Moderate vol:** `risk-budget-daily=$1,250` is the 1σ daily PnL swing the sizer targets. On $200k gross
  that is 0.63%/day, ≈ 10.0%/year (`0.63% × √252`) — a standard *moderate* institutional vol target
  (cited convention, not invented).
- **The target in return terms:** $1,000/day on $200k = **0.5%/day ≈ 126%/yr**. That is a **strong-day**
  number, not a daily guarantee. At ~10% vol and a realistic annualized Sharpe ~1, the *expected* daily
  average is far lower (tens–low-hundreds of $); $1,000 is hit on good days and missed on bad ones. Stated
  honestly so a later cycle does not read "$1,000/day" as a floor the math supports — it does not.
- **Scale check:** today's realized 0.31%-of-gross day → **~$620** at $200k; a 0.5% day → **~$1,000**. So
  the target is reachable on strong days at this size, which is the point of the 5× lift.

## Consequences

- **Intended:** the book actually deploys ~$200k, so a good day earns hundreds-to-~$1,000 instead of ~$122;
  the optimizer stops fighting its own book size.
- **Risk:** ~5× the position size is ~5× the daily PnL swing (~$1,250 1σ, down days included) and ~5× the
  turnover cost — so the ADR-0130/execution turnover-efficiency work matters more now, not less. Still 40%
  of the gross cap with the drawdown breaker intact.
- **Honest limit:** $1,000/day *sustained* at moderate risk on $200k is not achievable on edge alone; to
  make it a true floor you must either add capital (≥ ~$1–2M gross) or accept aggressive risk. Tracked.
- **Reversible:** all three are config dials; revert restores the preservation regime. Owner-set, dated.
