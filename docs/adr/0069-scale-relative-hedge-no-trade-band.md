# ADR-0069: The hedge no-trade band is scale-relative, not an absolute dollar floor

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** risk, hedging, execution
- **Extends:** ADR-0039 (hedge lifecycle — Decision 2, the churn guards)

## Context

ADR-0039 makes two promises. Decision 1: the book is held target-flat, and *"if `|e|` (the underlying
the hedge exists for) falls to zero, any residual hedge is **unwound** — no underlying, no hedge, so a
fully-exited strategy never leaves a naked proxy leg lingering."* Decision 2: churn is controlled at the
**trade** level by a minimum hedge notional (default `$10,000`), because *"a hedge trade smaller than
this is not worth its spread/impact under ADR-0025."*

Decision 2 as implemented silently repeals Decision 1. The guard is an absolute dollar amount compared
against the delta with no reference to the size of the hedge it is guarding, so it is not a churn filter
at all once the hedged book is smaller than the guard — it is an **absolute barrier**. Every delta a
small hedge can produce, up to and including its own full unwind, is below `$10,000`, so the hedge
becomes untradable in both directions and whatever position it happens to hold at the moment the book
shrinks is stranded on the firm book permanently.

This is not hypothetical; it is the live state that prompted this ADR. On 2026-07-26 the equity book
collapsed from a large multi-name position to a handful of single-share residuals after a reverted
change was worked off. The advisor correctly re-sized the equity hedge down to a small target and
correctly computed the delta — and then reported `"largest delta under the $10,000.00 min trade,
holding"` and stopped. The result: an ES position several times its own target sat on the HEDGE book,
accounting for the large majority of firm gross exposure and effectively all of firm net exposure, with
no mechanism that could ever remove it. It is pure uncompensated risk — exposure the hedging system
itself says it does not want, on an axis it is not hedging, held only because a threshold cannot see
scale. It is also the exact failure mode ADR-0068 identifies in the risk dials: an absolute-dollar
number calibrated for a big book goes wrong, not merely stale, when the book shrinks.

The general principle the platform already commits to (feed-agnostic, self-calibrating signals; no
hardcoded levels) applies just as much to *execution* thresholds as to signals: a threshold that gates
trading must be expressed relative to the thing it gates.

## Decision

We will make the hedge no-trade band **the smaller of the absolute churn guard and a fraction of the
hedge's own scale**:

```
scale(proxy) = max(|targetQty|, |heldQty|) · price · multiplier      // notional at stake
band(proxy)  = min( minTradeNotionalUsd , noTradeBandFraction · scale(proxy) )
trade  ⟺  |delta| · price · multiplier  ≥  band(proxy)
```

Equivalently: **trade when the delta is material in absolute terms *or* material relative to the hedge
itself.** Taking the minimum, never the maximum, is the whole point — it lets the absolute guard bind
only where it is the *looser* constraint.

- `jethro.hedge.min-trade-notional-usd` = `10000`, unchanged (ADR-0039 Decision 2).
- `jethro.hedge.no-trade-band-fraction` = `0.25`, new. Source: the 25%-relative leg of the standard
  5/25 band-rebalancing convention (rebalance when a holding drifts by 25% of its own target). Marked
  `PLACEHOLDER — Oleg to set` in `application.properties` — it is a convention, not a number measured
  from this platform's ES/NQ round-trip cost. Setting it to `0` disables the relative leg and restores
  pure ADR-0039 behaviour.
- The fraction is clamped to `[0, 1]` in the constructor.

Two properties follow directly, and both are asserted in `HedgeAdvisorTest`:

1. **Large books are unaffected.** When `scale ≫ minTradeNotionalUsd`, `0.25 · scale > $10,000`, so the
   band *is* `$10,000` and ADR-0039's behaviour is bit-for-bit unchanged. The existing regression test
   (`deltaUnderTheMinTradeNotionalHolds`: an `$8,000` trim on a `$1.8M` hedge) still holds its trade.
2. **A zero target is always reachable, at any book size.** With a zero target, `|delta| = scale`, and
   since the fraction is at most 1, `band ≤ scale = |delta|`. So the unwind always executes — ADR-0039
   Decision 1's promise becomes structurally true instead of true-only-above-`$10k`.

Everything else in the hedge path is untouched: the target sizing (ADR-0038/0040/0042), the per-axis
cooldown, the rebalance floor on `|net|`, the tradability gate, the one-order-per-cycle rule, and the
deterministic floor below it all (pre-trade guardrail, firm breaker) are unchanged. This ADR narrows
*when the advisor may act*; it changes no risk limit and no money calculation.

### Worked example — the live 2026-07-26 state

ES marked at `5449.545954`, multiplier `50` → `$272,477.2977` per contract (all figures below
recomputed in exact decimal from the live `/api/risk` and `/api/hedging` snapshot, not rounded by hand).

| | quantity | notional |
|---|---|---|
| held | `0.021254` | `$5,791.23` |
| target | `0.004011` | `$1,092.91` |
| delta | `-0.017243` | `$4,698.33` |

- `scale = max(0.004011, 0.021254) · 272,477.2977 = $5,791.23`
- `0.25 · 5,791.23 = $1,447.81`
- `band = min(10,000.00, 1,447.81) = $1,447.81`
- `4,698.33 ≥ 1,447.81` → **SELL `0.017243` ES**, taking the hedge to its computed target.

Under the old rule `4,698.33 < 10,000.00` → hold, forever. The trade removes roughly `$4.7k` of
unwanted gross exposure — about two thirds of firm gross — and takes firm net from roughly `$4.9k` to
roughly `$0.2k`, with no change to the strategy book and no view expressed on any price.

### Churn check

The band is a *proportional* band, so it does not degenerate into churn on a small book: the hedge
re-trades only once it is more than a quarter away from its own target, and each trade goes to the
target, so the next trade needs a fresh 25% drift. A 1% drift on a small hedge trades nothing
(`smallBookStillDampsSubBandChurn`). What changes is only that "more than a quarter off target" is now
*reachable* at every book size.

## Alternatives considered

- **Lower `min-trade-notional-usd` to a small absolute number.** Rejected: it moves the barrier without
  removing it — the same stranding recurs one order of magnitude down, and on a large book it re-admits
  exactly the sub-noise churn ADR-0039 Decision 2 exists to prevent. The defect is that the threshold is
  absolute, not that it is `$10,000`.
- **Exempt unwinds (a zero target) from the guard.** Rejected as too narrow: it fixes the flat-book case
  only. The live state was not a zero target — it was a hedge at five times its non-zero target, which
  is just as uncompensated and just as unreachable.
- **`max(absolute, fraction · scale)`.** Rejected: that is strictly *more* restrictive than today on
  large books and does nothing for small ones — the wrong direction on both.
- **Size the band from measured round-trip cost (spread + fee + impact) instead of a fraction.** This is
  the economically correct form — trade when the risk reduction is worth its cost — and it is where this
  should end up. Deferred, not rejected: it needs a per-proxy cost estimate wired from the ADR-0025
  model and a stated exchange rate between "dollars of residual exposure" and "dollars of cost", which
  is a risk-appetite decision for Oleg, not a number the loop may invent. Tracked in the deferred
  register; the 25% convention holds the line until then.

## Consequences

- The hedge can be trimmed and unwound at any book size; a stale proxy leg can no longer be stranded.
  Expect firm gross and net exposure to fall materially on the next cycle as the current excess unwinds.
- Small books now pay a small number of hedge commissions they previously (accidentally) avoided — the
  cost of actually holding the target-flat policy ADR-0039 chose. Bounded by the 25% band.
- The `ON-TARGET` rationale now reports the *effective* band rather than the absolute dial, so the UI
  shows the number that actually gated the decision.
- One behaviour is deliberately not addressed: the strategy book's own sub-unit residuals (the ±1-share
  stubs) are a different mechanism (`TargetPlanner`/`FusionExecutor` whole-unit truncation) and are
  already in the deferred register.
