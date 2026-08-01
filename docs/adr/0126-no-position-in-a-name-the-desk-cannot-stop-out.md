# ADR-0126: No position in a name the desk cannot stop out — an unarmed trailing stop is a veto on OPENING

- **Status:** Implemented
- **Date:** 2026-07-29
- **Deciders:** Oleg
- **Tags:** trading, fusion, risk, sizing

## Context

ADR-0086 gives every name a trailing risk cut: when a position gives back more than a measured
multiple of its own σ from its peak, the target is set flat. The cut is priced from
`StreamVolatility.sigmaPerSample(name)`, which needs a warm-up span of returns before it will speak at
all. Until then `TrailingRiskCut` has no distance to cut at and the name simply is not protected —
`FusionLifecycle.seedVolatility` already logs exactly that, at WARN, in the words "this name cannot be
stopped out until its mark history has accumulated".

Nothing consumed that warning. The planner sized names on forecast and volatility budget alone, so a
name could be given a position the same cycle the log said it could not be protected. ADR-0125's
universe widening turned that latent hole into the desk's dominant risk: of the names the fusion
planner published live targets for, eight — `KO`, `WMT`, `BAC`, `MCD`, `PG`, `XOM`, `NEE`, `HD` — had
never warmed their σ sensor, and the two largest absolute targets in the entire book (`KO` short
101.879300, `WMT` short 74.222500) were both in that set. The desk's biggest planned bets were
precisely the ones it had no exit for.

This is the owner's thesis inverted. That thesis is explicitly "add or hold when the trend is confirmed
*and risk is contained*; cut when risk enters the danger zone" — the edge is asymmetry and risk control,
not prediction. A position that cannot be cut has no asymmetry: it keeps the full left tail and relies
on the forecast being right. Adding breadth is worth nothing if the new names are the unprotected ones.

There is a second, structural force. Two prior controls that were meant to stop the desk holding what it
should not — ADR-0075's reduce-only clamp and ADR-0118's trapped-exit escape — both live inside
`if (gate != null && !gate.mayIncrease(...))`, and `jethro.fusion.edge-gate.enabled=false` under
ADR-0122's owner-directed exploration mode. Both are present in the source and unreachable in the
configuration the desk actually runs. A risk control that a switch on an unrelated dial can silence is
not a control, and this ADR must not repeat that mistake.

## Decision

**We will refuse to add risk to any name whose ADR-0086 trailing-stop σ sensor has not warmed.** The
clamp is reduce-only: the desk may always cut, hold, or be stopped out of such a name; it may not open
or enlarge one. A name becomes eligible automatically on the first cycle its sensor warms — the same
`sigmaPerSample(name).isPresent()` that `TrailingRiskCut` requires before it will cut, so "the desk may
open it" and "the risk cut can protect it" are one question answered in one place.

Two specifics make it bind:

- It is applied in `PositionBuffer.apply`, not clamped upstream. That step re-derives every delta from
  the aim and discards any delta computed before it, which is why ADR-0064's upstream clamp had to be
  re-applied there too. The aim is re-seeded to where the desk will actually be, so intent cannot
  accumulate behind the veto and fire as one large order the moment the sensor warms.
- It is evaluated **independently of the edge gate** — `PositionBuffer.mayIncrease` is a conjunction of
  two reasons, either of which alone vetoes, and it returns a verdict when `gate == null`. Turning the
  edge gate off cannot silence it.

No number is introduced (invariant 7 / ADR-0016): the condition is the sensor's own warm-up state, which
is measured, not dialled. Exact decimal throughout (invariant 1). It sits above the deterministic floor —
the pre-trade guardrail and the firm drawdown breaker still have the last word.

## Alternatives considered

- **Exclude σ-cold names from the planned universe entirely.** Rejected: it also blocks the *exits*. A
  name can be σ-cold while already held (a restart re-seeds from mark history, and a name can drop out
  of coverage), and removing it from the plan would strand that position with no target at all — the
  ADR-0118 trap again. Reduce-only keeps the way out open, which is the whole point.
- **Size σ-cold names down rather than to zero (e.g. a fraction of the budget).** Rejected: the fraction
  would be an invented number gating money, and the risk is not that the position is too big — it is
  that there is no exit at any size. A smaller unstoppable position is still unstoppable.
- **Substitute a proxy σ (a peer/sector estimate, or the book median) so the cut can fire immediately.**
  Deferred, not rejected — this is the right long-run answer and it would let breadth arrive without a
  warm-up penalty. It needs its own ADR: a cut distance priced off another name's volatility is a real
  money number and needs a stated estimator and a measured check that it does not fire spuriously.
  Revive when the warm-up delay is measurably costing entries the desk wanted (count σ-cold vetoes per
  cycle against realised opportunity).
- **Shorten the σ warm-up span.** Rejected as a fix for this: it trades one risk for another (a σ from
  too few returns is noisy, and an under-estimated σ puts the ADR-0086 trigger too close, cutting good
  positions on noise). It is a separate tuning question and does not close the hole for a genuinely new
  name, which has no history at all.

## Consequences

- The desk holds no position it cannot stop out. The eight σ-cold names above stay flat until their
  sensors warm, at which point they trade on the ordinary aim path with no further intervention.
- A side effect worth naming: ADR-0118's trapped-exit escape lives in the branch this control also
  enters, so for a σ-cold name it becomes reachable **with the edge gate off** — a held position whose
  intent ADR-0102 has clamped to flat is now closed in full rather than frozen. ADR-0118's churn
  argument transfers intact: the escape can only cycle if the desk can rebuild, and it cannot while the
  veto holds. Warming is monotonic per name, so the veto lifts once and never re-arms.
- **Negative: breadth arrives late.** ADR-0125 widened the universe to get breadth, and this control
  delays every genuinely new name by its σ warm-up (121 prices at the fusion cadence). A new name is
  therefore un-tradeable for roughly an hour of session time after it first appears, and a name added
  near the close waits until the next session. That is a real cost in missed entries, accepted because
  the alternative is unprotected risk; the proxy-σ alternative above is the way to buy it back.
- **Negative: it can mask a data problem as a risk decision.** A name whose marks never arrive stays
  σ-cold forever and is now silently never traded, where before it would at least have taken a position
  someone would notice. The existing WARN is the only signal; if this recurs, the σ-cold set belongs on
  the attention feed (ADR-0017) rather than only in the log.
- Related: ADR-0086 (the cut this protects), ADR-0094/0118 (the buffer this is applied in), ADR-0064/
  0075 (the other reason a name may not increase), ADR-0122 (the disabled gate this deliberately does
  not depend on), ADR-0125 (the widening that exposed it).
