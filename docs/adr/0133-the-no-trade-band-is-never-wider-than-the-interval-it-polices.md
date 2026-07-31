# ADR-0133: The no-trade band is never wider than the interval it polices

- **Status:** Implemented
- **Date:** 2026-07-31
- **Deciders:** continuous-improvement loop (ADR-0063); Oleg to ratify
- **Tags:** backend, risk, fusion, execution

## Context

The desk was **DORMANT** at the US open with a $200k deploy budget (ADR-0132) and a $1.5M firm gross cap,
holding **$14,215** against a target book it had itself computed at **$303,271** — 4.7% of the risk its
own forecasts asked for. `insideBuffer` read **19, 18, 20, 20** of **20** names across four consecutive
re-plans; two of the four planned *nothing at all*. The cause is in the ADR-0094 buffer's own arithmetic.

### The band and the interval it is tested inside are measured on different scales

Write `V` for the name's position at a full-strength forecast, `F` for `TARGET_ABS` (10), `w` for the
buffer fraction and `f` for this cycle's combined forecast. The planner's target is **linear in the
forecast** — every step between them (the ADR-0083 volatility budget, the ADR-0079 portfolio normaliser)
is a per-name or book-wide scalar that does not depend on forecast *strength* — so:

```
|target| = (|f| / F) · V                          the position this cycle's conviction asks for
band     = w · |target| · F / |f|  =  w · V       ADR-0094/0101 — the two |f| cancel
```

**The band does not shrink when conviction is weak; the target does.** ADR-0102 then confines the aim to
the closed interval between flat and the *current* target, an interval of width `|target|`, and ADR-0094
tests the band against `gap = aim − held` *inside that interval*. So the band consumes

```
band / |target|  =  w · F / |f|
```

of the entire interval the aim may ever occupy — and once `|f| < w · F` it is **wider than the whole
interval**. From flat, `|gap| = |aim| ≤ |target| < band` at *every* aim the ADR-0080 path can ever reach,
so `bufferedDelta` returns exactly zero forever. The name can never be opened at all.

This is not a slow ramp. It is a permanent veto on every name whose conviction falls below `w · F`,
decided by no ADR, expressing a conviction floor the desk already has as a separate deliberate control
(`min-forecast-to-route`) — and invisible in every telemetry field except the delta itself.

At the shipped `jethro.fusion.position-buffer.fraction=0.10` and `TARGET_ABS = 10` the threshold is
`|f| < 1.0`. Above it the veto is not permanent, but the lockout is still long: the aim must climb
`w · F / |f|` of the way to the target before the **first share** trades, against an aim path whose
e-folding time is one hour (`a = 1 − e^(−30/3600) = 0.0082987…`, ADR-0080) while the target re-anchors
every 30s and ADR-0102 clamps the aim back down each time the target shrinks.

### Read from the live book

`/api/fusion/targets`, US session open, `routing: true`, `edgeGate: null` (so `widthFor` returns the
convention `0.10`), at `atMillis` 1785504804718 / 1785504834909 / 1785504865091 / 1785504895225:

- **6 of 20 names had `|forecast| < 1.0`** — CAT (0.208), XOM (0.200), HD (0.175), PG (0.127),
  JNJ (0.033), KO (0.000) — a band strictly wider than their entire target interval: permanently
  untradeable at any aim.
- Of the remaining 14, the band consumed **12%–50%** of the target. Worked from the endpoint's own
  published fields: AAPL `102.395 × 10 / 8.075 × 0.10 = 12.681` against `gap = 43.665 − 32 = 11.665`
  → inside. NEE `403.475 × 10 / 2.305 × 0.10 = 175.021` against `gap = 34.533` → inside. PFE
  `959.066 × 10 / 2.541 × 0.10 = 377.467` against `gap = 7.959` → inside. BAC
  `263.702 × 10 / 1.986 × 0.10 = 132.757` against `gap = 22.772` → inside.
- Gross exposure across the four re-plans: $18,393 → $17,560 → $17,605 → $17,615, against a $303,271
  target book. The SITUATION header flagged **DORMANT**.

### Why the cited rule cannot produce this

The class cites Carver (*Systematic Trading*, Harriman House 2015; *Advanced Futures Trading Strategies*,
2023): buffer the position at a fraction of the **average position** and trade to the buffer edge. In
that rule the distance tested is `|target − current|`, and at a typical-strength forecast the target *is*
the average position — so the band is at most `w` of the distance it is compared against, commensurate by
construction, and can never swallow the whole position. ADR-0094 moved the test onto the aim (correctly:
under partial adjustment the ADR-0055 band could never bind) but carried the average-position *scale*
across unchanged, and ADR-0102 later shrank the aim's interval with conviction. The incommensurability
is the residue of those two changes, not of either one alone.

## Decision

Cap the buffer's position scale at the target it is policing:

```
scale = min( |target| · TARGET_ABS / |forecast| ,  |target| )
band  = scale · width
```

so `band ≤ width × |target|` always: a no-trade half-width can never exceed `width` of the interval it
sits inside. Nothing else changes.

- **The cap is inert at and above a typical-strength forecast.** `avg > |target|` exactly when
  `|f| < TARGET_ABS`, so for `|f| ≥ 10` the min returns the existing term unchanged and the name is
  byte-identical.
- **Below that the band becomes `width × |target|`** — Carver's own ratio at a typical-strength forecast,
  where the target *is* the average position — instead of growing without bound as conviction weakens.
  Note this is a wider change than the veto case alone (`|f| < 1.0`): it binds on every name with
  `|f| < 10`, which on the live book is all 20. That is deliberate. The veto is only the extreme end of
  one continuous defect — a band that grows as `1/|f|` while the interval it is tested inside shrinks as
  `|f|` — and capping only the extreme would leave the same mechanism throttling every other name.
- **Trading becomes possible on a uniform timescale.** The desk starts trading once the aim has covered
  `width` of the way to the target — about 6 minutes of aim path from flat at the ADR-0080 derived rate —
  regardless of how weak the view is, and it converges to within `width` of its own target instead of
  being pinned at 5% of it.
- **Strictly one-way.** `min` can only ever NARROW a band, never widen one. So this can only permit a
  trade the desk's own aim path had already decided to make; it never enlarges a trade, never changes a
  sign, and never moves the aim.

### No number is introduced

The cap is `|target|` — the quantity the planner already computed this cycle, which is the same
provenance ADR-0102 uses to bound the intent and ADR-0132 uses to bound the destination. `width` stays at
Carver's `0.10`; ADR-0101's measured width still floors at it; `TARGET_ABS` and the ADR-0080 rate are
untouched. No dial changes (invariant 7 / ADR-0016).

## Consequences

**Positive.** The desk can enter a name it has decided to hold. Exposure should rise from the ~5% of its
own target book it was pinned at toward the owner-set deploy budget — which is the stated objective
(ADR-0132: undeployed capital under the budget is a failure to attack, not safety). Low-conviction names
stop being silently vetoed by an execution dial that was never meant to express a conviction floor.

**Negative / risks.**
- **Exposure and turnover will rise, deliberately.** Names that planned zero for hours now plan a rated
  step once the aim clears the narrower band. If the measured edge is genuinely absent, that turnover is
  a pure cost — the ADR-0116 scorer will grade it ❌ and revert it. That is the honest test of whether the
  buffer was protecting the book or strangling it, and it is the check this loop exists to run.
- The band still suppresses oscillation, but now within `width` of the target rather than within `width`
  of a full-conviction position. On a weak-conviction name that is a materially tighter no-trade region,
  so churn there is bounded by the ADR-0080 rate rather than by the band.

**Unchanged — every other semantic, and the whole deterministic floor.** The aim path (ADR-0080), the
intent clamp (ADR-0102), the destination clamp (ADR-0132), the ADR-0107 rated view-change unwind, the
ADR-0090 unrated same-side de-risking, ADR-0118's trapped-exit branch, the reduce-only re-seed under a
shut ADR-0064 gate or ADR-0126 σ-cold veto, and the unbuffered full working of a flat target (ADR-0086
chandelier cut, ADR-0065 orphan unwind, ADR-0027 breaker) all keep their exact semantics — this ADR
changes one term inside `band(...)` and touches no other method. Below it, the ADR-0083 volatility
budget, the ADR-0079 portfolio normaliser, the book volatility brake, the per-instrument/book/firm gross
and net exposure caps, the pre-trade guardrail and the firm drawdown breaker all still have the last word
on every order.

Exact decimal throughout (invariant 1). Nothing here prices or sizes anything (invariant 7 / ADR-0016).
