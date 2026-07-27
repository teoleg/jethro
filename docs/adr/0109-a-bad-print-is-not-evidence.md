# ADR-0109: A bad print is not evidence — the jump guard's threshold bounds the expectancy too

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, signals, statistics, edge-gate, data-quality

## Context

ADR-0108 uncapped the edge gate's evidence budget, and it worked: `reversion` at the 225 s rung went
from 58 emission cohorts to over 300. The gate still reads `mayIncrease: false`, the book still holds
`grossExposure $0.00`, and total P&L has not moved in four hours. So the sample size was a real
constraint but not the last one. This ADR is about what the enlarged sample turned out to contain.

The desk's expectancy is a Fama–MacBeth statistic (ADR-0077): reduce each emission cohort to its mean
directional return, then take the mean and standard error **across cohorts**. That standard error is
the denominator of every gate above it — the desk-wide verdict, the per-name cost test (ADR-0075), the
source weights (ADR-0097). It is therefore the single most leverage-bearing number on the desk, and it
is a plain sample standard deviation, which has an unbounded influence function: **one observation can
set it.**

One did. Measured on the live table, `reversion` @ 225 s over 313 cohorts:

| | cohorts | mean | std error | t (net of cost) | p | gate |
|---|---|---|---|---|---|---|
| as measured | 313 | 4.55 bps | 1.90 bps | 2.18 | 0.0152 | **shut** (α = 0.00758) |
| dropping ONE cohort | 312 | 2.78 bps | 0.55 bps | 4.33 | 0.00001 | open |

The cohort in question has a mean of **+573 bps** where every other cohort in the sample is inside
±35 bps. Its two extreme members are:

| instrument | entry mark | exit mark | "return" over 225 s |
|---|---|---|---|
| GOOG | 174.768112 | 323.580000 | **+8 515 bps** |
| NQ | 19 773.348660 | 28 677.000000 | **+4 503 bps** |

An 85% move in GOOG in under four minutes did not happen. Reading the rest of that cohort's entry
marks says exactly what did: `AAPL 188.94`, `MSFT 429.14`, `ES 5438.30`, `GOOGL 99.72`, `TSLA 99.84`,
`ORCL 99.80` — the simulator's own start levels (`jethro.trading.sim-start-prices`), while the live
tape has `AAPL 337.12`, `MSFT 390.37`, `ES 7454.75`, `GOOG 328.46` from alpaca. Twenty-one of the
twenty-three names entered and exited on the same tape and behaved (+0.7 to +15.8 bps). GOOG and NQ
entered on one tape and exited on the other, and the handover was booked as a market return.

Two things follow, and only the second is this ADR's business.

**First**, the tape changed under a running session without the epoch change invariant 8 requires. That
is a real defect and it is recorded in the deferred register; it is not fixed here, because fixing it
would not help — the contaminated rows are already in the table, the seven-day rolling window will
carry them for another week, and the *next* bad print from any cause would do the same thing again.

**Second, and the point:** the desk already has a name for a price move that is not a market move. The
corporate-action / bad-print jump guard (`jethro.trading.mark-jump-bps`, EQUITY/DEFAULT 2000 bps,
FUTURE/SWAP 1000, FX/BOND 800) exists precisely to say *"this is a bad print or a split, not the
market"*, and when it fires the suspect price **never reaches P&L, orders, sizing or history**. The
signal telemetry read it anyway. The guard's own definition of an impossible move was already sitting
in the config, already carrying its provenance, and the estimator that gates the whole book was not
consulting it.

This is the sharpest possible case of the standing rule that a control which cannot be improved by
gathering evidence is broken: no quantity of honest measurement can outvote a single +8 515 bps print,
because it enters the denominator as well as the numerator. Left alone, the desk stays flat until that
row ages out of the rolling window — and then stays flat again after the next one.

## Decision

**An observation whose realised directional move reaches the desk's own bad-print threshold for that
instrument's asset class is not a measurement of a signal, and is excluded from the expectancy.**

- The threshold is `jethro.trading.mark-jump-bps.<CLASS>`, resolved through reference data
  (`instrument.asset_class`) exactly as the mark cache's `JumpThresholds` resolves it. **No number is
  introduced by this ADR.** An instrument outside the master takes `DEFAULT`.
- The test is on the absolute move, so it is **symmetric**: it cannot favour a direction, and here it
  *lowers* the surviving point estimate (4.55 → 2.63 bps) while lowering the standard error by more.
  It removes fake profit and fake noise together.
- It is applied at **read** time, in the same SQL that ADR-0108 moved the cohort grouping into, before
  the gap-cut. That heals the history already in the table rather than only future rows, and it leaves
  `signal_observations` intact as the audit record — the impossible observation is still stored, still
  visible, simply not counted as evidence.
- Applying a **per-update** threshold across a whole **horizon** is deliberately looser than the guard
  itself: over 3 600 s a 20% equity move is merely extraordinary, over 225 s it is impossible. Looser
  is the conservative direction for a rule whose effect is to delete evidence.
- It **fails open.** A class set to `0` disables the exclusion exactly as it disables the jump guard;
  an absent or unusable threshold map degrades to "count everything", never to "count nothing". A
  mis-wired threshold must not be able to empty the desk's sample.
- What is excluded is **counted, not dropped in silence**: `/api/signals/discards` reports it per
  source and rung, and the loop report carries it. Empty is the healthy state; a rising count is the
  desk saying its mark stream changed, not its alpha.

### What this does to the desk, measured on the live table before shipping

Running the production query against the live database: **2 observations of 5 101** are excluded at
`reversion` @ 225 s and **2 of 5 159** at `trend` @ 225 s — 0.04%. Across all fifteen source × rung
cells the largest exclusion rate is 7% (one row of a fourteen-row sample). Every rung's dispersion
falls to a physically sensible magnitude — the 3 600 s standard error goes from 145 bps to 14 bps —
which is the real tell: the estimator was measuring the bad prints, not the market.

Exactly **one** cell changes verdict:

| source @ rung | before | after |
|---|---|---|
| `reversion` @ 225 s | t = 2.17, p = 0.0152 — shut | **t = 4.05, p ≈ 0.00003 — open** |
| `reversion` @ 900 s | t = −0.24 | t = 1.70, p = 0.045 — still shut |
| `reversion` @ 3 600 s | t = 0.97 | t = 0.61 — still shut |
| `trend` @ 225 s | t = −2.19 | **t = −4.68** — more clearly negative |
| `momentum` @ 225 s | t = −3.28 | t = −3.28 — unchanged |
| `social` @ 225 s | t = −0.64 | t = −1.87 — still negative |

No source that measured negative is rescued; the ones that measured negative measure *more* negative,
because their noise fell too. This is not a device for buying significance — it is the removal of a
price the desk could never have traded at.

The desk-wide gate therefore opens at the 225 s rung on `reversion`, and permission then narrows name
by name under the ADR-0075 per-name cost test. Two consequences follow automatically and are intended:
the selected rung moves 3 600 s → 225 s, so the ADR-0080 holding rate becomes
`a = 1 − e^(−30/225)`; and `trend`, which currently carries essentially the whole planned book at
weight 1.26 while measuring t = −4.68 at that rung, is demoted to the ADR-0097 minimum weight.

## Alternatives considered

- **Winsorize or trim the cohort means (Hampel's 3-MAD rule, or a 1%/99% winsor).** The textbook answer
  to a heavy-tailed panel, and it was tested numerically first — which is why it was rejected. At 3 MAD
  it caps **7–20%** of cohorts, not the 0.27% the rule is calibrated for, because the cohort-mean
  distribution is genuinely heavy-tailed; computing a standard deviation on the winsorized series then
  understates the standard error badly enough to flip `reversion` @ 900 s from t = −0.24 to **t = +7.95**.
  That is not removing contamination, it is redefining the estimand, and it would have opened the gate
  on arithmetic rather than on evidence. Rejected, and recorded here so it is not re-attempted.
- **Fix the tape handover instead.** Necessary, and tracked separately — but it does not clear the
  contaminated rows already in the seven-day window, and it defends against exactly one cause. The
  estimator needs a floor under it whatever the cause.
- **Quarantine the instrument (fire the existing jump guard) so the mark never lands.** The guard did
  not fire here; why is worth a separate investigation. But quarantining GOOG would have frozen the
  *live* alpaca price behind the stale sim one — rejecting the good data to protect against the bad.
  The right place to reject a bad print, once it is already in the mark history, is where it is being
  read as evidence.
- **Cross-sectionally demean each cohort.** Already tested and rejected in ADR-0108 for a different
  reason; it does not address a single-name impossible print at all.
- **Do nothing and wait for the row to age out.** The rolling window is seven days, the book earns
  nothing at zero exposure for all of them, and the next bad print resets the clock. A gate whose
  verdict is set by whichever outlier is currently inside the window is not measuring anything.

## Consequences

- The edge gate's denominator is now bounded by the same definition of an impossible price that governs
  P&L, orders, sizing and history. One data-quality event can no longer hold the whole book at zero.
- Gross exposure rises from `$0.00`, which a book at zero cannot avoid. Stated up front as the
  trade-off: if exposure rises and P&L does not, the loop's scorer marks this ❌ BAD and reverts it, and
  that is the correct outcome.
- The desk's measured expectancy gets *smaller* and its confidence in it gets larger. Both are the
  honest direction.
- `/api/signals/discards` is a new, and deliberately boring, operator surface. If it starts climbing,
  the finding is about the feed, not the strategy.
- The tape-handover defect that produced this cohort remains open in the deferred register. This ADR
  makes the desk robust to it; it does not fix it.
