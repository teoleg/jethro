# ADR-0118: A flat aim in a name the gate has shut is an EXIT, not a rebalance

- **Status:** Proposed
- **Date:** 2026-07-28
- **Deciders:** Oleg
- **Tags:** backend, risk, fusion, execution

## Context

The fusion planner decides how much of each name the desk means to hold (the **aim**, ADR-0080/0102) and
then asks whether closing the distance between that intent and the book is worth paying spread for (the
**no-trade buffer**, ADR-0094/0101). Above both sits the cost-aware edge gate (ADR-0064/0075), which
answers a different question: may the desk put risk **on** at all? When no source's measured expectancy
beats the desk's measured execution cost at the demanded confidence, the gate is **reduce-only** — every
name may be cut, none may be opened or grown.

Those three controls compose badly in one specific state, and the live book has been sitting in it.

A flat aim arises two ways. Either a control planned the name flat — the ADR-0086 risk cut, the ADR-0065
orphan unwind, the breaker — in which case `nextAim` snaps the aim to zero and `bufferedDelta` already
works the whole gap in full, unbuffered, exactly as ADR-0090 requires. Or ADR-0102's `withinTarget` clamp
produced it, and that clamp fires for exactly one reason: **the held position is on the side the current
forecast opposes**, so no position between flat and the target contains the holding and the intent is
held at flat ("intent never opposes the current view").

The second case reaches the buffer as an ordinary rebalance. It is therefore measured against a band
scaled by `|target| × TARGET_ABS / |forecast|` — the average position implied by the **target**. Under a
shut gate the desk may never take that target. So the no-trade region is sized by a position it is
forbidden to hold, and any wrong-side holding smaller than that region is **frozen**: the delta is
exactly zero, every cycle, indefinitely. The position is not being wound down at a measured pace; there
is no path out of it at all short of a risk cut firing or the sources going silent.

Worked from the live book of 2026-07-28 (`/api/fusion/targets`, forecast and target as published):

```
  held            = -1                      (short 1 AAPL, ALPHA)
  target          = +6.031064               (the desk's own model wants to be LONG)
  forecast        = +0.436598559661783
  averagePosition = 6.031064 x 10 / 0.436598559661783 = 138.137520
  band            = 0.10 x 138.137520                 =  13.813752
  a               = 1 - e^(-30/3600)                  =   0.008298755...   (30 s cycle, 3600 s rung)
  aim (raw)       = -1 + a x (6.031064 + 1)           =  -0.941652         (opposes the target)
  aim (ADR-0102)  = 0                                  the desk intends to hold NOTHING
  gap             = 0 - (-1)                          =  +1.000000
  |gap| = 1.000000 <= 13.813752                       =>  delta 0
```

The desk was short a name its own model wanted long, was forbidden to rebuild it, and could not close it.
It then hedged that short with ES on the EQUITY axis — so it paid **gross exposure on both legs** for a
position no control wanted — and the whole structure sat unchanged for hours, generating mark noise
against the firm total and no measurable edge. Doing nothing leaves the desk carrying dead exposure it
has already decided against, in every name whose view flips while the gate is shut and whose holding is
smaller than a band derived from a target the gate forbids.

## Decision

We will treat a **flat aim in a name the edge gate has put reduce-only** as an exit and work it in full,
the same way a flat target is worked in full today.

In `PositionBuffer.apply`, the existing reduce-only branch gains one condition: when the settled aim is
zero and the desk still holds something, the delta is the whole holding (`-held`), resolving the position
to exactly flat. Otherwise the branch is unchanged — the buffered delta, clamped by
`TargetPlanner.reduceOnly`. The aim is re-seeded to `held + delta` as before, so intent and book agree at
flat and the following cycle has nothing to do.

The condition is deliberately conjunctive. **Reduce-only alone is not enough** (a shut gate must not
liquidate a book that is merely on hold), and **a flat aim alone is not enough** (that is ADR-0090's
churn case, below). It is the pair — a wrong-side position the desk may not rebuild — that makes the
trade an exit rather than a rebalance.

No number is introduced. The branch resolves to flat, which is not a size; every dial, band, rate and
cost stays exactly as measured (invariant 7 / ADR-0016). Exact decimal throughout (invariant 1). This
sits strictly above the deterministic floor — the pre-trade guardrail and the firm breaker still have the
last word on every order it produces.

## Alternatives considered

**Work every reduction in full again (revert to ADR-0080).** This is precisely the policy ADR-0090
narrowed, and for a good reason: with a mean-reverting source in the mix the combined forecast crosses
the held position many times inside one measurement horizon, and liquidating on each crossing paid a full
round trip per wobble while the desk never reached the size at which its measured edge could pay for that
cost. Re-instating it would re-buy that loss. The gate condition is exactly what excludes it — that
failure mode needs the desk to be able to **rebuild**, and a shut gate has taken rebuilding away, so the
only trade available in the name is the cut itself. A gate that is open leaves every path here
byte-identical, which the tests pin.

**Scale the buffer band by the aim instead of the target.** ADR-0101's derivation does read `band =
a·(2C/μ)` with `a` the optimal position, so there is a real argument that the band should follow the aim.
But Carver's convention — the floor this desk uses when nothing is measured — is deliberately a fraction
of the **average** position, a fixed-width region that does not collapse as the aim shrinks, and
collapsing it would re-introduce turnover on every name whose aim is small for ordinary reasons. It would
also not fix this case cleanly: at a flat aim the band goes to zero and the gap is then rated by
`bufferedDelta`'s cross-flat branch at `a = 0.0083`, giving 0.008298 shares per cycle, which
`tradableQuantity` rounds toward zero to **no order at all** — the position stays frozen by a second
mechanism. Wider blast radius, and it does not actually free the position.

**Set the target flat for every reduce-only name.** Simple, and it reuses the existing exit path — but it
is a far stronger policy than the gate states. Reduce-only means "do not add", not "liquidate"; flattening
the whole book each time the gate shuts and rebuilding each time it opens is a round trip charged against
the desk's own oscillating significance test, which is the ADR-0090 loss in a slower costume.

**Carry the untraded remainder across cycles (a residual accumulator), so a delta that rounds to zero
eventually accumulates into a whole unit.** A textbook fix for the rounding half of the problem, and it
preserves the ADR-0080 rate exactly in expectation. It is rejected here as the wrong shape for this
defect — the AAPL delta is not a small number lost to rounding, it is **exactly zero**, suppressed by the
buffer before rounding is reached, so an accumulator would carry nothing. It is also stateful in a way
that needs reset rules on every target/side change. Registered as deferred work, not as the fix.

## Consequences

- **Positive.** A position whose own forecast has turned against it, in a name the desk is forbidden to
  rebuild, can now be closed. That is dead exposure removed at the moment the desk decides against it,
  which is the whole point of the reduce-only state; on the live book it also unwinds the ES hedge
  carried against it, so both legs of gross exposure go. It closes a trap that was invisible in
  telemetry — `deltaQty: 0` reads identically whether a position is inside its buffer by design or stuck
  in it forever.
- **Negative — and this is a real cost.** Exiting in full pays the whole round trip now rather than
  spreading it, and a forecast that flips back while the gate is still shut leaves the desk flat in a
  name it would have been holding. That is a genuine loss of optionality, accepted because the desk
  cannot act on that optionality anyway while the gate is shut. The exit also crystallises whatever
  unrealised PnL the position carries; a wrong-side position is the one where that is most likely to be
  a loss, and "cut losers fast" is the policy this desk has chosen.
- **Negative.** One more branch in a control path that already composes four ADRs. The conjunctive
  condition is the mitigation: it can only fire where the gate is shut and the intent is flat, and it
  resolves to flat and nothing else, so it can never open, enlarge or flip a position.
- **Follow-ups.** The residual-accumulator gap above is registered in `docs/deferred-register.md`: a
  rated delta on a whole-unit name rounds toward zero, so any gap below `1/a` units — about 120 shares at
  the 3600 s rung — trades nothing, and that is a separate trap from this one. Worth measuring once the
  gate reopens and the desk is rebalancing again.
