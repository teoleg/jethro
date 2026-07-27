Stopped the desk paying a round trip for a view it never held long enough to be graded on: the fused conviction is now averaged over the same measured horizon the position is held for (ADR-0088).

## Situation (read from the live endpoints; every number below is quoted, none computed here)

1. **Money.** Total PnL is **higher** than last run — `-792.34` against `-792.49`, `+0.15` — and `+33.72`
   across the last three runs. `pnl_growth_pct 4.06` against a `1.0` target, so **on track** and not
   bleeding. Still `underwater`.
2. **Risk.** This is the problem. Gross exposure went `130.69 → 21,142.84` in one window, net `-1,954.40`.
   Historical VaR95 on it is `298.78`, the breaker is nowhere near, and parametric VaR reports its whole
   covered exposure as skipped. So it is not a danger state by any limit — but PnL is flat while exposure
   rose by two orders of magnitude, and that is the objective (PnL per unit of exposure) moving hard the
   wrong way. Worse, it is not finished: MSFT's planned target is `220.73` shares against `21.0` held, and
   the desk is grinding toward it at the derived rate.
3. **Cause.** Last cycle's change (ADR-0087, removing the source-weight floor) scored **❌ BAD** and was
   auto-reverted. The verdict is right and the mechanism is the one I flagged when I shipped it: removing
   the floor uncancelled `reversion` against `trend`, which raised forecast magnitude and therefore the
   target book. The revert restores the floor, but it does **not** stop the ratchet — at the floored
   weights MSFT still plans ~185 shares.
4. **Danger.** Not by the breaker. But "exposure rising into a losing position" is the shape, and it is
   what I acted on rather than shipping another signal.
5. **Order post-mortem.** The window's tape is one name: **74 MSFT fills**, running BUY 8 → BUY 4 → BUY 5 →
   BUY 6 → BUY 2 → BUY 3 → BUY 1 → SELL 16 → SELL 6 → SELL 9 → SELL 2 → SELL 1 → BUY 16 → SELL 4, for a net
   position change of 21 shares. A sign flip roughly every 3-5 minutes against a **900 s** selected rung.
   MSFT is the only equity whose measured round trip clears the ADR-0075 cost test, and its realised PnL
   over the window is negative on ~1.0 bp of fee per leg. The HEDGE book shorted ES against every swing,
   which is why gross doubled: `MSFT 9,001` long, `ES -8,950` short.
6. **Change vs market — attribution.** Clean and unflattering to me: every non-MSFT equity line is flat or
   was only reduced, and the market contributed essentially nothing. The exposure move is **~100% the
   direct effect of the reverted change plus the ratchet it accelerated**, not a market drift.

## Diagnosis and what I changed

ADR-0080 reconciled the desk's holding period with the horizon its edge is measured over — for the
**position**. It said nothing about the **signal**, and its asymmetry (reduce in full immediately, add
slowly) is what turns that omission into churn: when the forecast flips sign, the gap is
`|target| + |current|`, which clears the 50% no-trade band trivially, and the reducing leg fires at full
speed. A sinusoidal view becomes a sawtooth of trades. On this book both sensors are pinned at the ±20
**cap** on MSFT — `reversion +20`, `trend −20` — so a flip swings the target between the two largest
positions the sizer can express, with no magnitude information in between.

So the conviction is now averaged over the horizon it is graded on: one EWMA per name on the combined
forecast at `α = 1 − exp(−Δt/h)`, which is literally `TargetPlanner.adjustmentRateFor` evaluated on the
signal instead of on the trade, with `h` the rung the evidence picked and `Δt` measured elapsed time. No
dial and no money number. A constant forecast is its own average, so a real trend sizes exactly as before;
a ±20 square wave with a 4-minute half-period settles at `20·tanh(240/1800) = 2.65`, under the ADR-0059
conviction floor of 5.0 that has never once bitten because a saturated forecast never falls below it.
`|average| ≤ max|forecast|`, so this can only ever ask for a smaller book. Every risk control is
downstream and untouched: the ADR-0086 cut can still set a target flat, the gate still clamps to
reduce-only, the guardrail and breaker are the floor and I did not go near them. Worked example pinned as
an exact test; two deferred-register rows filed — the telemetry still grades the raw sensor call, and the
sensors' cap saturation is measured but not yet addressed.
