Last cycle's warm restart never actually ran: it read the wall clock against a store keyed by feed
time, so on a feed running half an hour behind, both sensors booted on 4 prices instead of 193 and 241
— now anchored on the feed's own clock (ADR-0071 correction).

## Situation (live endpoints, read first)

**Money — flat, not bleeding.** Total PnL `-$867.28`, `-$0.10` since the last run and `+$9.36` across
the last three. Both moves are inside the scorer's noise deadband. There were **zero orders in the
window** (last fill 16:57, report ran 17:45), so 100% of that move is mark drift on two untouched
single-share stubs: **market, not change.** We are far off the ≥1%-per-3-iterations target, and the
reason is not a loss — it is that the desk is forbidden to open a position.

**Risk — minimal, and the DANGER flag is a threshold artifact.** Gross `$785.99`, net `$56.85`. The
"EXPOSURE RISING" flag fired on `+$0.79` of mark drift against a `$786` book — noise, not a risk build.
Historical VaR95 is `$6.87`; the firm drawdown breaker is untripped and nowhere near it. This is not a
live danger state, so de-risking is not the right move — there is nothing meaningful left to cut.

**Cause — last cycle's change scored ⚠️ MIXED "no material change", and I can now show why.** ADR-0071
was meant to boot the trend and reversion sensors warm from the durable LMDB mark history. It didn't.
The app log has been saying so on every boot — `reversion sensor warmed AAPL from 4 stored prices
(needs 241)`, and the same for trend against 193 — and `reversion` has still never once appeared in
`fusion_targets.weights` or `signals_telemetry`. That is exactly the absence-is-the-tell check the
findings memory told me to run.

## Diagnosis — two clocks, one silent failure

The seed asked the store for `System.currentTimeMillis() - lookback`, but the store is keyed by
**provider** timestamps. On this feed provider time runs about half an hour behind the wall clock — the
lag grows monotonically across the last four report snapshots, and the live `/api/history` series ends
~1,910 s behind wall-clock now. So the lower bound sat almost entirely in the feed's *future* and
admitted only a ~20-second sliver of the series: 4 samples. Once a feed's lag exceeds the lookback the
seed returns nothing at all, and the sensor cold-starts forever while logging that it succeeded.

This is not a sim quirk. Invariant 5 exists because provider and ingest time differ; `/api/feeds`
reports `delayed`/`delaySeconds` because a lagging feed is normal — 15-minute-delayed equity data is an
industry standard and any replay lags arbitrarily. A wall-clock window on a provider-keyed store is a
bug on every feed.

## Change

Anchor the seed window on the provider timestamp of the mark being processed: one line per sensor, same
thinning, same gap tolerance, same replay path, no money or risk number touched. Filed as a correction
on ADR-0071 rather than a new ADR — the design stands, only its clock source was wrong. Two hygiene
changes ride along, because this stayed invisible for two cycles: a sensor still cold after seeding now
logs at **WARN**, not INFO; and the report now captures `logs/jethro-app.log` alongside
`docker compose logs`. The app runs on the host, so until now **none of the platform's own warnings ever
reached this report** — the "warmed 4 of 241" line was written every boot and read by nobody.

## What to expect, honestly

This cannot move PnL or exposure by itself. The ADR-0064 edge gate stays reduce-only until some source
measures a cost-beating expectancy, so the scorer will most likely read another "no material change".
What it buys is the ability to *measure*: trend should boot near-warm from the history now accumulating
in the feed-mode namespace, and reversion should finally publish a reading and begin earning or losing
its own track record. If reversion is still absent from the weights map next cycle, the warm-restart
approach is the wrong lever, and I should stop paying for it and cut a shorter-warm-up sensor instead.
