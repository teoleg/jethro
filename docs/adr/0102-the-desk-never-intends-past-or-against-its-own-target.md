# ADR-0102: The desk never intends past — or against — its own target

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, risk, sizing

## Context

ADR-0080 gave the fusion desk a partial-adjustment rate derived from the horizon its edge is measured
over, and ADR-0094 moved the no-trade band off the target and onto the **aim** — the intended position
the desk trades toward. The aim is maintained as

```
aimₜ = aimₜ₋₁ + a·(Tₜ − aimₜ₋₁)
```

Unrolled, that is an exponential moving average of the target sequence,
`aimₜ = a·Σₖ (1−a)ᵏ·Tₜ₋ₖ` plus a decaying seed. Its weights are non-negative and sum to one, so the
aim is a **convex combination of the targets the desk has held in the past**. That is not the same set
as the target it holds *now*, and the two diverge exactly when the target moves faster than `1/a`.

This desk's only source that passes its own edge gate is a **mean-reverting** one, measured at the 900 s
rung — a source whose forecast crosses zero repeatedly inside the horizon the rate is derived from. So
the divergence is not a corner case here, it is the steady state. Two failure modes follow, and both
were visible on the live book at the time of writing:

- **Overshoot.** When the target shrinks, the aim is left larger than it. EURUSD carried an intent of
  `−19268.293125` units against a target of `−13006.790342` — the desk intended, and was trading toward,
  `6261.502783` units more short than its own forecast asked for.
- **Inversion.** When the target changes side, the aim spends the whole decay on the old side. JNJ
  carried an intent of `+8.043404` against a target of `−219.420787`, while the book actually held
  `+104` — the desk's largest position, long, in the name its own strongest forecast said to be short,
  and the aim path was walking it *further* long before the EWMA could cross zero. AUDUSD and GBPUSD
  carried inverted intents of `−11494.134880` (target `+17285.020487`) and `−2024.437007` (target
  `+19015.931789`) from a flat book — i.e. the desk was about to *open* positions the wrong way round.

Neither is what partial adjustment means. Gârleanu & Pedersen's optimal policy trades a fraction of the
way toward an aim that is itself a weighted average of the **current and expected future** targets
(*Dynamic Trading with Predictable Returns and Transaction Costs*, JF 68(6), 2013, §III) — every element
of which is a position the model wants now or expects to want. Averaging over a *realised past* admits
neither an intent larger than the current target nor one opposed to it; our implementation admits both.

The cost is not subtle. The desk's measured edge is `+8.53` bps per 900 s horizon on the passing source;
holding the wrong side of it turns that into `−8.53`, a 17 bps swing, against a measured round trip of
`2 × 1.43 = 2.86` bps. Correcting an inversion is roughly a 6:1 trade in the desk's favour on its own
measured numbers — the same arithmetic ADR-0101 used to set the buffer width. And the overshoot is pure
excess gross exposure, which is the denominator of the objective this loop optimises.

Doing nothing leaves the desk paying spread to reach positions its own evidence contradicts, and
carrying gross it never planned.

## Decision

We will clamp the aim into the closed interval between flat and this cycle's target:

```
aim' = min( max( aim, min(0, T) ), max(0, T) )
```

One expression, applied at the end of `PositionBuffer.nextAim`, after the ADR-0080 step and before the
band is measured against it. Three cases:

| case | result |
|---|---|
| `aim` between flat and `T` (the ordinary case) | unchanged — path byte-identical to ADR-0094 |
| `aim` same side as `T`, `\|aim\| > \|T\|` | held at `T` — never intend past the target |
| `aim` opposite side to `T` | held at flat — never intend against the view |

The flat-target branch returns **before** this clamp, so a name planned flat still snaps its intent to
zero and is worked in full: the ADR-0086 chandelier cut, the ADR-0065 orphan unwind, the ADR-0027 breaker
and the pre-trade guardrail keep their exact semantics.

**No number is introduced.** The bound is the target the planner already computed this cycle. The rate,
the band width (ADR-0101), the band location (ADR-0094), the volatility budget (ADR-0083) and the
portfolio normaliser (ADR-0079) are all untouched.

**Two properties hold by construction, and are asserted in tests:**

- `|aim'| ≤ |aim|` — the clamp can only ever *shrink* intent.
- `sgn(aim') ∈ {0, sgn(T)}` — intent is never on a side the current target does not name.

Together these make the change **strictly one-way**: it can never open a position, never enlarge one,
never flip one onto a side the forecast is not on, and never widen or narrow a band. Every trade it
newly admits moves the book toward flat or toward the target's own side.

### Worked example (the live JNJ plan, exact decimal)

Held `+104`, target `−219.420787`, forecast `−12.64`, derived rate `a = 1 − e^(−30/900) = 0.0327838995…`,
buffer width at Carver's floor `0.10`.

```
step   = 104 + a·(−219.420787 − 104) = 104 − 10.601… = 93.398…   opposite side to the target
aim'   = 0                                                        ADR-0102 clamp
gap    = 0 − 104 = −104.000000                                    an intent of zero is an exit
delta  = −104.000000                                              sell the whole holding, this cycle
```

and on the following cycle, from a flat book and a flat intent:

```
aim    = 0 + a·(−219.420787) = −7.193469                          same side, inside the target
band   = 219.420787 × 10 / 12.64 × 0.10 = 17.359240
|gap|  = 7.193469 ≤ 17.359240                                     ⇒ NO ORDER
```

So the inversion is cut **once**, the move stops at flat, and the short is then rebuilt at the derived
rate through the buffer. It is a half round trip, and the half it performs is the risk-reducing half.

## Alternatives considered

**Leave the aim alone and average the forecast instead.** Tried, as ADR-0088 (an EWMA on the fused
conviction at the same identity). It was scored ❌ BAD by the loop's own measurement and reverted:
smoothing the signal changed what the desk *believed* rather than what it was allowed to *intend*, and
the book's gross exploded. This ADR deliberately leaves every forecast and every scale exactly as
measured and constrains only the intent.

**Widen the band instead, so an inverted aim never trades.** This suppresses the symptom and keeps the
disease: the intent stays wrong, and the moment the band is cleared the desk trades toward the wrong
position anyway. It also directly contradicts ADR-0101, which derives the width from measured cost and
edge and has no room for a term that means "and also ignore inverted intents".

**Slow the rate further so the aim cannot invert.** The rate is not free to choose — ADR-0080 derives it
from the horizon the edge is measured over, and breaking that identity re-opens the over-permissive-gate
problem that ADR made the whole point of. It would also not help: any positive rate over a sign-flipping
target still produces an inverted aim, just for longer.

**Clamp to the target but let an inverted aim decay rather than snap to flat.** This keeps the desk
trading *toward* a position its evidence opposes for a full decay constant. The measured 6:1 edge-to-cost
ratio above says that is the expensive choice, and the snap stops at flat — it does not build the other
side at speed, which was ADR-0090's concern.

## Consequences

- **Positive.** Intent can no longer exceed or oppose the desk's own current evidence. Excess gross the
  planner never asked for is removed at source (EURUSD alone: `6261.502783` units, ≈ `$6,789` of notional
  at a `1.084448` mark). Wrong-side positions are cut instead of being walked further wrong, and
  wrong-side *entries* from a flat book are not opened at all. Both effects push the loop's objective —
  total PnL per unit of total exposure — the right way, and the change is exposure-reducing by
  construction rather than by hope.
- **Negative.** An inversion is corrected by a single unbuffered trade to flat, which pays a round trip
  (on the JNJ example, `104 × 154.81 × 1.43` bps ≈ `$2.30`). If a name's forecast oscillates in sign at
  a period much shorter than the aim's rebuild time, this could be paid repeatedly; the rebuild path
  through the band (~20 cycles to clear the buffer on the worked example) is what bounds it, and the
  loop's ledger will measure whether that bound holds in practice.
- **Risk accepted.** The clamp reads the target as the desk's current best statement of what it wants.
  If the target itself is noisy at the cycle cadence, the clamp propagates that noise into the intent
  faster than the EWMA did. This is the honest reading — the alternative is to keep acting on evidence
  the desk has already superseded — but it is the assumption to revisit first if the change scores badly.
- **Follow-ups.** The `aims` map is in-process and re-seeds from the held position on restart, so the
  clamp performs a one-shot inversion sweep after every restart. That is correct but worth watching. The
  overlay-posture question (ADR-0098, ADR-0100) and the absence of any absolute book-level volatility
  target both remain open and are unaffected by this change.
