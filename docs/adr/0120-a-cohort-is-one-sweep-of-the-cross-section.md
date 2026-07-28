# ADR-0120: A cohort is one SWEEP of the cross-section, identified by its names — not by a clock gap

- **Status:** Proposed
- **Date:** 2026-07-28
- **Deciders:** Oleg
- **Tags:** trading, signals, statistics, edge-gate, risk

## Context

ADR-0077 made the *cohort* — one draw of the market — the unit the edge gate's standard error is
estimated in. The reason is Fama–MacBeth: the names a source calls in one pass over its cross-section
are correlated views of the same interval, not independent samples, so dividing their dispersion by
√n understates the standard error by √(1+(n−1)ρ̄). That standard error is the denominator of the
t-statistic in `EdgeGate`, which decides whether the desk may put risk on at all.

ADR-0077 identified a pass by a **clock gap** — `jethro.signals.cohort-window-seconds=60` — on an
explicitly stated assumption, still written into `SignalScoring`'s javadoc: *"23 names called in the
same 200ms burst"*. The dial's own comment records the same belief: *"chosen two orders of magnitude
above a burst's width"*.

**That assumption is false on this desk.** The trend and reversion sensors publish per name as each
name's mark updates, on a staggered scheduler. A single pass over the eight-name equity cross-section
takes minutes, not milliseconds. From the live `signal_observations` on 2026-07-28 (`trend`, 3600 s,
LIVE):

```
13:34:36 NQ    13:38:32 AAPL   13:42:47 AMZN   13:43:02 MSFT
13:43:32 NVDA  13:44:12 GOOG   14:01:17 JNJ    14:02:32 JPM     <- ONE pass, spanning 28 minutes
14:35:31 NQ    14:39:00 AAPL   14:43:01 AMZN   14:44:01 MSFT
14:44:01 NVDA  14:45:02 GOOG   15:02:45 JNJ    15:03:25 JPM     <- the next pass
```

Under the 60 s gap rule those two passes are cut into **eleven** cohorts. The gate believed it held
eleven independent draws of the market where it held two. Measured across every source and horizon in
the live book, the gap rule reports **≈2.2× more cohorts than there were passes**:

| source | horizon | gap cohorts | sweep cohorts |
|---|---|---|---|
| trend | 3600 s | 16 | 7 |
| reversion | 3600 s | 13 | 6 |
| trend | 900 s | 59 | 25 |
| reversion | 900 s | 52 | 20 |
| trend | 225 s | 131 | 68 |
| reversion | 225 s | 118 | 55 |

This is the **anti-conservative** direction on a control that governs exposure: more cohorts means a
narrower standard error *and* more degrees of freedom in the Student-t reference distribution
ADR-0081 installed precisely because df is what the verdict turns on at these sample sizes. It is
exactly the √(1+(n−1)ρ̄) understatement ADR-0077 was written to prevent, readmitted through the
grouping rule instead of through the averaging.

The deeper fault is that a gap rule measures **how fast the scheduler walks the universe**, not how
often the market was drawn. A sensor tuned to publish faster would have silently multiplied the gate's
apparent evidence without a single new observation.

## Decision

**A cohort is one sweep: a name appears at most once per cohort, and cohorts are contiguous in time.**

Equivalently — and this is what both implementations compute — a call that is the *k*-th on its own
name belongs to cohort *k* at the earliest, so:

```
  cohort(r) = max over rows up to r, in entry-time order, of occurrence(name, r)
```

where `occurrence(name, r)` is how many times that name has been called up to and including `r`. This
running maximum is the coarsest partition satisfying the rule.

The definition carries **no dial**: `jethro.signals.cohort-window-seconds` is retired, not retuned.
Nothing replaces it, because a sweep is identified by its own content.

It also handles a late-joining name without a special case. A sensor still warming (the cold-start
warnings on `TrendForecastLifecycle` / `ReversionForecastLifecycle`) makes its first call mid-pass;
occurrence 1 can never raise the running maximum, so it joins the pass in progress rather than opening
a spurious cohort. Symmetrically, a source calling one name repeatedly — NQ alone, hour after hour,
before the other sensors warmed — still gets one cohort per call, because those really are
independent draws.

Two implementations, one definition: `SignalScoring.sweepCohortMeans` is the reference and is pinned
by exact tests; `SignalTelemetryStore.resolvedCohorts` computes the same partition in SQL so the
transfer stays constant (ADR-0108). The occurrence count is taken *after* the ADR-0109 bad-print
predicate, so evidence removed from the estimator does not silently re-cut the sample it is estimated
from.

## Consequences

**The gate gets strictly harder to open on this book, and that is the point.** Fewer independent draws
means fewer degrees of freedom. Applied to the live readings, `trend` at 3600 s moves from 16 cohorts
with a mean of −2.41 bps to 7 sweeps with a mean of −8.40 bps: the source looks *worse*, honestly,
because Fama–MacBeth weights each draw equally and the merged passes no longer count eight times over.

**It does not open the gate.** The only reading whose t-statistic rises sharply under sweep grouping
is `momentum` at 3600 s (2 sweeps), and `jethro.fusion.edge-gate.min-sample=30` *resolved observations*
blocks it outright — momentum has 4. Every source that clears `min-sample` (`trend` and `reversion` at
900 s and 225 s) sits between t = −0.57 and t = +0.19 against the cheapest measured round trip. The
book was flat and reduce-only before this change and remains so; what changes is that the number the
gate will eventually act on is the number it should have been reading all along.

**The mean moves, not just the variance.** Merging changes the Fama–MacBeth weighting, so expectancies
shift in both directions (`reversion` at 3600 s rises, `trend` falls). This is a re-specification of
the estimator, not a one-way loosening or tightening — which is the property that makes it a
correctness fix rather than a tuning of the hurdle.

**Retroactive.** The grouping is computed at read time from stored observations, so the desk's whole
7-day rolling history is re-scored immediately; no migration and no waiting.

**What this does NOT do.** It does not make a cohort's members market-neutral. Once passes are grouped
correctly a cohort has ~8 members drawn from one interval, which is the precondition for asking
whether a source's expectancy is skill or simply the market factor times its net directional tilt —
the desk hedges its net equity exposure toward flat (ADR-0019), so the beta component is not P&L it
keeps. That is a separate decision and deliberately not taken here.

**Risk.** The SQL is the only part with no test harness in this repo (it joins reference data). It was
executed against the live schema before shipping and returns the partition above. Its failure mode is
also safe: a broken cohort read returns no cohorts, every source reads zero-cohort, and `EdgeGate`
requires `cohorts ≥ 2` — so the gate fails **shut**, never open.

## Alternatives considered

- **Retune `cohort-window-seconds` to ~30 minutes.** Rejected: it replaces one wrong number with
  another and still measures scheduler cadence. Worse, it is fragile in both directions — too short
  re-splits a slow pass; too long merges genuinely independent hourly draws of a single name (the NQ
  case above), which the sweep rule keeps correctly separate.
- **Group by a fixed time bucket (e.g. the measurement horizon).** Rejected: a pass straddling a bucket
  boundary is split, reintroducing the same artefact on a different clock.
- **Group by an explicit publication epoch stamped by the sensor.** The cleanest answer in principle
  and worth revisiting, but it needs a schema column, a migration, and a change to every emitter, and
  it would only apply to observations recorded *after* the change — no retroactive power, which is the
  whole benefit here. The sweep rule recovers the same grouping from data already stored.
