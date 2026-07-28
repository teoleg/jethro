Fixed the risk cut's σ sensor: a republished mark was decaying the stop distance toward zero, so every name held across a close would be stopped out on the first genuine move of the next session (ADR-0116).

*Every figure below is quoted from the live endpoints, `logs/report.md` or the ledger; none is authored
here (invariant 7 / ADR-0016 — the scorer owns every number that gates money). The worked σ example is
arithmetic on the estimator's own formula, not a measurement of this book.*

## Situation — the four questions, in plain numbers

**1. Money.** Total PnL reads `$0.00`, flat against the last run and across the last three. That is not a
loss and not staleness: the Alpaca feed was mis-tagged `SIM` and was corrected to `LIVE` overnight
(commit `2c5b6d2`), which under invariant 8 / ADR-0029 starts a new epoch — the `$5,665.94` sitting in the
SIM equity curve does not carry across, and must not. The desk is a **fresh live book, flat**, with no
fills since `2026-07-27 19:54:38Z`.

**2. Risk.** Gross `$0.00`, net `$0.00`, zero positions, zero fees. Nowhere near the drawdown breaker.
There is no risk on at all — which is the actual problem, and the opposite of the danger state.

**3. Cause.** The last *scored* change was ADR-0113 (⚠️ MIXED, inside the noise band). Four changes landed
overnight while the market was closed and the loop was skipping cycles — the feedMode correction,
ADR-0114 (the seed counted in prints), ADR-0049 (social out of sizing), ADR-0115 (the session-hours gate).
None of them can be credited or blamed for a dollar: the book held nothing for the entire window.

**4. Danger.** No. Not bleeding, exposure not rising. No order was placed in the window (`orders_day` total
`0`), so there is no losing trigger to attribute and no winner to strengthen.

**Change vs market — honestly, neither.** With zero positions all window the market's effect on the book is
`$0.00` by construction, and no code change had a position to act on. I claim no credit and accept no blame
for this window's number, and I have not tried to read a cause into it.

## What I found instead, and why it was worth this cycle

Reading the live endpoints rather than the report narrative: `/api/fusion/targets` shows the desk holding a
view on **one** name of 35 — the LIVE mark history is only hours old and the equity sensors are still
warming. That self-heals as the session prints, so I left it alone. What does **not** self-heal is what
ADR-0113's own deferred row already predicted. `StreamVolatility` — the per-name σ that ADR-0086's trailing
risk cut measures its stop distance in — is fed once per 30 s planning cycle from a last-value mark cache.
On a quiet tape the same price arrives over and over, and each one is absorbed as a return of `ln(p/p) = 0`.
The class's own javadoc refuses to invent a return "because a fabricated zero return would bias the estimate
toward *this name does not move*, which is the dangerous direction for a control that decides when to cut" —
but that guard was written for a *null* price, and the republish walks straight past it.

σ **is** the stop distance (`cut when e > k·σ_h`), so this is not a slightly wrong statistic. Worked at
span 4 on a name warmed on ±1 % steps: σ_Δ = 0.995 %, and the 3σ trigger over a 3600 s horizon sits at
**32.70 %**. Twenty republished marks later — ten minutes of quiet, not even an overnight — σ_Δ = 0.0060 %
and the trigger sits at **0.198 %**, 165× tighter. The desk would stop itself out of a position on the noise
it was sized to sit through, pay the spread, and then hold the name reduce-only for a full holding horizon.
The warm-up counter fills with the same silence, so a name can start speaking a σ built from observations
that never happened.

## The change

`StreamVolatility` now admits a sample only when the mark's **provider** timestamp is strictly newer than
the last one it consumed — the same rule and the same `PrintClock` ADR-0113 established for the forecast
sensors, applied at the boundary where `FusionLifecycle` already holds the provider clock for its seed. No
dial and no number needing provenance; the gate is on the market's clock and never on price equality, so a
name that genuinely reprints at the same price keeps that honest zero return. Proposed ADR-0116 ships in the
same commit. The covariance half of the original register row stays deferred on purpose: a joint snapshot
cannot drop a member without breaking the pairing that makes it a covariance.

**The honest limit.** This cannot show up as PnL until the desk holds positions again, and it costs
protection in the other direction — a slow name now warms at its own print rate, and until it does the risk
cut makes no claim about it. I expect the next scored verdict to be MIXED on a still-flat book. The thing to
check next cycle is not the P&L, it is whether σ survives the 20:00Z close with its pre-close value intact.
