The desk was claiming a full two-source diversification bonus while one source carried 91% of the weight — the multiplier now measures the breadth the weights actually deliver, so a source held at the floor for losing money stops buying leverage (ADR-0076).

## Situation (live endpoints, read first)

**Money — flat, not bleeding.** Total PnL is unchanged run-over-run and unchanged across the last three
runs; every move in that span sits inside the scorer's noise deadband. `stale` and `underwater` are both
set and we are off the +1%-per-3-iterations target. The cause is arithmetic, not a loss: with zero
exposure, PnL cannot move. The day's damage was done in a ~40-minute window this afternoon, when a large
book was opened and then unwound in seven geometric halving steps, paying the round trip on every step;
nothing has traded since.

**Risk — zero, no danger state.** Gross and net exposure are both flat at zero, VaR reports "no
positions", the firm drawdown breaker is untripped, the hedge axis reads FLAT. Nothing to de-risk,
nothing to cut, so the danger-state override does not apply.

**Cause — last cycle's change did what it said and could not have moved the vector.** ADR-0075 opened the
edge gate's cost comparison to each name's own round trip. It scored ⚠️ MIXED "no material change", and
correctly: the gate is shut for reasons cost cannot fix. `reversion` — the one source with positive
measured expectancy — has 23 resolved observations against a 30-observation minimum, and its t-statistic
against a **zero** round trip is still short of the 2.0 hurdle. No cost refinement reaches that. Five
consecutive cycles have now been spent on the gate; the gate is not the binding constraint.

**Order-level post-mortem.** The window's only orders were two 1-share ALPHA buys with matching fractional
HEDGE trims, both under the reduce-only gate. **No trigger opened a position**, so 100% of the (nil) PnL
move is mark drift on a flat book — market, not change. Nothing to blame, nothing to credit.

## What I found instead

Looking past the gate at what happens *when* it opens: the combiner sizes every target with a
diversification multiplier taken from the **count** of contributing sources, under a precondition its own
javadoc states — "for `n` equally-important forecasts" — that has not held since source trust became
evidence-driven. Weights span a 12× band, and the live vector is `reversion 2.52` against `trend 0.25`:
normalised, one source holds 91% of the vote and the desk was awarding itself the full two-equal-source
multiplier. The error is one-directional and worst exactly when the evidence is worst — the more
convincingly a source is measured to lose money, the more concentrated the weights become and the more the
count over-states breadth, so a source pinned at the floor *for losing money* still bought a full extra
unit of leverage.

## The change

The multiplier is now computed from the concentration of the weight vector actually used:
`DM = 1/√(Σwᵢ² + ρ(1−Σwᵢ²))`, which is the same formula evaluated at the inverse-Herfindahl **effective**
number of sources instead of the roster size. No new dial, no new number — same ρ, same cap, same weights.
Three properties are proved and tested: it is **identical** to the old rule at equal weights (cold start
and `weights.mode=equal` are byte-unchanged); by Cauchy–Schwarz it is **never larger** than the old rule,
so this can only shrink the book, never grow it; and it never falls below 1, so averaging cannot make the
desk less confident than a single view. At the live weights the target book is materially smaller for an
identical forecast — less capital at risk for the same view, which is the objective directly.

**Honest expectation.** With the gate shut and the book flat this will very likely score ⚠️ "no material
change" again, and I am not going to pretend otherwise. It is not a bet on this window: it is the
difference between the desk re-entering at honest size and re-entering over-levered once `reversion`
clears its sample. I did *not* loosen the gate to manufacture activity — the evidence does not support
trading yet, and forcing it is exactly how the ❌ BAD change earlier in the run was earned.
