The desk's risk sensor has been wrong by 10–12× on every name that has lived through a sim↔live switch — the daily close series was never feed-mode scoped, so a feed handover was being measured as a ±40–75% market day (ADR-0073).

## Situation (live endpoints, read first)

**Money — flat, not bleeding.** Total PnL `-$867.73`, unchanged run-over-run and effectively flat
across the last three; every move in that span is inside the scorer's noise deadband. `stale` and
`underwater` are both set and we are nowhere near the ≥1%-per-3-iterations target. The reason is not a
loss: with zero exposure, PnL cannot move at all.

**Risk — zero, and no danger state.** Gross and net exposure are both `$0.00`, VaR reports "no
positions", the firm drawdown breaker is untripped. There is nothing to de-risk and nothing to cut, so
the danger-state override does not apply this cycle.

**Cause — last cycle's change is inert by construction, exactly as stated in advance.** ADR-0072
(per-name execution cost in the edge gate) scored ⚠️ MIXED "no material change". That was the
prediction written before it shipped: with the gate shut and the book flat it can only ever subtract
permission, so there is nothing for it to act on yet. The window's only orders were the last leg of the
ADR-0065 flattening — single-share buys closing the remaining ALPHA shorts (AAPL, GOOG) and the matching
HEDGE ES trims — under a reduce-only gate. **No trigger opened a position this window**, so 100% of the
(nil) PnL and exposure move is mark drift plus that flattening: market and prior policy, none of it
attributable to my last change, good or bad.

**One genuinely new fact: `reversion` speaks.** For the first time it appears in `fusion_targets.weights`
and in per-name contributions, with 23 open observations and none yet resolved. That is the
absence-is-the-tell check the findings memory told me to run, and it now passes: ADR-0070 + the ADR-0071
warm restart work. Its calls resolve one signal horizon out, so it becomes measurable — and the edge gate
can finally judge it — within the next cycle or two. The plumbing is done; I did not touch it again.

## Diagnosis — the risk sensor, not the signal

With the gate correctly shut on measurement (trend −17.31 bps over 69 observations, momentum and social
negative alongside it), the honest place to spend this cycle is the first sensor in the owner's thesis:
*know how much is at risk right now*. It has been lying.

`daily_close` is the return series behind historical + parametric VaR, behind per-name daily volatility
— which **vol-targets position sizing** — and behind every change chip on the UI. It was the one
end-of-day artifact never scoped by feed mode. The defect is visible in its starkest form inside a single
method: `EodService.rollover()` and `MarketHistoryRecorder.recordOnce()` each write `firm_equity` **with**
`feed_mode`, and three lines away write `daily_close` **without** it. So a LIVE session's closes and a SIM
session's closes share one series and overwrite each other, and the close-to-close "return" at a handover
is the ratio of two unrelated price levels — the seed's AAPL at 190.00 against the live feed's at 326.95
is a fabricated **+72%** day, then −43% back to the sim, then +76%, then −42%. Four phantom days in the
last eight observations of the window, and EWMA(λ=0.94) weights the most recent observations hardest.

Measured against the clean block, that overstates daily vol by 10.1× on GOOG, 10.7× on AAPL, 12.4× on
JPM, 11.9× on ES. The tell is decisive: names that have only ever run under one feed — GOOGL, BRK.B, GS,
TSLA — sit at a sane 0.4–0.9%. **The 10–12× is a function of how many feed boundaries a name has lived
through, not of the name.** It is also a hard-invariant-8 violation, and `training_bars` already keeps a
private copy of the series precisely because the runtime one is "sim-contaminated at the tail" — someone
worked around this once already instead of fixing it.

## Change (ADR-0073)

Tag every recorded close with the stream that produced it, and never take a return across a handover.
`daily_close` gains `feed_mode` with PK `(day, instrument, feed_mode)`; both session writers stamp
`Provenance.mode()` like the `firm_equity` write beside them; the bootstrap history is tagged `SEED` —
reference history loaded before any session ran, the prior every mode starts from. Readers admit the
running mode's rows plus `SEED`, and take a return **only between two closes from the same stream** —
that second rule is what actually kills the phantom, since filtering rows alone leaves the boundary pair.
History is re-tagged deterministically from the platform's own record (`firm_equity.feed_mode`), never
from a rule about prices; session-era days whose mode cannot be established are dropped as inadmissible
observations. I dry-ran the migration in a rolled-back transaction: it leaves a clean 1,557-day seed
block, 4 LIVE days and 1 SIM day, so VaR keeps a full window and vol stays measured — just correct.

No risk formula, estimator, dial or gate changed; the deterministic floor is untouched. This changes only
**which observations are admissible**, which is why it carries no number of its own.

**What to expect, honestly:** with exposure at zero this cannot move PnL this cycle either, and I expect
another ⚠️ "no material change" — say it up front rather than dress it up. What it buys is that when
`reversion` earns its keep and the gate opens, every position is sized against a real σ instead of one
ten times too large, and the VaR the owner watches stops counting a feed switch as a market crash.
