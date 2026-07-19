# ADR-0036: AI signal balance — measure skew, feed it back, never quota

- **Status:** Proposed
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** ai, hypothesis, risk

## Context

The hypothesis layer's output is **one-sided**: it proposes mostly one direction (typically long) and
clusters on a few names. Causes compound — news sentiment skews bullish, a small local model has a
directional prior, and in a long-only book a SELL only ever reduces, so longs dominate structurally.
A book built from lopsided calls carries concentrated directional risk and the "AI signals feel
unbalanced" complaint is real.

Constraints: the fix must respect invariant 7 (deterministic guardrails; the model narrates/proposes,
it never has a number forced into risk) and must not degrade quality by *manufacturing* trades — a
hard "propose one short per cycle" quota would force bad calls, which is worse than a skew. The
deterministic risk guardrail already bounds directional risk (net-exposure caps per book), so the
question is how to reduce the skew *at the source* without a quota. Doing nothing leaves the skew and
no visibility into it.

## Decision

We will **measure the directional balance and feed it back to the model as advisory context — never
a quota**:

- **Measure.** Compute the recent long/short mix (and per-name concentration) of proposed calls;
  surface it on the hypothesis stats API and UI so the skew is visible, not folklore.
- **Feed back.** The prompt carries a `directionBalance` line ("recent calls: 7 long, 1 short —
  skewing long"); a rule tells the model that when its book is lopsided it should *actively look for
  the other side where a thesis genuinely supports it* (a reduction, a hedge, a short on a bearish
  headline) — **but never force a call to hit a ratio**. Combined with the RAG outcome memory
  (ADR-0035), the model also sees that piling one direction has lost before.
- **Floor stays deterministic.** The existing net-exposure guardrail (ADR-0022) remains the hard
  bound on directional risk; balance feedback reduces the skew before it reaches that cap.

## Alternatives considered

**Hard directional quota (force N shorts per cycle).** Guarantees balance, but manufactures trades
with no thesis — worse than the skew and a clear invariant-7 violation (a ratio dictating trades).
Rejected.

**A deterministic post-filter that drops surplus same-direction calls.** Balances the *surfaced* set
without inventing trades, but it discards genuine calls to hit a ratio and hides the model's real
behaviour rather than improving it. Rejected as the primary lever; the net-exposure cap already
drops calls that would over-concentrate risk, which is the principled version.

**Do nothing (rely on the net-exposure cap alone).** The cap bounds *risk* but not the *proposal*
skew — the feed still reads one-sided and the model never learns. Rejected; measurement + feedback is
cheap and targets the source.

## Consequences

- **Positive:** the skew is visible and measured; the model self-corrects toward genuine two-sided
  calls; no forced trades; ties into the outcome memory so balance improves as the track record grows.
- **Negative:** feedback is a nudge, not a guarantee — a persistently bullish tape may still skew
  long (correctly), so "balanced" is not a target to hit; another prompt line on a slow local model
  (kept short); the balance metric is descriptive, and must not be mistaken for a risk limit (the
  net-exposure cap is the limit).
- **Follow-ups:** per-instrument concentration feedback; wire the balance signal into the AI-sleeve
  autonomy sizing once there's a measured track record by direction. Depends on ADR-0022 (hypothesis
  layer), ADR-0035 (outcome memory).

## Implementation status (2026-07-18) — built

- **Measured:** `HypothesisLifecycle.directionBalance()` counts live (not-yet-scored) long/short
  calls and labels the skew (`HypothesisBalance`, pure + tested); `/api/hypotheses/balance` and a
  line on the Ops page surface it.
- **Fed back:** the balance line goes into `HypothesisContext.directionBalance` (additive, backward-
  compatible) with a prompt rule to look for the other side where a thesis supports it — **never a
  quota** (a forced call is worse than a skew), and combined with the ADR-0035 outcome memory the
  model also sees when piling one way lost. The net-exposure guardrail remains the hard risk floor.
