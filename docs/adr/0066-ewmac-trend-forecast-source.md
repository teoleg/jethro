# ADR-0066: A continuous, self-calibrating trend sensor as a fusion forecast source

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** strategy, signals, fusion

## Context

The desk's stated thesis is a **risk-managed trend problem, not a prediction problem**: sharpen a risk
sensor and a trend sensor from the stream itself, add or hold when the trend is confirmed and risk is
contained, cut when risk enters the danger zone. The platform has the risk half — VaR/ES, the vol
regime, the pre-trade guardrail, the firm drawdown breaker — and, since ADR-0064, a cost control that
refuses to pay for edge it cannot measure.

What it does not have is a **trend sensor that speaks continuously**. Every deterministic source today
is a *threshold detector*: `MomentumStrategy` and `MeanReversionStrategy` fire when a short window's
move is statistically unusual and are silent otherwise, and `TrendDetector` (ADR-0044) emits a
*categorical* TREND/CHOP regime used to scale sizing, not a directional view. A threshold detector
answers "did something just happen?". A trend book has to answer a different question, on every cycle
and for every name: **"are we in a trend here, in which direction, and how much of it should we own?"**
A source that is silent most of the time cannot answer it, and — as the fusion layer is now the sole
order origin (ADR-0055/0059) — silence across all sources is a flat book.

That is the state the desk is in. Two consecutive de-risking changes (ADR-0064's cost-aware gate,
ADR-0065's held-name targets) did what they were designed to do and wound the book down. They were
right: they removed turnover that had no measured edge behind it. But removing bad trades cannot by
itself produce good ones, and a flat book earns nothing. The gap is a source with a defensible prior
of positive expectancy — and the one such prior with a century of out-of-sample evidence behind it is
time-series momentum (Moskowitz, Ooi & Pedersen, *JFE* 2012; Hurst, Ooi & Pedersen, "A Century of
Evidence on Trend-Following Investing", *JPM* 2017).

## Decision

We will add **`trend`**, a fifth fusion forecast source: an exponentially-weighted moving-average
crossover (EWMAC), normalised by the instrument's own volatility, weighted by trend quality, and
rescaled to the desk's shared forecast convention. Per instrument, on each cycle's fresh mark:

```
fast, slow = EWMAs of price with spans Nf, Ns              (Ns = 4·Nf — Carver's ratio)
vol        = EWMA of |Δprice| over span Ns                 the name's own step scale
raw        = (fast − slow) / vol                           dimensionless: trend in vol units
er         = Kaufman efficiency ratio over the last Ns steps ∈ [0,1]
q          = raw × er                                      quality-weighted trend
scale      = EWMA of |q| over span Nn                      what "typical" means ON THIS STREAM
score      = q / scale                                     E|score| ≈ 1 by construction
forecast   = score × TARGET_ABS                            the house convention, [-20,+20] capped
```

Four properties justify this specific shape.

**It is continuous and therefore reactive.** The forecast is re-derived from scratch every cycle, so
the position it implies grows as a trend strengthens and shrinks as it decays — the "add or hold when
confirmed" half of the thesis. It is also intrinsically **asymmetric in the right direction**: as a
trend rolls over, the crossover shrinks, crosses and flips sign, so the position is cut by the signal
itself. That is a structural cut-losers rule, not a bolted-on stop with a hand-chosen level.

**It self-calibrates, so it is feed-agnostic** (invariant 9). Every step is expressed relative to the
instrument's own measured behaviour: the crossover in units of its own step vol, the result in units of
its own typical reading. No price level, bps constant, or asset-class assumption appears anywhere. The
same sensor reads a simulated equity, a real FX cross and a futures contract on one scale, and follows
that scale as it moves — which is what lets a sim-tested signal mean the same thing on a live feed.
This is asserted by test: the reading is invariant to multiplying an instrument's prices by 100.

**The efficiency ratio enters as a discriminator, not a haircut.** Applying trend quality *after*
normalisation would be a blanket shrink toward zero (E[ER] < 1) that quietly changes what the ADR-0059
conviction floor means. Applying it *before* keeps the book's overall scale at the house convention
while making a clean trend outrank a noisy one of the same size — both across names and over time.
Chop reads small because it is chop, not because everything reads small.

**It is a sensor, not an authority.** It publishes into the same `ForecastRegistry` as every other
source and records every call in the ADR-0055 phase-1 telemetry. It therefore arrives with **no**
privileges: its fusion weight is the measured one (equal-shrunk until it has a sample), and it cannot
put risk on until the ADR-0064 edge gate finds a source whose measured expectancy beats measured cost.
The ADR-0049 backtest-support veto, the conviction floor, the pre-trade guardrail and the firm breaker
are all unchanged and all still upstream of any fill. A new source earns its allocation from evidence
or it does not get one.

### Dials and their provenance

`fast-span` / `slow-span` = 16/64 — Carver's standard EWMAC16/64 variation (*Systematic Trading*, 2015),
slow = 4× fast. `normalisation-span` = 256 (4× slow) is **mine**: long enough that "typical reading" is
a property of the stream rather than of the trend being measured. All three are **lookback lengths in
evaluation cycles — shape dials, not money, risk or exposure numbers**: the sensor normalises its output
to the same expected magnitude whatever they are, so changing them changes *which* trends it sees, never
how large the book is. Position size remains governed by `unit-notional-usd`, the conviction floor, the
edge gate and the guardrail — none of which this ADR touches.

## Alternatives considered

- **Re-tune the existing momentum strategy (longer lookback, lower threshold).** Rejected as the
  primary lever: it would still be a threshold detector, still silent between firings, and its
  `threshold-sigmas` dial has already been moved five times through the live panel without settling.
  The defect is the *shape* of the signal, not its calibration.
- **Use `TrendDetector`'s TREND/CHOP regime directly as a forecast.** Rejected: it is categorical and
  undirected — it says a name is trending, not which way or how strongly — and its hysteresis is tuned
  for a sizing scalar, not for a position. Its efficiency ratio is reused here (one definition, now a
  shared pure function) precisely so the two agree on what "trending" means.
- **A Donchian/breakout rule (buy the N-period high).** Rejected for now: it is binary and path-
  dependent at the boundary, which makes it churn against the Gârleanu-Pedersen no-trade band, and its
  natural cousin — a fixed-fraction trailing stop — needs a hand-chosen level with no measurement
  behind it. EWMAC gets the same trend exposure with a continuous, self-scaling output.
- **A cross-sectional momentum ranking (long the top decile, short the bottom).** Rejected at this
  size: with a few dozen names the deciles are 3-4 names each, so the portfolio is concentration, not
  breadth, and the ranking is dominated by estimation error.
- **Wait for a live feed before adding any source.** Rejected: the sensor is deliberately built so that
  the sim/live distinction does not change its computation, and refusing to build until the data is
  "real" is how a flat book stays flat. The measurement machinery scores it either way.

## Consequences

- **Positive:** the desk gains a source that expresses a view on every name every cycle, so the fusion
  layer has something to plan from; the view is directional, continuous, and cuts itself when the trend
  turns; the trend/chop discrimination the platform already computed for sizing now also shapes *what*
  it holds; and the source is measured from day one, so if it has no edge the existing weights and edge
  gate will say so with a number rather than an opinion.
- **Negative — and this is the honest cost:** a source that always speaks will, when the edge gate is
  open and names are backtest-supported, take the book from flat to positioned across many names. Gross
  exposure will rise. That is the intended trade (a diversified trend book *is* many small positions —
  Grinold's IR ≈ IC·√breadth cuts both ways), but it means the next ledger row is expected to show
  exposure up, and the verdict then turns on whether the PnL earned justifies it. If it does not, the
  scorer reverts this change, which is the correct outcome and the reason it is one self-contained
  commit.
- **Negative:** EWMAC is a *lagging* signal by construction — it is late into a trend and late out of
  it, and it loses money in sustained chop. The efficiency-ratio weight mitigates but cannot remove
  this. The mitigation of last resort is unchanged: the guardrail and the firm breaker.
- **Follow-ups:** surface the per-name readings (score, raw trend, efficiency ratio) on the fusion API
  so the operator can see *why* a name is held; consider a second, slower span pair combined with this
  one (Carver runs several variations to diversify the lookback choice) once there is measured edge to
  justify the added turnover; and revisit whether the ADR-0049 backtest-support veto — which asks a
  momentum/mean-reversion backtest whether a name is tradable — is the right gate for a trend forecast,
  since it can veto a name on the evidence of a different signal entirely.
