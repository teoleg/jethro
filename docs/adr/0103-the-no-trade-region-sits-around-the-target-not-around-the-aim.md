# ADR-0103: The no-trade region sits around the TARGET, not around the aim

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, execution, cost, risk

Supersedes the *band location* decision of **ADR-0094**. The band's **width** (ADR-0101), the aim path
and its rate (ADR-0080), the aim clamp (ADR-0102), the average-position scale (ADR-0094), the
reduce-only clamp (ADR-0064/0075) and every exit control are unchanged.

## Context

**The desk stopped being able to hold a position.** Firm gross exposure fell from `$26,684.11` to
`$7.90` over one cycle and stayed there; total PnL froze at `$787.04` while fees kept accruing. Twenty
of twenty-three names reported `insideBuffer` on every plan, all twenty-three carried a non-zero
target, and the position book was empty. A flat book earns nothing, so this is a failure state, not a
conservative one.

The cause is a units mismatch between two decisions that were each correct on their own.

**ADR-0101 derives the width about the frictionless optimum.** Its derivation is the standard myopic
benefit-versus-cost threshold (Grinold & Kahn, *Active Portfolio Management* 2e ch. 16):

```
  U(n) = μ·n − ½λσ²n²        maximised at  a = μ/(λσ²)      ← a is the OPTIMUM
  rebalancing pays when  ½λσ²·g² > C·|g| ,  g = a − h
  ⇒  |g| > 2C/(λσ²) = a·(2C/μ)          band = averagePosition · (2C/μ)
```

Every `a` there is the position the model wants *now* — this planner's **target**.

**ADR-0094 hands it a different `g`.** It compares the band against `aim − held`, where the aim is the
ADR-0080 exponential path toward the target. In steady state that quantity is roughly the adjustment
rate times the real gap: at the live derived rate `a = 1 − e^(−30/900) = 0.0328`, the desk was testing
a quantity about **3%** of the one the width was sized for.

ADR-0094 made that move for a reason that has since expired. At Carver's `0.10` the band was
`0.10 × averagePosition` and could not bind around the target — "every planned name with a non-zero gap
traded at exactly the full rated fraction". Moving the band onto the aim made it bind. But ADR-0101 then
*measured* this desk's width and it is not `0.10`: live it runs `0.24` (AAPL) to `1.00` (the capped
names), against `μ = 7.9748` bps and per-name round trips of `0.42–20.11` bps. A band that wide binds
around the target perfectly well. The workaround outlived the problem, and at the measured width it
became a brake with no release.

**Two failures followed, both measured on the live book.**

1. **A position could not be opened at a useful speed.** From flat, the aim must accumulate a whole
   band before the first order clears. For the live GOOG plan (forecast `−16.19`, width `0.4944`) the
   band is `30.5%` of the target, so `1 − (1−a)ⁿ ≥ 0.305` ⇒ **n ≈ 11 cycles ≈ 5½ minutes** of unbroken
   agreement before one share trades.

2. **And that wait was restarted continuously.** The only source that passes this desk's gate is
   `reversion`, measured at the **900 s** rung, and it changes side well inside 5½ minutes. Each change
   of side clamps the aim flat (ADR-0102) — and the old rule read a flat aim as an **exit**, dumping the
   whole position at market, unbuffered. Observed live over four minutes of polling: GOOG was sold 8
   shares at forecast `−16.19` against a target of `−103.398`, and all 8 were bought back at `+2.00`.
   A complete round trip, spread paid twice, on a position that never reached 8% of its target.

So the desk paid a round trip per sign flip while never accumulating a position — the exact inverse of
what a no-trade region is for. The band was suppressing **accumulation toward the target** (which costs
one spread and buys the whole edge) and permitting **oscillation around the position** (which costs two
spreads and buys nothing).

## Decision

**Test the gap to the target; take the aim's step.**

```
  step = aim − held                                the ADR-0080 rate-limited move, unchanged
  gap  = target − held                             the distance ADR-0101's width is derived about
  target = 0                     → step            an exit is worked in full, never buffered (ADR-0090)
  |gap| ≤ band                   → 0               close enough to the optimum to leave it alone
  sgn(step) ≠ sgn(gap)           → 0               the intent does not point at the optimum
  otherwise                      → step, capped in magnitude at  |gap| − band
```

The band decides **whether** the position is far enough from its target to be worth a round trip; the
aim decides **how far** to move once it is. The settled position is unchanged at `target − band·sgn` —
one full cost-derived buffer inside the desk's own target.

### What this changes, honestly

It **restores exposure**. That is the point: the book is flat and the objective is PnL per unit of
exposure, not the absence of exposure. From the live plan, the fixed point is roughly `$79k` of gross
across the names the gate lets increase — nine of twenty-three names have `band > |target|` and stay
flat by the rule itself, and seven more are reduce-only on cost (ADR-0075). The rebuild is
rate-limited, so it takes ~10–20 minutes rather than arriving as a jump. If the measured result is a
worse vector, the scorer says so and this is reverted.

It also **removes** the round trip described above: a target wobbling within a band of the position now
trades nothing at all, where before it round-tripped the whole holding.

### What this cannot change

- A **flat target** still returns the whole position in one cycle, unbuffered. The branch is now keyed
  on `target == 0` rather than on `aim == 0`, which is the same condition for every control that means
  *get out* — `nextAim` snaps the aim to zero precisely then. The ADR-0086 chandelier cut, the ADR-0065
  orphan unwind, the ADR-0027 firm breaker and the pre-trade guardrail keep their exact semantics, and
  that identity is pinned as a test.
- The step is **never larger** than the ADR-0080 one and **never carries the position past the near
  edge** of the region, so the desk still cannot reach, let alone exceed, its own target.
- No number is introduced. The bound is the target and the band the planner already computed
  (invariant 7 / ADR-0016). Exact decimal throughout (invariant 1).

## Worked example — the live AAPL plan, asserted as a test

forecast `−9.64`, target `−142.319300`, held `−7`, rate `a = 0.0327839…`, width `0.10`:

```
  averagePosition = 142.319300 × 10 / 9.64  = 147.634128
  band            = 0.10 × 147.634128       =  14.763413
  aim             = −7 + a·(−142.319300 + 7) = −11.436294
  step            = −11.436294 − (−7)        =  −4.436294
  gap             = −142.319300 − (−7)       = −135.319300      |gap| > band ⇒ outside
  |step| = 4.436294 ≤ |gap| − band = 120.555887                 ⇒ delta = −4.436294
```

ADR-0094 tested `|step| = 4.436294 ≤ 14.763413` and traded nothing — for as long as the target stayed
out of reach.

And the cap, at an intent that has run ahead: aim `−140`, same band and target ⇒ `step = −133`, capped
at `−(135.319300 − 14.763413) = −120.555887`, landing at `−127.555887 = target + band` exactly.

And the wobble it now suppresses — the live GOOG round trip, at its own measured width
`2C/μ = 2 × 1.9714/7.9748 = 0.4944`: held `−8`, forecast flipped to `+2.00`, target `+28.092000` ⇒
`averagePosition = 140.460000`, `band = 69.443424`, `gap = 36.092000 ≤ band` ⇒ **no order**. The old
rule bought all 8 back at market.

## Consequences

- The desk can open a position on the cycle a view appears, instead of after eleven cycles of unbroken
  agreement it does not get from a 900 s mean-reverting source.
- Turnover moves from round trips to accumulation. Whether that nets out cheaper is measurable in
  `turnover_cost_by_name` and in the ALPHA fee line, and the improvement ledger scores the vector.
- Gross exposure rises off zero. The volatility budget (ADR-0083), the portfolio risk normaliser
  (ADR-0079/0089), the per-name cost gate (ADR-0075), the chandelier cut (ADR-0086) and the firm
  drawdown breaker (ADR-0027) all continue to bound it; none of them is touched here.
- ADR-0094's *width* rationale and its average-position scale survive intact; only its choice of the
  quantity the band is measured against is superseded.

## Alternatives considered

- **Widen the rate instead.** The rate is a derived identity (ADR-0080: the position's time constant is
  one evidence horizon) and is not the thing that is wrong. Changing it to compensate for a
  mis-anchored band would break that identity to fix a different bug.
- **Drop the partial adjustment entirely** and trade straight to the band edge — the pure
  Constantinides / Davis–Norman policy this desk's proportional costs call for. Defensible, and the
  band alone would control turnover. Rejected for now because it arrives as a step change in exposure
  with no rate limit; keeping the ADR-0080 step makes the rebuild gradual and observable, and the two
  policies share the same fixed point, so this can be revisited from measurement rather than argument.
- **Revert ADR-0102.** It is not wrong — an intent on the opposite side of the current forecast is a
  real defect and it was live. What was wrong is that a clamped aim was read as an exit, which is a
  consequence of the band's location, not of the clamp.
