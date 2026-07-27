# ADR-0094: The no-trade band is measured against the AIM, not the target — a buffer the desk can actually be inside

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, execution, cost
- **Superseded in part by:** [ADR-0103](0103-the-no-trade-region-sits-around-the-target-not-around-the-aim.md) — the band's LOCATION returns to the target once ADR-0101 measured a width that binds there. The average-position scale and the trade-to-the-near-edge policy shape below stand.

## Context

The fusion desk has had a "no-trade band" since ADR-0055 phase 3. Its stated purpose is the cost-aware
half of the Gârleanu-Pedersen policy: *small frequent corrections are suppressed so we don't pay spread
to chase noise*. It is implemented in `TargetPlanner.orderDelta` as

```
gap  = target − held
band = |target| × bufferFraction        (bufferFraction = 0.5, OLEG-SET 2026-07-21)
|gap| ≤ band → trade nothing
```

**It has never suppressed a single order, and it structurally cannot.** ADR-0080 made the desk close
only a derived fraction `a` of the gap each cycle, so the held position is an exponential average of the
target with time constant equal to the evidence horizon. When the target moves on a timescale shorter
than that constant — which it does, because a mean-reverting source is one of the two live sensors — the
held position never approaches the target. The gap is then a *large* fraction of the target every cycle
and the band is a small one, so the comparison has exactly one answer.

The live target book says so directly. Every planned name with a non-zero gap this cycle traded at the
full rated fraction — `deltaQty / (targetQty − currentQty) = 0.032784` on all thirteen, the derived
30 s/900 s rate to seven digits, i.e. the band suppressed nothing — while the desk held between **5% and
30%** of its own target (AAPL −7 against −142, JNJ 47 against 260, GOOG 38 against 131).

The bill is the whole point. The desk trades every routable name every cycle, and the resulting
turnover — 1,068 fills in the session on five names — is what the strategy book earns. `ALPHA` shows
gross alpha of about `$283` against `$268.61` of fees: **the fee line is 95% of the gross**, and the
firm total is what is left. The mechanism is not a bad signal, it is a policy that pays a continuous
proportional cost to hold a small, lagging fraction of the risk it decided to take. This desk's cost is
proportional to *quantity* (the fee is a fixed fraction of notional; measured slippage is quoted in bps),
so the lever is suppressing the oscillation, not the order count.

Doing nothing keeps a documented control inert while it is cited as the reason the policy is cost-aware.

## Decision

We will **separate where the desk intends to be from when it is worth paying spread to get there**, and
put the no-trade region around the first. A new pure-arithmetic stage, `PositionBuffer`, runs last in the
planning cycle and re-derives each name's delta:

```
aim   ← aim + a·(target − aim)                  the ADR-0080 exponential path, unchanged
aim   ← 0                                        when the target is FLAT — an exit is not buffered
scale = |target| · TARGET_ABS / |forecast|       this name's position at a typical forecast
band  = scale · positionBufferFraction
gap   = aim − held
|gap| ≤ band → 0                                 inside the buffer: the desk is where it means to be
otherwise    → gap − band·sgn(gap)               trade to the NEAR EDGE of the buffer, not to the aim
```

`aim` is seeded from the held position on first sight of a name.

**Exposure is unchanged by construction.** The aim path is precisely the position the ADR-0080 policy
converged to; the buffer only decides whether the last stretch of the way is worth trading. What changes
is that a target oscillating *inside* the buffer is no longer bought and sold every thirty seconds —
only its net drift is traded.

This is the standard buffering rule (Carver, *Systematic Trading*, Harriman House 2015; *Advanced
Futures Trading Strategies*, 2023: buffer at a fraction of the average position and trade to the buffer
edge), and it is the form the optimal policy provably takes under **proportional** transaction costs — a
no-trade region with trading at its boundary (Constantinides, *JPE* 94(4) 1986; Davis & Norman, *Math.
of OR* 15(4) 1990). Gârleanu-Pedersen's smooth partial adjustment is the *quadratic*-cost solution; this
desk pays a proportional cost, and the two prescriptions differ exactly here.

### Numbers and their provenance

- `jethro.fusion.position-buffer.fraction = 0.10` — **cited market convention**, Carver's published
  buffering width (10% of the average position). Dimensionless: it sizes nothing and prices nothing, it
  only decides when a difference is worth paying spread for.
- The **average position** is *derived*, not dialled: the target is linear in the combined forecast (the
  ADR-0083 volatility budget and the ADR-0079 portfolio normaliser are per-name and book-wide scalars
  that do not depend on forecast *strength*), so `|target| · TARGET_ABS / |forecast|` is that name's
  position at a typical forecast, read off this cycle's own arithmetic with no estimator and no warm-up.
  Where the forecast or the target is degenerate the buffer falls back to `|held|` — the only position
  actually at stake — rather than inventing a scale.
- `jethro.fusion.buffer-fraction = 0.5` is **left exactly as Oleg set it**. It still shapes the planner's
  intermediate deltas and is now documented in `application.properties` as superseded at the routing
  stage, with the measurement above as the reason.

### Worked example — the AAPL plan this was diagnosed from

Forecast −9.64, target −142.319300, held −7, derived rate `a = 1 − e^(−30/900) = 0.0327839…`:

```
averagePosition = 142.319300 × 10 / 9.64 = 147.634128
band            = 0.10 × 147.634128      =  14.763413
aim             = −7 + 0.0327839…×(−142.319300 + 7) = −11.436294
gap             = −11.436294 − (−7)                 =  −4.436294
|gap| = 4.436294 ≤ 14.763413                        ⇒  NO ORDER
```

The old policy sold 4 shares here, and again thirty seconds later, and again — for as long as the target
stayed out of reach, which was every cycle. Once intent has genuinely drifted (aim −40 against a −7
holding) the gap is 33, the buffer is breached, and the desk trades `33 − 14.763413 = 18.236587` — to the
buffer's edge, not to the aim.

## Consequences

**Good.** Turnover falls to the aim's *net* drift instead of its round trips, and the proportional fee
and slippage bill falls with it, on the same held risk — which is the objective (total PnL up per unit
of total exposure). The desk's no-trade region becomes a live control instead of documentation. The
policy now matches the cost structure it actually faces.

**Bad / risk.** A buffer is a deliberate tracking error against the aim: the desk sits up to one band
away from its intended position, which costs a little correlation with the signal. Carver's 10% is
chosen precisely because that cost is small next to the turnover saved, but it is not zero. The aim is
process-local derived state, so a restart re-seeds it from the held position and the desk starts
conservatively — a slow start, never a jump.

**Explicitly preserved.** Nothing on the deterministic floor moves. A **flat target is never buffered**:
the ADR-0086 chandelier cut, the ADR-0065 orphan unwind and any control that means "get out" plan the
name flat, which snaps the aim to zero and trades the whole position this cycle exactly as ADR-0090 works
it. Where the ADR-0064/0075 edge gate says a name may not increase, the buffered order is clamped
reduce-only **and the aim is re-seeded to where the desk will actually be**, so intent cannot accumulate
behind a shut gate and arrive as one large order when it reopens. The firm breaker, the pre-trade
guardrail and the ADR-0049 backtest support sit below this stage and are untouched.

**Reversible.** `jethro.fusion.position-buffer.enabled=false` restores the ADR-0080 deltas byte for byte.

## Alternatives considered

- **Widen `buffer-fraction`.** Cannot work: the band is a fraction of the target and the gap is ~0.9 of
  the target every cycle, so any fraction below ~0.9 never binds and anything above it freezes the book.
  The reference is wrong, not the width.
- **Slow the adjustment rate.** Turnover *and* held exposure both scale with `a`, so the ratio the desk
  is graded on is unchanged. It is not the lever.
- **Suppress small orders (a minimum trade size).** Cost here is proportional to quantity, not to order
  count, so skipping a small order today and a larger one tomorrow saves nothing.
- **Smooth the forecast instead.** Algebraically identical to the current policy — the held position is
  already an exponential average of the target — so it changes neither the position path nor the
  turnover. Only a *region* changes the turnover.

## References

- ADR-0055 (fusion phases / the original band), ADR-0080 (the derived adjustment rate), ADR-0090 (a
  change of view is not an exit), ADR-0084 (post to enter, cross to exit), ADR-0064/0075 (the edge gate).
- Carver, R. *Systematic Trading* (Harriman House, 2015); *Advanced Futures Trading Strategies* (2023).
- Constantinides, G. "Capital Market Equilibrium with Transaction Costs", *JPE* 94(4), 1986.
- Davis, M. & Norman, A. "Portfolio Selection with Transaction Costs", *Math. of OR* 15(4), 1990.
- Gârleanu, N. & Pedersen, L. "Dynamic Trading with Predictable Returns and Transaction Costs",
  *JF* 68(6), 2013.
