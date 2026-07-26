# ADR-0071: Continuous forecast sensors warm-restart from the durable mark history

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** signals, fusion, operations, warm-restart

## Context

The two continuous forecast sensors — the EWMAC `trend` sensor (ADR-0066) and the range-position
`reversion` sensor (ADR-0070) — hold all of their state in heap and rebuild it from zero every time
the JVM starts. Both are deliberately silent until their windows are full **and** their scale
estimator has absorbed a warm-up sample, because ADR-0066 paid for the alternative: dividing by a
one-observation anchor pins a fresh name at the forecast cap, i.e. maximum conviction at the moment
the sensor knows least. That warm-up is counted in evaluation cycles, so in wall clock it is:

| sensor | cadence | prices needed before it speaks | wall clock |
|---|---|---|---|
| `trend` | 5s | `slowSpan + 1 + normSpan/2` = 64 + 1 + 128 = 193 | **~16 min** |
| `reversion` | 10s | `rangeSpan + 1 + normSpan/2` = 120 + 1 + 120 = 241 | **~40 min** |

The improvement loop (ADR-0063) redeploys the app on every accepted change; the observed interval
between restarts over the last five cycles is **14–55 minutes**, most of them under 25. Set the two
tables side by side and the consequence is arithmetic, not opinion:

- `reversion` **never speaks at all.** Its warm-up exceeds the typical process lifetime, so it
  publishes no view, contributes nothing to the fusion cross-section, and — this is the expensive
  part — accumulates **no telemetry about itself**, so the ADR-0064 edge gate can never judge it. The
  sensor ADR-0070 added to give the desk a view in the CHOP regime is, in deployment, dead code.
- `trend` speaks only in the last few minutes of each process life, and always from a scale estimate
  built on the **minimum possible sample**. Every one of its resolved observations was produced by a
  barely-warmed sensor. Its measured expectancy — the number the edge gate uses to keep the desk
  reduce-only — is therefore measured on a permanently cold-starting instrument.

This is a property of the deployment cadence, not of either signal, and it silently starves the whole
measurement chain the desk's ability to trade depends on. Meanwhile the *same* price series the
sensors consume live is **already durable and already survives a restart**: the LMDB mark history
behind the interactive chart (ADR-0014 derived data, ADR-0017), 12h retention, ~1 Hz conflated marks,
ordered range scans. The data needed to boot a calibrated sensor is on the box, unused.

## Decision

We will **warm each continuous forecast sensor from the durable mark history on first sight of an
instrument**, before it consumes its first live mark (`SensorWarmup`).

- The seed is read from the same `md.marks` series the sensor consumes live, thinned to **one price
  per evaluation interval** — replaying every 1 Hz mark would define the sensor's windows over a
  different horizon than live operation does.
- The number of prices requested is read off the sensor itself (`warmupSamples()`), so the requirement
  is never restated by the caller and cannot drift from the sensor's actual arithmetic.
- The walk runs newest-first, so the seed always ends at the present, and **stops at a hole** wider
  than 30 evaluation intervals: a redeploy blip (seconds) is bridged, a genuine outage truncates the
  seed rather than fabricating a price jump across it.
- Seed prices go through the sensor's ordinary `update` path but are **never recorded in the signal
  telemetry** — a historical price is not a call the desk made, and counting it would fabricate track
  record for a source the edge gate is about to judge.
- The mark-history store is **namespaced by feed mode** (`data/ui-history/<sim|live|replay>`). Replaying
  it into a sensor makes invariant 8 load-bearing where it previously was not: a seed built from the
  other mode's prices is exactly the cross-mode aggregation ADR-0029 forbids.

No sizing, gate, threshold or floor changes. This ADR changes *when a sensor is calibrated*, not what
it says or what the desk does with it. With no history available (in-memory profile, tests, first boot
after the namespace change) every sensor cold-starts exactly as it does today.

## Alternatives considered

- **Shorten the sensors' spans so the warm-up fits inside a process lifetime.** Rejected: it fixes
  the symptom by damaging the signal. Warm-up is `(window + normSpan/2) × interval`, so fitting a
  ~15-minute lifetime means a range window of a few minutes — a different, noisier statistic chosen
  for an operational reason, and the horizon would have to be re-cut every time the deploy cadence
  moved.
- **Persist each sensor's internal state (EWMAs, windows, scale) to LMDB and restore it.** Rejected
  for now: it is more machinery, it makes every sensor's private state a serialisation format that
  must be versioned, and a stale or schema-drifted blob restores a *wrong* calibration silently —
  where a replayed price series is self-evidently correct because it is the same input the sensor
  would have seen. Revive if a future sensor's state is not reconstructible from prices alone.
- **Seed from the stored daily-close history** (`daily_close`, 1571 days, already in process).
  Rejected: daily bars are the wrong sampling frequency by three orders of magnitude, so the scale
  estimator would boot calibrated to daily volatility and then spend a full normalisation span
  un-learning it — worse than cold.
- **Slow the improvement loop down so sensors have time to warm.** Rejected: it trades the desk's
  iteration rate for a defect that has a direct fix, and it leaves the sensors cold on any other
  restart (deploy, crash, config change).

## Consequences

- `reversion` gets its first opportunity to publish a view and therefore to be *measured*. It still
  arrives with no evidence and no privilege: it must earn a cost-beating measured expectancy before
  the ADR-0064 gate lets it size anything, and while the gate is reduce-only this change cannot
  increase exposure at all.
- `trend`'s readings become properly scaled from the first mark. **This is a genuine downside as well
  as an upside:** its existing resolved observations were generated under the old, cold-start
  behaviour, so the telemetry now mixes two regimes of the same source and its measured expectancy
  will be a blend until the new observations dominate. We accept that rather than discarding history.
- The chart's stored history is reset once by the feed-mode namespace change. The `md.marks` boot
  replay refills the window within seconds of start-up, and the first boot after this change seeds
  sensors from a partial window — full warm restart from the second boot onward.
- A new coupling: the fusion layer now reads the ui-gateway's mark history (through a narrow
  functional interface, adapted at the composition root, so `app.fusion` holds no ui-gateway type).
  If that store is ever repurposed or its retention shortened below a sensor's warm-up span, sensors
  quietly return to cold-starting — a silent degradation, mitigated only by the per-instrument
  "warmed N from M stored prices" log line.
- Related: ADR-0014 (derived state on local disk), ADR-0017 (the chart the store exists for),
  ADR-0029 / invariant 8 (feed-mode namespacing), ADR-0064 (the gate this feeds), ADR-0066, ADR-0070.
