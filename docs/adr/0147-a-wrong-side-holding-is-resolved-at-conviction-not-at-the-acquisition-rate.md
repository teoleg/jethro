# ADR-0147: A wrong-side holding is resolved at conviction, not at the acquisition rate

- **Status:** Implemented
- **Date:** 2026-08-10
- **Deciders:** continuous-improvement loop (ADR-0063); Oleg to ratify
- **Tags:** fusion, execution, risk, holding-period, exposure

## Context

ADR-0145 shipped last cycle and its VERIFY-BY has now been met. `scripts/reversal-rate.py` over the
change's full post-deploy report series puts the ALPHA same-name direction-reversal rate at
**0.0659 (11 reversals of 167 pairs)** against a stated baseline of **0.1975 (32 of 162)** and a
pre-stated minimum detectable effect of **below ~0.080** on a pooled-pairs gate of **≥ 150**. Over the
newest six reports alone it reads **0.0421 (4 of 95)** — the same direction, under the sample gate. The
round-trip churn item #1 carried for five cycles is closed. `/api/attribution` corroborates: `totalFees`
**$94.669275** against `firmTotal` **-$372.57213777** — **25.4%** of the loss, where the four windows
before the change read 41.1 / 47.8 / 46.6 / 46%.

What the fixed exit leg exposes is the *other* half of the same asymmetry, and this cycle's own
`fusion_targets` states it without ambiguity. Two of the six planned names the report carries are held on
the **opposite side of flat from their own target**, at forecasts well above the conviction floor:

| name | forecast | target | held | aim | deltaQty routed |
|---|---|---|---|---|---|
| MSFT | **−7.568314** | −47.677599 | **+5** | −2.026033 | **−0.041494** |
| WMT | **−7.312571** | −364.857367 | **+31** | −13.663345 | **−0.257260** |

The desk is long MSFT while its own combiner says short at |f| = 7.57 — a strength that is 51% above the
`min-forecast-to-route` floor of 5.0 it would need to *open* that short — and it is shedding the wrong-side
holding at **four hundredths of a share per cycle**. Those routed quantities are not approximations of the
mechanism; they *are* the mechanism, to the last digit:

```
a = 1 − e^(−30/3600) = 0.008298707…        30 s planner cycle, 3600 s evidence horizon (ADR-0080)
5  x 0.008298707 = 0.041494                = MSFT deltaQty, exactly
31 x 0.008298707 = 0.257260                = WMT  deltaQty, exactly
```

### Where the rate comes from, and what it is an argument about

`TargetPlanner.adjustmentRateFor` derives `a = 1 − e^(−c/h)` so that the desk's holding period equals the
horizon its sources' expectancy was measured over: a unit of the gap survives `(1−a)` per cycle, exposure
e-folds with time constant `τ = −c/ln(1−a)`, and setting `τ = h` gives the identity. That argument is
sound and this ADR does not touch it. But read what it is an argument *about*: how fast to put risk **on**,
so the desk pays one round trip per horizon of measured return — the trade the ADR-0064 gate priced
(Gârleanu & Pedersen, "Dynamic Trading with Predictable Returns and Transaction Costs", *JF* 68(6), 2013).

There is no corresponding argument for how fast to take **off** a position the desk's own live forecast
says is backwards. That holding does not earn the view over the horizon; it earns the negative of it, for
every cycle it is carried. At `a = 0.008299` it takes an hour to shed 63% of it and roughly four and a half
to shed 99% — a full US session spent carrying risk the desk decided against on the first cycle.

### Why the existing controls do not cover it

Each of them stops one step short, and the gap between them is exactly this case:

- **ADR-0102 (`withinTarget`)** clamps the *intent* into the closed interval between flat and the target,
  so the aim never opposes the current view. It bounds the aim, not the position.
- **ADR-0132 (`onTargetSide`)** closes the next gap: it bounds the *destination* to the target's side of
  flat, and its own record claims "the clamp resolves the destination to flat, so `|held + delta'| = 0`".
  **That guarantee does not hold as implemented.** `onTargetSide` runs inside `bufferedDelta`, and the
  ADR-0107 rating branch runs immediately after it — on precisely the same condition (`held` on one side
  of flat, `aim` on the other), because that condition *is* the wrong-side case. So the resolution to flat
  is multiplied by `a` on the way out, and ADR-0132 delivers 0.83% of its stated destination per cycle.
  The MSFT row above is that composition, traced end to end.
- **ADR-0145 (`ConvictionHold`)** asks the right question but runs downstream of the rating and can only
  ever make an order *smaller*. It passes `−0.041494` through untouched; it cannot restore the 5.
- **ADR-0118 (`isTrappedExit`)** does resolve a wrong-side holding in full — but only on the branch where
  `mayIncrease` is false, i.e. under a shut edge gate. This cycle's `fusion_targets` reports
  `"edgeGate": null`; the gate is off under ADR-0122, so that branch does not run on the live path.

### Why ADR-0107's rating is nonetheless right, and must stay

ADR-0107 exists because ADR-0102's clamp can jump the aim across flat on a *wobble*: a mean-reverting
forecast crosses the held position many times inside one horizon, and reading each crossing as a cut
liquidated the whole accumulated position and rebuilt it the other way — the round trip ADR-0090 removed.
That failure mode is a forecast **at or near zero**. This cycle's evidence says the same thing ADR-0145's
did: `signal_observations` LIVE hit rates are **0.473 / 0.526 / 0.498** at 225 s on n = 1,513 / 1,368 /
1,264, and no source's `signals_telemetry` expectancy reaches |t| = 1.5 at any horizon. A crossing at
`f ≈ 0` carries no information and must not move the book.

So the two cases are not the same trade and must not share a rate. The discriminator already exists and
the desk already owns it.

## Decision

**The unwind of a wrong-side holding is not rated by the ADR-0080 acquisition fraction when the opposing
view carries ADR-0059 conviction.** Where `|combinedForecast| ≥ min-forecast-to-route` — the same
strength that would have been required to *open* the opposite position — the holding resolves to flat
this cycle. Where it does not, every path is byte-identical to ADR-0107's and the wobble-crossing stays
rated exactly as today.

This is the mirror of ADR-0145 and completes the same Schmitt trigger: **enter on conviction, exit on
conviction, do nothing in between.** ADR-0145 stopped the desk closing a position because its view
*faded*; this stops the desk taking hours to close one because its view *reversed*. Both use one
threshold and neither introduces a number.

### Where it lives

`PositionBuffer.convictedWrongSide(aim, held, target, forecast, minForecastToRoute)`, consulted by
`bufferedDelta` at the single point where the rate is chosen:

```java
BigDecimal unwind = TargetPlanner.reduceOnly(edge, held);
double rate = convictedWrongSide(aim, held, target, forecast, minForecastToRoute)
        ? 1.0
        : Math.max(0.0, Math.min(1.0, adjustmentRate));
```

The predicate is three sign tests and one magnitude test:

```
|forecast| >= min-forecast-to-route     the opposing view would have been allowed to open the reverse
target != 0                             a control has not ordered the exit (that branch returns above)
sgn(held) == −sgn(target)               the holding contradicts the current view
sgn(aim) in {0, sgn(target)}            ADR-0102's interval holds, so there IS a current view
```

`FusionLifecycle` already passes `minForecastToRoute` into `PositionBuffer.apply` for ADR-0145; the
forecast is on the target record. The five-argument `bufferedDelta` overload delegates with the floor at
zero, which is ADR-0107's behaviour unchanged, so every other call site and the sizing controls that
re-derive deltas through `TargetPlanner.orderDelta` are untouched.

### Worked example (the live MSFT plan above)

Held `+5`, target `−47.677599`, aim `−2.026033`, forecast `−7.568314`, floor `5.0`, `a = 0.008298707`.

```
gap    = aim − held = −2.026033 − 5           = −7.026033
|gap| <= band                                 ⇒ edge = 0                       (inside the buffer)
onTargetSide: dest = 5 + 0 = +5, target < 0   ⇒ edge = −5.000000               (ADR-0132)
unwind = reduceOnly(−5.000000, +5)            = −5.000000

before (ADR-0107):  0 + (−5.000000 x 0.008298707) = −0.041494   ← the live deltaQty, to the digit
after  (ADR-0147):  |−7.568314| >= 5.0 ⇒ rate 1  = −5.000000    ← flat this cycle
```

And the same geometry on a wobble — the identical aim, held, target and band, with the forecast at
`−0.288` (a value read off a real BAC order in the ADR-0145 window) — returns `−0.041494`, unchanged. Both
are asserted as exact decimals in `PositionBufferTest`.

## Consequences

- **It introduces no number.** The threshold is `jethro.fusion.min-forecast-to-route`, already the
  ADR-0059 entry floor and, since ADR-0145, the forecast-authored exit floor. Setting it to zero disables
  all three together (invariant 7 / ADR-0016).
- **Strictly one-way, bounded by flat.** The changed rate multiplies only `TargetPlanner.reduceOnly`'s
  projection of the move — the part that shrinks `|held|`. So `|held + delta'| >= 0` with the destination
  at flat: it never opens a position, never enlarges one, never flips one onto a new side, never touches
  an increase or a same-side rebalance, and never reaches the `target == 0` exit branch. The ADR-0086
  chandelier cut, the ADR-0065 orphan unwind, the ADR-0027 breaker, the pre-trade guardrail and the firm
  drawdown breaker all keep their exact semantics and still have the last word.
- **Gross exposure falls on the names it fires on, and this is the one cut CLAUDE.md licenses.** A
  position the desk's own live view contradicts is dead exposure by definition — risk with negative
  expected return under the model that is sizing the book. It is not a retreat from ADR-0132's deploy
  mandate: gross is **6.9%** of the firm cap with **$1,395,758** of headroom, and clearing the wrong-side
  holding is what *unblocks* deployment onto the side the view supports. ADR-0102 pins the aim to flat or
  the target's side while the holding is on the other; once the name is flat the aim tracks the target and
  the desk builds the position it actually wants. On this cycle's book that is MSFT `−47.677599` and WMT
  `−364.857367` — **more** risk on, on the right side, instead of a decaying stub on the wrong one.
- **It does not re-open the ADR-0090 churn, and the verified metric says so.** The conviction test
  excludes every crossing at `f ≈ 0`, which is where the churn lived; ADR-0145's reversal-rate metric is
  the direct measurement and is a guard on this change's verdict.
- **The risk is a whipsaw at high conviction:** a forecast that reaches ±5 on one side and then ±5 on the
  other inside a horizon would now flip the book at full speed. The measured shape of the forecast series
  makes that rare rather than routine — the crossings in the ADR-0145 window arrived at |f| < 0.3, not
  above 5 — and the reversal-rate guard is exactly what would catch it if that reading is wrong.
- **Exact decimal on every quantity** (invariant 1); the only doubles are the dimensionless forecast, the
  floor and the rate.

## How it will be verified

`scripts/wrong-side-share.py`, committed with this change and computed from the report's own
`fusion_targets` block — never by hand (invariant 7 / ADR-0016). The metric is the **convicted-wrong-side
share of held notional**: the fraction of the desk's held notional, across planned names, sitting on the
opposite side of flat from its own target at `|f| ≥ 5.0`. It is a *proportion measured inside the window*,
the property that let ADR-0145's metric survive the drift test every churn-rate candidate failed.

- **Baseline, over the nine reports of 2026-08-10 that carry a book:** **0.2698**
  (`56,283.94 / 208,628.65`).
- **Dispersion, per report, on that same no-deploy series:** mean 0.2791, sd 0.2459, CV 0.88, range
  0.0000–0.7240 over n = 9.
- **Minimum detectable effect, stated in advance and not to be moved:** the pooled share must fall below
  **0.1058** — two standard errors of the mean (`0.2698 − 2 × 0.2459/√9`) below the baseline. The
  mechanism predicts far more than that: the change resolves a convicted-wrong-side name to flat in a
  single cycle, so the residual should be only names that turned wrong-side within the snapshot cycle.
  The `√n` reduction is an assumption, stated as one — nine reports from a single session are not enough
  to measure the pooled noise floor directly, which is the honest limit of this baseline.
- **Sample gate:** pooled held notional ≥ **6,035.85**, the thinnest single report in the baseline
  window; under it the script exits 2 and prints NO VERDICT, which is never a pass.
- **Guards, both required:** ADR-0145's reversal rate must not rise back above its **0.080** MDE
  (`scripts/reversal-rate.py`), and `firmTotal` must not deteriorate.
