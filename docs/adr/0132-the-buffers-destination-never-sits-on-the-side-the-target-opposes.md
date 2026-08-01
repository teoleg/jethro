# ADR-0132: The buffer's DESTINATION never sits on the side the target opposes

- **Status:** Implemented
- **Date:** 2026-07-30
- **Deciders:** continuous-improvement loop (ADR-0063); Oleg to ratify
- **Tags:** backend, risk, fusion, execution

## Context

ADR-0102 established that the desk's **intent** — the aim — must lie in the closed interval between flat
and the current target: never past it, never against it. That bound is on where the desk *means* to be.

It is not a bound on where the desk *actually stops*. ADR-0094 buffers around the aim and trades to the
**near edge** of the no-trade region, so the position the desk settles at is `aim − band·sgn(gap)`, a
whole band-width away from the intent. And the band (ADR-0094/0101) is scaled by the name's **average
position at the TARGET**, `|target| × TARGET_ABS / |forecast|` — which, early on the aim's deliberately
slow ADR-0080 path (`a = 1 − e^(−30/3600) = 0.0082987…`, an hour-long e-folding), is routinely many
multiples of the aim itself.

Subtracting a band that large from an aim that small lands **past flat**. The no-trade region therefore
straddles zero and reaches onto the side the desk's own forecast opposes. Two failures follow, and the
live book is in both:

1. **Frozen.** A wrong-side holding smaller than the band sits *inside* the no-trade region, so the delta
   is exactly zero — every cycle, indefinitely. There is no path out of the position at all short of a
   risk cut firing or the sources going silent.
2. **Steered to the wrong side.** A wrong-side holding larger than the band is traded toward a
   destination that is *itself still on the wrong side*, so the desk's own arithmetic says it intends to
   stop while short a name it wants long.

Either way the desk carries gross exposure on a position its current view contradicts, and pays again on
the ES hedge sized against the resulting firm net — exposure on both legs, for a position nothing planned.

**ADR-0118 diagnosed exactly this and its remedy does not reach the shipped configuration.** That ADR
worked the identical AAPL plan (short 1 against a target of +6.031064) and fixed it — but scoped the fix
to `isTrappedExit`, which only runs inside the `!mayIncrease` branch: a **shut** edge gate, or an
ADR-0126 σ-cold name. Under ADR-0122 (owner-directed, `jethro.fusion.edge-gate.enabled=false`) the gate
is off, and the ADR-0071 boot seeding warms the σ sensors, so `mayIncrease` is true for effectively every
name and that branch never runs. The trap ADR-0118 documented has been live on the ordinary path ever
since, and its own test (`anOpenGateStillRebalancesAWrongSideHoldingRatherThanLiquidatingIt`) pinned the
open-gate case at delta 0 as if that were correct.

### Measured on the live book, 2026-07-30

Read from `/api/fusion/targets` (`atMillis` 1785440309282; `insideBuffer` **19** of **24**). The
published `aims`, `targetQty`, `currentQty` and `combinedForecast` reproduce the published `deltaQty`
exactly under the ADR-0094/0101/0102 arithmetic, which is how the destination was identified:

```
GOOG   held -9        target +32.814821    forecast +2.618561447358846   aim +9.575088
  averagePosition = 32.814821 x 10 / 2.618561447358846 = 125.316220
  band            = 0.10 x 125.316220                  =  12.531622
  gap             = 9.575088 - (-9)                    =  18.575088      |gap| > band
  edge            = 18.575088 - 12.531622              =   6.043466
  DESTINATION     = -9 + 6.043466                      =  -2.956534   <-- SHORT, target is LONG
  rated (ADR-0107): 6.043466 x 0.008298707… = 0.050153  == the published deltaQty

AMZN   held -29       target +89.459992    forecast +7.582254431777378   aim +5.177946
  band  = 11.798713 ; gap = 34.177946 ; edge = 22.379233
  DESTINATION = -29 + 22.379233 = -6.620767             <-- SHORT, target is LONG
  rated: 22.379233 x 0.008298707… = 0.185720            == the published deltaQty
```

And the frozen half, from earlier re-plans the same cycle:

```
KO     held +45       target -18.120000    forecast -0.31        aim 0 (ADR-0102 clamp)
  band = 18.120000 x 10 / 0.31 x 0.10 = 58.451613
  gap  = 0 - 45 = -45.000000 ;  |gap| <= band   =>  deltaQty 0.000000, indefinitely
WMT    held +9        target -10.230000    forecast -0.30
  band = 34.100000 ; |gap| = 9 <= band          =>  deltaQty 0.000000, indefinitely
```

Across three consecutive 30 s re-plans the same six names — GOOG, AAPL, NVDA, AMZN, PG, KO/WMT — were
held against their own live target, moving at between 0.000000 and 0.2 shares per re-plan on gaps of
9 to 45 shares.

## Decision

**The position the buffer stops at is held to the side of flat the current target is on.** When the
destination `held + delta` lands on the opposite side, it is replaced by **flat**; everything else is
returned untouched.

```
  dest = held + delta                         the ADR-0094 near-edge destination
  sgn(dest) opposite to sgn(target)  =>  delta' = -held      (destination = flat)
  otherwise                          =>  delta' = delta      (byte-identical)
```

This is the same bound ADR-0102 puts on the intent, applied one level down to the position the desk
actually reaches. It is applied where the delta is finally decided (`PositionBuffer.onTargetSide`), before
the ADR-0107 rating — so a clamped move is still a **rated unwind and not a liquidation**, which is the
property ADR-0107 exists to protect.

**The band, the aim path, the width derivation and the rating are all unchanged.** No dial moves and no
number is introduced: the bound is flat (invariant 7 / ADR-0016). Exact decimal throughout (invariant 1).

### Scope, proved rather than asserted

- **It fires only on a holding the target opposes.** For `held` on the target's own side with the aim in
  ADR-0102's interval: if `gap ≥ 0` then `dest = held + edge ≥ held`, and if `gap < 0` then
  `dest = aim + band`, which has the target's sign. Neither can cross flat. So every same-side rebalance
  — the ADR-0094 worked example, the ADR-0107 same-side reduction, every ordinary cycle — is untouched.
- **It can only ever take exposure off.** The clamp resolves the destination to flat, so
  `|held + delta'| = 0 ≤ |held|`: it never opens a position, never enlarges one, never flips one onto a
  new side.
- **It never trades past the aim.** It fires only when `held` opposes the target, and
  `sgn(aim) ∈ {0, sgn(target)}`, so aim and holding are on opposite sides of flat (or the aim is flat)
  and `|gap| = |aim| + |held| ≥ |held| = |delta'|`.
- **An exit keeps its exact semantics.** A flat target returns before this rule, so the ADR-0086
  chandelier cut, the ADR-0065 orphan unwind and the ADR-0027 breaker above them are unaffected, as is
  ADR-0118's trapped-exit escape under a shut gate.
- **Where ADR-0102's guarantee does not hold** for the aim it is handed — an aim on neither flat nor the
  target's side, which `nextAim` cannot produce — there is no interval to hold the destination to and no
  claim to make, so the delta is returned untouched.

### What this changes on the live book

The two ADR-0107 cases whose destination sat on the wrong side of flat now unwind from the whole holding
rather than from the holding less a band, still at the ADR-0080 derived rate:

```
GOOG  0.050153  ->  9.000000 x 0.008298707… = 0.074688   per re-plan
KO    0.000000  -> -45.000000 x 0.008298707… = -0.373442 per re-plan  (was frozen)
```

## Consequences

**Good.** A position the desk's own forecast contradicts now has a path to flat on every configuration,
not only under a shut gate. Gross exposure that nothing planned — and the hedge notional sized against
it — is wound off at a measured, provenanced rate. `insideBuffer` should fall as the frozen names leave
the no-trade region.

**Cost.** Slightly more turnover in wrong-side names, bounded by the ADR-0080 rate and by `|held|`, and
paid only where the alternative was carrying a contradicted position indefinitely. The anti-churn
property ADR-0090/0107 protect is intact: the move is rated, so a forecast that crosses the holding and
crosses back cannot pay a full round trip per wobble.

**Risk.** If the forecast is noise, this unwinds toward flat slightly faster than before — which lowers
exposure. It cannot raise it.

**Narrows** ADR-0094 (the no-trade region may not extend past flat onto the side the target opposes) and
**generalises** ADR-0118 (the same trap, on the path where the gate is open). It does not supersede
either; both keep their decisions.

## Verification

`PositionBufferTest` — exact-decimal assertions on the live GOOG and KO plans above, on ADR-0118's AAPL
plan under an **open** gate (0.008299, was 0), on the ADR-0107 inverted-intent case (−3.409526, was
−2.840422), plus a sweep asserting the three scope properties over every aim ADR-0102 can produce.
Unchanged: the ADR-0094 worked example, the same-side reduction, every flat-target exit path.

**VERIFY-BY (next cycle, from live telemetry).** On `/api/fusion/targets`: **no name has `currentQty` and
`targetQty` of opposite sign with `|deltaQty|` below `|currentQty| × 0.0082987`**, and `insideBuffer`
falls from **19** of **24**. Firm gross exposure should fall as the contradicted positions wind off.
