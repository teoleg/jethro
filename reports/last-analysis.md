The desk's "no-trade band" has never suppressed a single order and structurally cannot — so the book is re-traded every cycle on every name and pays 95% of its gross alpha in fees; the band now sits around the AIM at a fraction of the average position, where it can actually bind (ADR-0094).

## Situation — read off the live endpoints; every figure below is quoted, none computed here

**1. Money.** Total PnL is **up**, three runs running: `$67.76` now, `+$26.67` on the window and
`+$200.52` across the last three. `pnl_growth_pct` is far ahead of the 1% target and `on_track` is true.
The book is **not bleeding**.

**2. Risk.** Gross exposure `$26,197.73` against net `$3,527.47` — **up `+$3,162.22` this window**, but
**down `-$28,806.42` over three**. VaR95 `$216.31` / ES95 `$279.14` on `$26,183.41` covered; the firm
drawdown breaker is not tripped and is nowhere near it. Regime `CALM`, trend `CHOP`.

**3. Cause — the culprit is named and already gone.** Last cycle's change (`e33e9479c`, fusion weight =
shrunk measured edge, ADR-0093) scored **❌ BAD** and the scorer auto-reverted it (`f73e297`). Its
scored row is exactly the exposure rise the situation header flags: gross `$23,039.67 → $26,198.44` and
net `$647.79 → $3,525.84` for a PnL move of `+$22.73`. So the one danger flag this cycle is that change's
doing and is already backed out; I have not re-attempted that lever and will not.

**4. Danger state?** No. PnL rising, breaker far, VaR a fraction of gross, and the exposure rise is
attributable to a change that has been reverted. No de-risk override — this cycle was free to go at a
mechanism.

**7. Change vs market, honestly.** I claim credit for nothing this window. ADR-0093 re-weighted every
source, so it touched every name in the book — there is no untouched control group to read the market off,
and the `+$26.67` cannot be split from the numbers alone. I say that rather than guess a cause. The
exposure half *is* separable and *is* the change's: it moved net five-fold on a re-weighting that
re-pointed the cross-section, and that is what the ❌ verdict priced.

## Diagnosis — the mechanism, from the order flow

`/api/attribution` is unambiguous about where the money goes: `ALPHA` shows `$268.61` of fees against a
total of `$14.10`, so gross alpha before cost was ~`$283` and **the fee line is 95% of it**. The firm
total is what survives. `HEDGE`'s `-$323.33` is realised history from cycles already fixed by ADR-0091 —
it is holding on target now — and `MACRO`'s `+$376.99` is realised and flat. The only thing trading is
`ALPHA`, on five equities, to a wash.

Why: the ADR-0055 no-trade band is `|target| × 0.5` compared against the gap **to the target**, and
ADR-0080 partial adjustment deliberately never takes the desk to its target. So the gap is ~0.9 of the
target every cycle and the band has one answer. `fusion_targets` proves it — **all thirteen** planned
names with a non-zero gap traded at exactly the derived rate `0.032784`, not one suppressed, while the
desk held **5–30%** of its own target (AAPL −7 against −142, JNJ 47 against 260, GOOG 38 against 131).
`recent_orders` is the same fact in the time domain: AAPL bought for three cycles then sold for twelve
straight, JNJ round-tripped BUY→SELL→BUY inside eight minutes. The desk pays a continuous proportional
cost to hold a small, lagging fraction of the risk it decided to take.

## Change

`PositionBuffer` (ADR-0094, Proposed, same commit): trade toward the **aim** — the ADR-0080 exponential
path itself — and only when the held position has drifted more than a buffer away from it, then only back
to the buffer's near edge. Exposure is unchanged by construction (the aim path *is* the position the old
policy converged to); what stops is buying and selling the last stretch every thirty seconds. Buffer width
is Carver's published 10% of the average position, and the average position is derived per name from the
cycle's own arithmetic — no money number authored, and Oleg's `buffer-fraction=0.5` left exactly as set.
A flat target is never buffered, so every "get out" control (ADR-0086 cut, ADR-0065 unwind, the breaker)
works in full as before; a shut edge gate clamps reduce-only and re-seeds the aim so intent cannot pile up
behind it. `./gradlew -Pci test` green.

**What I expect to see next run:** turnover and the fee line fall materially on `ALPHA` with gross
exposure roughly where it is. If gross falls *and* PnL does not improve, the buffer is too wide and the
next lever is its width, not another mechanism. If turnover does **not** fall, my reading of the aim path
is wrong and the suspect becomes the target's own oscillation — the reversion sensor's span — rather than
the execution policy.
