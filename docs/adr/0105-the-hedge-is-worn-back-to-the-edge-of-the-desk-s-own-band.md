# ADR-0105: The hedge is worn back to the edge of the desk's own band, not to flat

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, hedging, risk, execution

## Context

The HEDGE book is still the firm's worst position and still the largest single drag on the objective.
Read the attribution snapshots the scorer has committed across today's cycles: the overlay's total PnL
runs `−598.79 → −623.08 → −626.93 → −645.64 → −650.16 → −690.53`. Six consecutive scored windows,
every one of them a loss, while the strategy books went `+1,171.14 → +1,555.99` over the same span.
The desk made `+$385` above and handed back `−$92` below; the firm total the owner watches grew by the
difference. In the most recent window the strategy books made about `+$91` and the overlay lost about
`−$40` — that is the whole reason the headline moved `+$10.97` rather than `+$51`.

**The mechanism is not cost and it is not the rate.** HEDGE fees are `$46.37` against a `−$690` loss,
so spread and commission are a rounding error in it. ADR-0098 attacked the target's **level** (shrink
it by one σ of its own step) and ADR-0100 attacked the **rate** (close only the fraction of the gap
the target's own path has earned). Both scored well and both are still in the code. What neither
touched is the question they both explicitly deferred: **what level of net exposure does this desk
actually want to run?**

The answer in `application.properties` is `jethro.hedge.equity-rebalance-floor-usd=0` — hedge from the
first dollar. That is not a decision anyone made; it is the absence of one. With the floor at zero, a
breach of the band is *always* met by hedging all the way back to the **centre**, which means the
overlay pays a round trip on every oscillation of an exposure the desk carries continuously and
intends to carry. The live axis snapshot shows exactly that shape: `netExposureUsd −6,019.93`,
`rawTargetNotionalUsd 6,849.76`, `floorUsd 0` — and the recorded net exposure across the day's
snapshots is `7,678 → 939 → 8 → 1,804 → −7,612 → −6,020`, a quantity that crosses zero and returns.
Against a book whose one measured edge is mean reversion, hedging that quantity to the centre is a
momentum position on ES taken on the desk's worst-measured view (ADR-0100 already recorded this), and
it is re-opened every time the net wanders.

This is a solved problem in the literature, and the solution is not "hedge less often" — it is a
**band**. Leland (*JF* 1985), Whalley & Wilmott (*Math. Finance* 1997) and Zakamouline (*JBF* 2006)
all reach the same policy under transaction costs: there is a no-hedge region around the target, and
when the exposure leaves it you trade back to the **edge of the band, never to the centre**. Hedging
to the centre from a zero floor is precisely the one policy that maximises the round trips paid.

The remaining problem is what sets the band width, and here the house has already been burned. A
number like "the desk tolerates $250k of net equity" would be the `$250k` hedge-cap mistake in
CLAUDE.md all over again — a self-chosen default that reads in the UI as a rule. ADR-0104 settled the
convention for exactly this situation on the fusion side: **replace a chosen constant with a quantile
of the quantity's own measured history.**

## Decision

The equity overlay hedges the exposure that stands **above the level this desk habitually carries**,
and leaves the habitual level standing.

Let `N` be the axis's signed net equity exposure in USD and `H` the **median of that axis's own |N|
history**, sampled once per hedge cooldown over a bounded window (`HedgeExposureLevel`). Define

```
f = max(0, |N| − H) / |N|              ∈ [0, 1]
q' = f · q
```

where `q` is the hedge quantity the desk would otherwise have worn — i.e. the ADR-0039/0040/0042
sized target, after the ADR-0098 churn shrink and before the ADR-0100 tracking rate.

**The band edge is exact.** The sized hedge is homogeneous of degree 1 in the exposure it covers (it
is a β- or ρ-weighted proportional hedge), so wearing the fraction `f` of it neutralizes `f·|N| =
|N| − H` and leaves exactly `H` standing — in the same beta-adjusted sense as the full hedge it
scales. Worked example, at the test fixture's numbers: `$1,000,000` long STOCK with `β̂ = 1.8` gives a
full hedge of `−6.428571` ES (`$1.8m` of ES notional). With `H = $600,000`,
`f = (1,000,000 − 600,000)/1,000,000 = 0.4` exactly, `q' = −6.428571 × 0.4 = −2.571428` at 6dp, and the
overlay neutralizes `$400,000`, leaving `$600,000` — the band edge — unhedged.

**The band is not a number anybody chose.** `H` is the median of the desk's own observed net exposure.
It asserts only this: *the exposure the book habitually runs is the exposure it means to run.* It
introduces no money figure into the risk path (invariant 7 / ADR-0016), and it self-calibrates to the
scale and volatility of whatever stream is running, so nothing here special-cases the simulator
(invariant 9). Only the estimation window is configured (`jethro.hedge.habitual-net.span`,
`.min-sample`) — sizes in samples, not dollars, and the control is scale-invariant in them.

## Consequences

- **Strictly one-way.** `f ∈ [0,1]` and the multiplier is positive, so `|q'| ≤ |q|` and
  `sign(q') ∈ {sign(q), 0}`: an estimated band can only ever leave the desk with *less* overlay than
  ADR-0098 and ADR-0100 already allowed, never more, and never on the other side. Same guarantee as
  ADR-0076 / 0079 / 0083 / 0086 / 0098 / 0100 / 0104 before it.
- **No ratchet.** The series sampled is the axis's **net exposure**, an input this control never
  touches — the overlay trades an index proxy, which is not a member of the axis. A cycle in which the
  hedge stood down therefore cannot drag down the level that made it stand down. (ADR-0104's rule 4,
  observed here by construction rather than by care.)
- **Silent while warming.** Below `min-sample` observations there is no band, and the advisor behaves
  exactly as it did before this ADR — no measurement, no claim.
- **Unwinds are untouched.** A target scaled to zero is still a *target*, so the ordinary per-proxy
  delta path unwinds any residual hedge in one cycle; ADR-0069's "an unwind is always executable"
  promise and ADR-0100's free reducing leg both stand.
- **The desk now runs a declared net equity exposure.** That is the point, and it is the honest cost:
  firm |net| will be larger than under hedge-to-flat, by construction up to `H`. Firm **gross** should
  fall, because the ES overlay is itself gross exposure; the objective is measured on both. The
  deterministic floor is unchanged — the pre-trade guardrail and the firm drawdown breaker still bind
  exactly as before, and they, not the overlay, are what stops a bad book.
- **What this is not.** It is not a claim that the overlay is worthless; a genuine, persistent
  directional tilt (`|N| ≫ H`) is still hedged, and `f → 1` as it grows. It is a claim that hedging
  the exposure the desk *always* has is a fee, not a hedge.
- **Reversible in one dial.** `min-sample > span` disables the band permanently (it can never warm);
  the code path then reproduces the previous behaviour byte for byte, as the `aWarmingBandHedgesExactlyAsItDidBefore`
  test asserts.

## Alternatives considered

- **A declared dollar floor** (`equity-rebalance-floor-usd = $X`). Rejected: it is the `$250k` mistake
  in CLAUDE.md — a self-chosen number that hardens into an assumed rule — and it does not travel
  across feeds or volatility regimes.
- **A cliff rather than a band** (the existing floor's shape: below the floor target zero, above it
  hedge to flat). Rejected: the discontinuity is itself a churn generator — a net oscillating around
  the floor toggles between no overlay and a full one. Scaling by `f` is continuous at `|N| = H`.
- **Turning the equity overlay off.** Rejected: it would score well on this cycle's numbers and would
  be untrue as a policy — it removes the desk's only defence against a genuine one-sided tilt, which
  is exactly the case the band still hedges.
- **Judging the overlay by its own realized PnL and unwinding when it loses.** Rejected: ADR-0095
  measured the overlay's own effectiveness and unwound below a ρ² floor, and it scored ❌ BAD and was
  reverted. A hedge is bought to lose money in the states it protects; grading it on PnL alone selects
  for removing protection exactly when it worked.
