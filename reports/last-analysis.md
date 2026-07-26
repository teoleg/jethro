The desk sits one lucky cohort away from releasing its whole planned book on three draws of the market; the edge gate's hurdle is now read against Student's t on the cohorts its standard error was actually estimated from, so it delivers the confidence it advertises at the sample sizes this desk really has (ADR-0081).

## Situation (live, in words)

**Money.** Total PnL is **unchanged** run-over-run and across the last three runs — identical to the
digit. All of it is realized: the book holds no position, so there is no mark-to-market in the number.
The book is not bleeding this window because nothing traded at all. Growth is off the 1%-per-3 bar and
the run is flagged UNDERWATER and stale.

**Risk.** Gross and net exposure are both **zero**. VaR reports "no positions", the firm drawdown
breaker is not halted, and nothing is near it. There is no live danger state in the held book.

**Cause.** Last cycle's change (ADR-0080, holding period derived from the evidence horizon) scored
⚠️ MIXED on a move of exactly zero. That is **unmeasured, not refuted** — a sizing-rate correction on a
book that never traded can only score zero, exactly as that cycle predicted in writing. Nothing to
revert. This is now the tenth consecutive cycle scored on a zero move.

**Danger — and it is not where the report points.** The *held* book is flat and safe. The **planned**
book is not. The fusion layer is publishing 23 live targets, sized and priced, every one of them with
`deltaQty = 0` only because the gate is reduce-only. The single thing holding that back is `reversion`
reading a t-statistic a hair under the fixed 2.0 hurdle — **on three resolved cohorts**. One favourable
burst flips a binary switch and the entire planned book arrives at once. That is the live danger this
cycle: not a position, a *permission* about to be granted on evidence that does not exist.

**Order post-mortem.** No orders in this window. The last window that traded remains the 20:08–20:21
MSFT sequence — eleven legs in thirteen minutes, buys and sells netting to exactly zero, every leg
paying spread and fee. That churn mechanism was addressed last cycle; nothing new has been put on tape
since, so there is no new trigger to attribute.

**Memory.** `docs/loop-findings.md` has flagged the measurement horizon as the next lever for three
cycles running, each time deferred behind a prerequisite. Its own stated condition for taking it is
"justified by evidence that the edge is fast, not by a wish for more samples" — and that evidence does
not exist yet, so it is deferred again, deliberately, for the fourth time.

**Change vs market.** Flat at both endpoints, zero orders in the window. Attribution is exact and
empty: **0% market, 0% change.** Nothing here is inferred from a noisy delta.

## Diagnosis

ADR-0077 changed what the gate's denominator *is* without changing what it is *compared against*. The
standard error stopped being formed from hundreds of observations and became a Fama–MacBeth estimate
across emission cohorts — of which a cross-sectional source produces exactly one per measurement
horizon. A ratio whose denominator is estimated from B draws follows Student's t on B−1 degrees of
freedom, not a normal; that is the original result and the original reason for it. At the three cohorts
`reversion` actually has, the true 97.7% one-sided point is roughly four and a half standard errors,
while the gate was applying two. The dial was not wrong and the intent was not wrong — the reference
distribution was. `min-sample = 30` does not cover the hole either: it counts observations, and 69
observations in 3 cohorts sails past it. It is a floor on the wrong index, the same shape of mistake
ADR-0079 recorded one level up.

## Change

The hurdle is converted once into the confidence it always claimed, `α = 1 − Φ(t-hurdle)` (2.0 ⇒
0.02275, value and calibration untouched), and the surplus is tested against that α under Student's t
on `cohorts − 1` degrees of freedom. A new pure `Significance` class supplies the tail probability, and
every one of its values is pinned in tests to something that exists independently of the code — published
t-table points, the Cauchy and df=2 closed forms, exact factorials. Evidence is now ordered by p-value,
the only statistic comparable across sources whose cohort counts differ. The correction is *exactly* a
no-op as df → ∞, so it can only bite where the normal approximation was invalid, and only conservatively.

**This will very likely score as no material move next cycle, and that is expected, not an accident** —
the gate was already shut and the book is already flat. What it changes is what happens when the gate
next opens: on accumulated evidence rather than on a third lucky burst. Given that the only ✅ GOOD row
in this ledger is the gate shutting the desk down, and the only ❌ BAD row is a relaxation that bought
exposure and no PnL, that asymmetry is worth paying a slower open for. Full suite green; ADR-0081 Proposed.
