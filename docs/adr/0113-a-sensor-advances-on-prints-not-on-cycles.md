# ADR-0113: A continuous sensor advances on PRINTS, not on cycles

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** trading, fusion, market-data, signals

## Context

The mark cache is a last-value conflation point (ADR-0014): it holds one price per instrument and the
adapters refresh it on their own poll cadence, whether or not the tape printed. Both continuous forecast
sensors — the ADR-0066 EWMAC trend sensor and the ADR-0070 range-reversion sensor — read that cache once
per evaluation cycle and feed whatever they find into their windows. Each already carried the comment
"never advance the sensor's windows on a repeated stale price", guarded by `MarkSnapshot.stale()`.

That flag cannot answer the question. It is a **warm-load marker**: `MarkCache.loadStale` sets it at boot
and the first live `update` clears it, permanently. It says "this price came from LMDB and no tick has
arrived yet" — not "this price is old". So the guard fires for a few seconds after every restart and is
inert for the rest of the process life, including for a name whose tape stopped hours ago. Live on this
desk at 22:00Z: the seven Alpaca equities carry ingest ages of 180–558 ms against provider clocks
**122.8 minutes** old (the 20:00Z cash close), ES and NQ 62.8 minutes, and every one of them reports
`stale: false`. The 10 s reversion sensor has therefore been fed the same closing print several hundred
times per name, as if each were an observation.

The cost is not silence, it is corruption, in the two places that matter. **The scale estimator:** both
sensors divide their raw reading by an EWMA of its own typical magnitude, and a run of repeated prices is
a run of zero-magnitude readings that drags that denominator toward zero — so when the tape resumes, an
ordinary move is divided by a near-zero scale and the name reports an extreme conviction. That is exactly
the failure ADR-0066 was written for (there the denominator was an arbitrary one-observation anchor; here
it is an anchor decayed by observations that never happened), and it arrives at the session open, on the
source carrying the dominant fusion weight. **The measured expectancy that gates money:** every published
reading is booked as a directional call in the signal telemetry, so a frozen name books a fresh call every
cycle off the same dead price and every one resolves at exactly zero realised return — structural zeros
entering the cohort statistics the ADR-0075 edge gate reads, shrinking a source's standard error with
observations that carry no information. That is manufactured significance in the test that decides whether
the desk may put risk on.

There is a third, quieter symptom: `ForecastRegistry` expires a view after a freshness window precisely so
"a source that has gone quiet drops out rather than lingering as a phantom vote", and the sensors were
re-submitting a view off a dead price every cycle, so that window could never fire.

## Decision

**We will admit a mark to a continuous sensor only when the market's own clock has advanced.** A new
`PrintClock` records, per sensor and per instrument, the provider timestamp of the last mark consumed, and
`TrendForecastLifecycle` / `ReversionForecastLifecycle` skip any mark whose provider timestamp is not
strictly newer. The `stale()` check stays, narrowed to what it actually means (warm-loaded, not yet
refreshed — invariant 4).

Provider time is the market's honest clock (invariant 5); ingest time only reflects our poll cadence,
which is why it cannot answer this. The rule introduces **no dial**: it is not an age threshold, so there
is no "too old" to choose and no number that needs provenance. It is the parameter-free question "did the
tape print since I last looked?", so a live feed, a 15-minute-delayed feed, a replay and a simulated clock
all read correctly with no edit (invariant 9). It also makes ADR-0071's stated invariant true: the
warm-restart seed walks the durable mark series, which is keyed by provider time and holds one point per
distinct print, so the seed has always advanced per print while the live path advanced per cycle — the two
halves of a sensor's history were different series, and now they are one.

## Alternatives considered

**Give `MarkSnapshot` a real freshness predicate (provider age > N seconds).** Rejected: N is a number
that gates risk and would have to be chosen, per asset class, against feeds whose normal print interval
spans four orders of magnitude on this desk (sub-second FX to a daily Treasury par curve). The strictly
correct statement needs no threshold, and a threshold would additionally be wrong for a name that prints
legitimately but slowly.

**Widen `SensorWarmup`'s gap tolerance so the seed bridges the halt.** Rejected — it treats a symptom and
in the wrong direction. The seed truncating at a hole is correct behaviour (it refuses to fabricate a jump
across an outage); the observed seeds of 1 of 241 samples on every equity are the *consequence* of a real
hour-scale hole at the newest end of the series, not a bug in the tolerance. Bridging it would feed the
sensor the jump instead of the duplicates — a different fabrication.

**Suppress the telemetry record only, and leave the sensors advancing.** Rejected: it fixes the expectancy
statistic and leaves the scale estimator decaying, which is the half that puts on the position. Two guards
for one cause, and the one omitted is the expensive one.

**Stop the sensors when the desk's calendar says the market is closed.** Deferred, not rejected: a session
calendar is genuine reference data and would say more than the tape can (a halt is not a close). It needs a
per-instrument trading-hours source the platform does not have, and it would be a *second* answer to a
question the print clock already answers exactly. Revived if the desk acquires session calendars.

## Consequences

- **Positive:** a sensor's state after any number of cycles on a halted tape is bit-for-bit where its last
  real print left it — asserted as a test against a counterfactual sensor whose session never halted, so a
  halt is invisible to a gated sensor and demonstrably not invisible without one. The signal telemetry
  books one call per genuine print instead of one per cycle, so a source's measured expectancy and standard
  error stop being diluted by observations that never happened. `ForecastRegistry`'s freshness window
  becomes reachable, so the desk stops holding views on names that are not trading.
- **Negative, and real:** the desk will hold **no view at all** on a name whose tape has stopped, so
  overnight and through any halt the equities drop out of the planned cross-section entirely. A name with
  no fresh view is planned flat, which under the ADR-0065 no-view path is an *exit* — so a position held
  into a halt is targeted flat rather than held (the order path already rejects on absent market data, and
  the direction is reduce-only, but it is a behaviour change and not a small one). Warm-up also becomes
  slower for genuinely sparse names, which now accumulate at their own print rate rather than at the
  cycle rate; on a live tape the two are close (AAPL prints every ~12 s against a 10 s cadence), but a
  thin name will take longer to speak, and one whose print interval exceeds the registry's freshness
  window can never hold a view. That is the honest answer for a name the desk cannot observe, but it
  narrows the tradable universe rather than widening it — this change lowers exposure, it does not raise it.
- **Follow-ups:** the same defect exists downstream, where `FusionLifecycle` feeds `StreamVolatility` (the
  ADR-0086 risk cut's σ) and `StreamCovariance` (ADR-0089) from planned-target prices on the cycle clock —
  a σ estimate decayed by non-prints makes a name look riskless to the control that is supposed to stop it.
  Not folded in here because it is a different plumbing point with different semantics (targets, not
  marks); recorded in the deferred register.
