# ADR-0145: The conviction floor applies to the EXIT as well as the entry

- **Status:** Implemented
- **Date:** 2026-08-07
- **Deciders:** continuous-improvement loop (ADR-0063); Oleg to ratify
- **Tags:** fusion, execution, turnover, holding-period, cost

## Context

`reports/must-fix.md` item #1 has carried the same diagnosis for four cycles: **the desk round-trips its
book and pays the fee each way.** Read from `/api/attribution` this cycle, `totalFees` is **$488.284665**
against a `firmTotal` of **-$1,188.68635665** — **41.1%** of the entire cumulative loss; on the ALPHA book
alone it is **$465.190115** of **-$949.70770444**, **49.0%**. Cumulative LIVE turnover is **$5,716,068.55**
over **3,319** fills. Against that, `signal_observations` puts the three large-n LIVE hit rates at
**0.490 / 0.501 / 0.498** on n = 14,672 / 14,395 / 13,471. The desk is paying half its loss in fees to
trade a coin flip.

Three prior cycles established *that* the churn exists and gave it a name ("the fusion target tracks a
~90-second mean-reverting forecast 1:1"). What they did not do is identify the **asymmetry** that turns a
harmless oscillation into a round trip. It is visible in one line of `FusionLifecycle.tick`:

```java
boolean reducing = TargetPlanner.isRiskReducing(t.deltaQty(), t.currentQty());
if (!reducing && Math.abs(t.combinedForecast()) < minForecastToRoute) {
    continue; // ADR-0059: below the conviction floor
}
```

**The floor is one-sided.** A name may only be OPENED at `|f| ≥ 5.0`. Nothing at all is required to
CLOSE it.

### Why a one-sided floor is a round-trip machine

`TargetPlanner.targetQuantity` is **linear in the combined forecast** — every step between the two (the
ADR-0083 volatility budget, the ADR-0079 normaliser, the ADR-0104 brake, the ADR-0137 cap) is a per-name
or book-wide scalar that does not depend on forecast *strength*:

```
target = V · f / TARGET_ABS
```

So a forecast that merely **decays toward zero** collapses the target toward zero, and the buffer then
unwinds the whole position — at a forecast strength that would not have been permitted to open a single
share of it. `f ≈ 0` is the combiner saying it has **no view**. No view is a reason to *hold*, not a
reason to *liquidate*.

### Read from this window's own orders

Every one of these is a LIVE FILLED ALPHA order, with the `forecast=` the ADR-0134 origination reason
carries:

| name | opened at | closed at | inside |
|---|---|---|---|
| AMZN | `BUY 34` at **f = +9.25** (18:54:25) | `SELL 26` at **f = +0.0688** (18:59:59), `SELL 2` at **f = +0.1135** (19:01:00) | 5 min |
| BAC | `SELL 156` at **f = −7.38** (18:39:12) | `BUY 1` ×4 at **f ≈ +0.0023…+0.10** (18:40:44–18:42:15), `BUY 115` at **f = −0.288** (18:42:45) | 4 min |
| KO | `SELL 45` at **f = −5.04** (18:26:32) | `BUY 19` at **f = −0.165** (18:27:33), then nine more buys totalling 117 | 32 min |

AMZN is the cleanest: the forecast never changed **sign**. It decayed from +9.25 to +0.07, and the desk
sold back 82% of a position its own view still nominally supported, five minutes after buying it, for a
reason no control stated and no conviction backed.

Note what this is **not**. It is not the ADR-0086 trailing cut (that plans the name flat and is a
different trigger). It is not the ADR-0064 gate (off under ADR-0122). It is not the buffer being too
narrow — ADR-0133 tried widening the band and was scored ❌ BAD, because a wider band is a *permanent
veto* on weak-conviction names rather than a filter on weak-conviction *exits*. The defect is in **which
question the floor is asked about**, not in how wide anything is.

## Decision

**Apply the ADR-0059 conviction floor symmetrically.** A reduction that the **forecast alone** authored —
the forecast-implied target has decayed below the holding — routes only when that forecast is strong
enough that it would have been allowed to OPEN the position. A reduction that a **risk control**
authored always routes, in full.

This is the standard Schmitt trigger: enter on conviction, exit on conviction, and do nothing in between.
It is the same hysteresis Carver's buffering applies to position *size* (*Systematic Trading*, Harriman
House 2015), applied here to the *signal* — and the standard remedy for a control whose input crosses its
threshold many times per holding period.

### Separating the two authors exactly

A reduction has two possible authors and only one of them is the forecast. They are separated by
capturing the planner's target **before** any control runs (`FusionLifecycle.tick`, immediately after
`FusionPlanner.plan`) and measuring both targets on the side of flat the holding is on. Write
`s = sgn(held)`, `h = |held|`, `p = s·planned`, `c = s·controlled`:

```
destination the planner alone asks for    min(h, p)
destination the controls impose           min(h, c)
reduction the CONTROLS author             max(0, min(h, p) − min(h, c))     ← always allowed
reduction the FORECAST authors            h − min(h, p)                     ← needs conviction
```

With no conviction the order is capped at the control-authored part. Every control keeps its exact
effect, because it is subtracted from the *planner's* destination and not from the holding.

### Worked example (the live AMZN plan above)

Held `h = 34`. Forecast `f = +0.0688`, below the floor of 5.0. The planner's target at that forecast is
`p = +0.25`; no control bit, so `c = p = +0.25`. The buffer asked for `−33.75`.

```
min(h, p) = min(34, 0.25) = 0.25
min(h, c) = min(34, 0.25) = 0.25
controls authored = max(0, 0.25 − 0.25) = 0
order = −min(33.75, 0) = 0                  ⇒ the position is held
```

Now the same name with the ADR-0137 gross cap halving the book while the forecast still says nothing:
`h = 100`, `p = +120` (the planner wanted *more* than is held), `c = +60`.

```
min(h, p) = min(100, 120) = 100
min(h, c) = min(100,  60) =  60
controls authored = 100 − 60 = 40
order = −min(40, 40) = −40                  ⇒ the cap's whole half routes, unchanged
```

And once the view has genuinely reversed — `f = −8.25`, above the floor — the hold does not fire at all
and the full unwind routes exactly as before.

### Where it lives

`ConvictionHold.apply(...)`, called from `PositionBuffer.apply` **only on the branch where the desk is
permitted to increase** — so where ADR-0064's gate or ADR-0126's unarmed stop has already clamped the name
reduce-only, and on ADR-0118's trapped exit, every path is byte-identical. A flat controlled target
returns before any of this, so the ADR-0086 chandelier cut, the ADR-0065 orphan unwind and the ADR-0027
breaker above them keep their exact semantics.

When the hold binds, the **aim is re-seeded to where the desk will actually be** — the same thing the
ADR-0064/0075 clamp does one branch above, and for the same reason. Without it the withheld reduction
would accumulate in the aim and fire as one large liquidation the moment conviction returned, which is
strictly worse than the behaviour being removed.

## Consequences

- **It introduces no number.** The threshold is `jethro.fusion.min-forecast-to-route`, the ADR-0059 floor
  the desk already applies to entries. Setting that property to zero disables both halves together
  (invariant 7 / ADR-0016 — nothing here is self-chosen).
- **Strictly one-way on the order.** `|delta'| ≤ |delta|` and `sgn(delta') ∈ {0, sgn(delta)}`, and it
  fires only on a delta `TargetPlanner.isRiskReducing` already classified as risk-reducing. It never
  opens a position, never enlarges one, never flips one, never speeds a trade up, and never touches an
  increase.
- **Exposure rises, and that is the intent (ADR-0132).** Positions the desk was shedding for no stated
  reason are kept. Gross is **2.8%** of the firm cap with **$1,458,644** of headroom, so this is deploying
  an unused budget, not relaxing a limit. Every deterministic floor is untouched and still has the last
  word: the pre-trade guardrail, the firm drawdown breaker, and every control above the buffer.
- **The holding period lengthens toward the horizon the edge was measured at.** That is the ADR-0080/0082
  identity the desk already claims to trade on and has never actually reached, because the exit fired on
  a timescale an order of magnitude shorter than the entry's.
- **The risk is that losers are held longer.** The ADR-0086 trailing chandelier cut is the control that
  exists for exactly that, it plans the name flat, and a flat target is exempt here — so a position that
  goes wrong is still cut on measured σ rather than on forecast noise. The honest statement is that this
  change moves the exit decision *from the forecast to the risk sensor*, which is the owner's stated
  thesis ("cut when risk enters the danger zone", not when the predictor shrugs).
- **Exact decimal throughout** (invariant 1); the only doubles are the dimensionless forecast and floor.

## How it will be verified

`reports/must-fix.md` item #1's VERIFY-BY, unchanged and drift-tested before adoption: the **ALPHA
same-name direction-reversal rate**, pooled over the change's full 6-report ADR-0116 window, computed by
`scripts/reversal-rate.py` from `recent_orders` — never by hand. Baseline over the six reports preceding
this change: **0.1975 (32 reversals of 162 pairs)**. Measured noise floor on no-deploy windows: mean
0.190, sd 0.055. The stated minimum detectable effect is **below ~0.080**, and the sample gate is
**pooled pairs ≥ 150**; under the gate the result is NO VERDICT, not a pass. Guards: gross exposure must
not fall, and `firmTotal` must not deteriorate.
