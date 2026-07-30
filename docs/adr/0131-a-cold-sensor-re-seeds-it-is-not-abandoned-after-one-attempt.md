# ADR-0131: A cold sensor re-seeds — the warm restart is not a single attempt at boot

- **Status:** Reverted
- **Date:** 2026-07-30
- **Reverted:** 2026-07-30 — see below
- **Deciders:** Oleg
- **Tags:** trading, fusion, sensors, warm-start, availability

> **REVERTED.** `scripts/score-change.py` scored the implementing commit `efccc6502` **❌ BAD** at the
> close of its ADR-0116 evaluation window; the ledger row carries the computed vector and the verdict.
> The scorer's own `git revert` hit a conflict on the loop's report files and did not land, so the code
> was reverted manually in a later cycle (this change), leaving this record in place rather than deleting
> it. The mechanism is **not** to be re-attempted as specified. What the five cycles of live observation
> established is recorded under "Why it was reverted" below; a future ADR that wants this behaviour must
> supersede this one and address that defect, not restate the original design.

## Context

ADR-0071 boots a continuous sensor already calibrated by replaying the durable recent mark series into
it, so a redeployed desk does not spend every process lifetime re-learning a warm-up it never finishes.
ADR-0114 and ADR-0117 refined *what* that replay reads. All three left one thing unstated, and it is the
thing that broke: the replay runs **on first sight of a name, and only then**.

First sight is boot. Boot is the single worst moment to read the store — the seed exists precisely
because the process just restarted, and the restart is very often preceded by the same discontinuity
that emptied the store's recent tail: an outage, a weekend, a pre-market start, a redeploy after the
close. `SensorWarmup` then does exactly the right thing — it declines to walk across the hole and warms
from the contiguous tail only — and hands the sensor two prices against a warm-up of hundreds. Under the
old rule the sensor was abandoned there for the whole life of the process, however much history the
store subsequently accumulated. The one read that mattered was taken at the one moment it was guaranteed
to fail.

**Observed live, 2026-07-30.** The JVM restarted at 07:47 ET after a 4h45m outage (the ADR-0129 migration
failure). Every equity's seed, taken in that first minute, is in `logs/jethro-app.log`:

```
07:47:37  trend sensor still cold for BAC  after seeding 0 of 193 stored prices
07:47:52  reversion sensor still cold for AAPL after seeding 1 of 241 stored prices
07:47:52  reversion sensor still cold for AMZN after seeding 1 of 241 stored prices
08:56:19  risk-cut σ sensor still cold for GOOG after seeding 0 of 121 stored prices
```

An hour and three quarters later — with the store holding 378 fresh AAPL prints spanning 81 minutes at a
1.1-second median, far more than any of those sensors needed — `/api/fusion/targets` reported
`forecastScalars` containing only `reversion` and `xsreversion`. The trend source had published nothing
for any name since boot; the per-name reversion source had published only for `NQ`, the one instrument
whose tape runs overnight and whose seed therefore returned `232 of 241`. The `warmed` count across the
trend, reversion and index-trend lifecycles for the whole process was **zero**.

The cost is not a degraded signal, it is a frozen desk, and the mechanism is short:

1. Only one source publishes per name (`xsreversion`, which needs a handful of prints).
2. ADR-0124 sets the agreement scalar to `0` at one effective source — the dispersion is unestimable, and
   the honest scalar for an uncorroborated view is nothing.
3. So **every** `combinedForecast` in the book was `0.0`, every `targetQty` `0`, `orders_day.total` `0`.
4. Separately, ADR-0126 refuses to OPEN a name whose σ sensor has not warmed — so even a corroborated
   view would have been vetoed, for the same reason, from the same failed boot read.

The book sat flat at zero gross against a $1.5M firm cap for the whole session, and the improvement
ledger recorded the change under measurement as `gross 0 → 0` — noise, because the desk could not act at
all. ADR-0124 anticipated this state and called it "self-healing, not a ban: the name sizes again as soon
as a second sensor warms". That is true, and it is exactly the promise a once-only seed cannot keep.

Doing nothing means every restart — and this desk redeploys on a 30-minute improvement cadence — rolls
the dice on one history read, and a bad roll costs the whole process lifetime.

## Decision

**A sensor that is still cold re-seeds from the durable store until it warms. A warm sensor is never
re-seeded.**

- `SensorReseed` owns the policy for all four seed sites (trend, per-name reversion, index trend, and the
  ADR-0086 σ sensor behind the risk cut and the ADR-0126 open veto): seed on first sight exactly as
  before, then re-attempt every *cadence* sightings while cold, and retire the name permanently once its
  sensor reports warm.
- **The cadence is the sensor's own `warmupSamples()`** (`warmupPrices()` for the σ sensor) — a count of
  samples read off the sensor, not a configured dial. No number is introduced (invariant 7 / ADR-0016).
  A cold name therefore costs one bounded history read per warm-up span, and recovers within one warm-up
  span of the history becoming available.
- **The replay goes into a state the caller has just dropped** — new `forget(instrumentId)` on
  `EwmacTrendForecaster`, `RangeReversionForecaster` and `StreamVolatility`. The store is fed from the
  same mark stream the sensor consumes, so it already contains every print the sensor absorbed; replaying
  on top would count those prices twice. Each `forget` drops the per-name state **whole**, because every
  field of it (the EWMAs, the step vol, the Donchian and efficiency-ratio windows, the variance and its
  warm-up counter, `lastPrice`) is estimated jointly from one price sequence — clearing the variance but
  keeping `lastPrice` would manufacture a return across the reset boundary.
- **The reset is confined to cold names, and that is what makes it safe.** A cold forecast sensor
  publishes no view, so nothing downstream is sizing on it. An unmeasured σ arms no trailing cut and,
  under ADR-0126, gates open no position — so there is no live stop to move underneath a live position.
  A warm sensor is left alone forever.
- `StreamVolatility`'s ADR-0116 print clock is deliberately **not** reset: it records the last provider
  timestamp consumed, and forgetting it would let a republished mark be read as a fresh print.

Nothing about what the seed reads changes — `SensorWarmup`, its ADR-0114 consumption step and its hole
tolerance are untouched. This ADR changes only *how often the read is attempted*.

## Consequences

- **Intended:** the desk recovers its forecast breadth within one warm-up span of the store having the
  history, instead of within one process restart that happens to land on a good moment. A restart into an
  outage, a weekend or a pre-market start is no longer a lost session. Concretely, the state that froze
  the book — one source per name, agreement `0`, every target `0` — is now transient rather than terminal.
- **Honest cost:** one extra bounded LMDB range scan per cold name per warm-up span (for the trend sensor
  at its 5-second cadence, roughly one scan per name every sixteen minutes, and only while cold). A name
  the store genuinely cannot warm — a newly promoted instrument with no history — pays that scan
  indefinitely; it is bounded, off the tick path, and it stops the moment the name warms.
- **A re-seed can in principle hand back a shorter series than the live sensor had accumulated**, if the
  store's contiguous tail is shorter than what the sensor consumed live. Accepted deliberately and not
  guarded against: the policy only ever touches a sensor that is publishing nothing, so the worst case
  discards state that was earning nothing and would have kept earning nothing. Adding a comparison would
  mean tracking a live sample count on every forecaster to protect a value that is zero by construction.
- **Unaffected:** every gate between a forecast and a fill — the ADR-0064 edge gate, the ADR-0059
  conviction floor, the ADR-0049 backtest-support veto, ADR-0124's agreement scalar, ADR-0126's open veto,
  the pre-trade guardrail and the firm breaker. This ADR lets sensors speak; it relaxes nothing that
  decides whether speaking becomes a trade. Sim and replay are untouched (invariant 9) — the policy reads
  only each sensor's own warm-up length and its own warm/cold state, on whatever stream it is given.
- **Supersedes nothing.** It completes ADR-0071, whose stated goal — "a sensor whose warm-up exceeds the
  process lifetime never speaks at all" — a single boot-time attempt could not deliver.

## Verification

`/api/fusion/targets` must show names with `sources ≥ 2`, a non-zero `agreement` and a non-zero
`combinedForecast`; `forecastScalars` must contain a `trend` entry; and the app log must contain
`trend sensor warmed …` / `risk-cut σ sensor warmed …` lines at timestamps well after boot. Today all
four of those read empty, zero, absent and absent.

## Why it was reverted

The verification above passed in the narrow sense — the retry does fire, and over the evaluation window it
warmed two sensors (PG, then PFE) that the boot-only rule would have left cold for the life of the process.
But the pre-registered test written alongside it — *no negative wave-over-wave seeded-count delta anywhere*
— **failed on three consecutive runs, on three distinct JVMs, with a different set of victim names each
time** (read from the app log's `still cold … N of M` lines). The consequence bullet above that accepted
this in advance is where the error is, and it is worth naming precisely, because the reasoning is seductive
and wrong:

> *"the policy only ever touches a sensor that is publishing nothing, so the worst case discards state that
> was earning nothing and would have kept earning nothing."*

The second clause does not follow from the first. A cold sensor at 191 of 193 prints is indeed publishing
nothing **now**, but it is two prints from publishing; the state it holds is not worthless, it is nearly
complete. `warmWhileCold` calls `forget()` unconditionally *before* it knows what the replay will return,
and `SensorWarmup` re-derives both the window start and the thinning stride from the current mark each
time — so a name that had walked to the edge of warm could be reset to a materially shorter series and
have to start again. Repeated every warm-up span, that is a mechanism that can hold a name cold
indefinitely: the opposite of the ADR's purpose. A separate class of names — the rate/swap instruments —
seeded the full count in **every** wave and stayed cold regardless, so for them the retry was pure waste;
sample count was never their binding predicate.

Both facts point at the same missing condition (replay only when the new series is *strictly longer* than
the best this name has held), and that guard was queued rather than shipped, because the ADR-0116 window
was still measuring the unguarded version. The window closed ❌ BAD first. The verdict stands and the code
is out; the diagnosis is preserved here so it is not re-derived from scratch, but a future ADR must earn
the behaviour on its own measurement rather than inherit this one's.
