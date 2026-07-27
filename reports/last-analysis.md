Every equity's tape stopped at the cash close two hours ago, and the sensors never noticed — the "skip a stale mark" guard is a warm-load flag that is false forever after the first tick, so they have been eating the same closing print several hundred times per name as if each were an observation.

*Every figure below is quoted from the live endpoints, `logs/report.md` or the ledger; none is authored
here (invariant 7 / ADR-0016 — the scorer owns every number that gates money).*

## Situation (live endpoints, read at the top of this cycle)

**Money — flat, and the "bleeding" flag is dust.** The SITUATION header reads total PnL $5,654.13, down
$0.07 since last run and $72.84 over three. But the book holds nothing: gross exposure $0.00, net $0.00,
every position row zero, no order since 19:54Z. A $0.07 move on a book with no positions is neither a
market move nor a consequence of any code — the $72.84 over three runs is the 19:00–20:00 flattening
crystallising, already scored. PnL growth is off the ≥1%/3-iteration target and the run-status flags say
stale, which is correct: the desk is not losing money, it is not doing anything.

**Risk — nothing on.** Gross and net both $0.00, VaR/ES `no positions`, breaker not tripped and nowhere
near. There is no danger state to override this cycle.

**Cause — last cycle's change deployed and is downstream of the real problem.** ADR-0112 (Roll effective
spread for unpriced names) reached the JVM: the per-name cost map has grown from 8 entries to 12, with
ZB/ZF/ES now at the floor and NVDA/AMZN/SAP newly priced. The scorer marked it ⚠️ MIXED, no material
change. That verdict is right and the reason is not the cost ladder: `/api/fusion/targets` reports
`instruments: 0` and `targets: []`. Last cycle there were eleven targets blocked by cost; this cycle there
are **no targets at all**. Nothing reached the cost gate, so nothing ADR-0112 did could show up.

**Danger — no,** so this cycle is free to fix a cause.

## What I found, and it is not the cost ladder

The desk publishes no forecasts because the only source it trusts publishes nothing. `reversion` carries
weight 2.90 and the only significant measured edge (`avgReturnBps 1.856`, `t = 3.55`); ADR-0111 correctly
stood `trend` and `momentum` down to 0.0 on their measured losses. So `reversion` cold ⇒ the cross-section
is empty. And the WARN log says why, on every tradable name: *"reversion sensor still cold for AAPL after
seeding 1 of 241 stored prices"* — one sample out of 241, while the rates names seed 132.

I pulled the stored series to separate a rung that never fires from one that fires and finds nothing.
AAPL's history is 1,926 points at a 12 s median cadence, and then a single **59.7-minute hole** immediately
before the newest point; JPM 56 minutes, ES 57.7. The seed walks newest-first and stops at a hole wider
than 30 intervals, so it truncates after one point — mechanically, on every equity. Then `/api/marks`
explained the hole: the seven Alpaca equities carry **provider clocks 122.8 minutes old** (the 20:00Z
cash close), ES/NQ 62.8 minutes — against **ingest ages of 180–558 ms**. The tape has stopped; the cache
is republishing the close.

The defect is what happens next. Both sensors carry the comment *"never advance the sensor's windows on a
repeated stale price"*, guarded by `MarkSnapshot.stale()` — and that flag is a **warm-load marker**:
`MarkCache.loadStale` sets it at boot, the first live tick clears it, permanently. It reports `false` for
all seven frozen names. The guard is inert in precisely the situation it was written for — the same shape
as last cycle's Rule 23. So the 10 s reversion sensor and the 5 s trend sensor have been fed the same
dead price hundreds of times per name, and that is not silence, it is corruption in the two places that
matter: it decays the scale estimator both sensors divide by toward zero (so the first real move at the
reopen is divided by a near-zero denominator and reports an extreme conviction — the ADR-0066 pin reached
from the other side, at the open, on the dominant-weight source), and it books a telemetry call every
cycle off a price that never moved, each resolving at exactly zero, feeding structural zeros into the
cohort standard error that the ADR-0075 edge gate uses to decide whether the desk may put risk on.

## The change

ADR-0113: a continuous sensor advances on **prints, not on cycles**. A new `PrintClock` admits a mark only
when its provider timestamp is strictly newer than the last one that sensor consumed for the name. No dial
— it is not an age threshold, so there is no number to choose and none to give provenance to; it is the
parameter-free question "did the tape print since I last looked?", which reads a live feed, a delayed
feed, a replay and a sim clock identically. It also makes ADR-0071's stated invariant true: the seed walks
a provider-keyed series with one point per print, so seed and live path are finally the same series.
Asserted against a counterfactual sensor whose session never halted — a gated sensor reads the reopen
bit-for-bit identically to one that never saw the halt, while the ungated one un-warms itself flat and
peaks several times higher on the same resumed prints. 545 app tests green.

**Honest expectation.** This will very likely score MIXED: the book is flat, the equity tape is shut for
the night, and the change *narrows* what the desk will look at rather than widening it — it lowers
exposure, it does not raise it. I am shipping it anyway because the loss it prevents is specific and
imminent rather than hypothetical: the reversion sensor's warm-up is 40 minutes of cycles and the JVM has
been up 42, so it is about to finish warming on ~250 identical prints and start publishing a calibrated-
looking view, at weight 2.90 with `mayIncrease: true`, on names whose last real trade was at the close.
Attribution for this window is neither market nor change — there were no positions to move either one.
