# ADR-0092: A forecast scalar is MEASURED, not promised — each continuous source is rescaled to the size its own readings actually are

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, sizing

## Context

Every continuous source hands `ForecastRegistry` a reading it *claims* is already normalised. The trend
(ADR-0066) and reversion (ADR-0070) sensors each promise a self-normalised score whose expected absolute
value on the running stream is ≈ 1; the deterministic strategy sources promise a z-score whose expected
absolute value is the configured `expected-abs-z`. The phase-2 mapper multiplies by `TARGET_ABS = 10` on
the strength of that promise and clips at `CAP = 20`. Nothing has ever checked the promise.

It does not hold. Measured on the live target book this cycle: the trend source's readings average
`E|f| = 15.2` against a promised 10, with a **median of 18.9 and 11 of 23 names pinned exactly at the
±20 cap**; reversion averages `14.7` with 6 of 23 at the cap. Over a third of all readings in the
cross-section are clipped.

That has two costs, and the second is the expensive one. First, sizing is linear in the forecast
(`target = forecast/TARGET_ABS × unitNotional / unit value`), so an inflated scale is an inflated book
and an inflated turnover bill to ramp into it — the desk plans ~$381k against a held book of ~$19k and
grinds toward it at the ADR-0080 rate, paying cost the whole way. Second, and worse: **a clipped forecast
carries no cross-sectional information.** Eleven names reading the identical +20 are indistinguishable
to the planner, so the source degenerates from a forecast into a sign function and the selection
information that justifies running 23 names instead of one is gone. Carver's cap is a defence against
rare extremes and negative skew; at a 37% clip rate it is not a defence, it *is* the forecast.

Doing nothing leaves the standing "forecast saturation" item open for a seventh cycle, with the desk
sizing off the cap rather than off the evidence.

## Decision

We will **measure each continuous source's own scale on the running stream and rescale it back to
`TARGET_ABS` before the cap** — the forecast-scalar step Carver prescribes, estimated rather than
assumed. A new pure `ForecastScalars` keeps one **expanding mean of |claim|** per source, pooled across
instruments, and the registry publishes `clamp(claim × scalar)` where

```
scalar_s = TARGET_ABS / max(TARGET_ABS, mean|claim_s|)
```

Four specifics make this safe and auditable:

- **The claim, not the output, is measured.** Each mapper's uncapped arithmetic moves into a
  `…Claim(…)` accessor that the `from…` mapper is then defined in terms of, so there is exactly one
  copy of it. Measuring the capped forecast would throw away the magnitude the estimate needs;
  measuring the *rescaled* forecast would feed the estimate back into itself.
- **Expanding, not decaying** (Carver, *Systematic Trading* 2015, ch. 7): the quantity estimated is a
  structural property of the sensor, not a market regime. This is also why the class carries no
  half-life or span to invent.
- **One-way.** `max(TARGET_ABS, …)` bounds the scalar at 1: an over-delivering source is scaled DOWN, an
  under-delivering one is left exactly as it is rather than levered UP on an estimate. A textbook
  forecast scalar is symmetric; this one is deliberately not, because ADR-0087 and ADR-0088 were both
  reverted for growing exposure with no PnL gain, and an estimate that can only shrink the book cannot
  repeat that.
- **Ordinal sources are excluded.** Hypothesis conviction (a category) and social channel counts are a
  declared scale, not an estimated one — there is no claimed `E|reading|` to hold them to.

Below `min-sample` readings the scalar is 1.0, i.e. bit-for-bit the pre-ADR-0092 behaviour, and the
scalar applied to a reading is estimated from the readings strictly *before* it, so a name's forecast
cannot depend on where in the cycle's cross-section it was published.

Worked example, pinned as a test. A source measured at `E|claim| = 15` gets `scalar = 2/3`. Two names
read 1.8 and 2.4 typical trends — claims of 18 and 24. Before: forecasts 18 and 20 (the second clipped),
so at `unitNotional = 50,000` and price 100 the targets are 900 and 1,000 shares — an 11% difference on
a reading 33% stronger. After: forecasts 12 and 16, targets 600 and 800 shares — the full 33% is
expressed again, and both positions are smaller. A claim of 45 still clips to 20: the cap is un-jammed,
not removed.

## Alternatives considered

**Hand-tune the four scaling constants down.** Fastest, and it is what the config comments have always
asked for ("SHOULD be estimated from the phase-1 telemetry"). Rejected: it invents a money-gating number
per source with no provenance, it goes stale the moment a sensor or a feed changes, and it violates the
feed-agnostic requirement — the whole point is that the same code adapts to any stream's volatility.

**Raise `CAP` so the clipping stops.** Rejected: the cap is a deliberate defence against estimation
error and negative skew from extreme readings, and raising it grows the book — the opposite of what the
diagnosis calls for. The problem is not that the cap is too low; it is that the input is too big.

**Down-weight the saturating source in the combiner instead.** Rejected as already tried and failed:
ADR-0087 removed the source-weight floor for exactly this shape of problem and was reverted (❌ BAD,
exposure up, no PnL). Weights rotate conviction between sources; they cannot repair a source whose
internal dispersion has been destroyed by clipping. The two mechanisms are not substitutes.

**Symmetric scalar (scale thin/quiet sources up too).** Deferred, not rejected — it is the textbook
form and it is what we should run once there is evidence the estimator behaves on this stream. The
trigger to revive it: two consecutive cycles in which the measured `E|claim|` per source is stable and
the ledger shows no exposure surprise from the one-way version.

## Consequences

- **Positive:** the cross-section selects again — names are sized on how strong their signal is rather
  than on whether it cleared the cap. The planned book and therefore the turnover needed to reach it
  shrink toward the size `unit-notional-usd` was actually set for, which is the dominant cost item on a
  desk whose fee bill has repeatedly exceeded its PnL. Every scaling constant in the config becomes a
  starting point that the stream corrects, not a number that must be right.
- **Negative:** a real one — a source that is genuinely quiet *and* honest is now measured against a
  promise it never made, and the one-way rule means we accept a permanently under-sized source rather
  than lever it up. The estimator is also per-session: it resets on restart and re-warms over ~30
  readings, so the first minute after a deploy sizes on the old behaviour. And an expanding mean adapts
  slowly by construction — if a sensor's dispersion genuinely shifts mid-session, the correction lags.
- **Follow-ups:** the per-source measurement is disclosed on the target book (`forecastScalars`) so the
  drift is visible; if a scalar sits far from 1 for many sessions, the honest fix is the sensor's own
  normalisation, not this correction. The symmetric variant above has its trigger stated.
