# ADR-0108: The evidence budget is counted in COHORTS, not rows — a wide source must not starve itself

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, signals, statistics, edge-gate

## Context

The desk has been **reduce-only for its whole life on this feed**. `/api/fusion/targets` reports
`edgeGate.mayIncrease: false` with the reason

> no source's measured expectancy beats measured execution cost at the demanded confidence in any name

and, underneath it, `deltaQty: 0` on **every** name of a nine-name planned book against a live
`grossExposure` of `$0.00`. The desk holds a view, plans a target, and trades nothing. A book at zero
exposure earns nothing, whatever the rest of the machinery does — so this is the binding constraint on
the loop's objective, and every other lever is downstream of it.

The gate itself is not wrong. It asks the right question (ADR-0064/0075), against the right
distribution (ADR-0081), over a sample of the right kind (ADR-0077 — Fama–MacBeth across **emission
cohorts**, because a source that calls 23 names in one 200 ms burst has drawn the market once, not 23
times), and it pays for searching the ladder (ADR-0082). What was wrong is the **size of the sample it
is allowed to see**, and the unit that size is counted in.

`SignalTelemetryStore.resolvedObservations` bounded the read at `jethro.signals.sample-limit = 500`
**rows**, newest first. That bound is older than ADR-0077: it dates from ADR-0055 phase 1, when every
observation was its own independent draw and 500 rows meant 500 draws. Since ADR-0077 the statistics
are taken over cohorts, and the row bound is spent at a rate set by **how wide a source's cross-section
happens to be**:

| source @ rung | observations read | cohorts obtained |
|---|---|---|
| `reversion` @ 225 s | 500 (capped) | **58** |
| `trend` @ 225 s | 500 (capped) | **58** |
| `momentum` @ 225 s | 234 (uncapped) | **186** |

A source calling ~20 names per burst is pinned at ~25–60 independent draws **forever** — it cannot
accumulate a 61st however long the desk runs, because every new burst evicts an older one. A source
calling one name at a time gets the full 500. Two sources measured on the same stream over the same
window are graded on samples that differ by an order of magnitude in statistical power, purely as a
function of breadth. That is not a conservative approximation; it is a measurement the desk cannot
improve by gathering evidence, which is exactly what the gate was built to reward.

The consequence is quantitative and it is the whole difference between trading and not. Re-running the
gate's own arithmetic over the live `signal_observations` table, unchanged except for the bound
(α = Φ̄(2.0)/3 = 0.007583 after the ADR-0082 Bonferroni haircut, Student's t on cohorts − 1 df, net of
the cheapest round trip the desk can actually pay, `ES` at 0.4214 bps):

| source @ rung | bound | N | cohorts | mean bps | s.e. bps | t | p | verdict |
|---|---|---|---|---|---|---|---|---|
| `reversion` @ 225 s | 500 rows | 500 | 58 | 10.35 | 9.996 | 0.99 | 0.16237 | shut |
| `reversion` @ 225 s | cohorts | 4 847 | **247** | **6.45** | **2.361** | **2.55** | **0.00566** | **OPEN** |
| `trend` @ 225 s | 500 rows | 500 | 58 | −12.02 | 9.766 | −1.27 | 0.89615 | shut |
| `trend` @ 225 s | cohorts | 4 857 | **247** | −5.59 | 2.321 | **−2.59** | 0.99492 | shut |

Note what the fuller sample does to the *point estimate*: `reversion`'s mean expectancy **falls**,
10.35 → 6.45 bps. This is not a bigger edge bought with a friendlier test — it is a smaller, better
estimated one. The standard error falls faster than the mean, by roughly √(247/58), because that is
what accumulating independent draws does, and the surplus over cost becomes distinguishable from zero
for the first time.

The same read demotes `trend`. It currently carries **the entire fused book** — all nine target names
list `sources: 1` with `trend` at weight 1.23 — on a 3 600 s reading of `+84.30` bps that cannot clear
anything (t = 0.89). Measured properly at the rung the desk would actually hold, `trend` is
**significantly negative**: −5.59 bps, t = −2.59. Under ADR-0097 that is a source held at the MINIMUM
combination weight rather than steering the book.

## Decision

**Bound each source's evidence in the unit its standard error is estimated in: emission cohorts.**

`jethro.signals.sample-limit` (rows) is replaced by `jethro.signals.cohort-limit` (cohorts, default
500, per source per horizon), and the read returns the most recent `cohort-limit` **cohorts**, each
already reduced to the sufficient statistics the estimator consumes.

The grouping moves into SQL — same rule, same place the rows live:

```sql
with resolved_obs as (
    select entry_at, realized_return,
           case when extract(epoch from
                    entry_at - lag(entry_at) over (order by entry_at)) * 1000 > ?
                then 1 else 0 end as cohort_break
    from signal_observations
    where source = ? and horizon_seconds = ? and resolved = true
      and feed_mode = ? and resolved_at >= ? and realized_return is not null
), cohorted as (
    select realized_return, entry_at,
           sum(cohort_break) over (order by entry_at) as cohort_id
    from resolved_obs
)
select count(*), sum(realized_return), sum(realized_return * realized_return),
       count(*) filter (where realized_return >  ?),
       count(*) filter (where realized_return <  ?)
from cohorted group by cohort_id order by max(entry_at) desc limit ?
```

— the identical cut `SignalScoring` makes in memory (observations ordered by entry instant, a new
cohort wherever the gap to the previous one exceeds `cohort-window-seconds`), and the identical
WIN/LOSS/FLAT classification `SignalScoring.outcome` makes at `flat-threshold-bps`.
`SignalScoringTest` pins the two groupings to the same `Stats` on the same data, so they cannot drift.

`SignalScoring.aggregate(String, List<Cohort>, long)` composes the same estimator from those
statistics: the expectancy is the mean of cohort means, the standard error is
`sd(cohort means)/√B`, the hit-rate is Σwins/(Σwins+Σlosses), and the per-observation dispersion comes
from `n`, Σx, Σx² pooled. **No arithmetic changed** — only which cohorts reach it.

**This reads less from the database, not more.** Each cohort returns as one row instead of its whole
cross-section, so the fusion cycle's two `statsByHorizon()` calls transfer ~500 rows per source-horizon
in the worst case against the 500 they transfer today, while covering an order of magnitude more
history.

### Provenance of the number (CLAUDE.md)

`cohort-limit = 500` is **mine and arbitrary in the same way its predecessor was**: it is the same
numeral the superseded row bound carried, now counting the unit the standard error is actually computed
in. It is not a money, risk or exposure dial — it is a measurement sample size and it sizes nothing
(invariant 7 / ADR-0016). Its direction of error is stated: **lowering** it shortens the sample and
widens the standard error, which is strictly conservative; **raising** it lengthens the memory, bounded
above by `rolling-days = 7`. Nothing else about the gate moves — `t-hurdle`, `min-sample`,
`hypotheses`, the cost model and the per-name veto are untouched.

## Alternatives considered

- **Raise `sample-limit` to a bigger row count.** Treats the symptom. The unit stays wrong, so sources
  are still graded on unequal amounts of history in proportion to their breadth, and the transfer grows
  without bound as the table fills.
- **Drop the bound entirely.** The rolling window would then set the sample, and the read grows with
  the emission rate — ~100 k rows per source-horizon at seven days on the 225 s rung, pulled twice per
  30 s cycle, on the box that also runs the local model. A bound is right; its unit was wrong.
- **Demean each cohort cross-sectionally before taking its mean** — grade the signal on its
  market-neutral return, since the equity overlay removes exactly the systematic component that
  dominates cohort-to-cohort dispersion. Measured on the live table before writing any code: it moves
  `reversion` @ 3 600 s from t = 1.04 to t = 1.14 and `trend` from 0.90 to 0.92 — the point estimate
  and the standard error shrink together (222 → 26 bps, 929 → 98 bps), so the *t* barely moves, and any
  cohort of one name collapses to identically zero. It is a genuine diagnostic — it says the sources'
  headline expectancy is ~90 % market drift — but it is not the constraint, and shipping it would have
  changed nothing. Recorded here so it is not re-attempted.
- **Lower `t-hurdle`.** That is buying permission rather than earning it, and it would loosen the gate
  everywhere at once rather than fix a measurement.

## Consequences

- **The gate can open, on evidence, for the first time.** On the live table `reversion` @ 225 s clears
  at p = 0.00566 against α = 0.007583. Because the per-name test (ADR-0075) charges each name its own
  round trip, permission arrives **name by name** as evidence accrues: at today's standard error only
  `ES` (0.4214 bps) clears; `AAPL` (0.8765) needs t = 2.36 against a required ≈ 2.44 and does not, nor
  does any wider name. This is deliberately gradual, and it widens only as √B grows.
- **The desk's holding period changes with the selected rung.** Selecting 225 s over 3 600 s sets the
  ADR-0080 partial-adjustment rate to `a = 1 − e^(−30/225) = 0.1248`, and the ADR-0097 weights are
  estimated at that rung — which is where `trend` is measured negative and demoted. The book stops
  being steered by the one source that has never cleared anything.
- **Risk-adjusted PnL is the claim under test, and exposure is expected to rise from zero.** A book at
  `$0.00` gross cannot get less risky. The honest statement of the trade-off: if the desk puts on
  exposure and does not earn on it, the loop's scorer returns ❌ BAD and reverts this, which is the
  correct outcome. What this change asserts is only that the *decision* is now made on the evidence the
  desk has actually gathered rather than on an arbitrary fraction of it.
- **Nothing below the floor moves.** The pre-trade guardrail, the firm drawdown breaker and the
  ADR-0016 gates are untouched; the edge gate can still only ever *subtract* trades from what the
  planner wanted.
- **Feed-agnostic (invariant 9).** Every input is the desk's own measured stream — no level, no price
  assumption, no sim special-case — and the read is `feed_mode`-scoped exactly as before (ADR-0029).
- **`SignalTelemetryStore.resolvedObservations` and its `Resolved` record are removed**, so there is no
  second, row-bounded way to sample the same table for someone to reintroduce the bug through.
