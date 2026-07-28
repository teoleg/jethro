# ADR-0090: A change of view is not an exit — only a flat target is worked at full speed

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** backend, fusion, execution, cost, turnover
- **Extends / partially supersedes:** ADR-0080 (holding period matches the evidence horizon) —
  specifically its Decision 2, the reduce-in-full asymmetry.

## Context

ADR-0080 fixed a dimensional error. The edge gate (ADR-0064/0072/0075) asks whether a source's
measured expectancy over horizon `h` beats the desk's measured **round-trip** execution cost — one
round trip against one horizon's return. That comparison is only sound if a position actually
persists for `h`. So ADR-0080 derived the partial-adjustment rate from the identity

```
a = 1 − exp(−c / h)        ⇒   τ = −c / ln(1 − a) = h
```

and at the shipped configuration (`c = 30 s`, `h = 3600 s`) that is `a = 0.008298707361…`: exposure
e-folds toward its target in exactly one measurement horizon.

**ADR-0080 then applied that rate to only half of the trade.** Its Decision 2 splits the gap at flat
and trades the *reducing* half **in full, this cycle**, rating only the *increasing* half. The stated
motive was risk: "cut when risk enters the danger zone", "a cut that waits is not a cut".

That motive is sound; the implementation does not test for it. `orderDelta` cannot see *why* the
target moved, so **every** reduction is treated as a cut — including the overwhelmingly common case
where nothing dangerous has happened at all and the desk has merely changed its mind. The fused
forecast is dominated by `reversion`, a mean-reverting source whose strongest measured rung is 225 s;
its sign crosses the held position many times inside one 3600 s horizon. Each crossing is read as a
danger cut and liquidates the entire accumulated position in one order.

The result is a ratchet, and it is visible order by order in the live window:

- AAPL is bought in ~3-share steps once every 30 s cycle for seven minutes — the derived rate walking
  toward a target the desk needs an hour to reach — and is then sold, **101 shares in one order**, the
  instant the combined forecast crosses. It immediately begins grinding back the other way.
- JPM and JNJ show the same shape in the same window: a slow one-directional build, one full-size
  reversal, a slow rebuild.

So `τ_in = h` and `τ_out = 0`. The desk enters at the rate the evidence justifies and exits at
infinite rate on a signal wobble, which means the position **never reaches its intended size** while
paying the **full** cost of getting there and back. Cost scales with turnover, which is unconstrained;
edge scales with position size, which is a small fraction of what was planned. That is precisely the
"N round trips against one horizon's return" over-permissiveness ADR-0080 was written to remove,
reintroduced on the other side of the trade.

This is not a marginal effect. In the window that prompted this ADR the firm's fee bill is the
**large majority** of the firm's total loss, on a gross book two orders of magnitude smaller than the
notional traded to produce it, while the best source's measured expectancy is a single-digit-to-low-
double-digit bps figure per horizon. (Every figure in that sentence is read from `/api/attribution`,
`/api/risk` and the signal telemetry — none is computed here; the scorer owns the arithmetic.)

The asymmetry is also not needed for the risk promise it was justified by. The desk acquired a
dedicated, deterministic risk-reactive exit in ADR-0086 (`TrailingRiskCut`, a volatility-scaled
chandelier stop) *after* ADR-0080 was written. That control, the ADR-0065 orphan unwind, and the
firm breaker are the mechanisms that answer "a position has gone wrong" — and all of them express
themselves the same way: **they set the target flat.**

## Decision

**A reduction toward a FLAT target is an exit and trades in full. A reduction toward a NON-ZERO
target is a rebalance and is worked at the same partial-adjustment rate as an increase.**

`TargetPlanner.orderDelta` gains no dial and no new input — the rule reads the target it already has:

```
gap  = target − current
band = |target| · bufferFraction
if |gap| ≤ band            → 0                       (unchanged, ADR-0055 no-trade band)
if target = 0              → gap                     (unchanged: exit in full)
otherwise                  → a · gap                 (this ADR: one rate, both directions)
```

The `target = 0` branch is what preserves every existing promise, because every control that means
"get out" already routes through it:

- **ADR-0086 risk cut** — `TrailingRiskCut.flatten` computes its delta against a literal `ZERO`
  target, so a chandelier stop still crosses out at full speed, this cycle. Unchanged, bit for bit.
- **ADR-0065 orphan unwind** — a held name whose sources have gone silent is planned with an implicit
  target of zero, so a position never gets stranded when its view disappears. Unchanged. (This is
  also the path that unwinds the book on a cold start, when no sensor has warmed yet.)
- **ADR-0027 firm breaker** and the pre-trade guardrail sit below this code entirely and are
  untouched. The deterministic floor is not modified by this ADR.

What changes is the middle case: the desk still holds a view on the name, and that view has moved.
Then the position is worked toward the new aim at `a` per cycle in whichever direction the aim lies,
which makes the whole policy the exponential smoother ADR-0080's identity describes:

```
positionₜ₊₁ = positionₜ + a · (aimₜ − positionₜ) = (1 − a) · positionₜ + a · aimₜ
```

Three consequences follow directly, and all three are asserted in `FusionEngineTest`:

1. **The holding period identity now actually holds.** `τ = h` in both directions instead of one, so
   the desk pays one round trip per horizon of return — the trade the edge gate priced.
2. **Turnover collapses on an oscillating view.** A forecast that reverses inside a fraction of its
   own measurement horizon no longer round-trips the book; it moves the position by `a` of the gap
   and is averaged away by the next cycle. This is the intended and measurable effect.
3. **The position tracks the *average* aim, not its peaks.** An aim that oscillates around zero
   smooths to a position near zero, so this is expected to *lower* gross exposure as well as turnover —
   the sawtooth (build to full size, dump, rebuild the other way) is replaced by a small, slow-moving
   holding. It cannot lever the book up: `a ∈ (0, 1]`, so no step is ever larger than the gap itself.

### Accepted trade-off, stated plainly

`VolatilityBudget` (ADR-0083) and `PortfolioRiskNormaliser` (ADR-0079/0089) shrink the target book and
recompute the delta against it. Under this ADR their shrink is also worked at `a` rather than
instantly, because they express themselves by *scaling* a target, not by flattening it. That is a
real, deliberate weakening of how fast those two controls de-risk: they now steer the desk toward a
smaller book over one horizon instead of in one cycle. It is accepted because (a) they are sizing
controls, not danger detectors — the danger detectors flatten, and flattening is untouched; and
(b) leaving them at full speed would reintroduce exactly the ratchet this ADR removes, since almost
every name in a live cross-section is covered by at least one of them.

### Worked example — the live AAPL flip, in exact decimal

Cycle `c = 30 s`, horizon `h = 3600 s`, so `a = 1 − e^(−30/3600) = 0.008298707361…`.
Position `+101` shares, new target `−210`, `bufferFraction = 0.5`.

```
gap  = −210 − 101              = −311
band = |−210| · 0.5            =  105          |gap| = 311 > 105 → the band is cleared, trade
```

- **Before (ADR-0080):** the gap splits at flat into `−101` reducing (traded in full) and `−210`
  increasing (rated): `−101 + 0.008298707361 · (−210) = −102.742740…` → **−102 shares** after
  rounding toward zero. The desk pays a 101-share round trip on a view that is one cycle old.
- **After (this ADR):** `0.008298707361 · (−311) = −2.580898…` → **−2 shares**.

Same view, same band, same rate — 2 shares traded instead of 102. If the forecast crosses back next
cycle (which is what a 225 s reversion signal does inside an hour), the desk has given up 2 shares of
tracking error instead of paying for a full round trip.

**A flat target is unchanged.** Position `+80`, target `0`: `band = 0`, `gap = −80`, and the
`target = 0` branch trades the whole gap → **−80**, identical to today. A risk cut is still a cut.

## Consequences

- **Positive.** Turnover — and therefore the fee bill that currently dominates the firm's loss — falls
  by the ratio of the smoothed position's movement to the sawtooth's amplitude. The position finally
  reaches a size at which the measured expectancy can pay for the cost of holding it. The policy
  becomes the textbook Gârleanu-Pedersen smoother rather than a hybrid that is optimal under neither
  cost model.
- **Negative.** The desk is slower to leave a position when the *view* reverses, so it will sit on the
  wrong side of a genuine trend change for up to one horizon. This is the deliberate price of the
  identity, and it is bounded on the loss side by ADR-0086's chandelier stop, which is unaffected.
- **Reversible.** One branch in `TargetPlanner.orderDelta`; reverting restores ADR-0080 exactly.
- **No new dial, no new money/risk number.** The rate and the band are the ones already configured and
  already sourced; this ADR only changes which half of the gap the existing rate applies to
  (invariant 7 / ADR-0016).
