# ADR-0114: The warm-start seed is counted in the name's OWN prints, not in our poll cadence

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, data, signals

## Context

ADR-0113 stopped the continuous sensors advancing on a republished last-value price: a mark is fed to a
sensor only when its provider timestamp is strictly newer than the last one that sensor consumed. That is
right, and it changed the unit the sensors accumulate in — a warm-up of 241 samples is now 241 **prints**,
not 241 evaluation cycles.

The ADR-0071 warm-start seed was not moved with it. `SensorWarmup.seedPrices` derives two quantities from
the sensor's *poll* cadence: how far back to read history (`interval × samples × 2`) and how large a break
in the series stops the walk (`interval × 30`). Both silently assume the tape prints at least as often as
we poll — the exact assumption ADR-0113 removed from the live path.

On this desk that assumption is false for most of the day. Measured live at 23:00Z from `/api/history`,
the median inter-print gap is 19.9 s on the Treasury curve, 90 s on NQ, 778 s on GBPUSD and 1,199 s on ES,
against a 10 s reversion cadence and a 5 s trend cadence. So for ES every ordinary 20-minute print interval
exceeds the 300 s tolerance and reads as an outage: the walk breaks at the first one and the seed is **1
sample of 241** — which is precisely what the logs say for ES, GBPUSD, NQ and every equity. The Treasury
curve fails the other way: its gaps clear the tolerance but 241 prints at ~20 s need 80 minutes of history
and the lookback asks for exactly 80, so it seeds **147 of 241**. Not one non-equity name can warm, and
because the live path now accumulates at that same print rate, none of them ever will — the ADR-0071
failure ("a sensor whose warm-up exceeds the process lifetime never speaks at all") re-entered through
ADR-0113's door. Live consequence: `reversion` carries fusion weight 2.90 and is the only source with
measured edge (t = 3.55), and `/api/fusion/targets` publishes `instruments: 0`. The desk holds no view on
anything, all night, every night.

Doing nothing means the desk can only ever form a view during the equity cash session, on the seven names
whose tape happens to print faster than we poll.

## Decision

We will measure the seed's lookback and its hole tolerance in the **step at which the live sensor actually
consumes that name** — `max(poll interval, the name's median inter-print gap)` — read from the name's own
stored series, instead of in the poll interval alone.

- The consumption step is `max(intervalMillis, median gap)` because the live sensor takes at most one mark
  per cycle **and** at most one per print (ADR-0113): it advances at the slower of the two clocks.
- Lookback becomes `step × samples × 2`; hole tolerance becomes `step × 30`. Both constants are unchanged
  — only their unit moves. The thinning rule stays `≥ intervalMillis`, which already yields
  `max(poll, actual gap)` per accepted point and is therefore already correct.
- When the first read is too narrow to see the name's cadence (`step > interval`), history is re-read once
  in the widened window and the step re-measured. First sight of an instrument only, so at most one extra
  range read per name per process life.
- The median, not the mean: print gaps are heavy-tailed and a median needs no outlier rule chosen.
- No dial is added and nothing is configured. The statistic is re-read from the running stream, so a live
  feed, a delayed feed, a replay and a simulated clock are each read on their own terms (invariant 9).

**This is not "widen the tolerance so the seed bridges the halt"**, which ADR-0113 considered and rejected.
The tolerance is a multiple of the name's *own* typical interval, so a 12 s equity tape that stops for three
hours at the cash close is still a hole and still truncates the seed — pinned as a test. What changes is
only that a 20-minute gap on a contract that prints every 20 minutes stops being called an outage.

## Alternatives considered

**Read the store's whole retained window and skip the cadence estimate.** Correct in principle and simpler,
but the recent-mark store retains 12 h at ~1 Hz — ~43,000 points per instrument, ~1.5M `BigDecimal` parses
across 35 names on first sight. Rejected on cost on this box, not on correctness.

**Configure a per-instrument print cadence in refdata.** Exact, but it is a number per name per session
that must be maintained and would be wrong the moment liquidity changes — and the stream already states it.
Rejected: a measurement will do, and CLAUDE.md's rule is that a number needs a source.

**Lower the sensors' warm-up spans so sparse names warm sooner.** Rejected: those spans define what the
signal *is* (a 20-minute range, an EWMAC16/64), so shortening them to fit a data problem changes the signal
rather than the sampling, and it would degrade the fast names that work today.

**Leave it and let the equities carry the desk.** Rejected: it concedes the ~13 h/day outside the cash
session and every non-equity name permanently, on a desk whose PnL growth flag already reads stale.

## Consequences

- **Positive:** names whose tape is slower than the poll can seed and warm at all — NQ (90 s) and the rates
  curve (20 s) reach a full seed; GBPUSD/AUDUSD/EURUSD/ES seed from as much of their series as the 12 h
  retention holds instead of one point. The `reversion` source can publish a cross-section outside cash
  hours. No name that works today changes: with `median ≤ interval` the step is the interval and every
  derived quantity is bit-for-bit what it was (pinned as a test).
- **Negative, and real:** on a name with only two or three stored points the median is a poor estimate of
  its cadence, and an over-large step could bridge a break that is genuinely an outage — feeding the sensor
  a jump. The exposure is bounded by the store's 12 h retention but not eliminated. It is also one more
  history read per slow name at first sight. And warming these names is not free money: it **raises**
  exposure by admitting names the desk currently cannot see, and if the reversion edge does not survive
  contact with them the loss is larger, not smaller.
- **Not affected:** nothing here prices, sizes or gates money — the class handles a price series and a
  count of samples, and every downstream gate (ADR-0075 edge gate, ADR-0059 conviction floor, ADR-0049
  backtest veto, pre-trade guardrail, ADR-0027 firm breaker) is untouched. Prices stay exact decimal
  (invariant 1); gaps are timestamps.
- **Follow-up:** `jointSeedSamples` (the ADR-0089 covariance seed) carries the same mis-denomination — its
  bucket grid and hole test are in poll intervals. It needs the *fastest* member's cadence rather than each
  name's own, which is a different derivation; deferred, and in the register.
