Built the desk's missing half: a continuous mean-reversion sensor for the chop regime the detector
actually reports — every existing continuous source is a continuation bet, and all of them are measured
losing (ADR-0070).

## Situation (live endpoints, read first)

**Money — the bleed has stopped; the level has not recovered.** Total PnL is `-$867.83`, **up** `$8.82`
since last run. Over the last three runs it is down `$845.00`, but that whole figure is the sunk cost of
the `fb9273505` round trip (scored ❌ BAD, auto-reverted) — a `$43k` book opened and worked back to
nothing across 185 fills carrying `$131` of fees. The current run rate is small and slightly positive.
The flag is UNDERWATER and we are nowhere near the ≥1%-per-3-iterations target — but the reason is no
longer an active loss, it is that **the desk cannot trade at all**.

**Risk — collapsed, deliberately, and correctly.** Gross exposure fell from `$6,145.14` to `$785.03`
(`-$5,360.47`) and net from `$5,417.85` to `$56.31`. Historical VaR95 is `$6.87` against that gross; the
firm drawdown breaker is not tripped and is nowhere near. The residual book is two single-share stubs
(AAPL, GOOG) and a hedge leg the advisor now reports ON-TARGET.

**Cause — last cycle's change did exactly what it was built to do.** ADR-0069's scale-relative hedge
no-trade band scored ⚠️ MIXED, and the live move confirms the mechanism: the stranded ES leg that had
been holding 84% of firm gross with no mechanism able to remove it is gone, and `/api/hedging` now reads
`held 0.001546 → target 0.001545, largest delta under the 105.17 no-trade band` — the band is now sized
from the hedge itself rather than from an absolute dollar floor five times larger than the position.
Attributing honestly: the `-$5,360` of gross is **100% the change** (the only orders in the window are
the hedge unwind it authorised); the `+$8.82` of PnL is essentially all **market** — mark drift on
untouched stubs plus the one small commission the unwind cost. Good change, correctly credited on the
exposure leg only.

**Danger — no.** We are not bleeding and adding; exposure is down an order of magnitude and the breaker
is far away. So this cycle is free to fix a cause rather than cut risk.

## The cause worth fixing

The desk is flat because the ADR-0064 edge gate is reduce-only, and it is reduce-only because **every**
routed source is measured negative: `trend` at a t-statistic several standard errors below zero over 46
resolved observations, `momentum` and `social` negative alongside it, against a measured round-trip cost
of 7.05 bps. The gate is right. What is wrong is the composition of the source set — `trend`, `momentum`
and `social` are all **continuation** bets, and a range-bound tape is the one state in which that entire
family is wrong together. It is also the state we are in: the regime detector reads CHOP with per-name
efficiency ratios from 0.09 to 0.48, and the walk-forward selector — an independent, out-of-sample,
cost-honest measurement — picks mean-reversion on every chop name it will trade, with momentum's median
path PnL deeply negative on those same names. Three separate measurements, three separate data sets,
one conclusion. The mean-reversion algo exists but is a *threshold detector*: it has fired once in this
feed mode, so it contributes nothing to the fusion cross-section and accumulates no evidence about
itself. The desk cannot even measure whether reversion pays here.

## What I changed

Added `reversion`, a sixth fusion forecast source (ADR-0070): a continuous, self-calibrating
**range-position** sensor — where a name sits inside its own realised Donchian range, faded, weighted by
`(1 − efficiency ratio)`, normalised by its own typical reading. It is deliberately **not** the trend
sensor negated (that is the classic overfit, and I refused it last cycle for the same reason): it is a
bounded *level* statistic against realised extremes rather than a smoothed *rate-of-change* crossover,
it is largest at the range edge where EWMAC is largest mid-move, and its quality weight is the exact
dual of the trend sensor's — the two partition the efficiency ratio instead of competing for it, so the
sensor refuses to fade a clean trend, which is the expensive way to trade reversion. It inherits
ADR-0066's warmed scale estimator verbatim, so it publishes no view until it knows what "typical" means.

It arrives with no evidence and no privilege: every reading is recorded in the phase-1 telemetry, so it
must earn a cost-beating measured expectancy before the same gate that is holding the desk flat lets it
size anything. **While the gate is reduce-only this change cannot increase exposure at all** — it can
only change how a held position is worked down. At roughly one observation per name per horizon it hits
the gate's 30-sample minimum within about an hour of warm running, so the hypothesis is tested fast and
is falsifiable by construction. Both span dials are shape dials with their provenance stated (the
20-minute range ≈ ⅓ of the 1h measurement horizon is mine and arbitrary); nothing here is a money, risk
or exposure number. The deterministic floor is untouched.

Honest expectation: the sensor spends its first ~40 minutes warming and silent, so the next scoring may
well read as no material change. What it buys is the first legitimate path off a flat book — evidence,
not a lowered bar.
