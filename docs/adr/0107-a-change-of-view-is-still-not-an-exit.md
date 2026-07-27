# ADR-0107: A change of view is still not an exit — an exit is what a control ORDERED

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, execution, turnover, cost

## Context

ADR-0090 established the rule this desk trades by: **a change of view is a rebalance, not an exit.**
Its motivation was stated exactly:

> With a mean-reverting source in the mix the combined forecast crosses the held position many times
> inside one measurement horizon, and each crossing liquidated the whole accumulated position and
> started rebuilding the other way — the desk entered at the rate the evidence justified and exited at
> infinite rate on a wobble, so it paid the full cost of a round trip while never reaching the size at
> which the measured edge could pay for it.

ADR-0090 encoded that rule in `TargetPlanner.orderDelta`, keyed on **the target being flat**: every
control that means "get out" — the ADR-0086 chandelier cut, the ADR-0065 orphan unwind, the ADR-0027
breaker above them — says so by planning the name flat, and only that is worked in full.

ADR-0094 then moved the no-trade region off the target and onto the **aim**, and the order is now
derived by `PositionBuffer.bufferedDelta`, which `FusionLifecycle` runs last. That method did not carry
ADR-0090's key across. It reads:

```java
if (aim.signum() == 0) {
    return gap; // an exit is worked in full (ADR-0090)
}
```

At the time that was a faithful restatement, because the only way the aim reached zero was a flat
target. ADR-0102 changed that. Its clamp holds an intent that has ended up on the wrong side of the
current target **at flat**, in one step — and it is explicit that this is the desk's steady state, not a
corner case, because the only source that clears the edge gate is a mean-reverting one whose forecast
crosses the held position repeatedly inside the horizon the rate is derived from. So on every crossing
the aim becomes `0` while the target is very much alive, `bufferedDelta` reads that as a cut, and the
whole accumulated position is liquidated in one order.

ADR-0102's own worked example is that liquidation, written down as intended behaviour:

```
step   = 104 + a·(−219.420787 − 104) = 93.398…                    opposite side to the target
aim'   = 0                                                        ADR-0102 clamp
gap    = 0 − 104 = −104.000000                                    "an intent of zero is an exit"
delta  = −104.000000                                              sell the whole holding, this cycle
```

**The clamp is right; classifying its output as an exit is what is wrong.** Nothing ordered that cut.
The forecast merely crossed the position, which is the precise event ADR-0090 exists to stop being a
round trip.

### What it costs, on the running book

The signature is plain in the order tape: small rated accumulations followed by a single large reversal
on the same name — AAPL bought in steps of 1/3/3/4/5/7/8 and then sold 22 and 13; JNJ bought 19/9/28/6
and then sold 14, 14, 34; JPM the same shape. The desk fills thousands of orders a day and its ALPHA
book pays a fee bill worth roughly a quarter of the P&L that book makes. Turnover is the dominant
identified cost on this desk, fees dominate turnover, and fees are a function of quantity traded — so
suppressing the largest single generator of traded quantity, the full liquidation on every crossing, is
the lever with the most measured money behind it. It is also the reason the desk holds a small fraction
of its own planned book: it never gets far before the next crossing empties it.

There is an execution asymmetry on top. Under ADR-0084 a risk-**increasing** delta rests as a passive
DAY LIMIT at the arrival mark and pays no spread, while a risk-**reducing** delta crosses as MARKET.
Every crossing-liquidation therefore pays the full spread on the entire accumulated position, having
paid nothing to build it.

## Decision

`bufferedDelta` keys the unbuffered branch on **the target**, not on the aim, and rates the gap that the
clamp created:

```
gap  = aim − held
T = 0                              → gap                       a control ordered the exit: in full
|gap| ≤ band                       → 0                          inside the no-trade region
edge = (|gap| − band)·sgn(gap)                                   trade to the NEAR EDGE (ADR-0094)
held = 0  or  sgn(aim) = sgn(held) → edge                        the aim moved by a rated step
otherwise                          → (edge − u) + a·u,   u = reduceOnly(edge, held)
```

The last line is the whole change. When the intent crossed flat in **one** step — which is the
ADR-0102 clamp and nothing else — the part of the move that unwinds the holding is worked at the
ADR-0080 derived rate `a = 1 − e^(−c/h)`, because that gap did **not** arrive by a rated step and so is
missing the rate the aim path carries everywhere else. The part that would open on the aim's own side
is left alone: that side *is* on the aim path and is already rated by it.

This is ADR-0090's rule, restated in the code path that replaced ADR-0090's: an exit is what a control
ordered, and a control orders it by planning the name flat.

**No number is introduced.** `a` is the rate the planner already derived this cycle (ADR-0080), `band`
is the width ADR-0101 already measured, and `T` is the target ADR-0102 already clamped against. No dial,
no money figure, no risk figure (invariant 7 / ADR-0016). The forecast, every scale, the volatility
budget (ADR-0083), the portfolio normaliser (ADR-0079), the book volatility brake (ADR-0104) and the
edge gate are untouched.

### Strictly one-way

`|edge| ≤ |gap|` and `a ∈ [0, 1]`, so the order returned is never larger than — and never of a different
sign from — the one returned before. Asserted as a test over every combination of held / aim / target
sign. It follows that this change:

- can never open a position the desk was not already opening, nor enlarge one;
- can never slow a cut a **risk control** ordered, because such a cut arrives with a flat target and
  returns on the first branch, unbuffered and unrated — the ADR-0086 chandelier stop, the ADR-0065
  orphan unwind, the ADR-0027 breaker and the pre-trade guardrail keep their exact semantics;
- can never slow a same-side de-risk, which reaches the buffer by a rated aim step and is excluded by
  the `sgn(aim) = sgn(held)` branch;
- leaves the aim path itself byte-identical, so the desk's *intended* position is unchanged.

### Worked example (the live JNJ plan, exact decimal — ADR-0102's example, corrected)

Held `+104`, target `−219.420787`, forecast `−12.64`, `a = 1 − e^(−30/900) = 0.0327838995179941`,
buffer width at Carver's floor `0.10`.

```
averagePosition = 219.420787 × 10 / 12.64      = 173.592395
band            = 173.592395 × 0.10            =  17.359240
step            = 104 + a·(−219.420787 − 104)  =  93.397005   → clamped to 0 (ADR-0102)
gap             = 0 − 104                      = −104.000000  ; |gap| > band
edge            = −(104.000000 − 17.359240)    =  −86.640760  (to the near buffer edge)
u               = reduceOnly(−86.640760, +104) =  −86.640760  (all of it unwinds the holding)
delta           = 0 + (−86.640760 × a)         =   −2.840422
```

and on the next cycle, the book still long `101` while the aim runs to the new side:

```
aim   = 0 + a·(−219.420787)          =   −7.193469
gap   = −7.193469 − 101              = −108.193469 ; |gap| > band
edge  = −(108.193469 − 17.359240)    =  −90.834229
delta = −90.834229 × a               =   −2.977900
```

The unwind cannot stall: the aim keeps marching toward the new target, so the gap grows as the holding
shrinks. The position e-folds to flat over one measurement horizon — the holding period ADR-0080 derived
and the edge gate priced — instead of being emptied in one market order, and a forecast that crosses
back before the unwind completes costs nothing at all, which is the entire point of ADR-0090.

## Alternatives considered

**Rate the gap in every direction.** ADR-0090's literal wording ("a rebalance at the derived rate in
both directions") would also rate a same-side reduction. Rejected: on the aim's own side the gap already
*is* a rated step, so rating it again is a second lag, and it would make the desk slower to shed risk in
a case that is not broken. The defect is specifically the discontinuity the clamp introduces, and the
fix is scoped to it.

**Post the crossing-liquidation passively instead of crossing (relax ADR-0084's exit rule).** Attacks
the spread but not the quantity, and fees — not spread — are the larger measured cost here. It is also a
weaker classification of the same insight; if the spread turns out to be material after this is
measured, it remains available as a separate change.

**Widen the buffer so a crossing never trades.** Rejected for the reason ADR-0102 rejected it: it
suppresses the symptom, and ADR-0101 already derives the width from measured cost and edge, so a width
chosen to fix this would be a number without provenance.

**Undo ADR-0102's clamp and let the aim decay through flat.** Rejected: intending a position the current
forecast opposes is exactly what ADR-0102 removed, and re-admitting it to fix a *speed* problem trades a
correct property for an incorrect one. The intent stays flat; only the rate at which the book reaches it
changes.

## Consequences

**Expected.** Traded quantity falls, and with it the fee bill and the spread paid on crossings. The desk
holds its positions through the crossings of a mean-reverting forecast rather than round-tripping them,
so it captures more of the horizon the edge gate measured its edge over.

**The honest trade-off.** Positions persist longer, so average gross exposure rises. That is a real cost
against the objective's denominator, and it is the thing to watch: the loop's scorer measures total P&L
and total exposure together, so if exposure grows without P&L this is scored ❌ BAD and reverted. The
claim being tested is that the money saved on turnover and the edge captured by holding a position for
the horizon it was graded on together exceed the cost of carrying it — not that exposure is free.

**Bounded.** The aim is still clamped to `[0, T]` (ADR-0102), still buffered (ADR-0094/0101), still
scaled by the volatility budget, the portfolio normaliser and the book volatility brake, and still gated
by the edge gate. Every risk control that orders a flat target still empties the position in one cycle
at market. Nothing above the deterministic floor is loosened and the floor itself is untouched.

**Feed-agnostic (invariant 9).** Every input is measured off the running stream — the derived rate, the
measured band width, the planner's own target. No level, no asset-class assumption, no sim special-case.
