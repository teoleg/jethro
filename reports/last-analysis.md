The hedge hands back half of what the desk makes by chasing a target that wanders across zero — so it now closes only the fraction of the gap that its target's own path has earned (ADR-0100).

## Situation — every figure below is quoted from the live endpoints; none is computed here

**1. Money.** Total PnL `$572.09` on `/api/risk` `.total`. The SITUATION header puts the window at
`+66.45` and the last three runs at `+333.01`, with no danger flags; `run-status.json` has `on_track`
true, `pnl_growth_pct` far above the 1% target, `stale` and `underwater` both false. The book is
**not bleeding**. `/api/attribution` splits it `ALPHA +794.32`, `MACRO +376.99` (unchanged to the
cent for a fifth cycle — frozen, position `0.000029` ES), `HEDGE −599.22`.

**2. Risk.** Gross exposure `$19,422.48`, net `$7,678.23`. Gross **fell** `−16,180.65` on the window
against `−17,021.00` over three runs — last cycle's cost gate took the planned book down hard. VaR95
`$158.32`, ES95 `$260.78` on `$19,405.91` of covered exposure; the breaker reads `halted: false`.
Nowhere near the danger zone.

**3. Cause.** Last cycle's ADR-0099 (a name pays its own quoted round trip before its first fill)
scored ✅ **GOOD** — PnL up, gross down by two-fifths, risk-adjusted `0.01446 → 0.02948`. Live
`fusion_targets` confirms the mechanism: the wide-quoted names (`GS`, `BRK.B`, `NFLX`, `ORCL`,
`TSLA`) still carry large planned targets but `deltaQty` is `0` — planned, not entered. No culprit,
no danger state, so nothing de-risking is owed this cycle.

**4. Order-level post-mortem.** The window's `recent_orders` are ALPHA reversion trades in
`MSFT/JPM/GOOG/JNJ/AAPL` — all winners or near-flat per name — plus **one HEDGE ES order per
cooldown**, and those are the problem. Eleven consecutive cooldowns read BUY `0.0068`, BUY `0.0097`,
BUY `0.0022`, BUY `0.0060`, BUY `0.0037`, SELL `0.0046`, SELL `0.0050`, SELL `0.0008`, SELL `0.0064`,
SELL `0.0036`, BUY `0.0058` — `0.0545` contracts traded to finish holding `−0.00494`. Runs of buys
then runs of sells: the target is not noise, it **wanders across zero**.

**5. Change vs market.** The window's gain sits on ALPHA names last cycle's change never opened or
resized (it only removed names from the increasable set), so market and change cannot be separated
there from the numbers alone and the change is credited with none of it. What IS attributable to it
is the gross collapse and the wide names staying unentered. The HEDGE line is attributable to
neither: it has gone `−323.33 → −451.51 → −509.21 → −550.99 → −599.22` across five scored cycles
regardless of what changed above it.

## Diagnosis and change

The overlay is the one thing on the board that is monotonically wrong, and it is **not** execution
cost — `$42.75` of fees at a measured `0.204` bps ES round trip is cents against `$599`. ADR-0098
already fixed the *level* of the target (subtract one σ of its own step) and scored GOOD, but the
live numbers show which half is left: the raw target is `−$6,039.12` against `σ_step = $958.51`, i.e.
**6.3 σ**, so that shrink removes only 16% and the overlay is free to chase every excursion of a
large target that keeps crossing zero. That is a defect of *rate*, and ADR-0080 already established
the house identity for it on the strategy side — approach your target at the rate it survives, and
slow only the risk-increasing leg. The hedge path never got it.

So the hedge now closes only `E` of the gap when it is **growing** the overlay, where `E` is that
target's own directional efficiency — Kaufman's efficiency ratio in exponential form,
`|EWMA(step)| / EWMA(|step|)`, on the series `HedgeTargetChurn` already samples once per cooldown, at
the same `λ = 0.94`. No dial and no invented number: a target going somewhere reads `E → 1` and is
hedged in full; one that returns to where it came from reads `(1−λ)/(1+λ)` and is barely tracked.
Strictly one-way (`|q| ≤ |d|` in every branch; `E = 1` is the old behaviour exactly), and reductions —
including a full unwind — still trade in one cycle, so ADR-0069's promise survives verbatim. Proposed
ADR-0100 ships in the same commit; the deterministic floor is untouched.
