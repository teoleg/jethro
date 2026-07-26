Three sources the desk has *measured losing money* were sitting at full trust in the fusion weights, because the credibility term counted a different sample than the estimate it was guarding — fixed before the gate opens on them (ADR-0074).

## Situation (live endpoints, read first)

**Money — flat, not bleeding.** Total PnL `-$867.73`, identical to the last run and to the run before
that; every move across the last three is inside the scorer's noise deadband. `stale` and `underwater`
are both set and `pnl_growth_pct` is −0.06% against the +1%/3-iterations target. The reason is
arithmetic, not a loss: with zero exposure, PnL *cannot* move.

**Risk — zero, no danger state.** Gross and net exposure are both `$0.00`, VaR reports "no positions",
the firm drawdown breaker is untripped, the hedge axis reads FLAT (held 0 → target 0 ES). There is
nothing to de-risk and nothing to cut, so the danger-state override does not apply.

**Cause — last cycle's change is inert by construction, as it said of itself.** ADR-0073 (feed-mode
scoping the daily close series) scored ⚠️ MIXED "no material change". That was the prediction written
before it shipped: it changes which observations VaR and per-name vol may admit, and with a flat book
there is nothing to measure. The window's only orders were the last of the ADR-0065 flattening tail —
single-share ALPHA closes in AAPL and GOOG with matching fractional HEDGE ES trims, under a reduce-only
gate. **No trigger opened a position this window.** 100% of the (nil) PnL and exposure move is mark
drift plus that prior flattening: market and prior policy, none of it attributable to my last change in
either direction.

## Diagnosis — the gate is right; what happens the moment it opens is not

The ADR-0064 gate is correctly shut. Every source with enough sample to speak measures significantly
negative: `trend` −15.96 bps over 92 resolved (t ≈ −5.2 before cost), `momentum` −11.30 over 18,
`social` −8.17 over 12, against a 6.93 bps measured round trip. Loosening that gate has been tried and
was auto-reverted ❌ BAD (`fb9273505`); I did not re-attempt it, and I did not invert a losing source
either — the findings memory records both as paid-for lessons. `reversion` (ADR-0070) now publishes,
23 open / 0 resolved, and resolves one horizon out, so the gate finally has a counter-trend candidate
to judge within a cycle or two. I left that plumbing alone.

So I read the *other* number the desk publishes every cycle and nobody had checked against the
telemetry beside it — the per-source conviction weights:

```
reversion 1.0   mean-reversion 1.0   momentum 1.0   social 1.0   trend 0.2732572
```

Every source at full trust except the one with the largest sample. That is backwards, and the mechanism
is a sample mismatch inside `TelemetryWeights`. The evidence statistic is Φ of the expectancy
t-statistic, and `avgReturnBps`/`stdErrorBps` average over **every resolved** observation, FLATs
included — correct, since a flat call earned nothing and belongs in an expectancy. But the Bühlmann
credibility term counted only **wins + losses**. Confidence in one statistic was being measured with the
sample size of another. A source's flat rate is a property of its horizon and dead-band, not of how much
evidence it has: `momentum` is 61% flat, `social` 67%, `mean-reversion` 100% — so their decisive counts
were 7, 4 and 0, all under the hard min-sample floor, which pinned them at exactly 1.0.

That floor was the second half of the defect. Clamping to 1.0 is *not* neutral — for any
below-average source it is strictly **more** trusting than its own shrunk measurement. So three sources
carrying measured-negative expectancy were laundered into full trust, while `mean-reversion`, having
never produced one decisive observation, was structurally unjudgeable forever.

This is inert today and I expect ⚠️ "no material change" again — say it up front. It is not inert next
week: when any source clears the gate, the planner sizes from the combined forecast, and roughly three
quarters of that first book's conviction would have come from views the desk already knows lose money.

## Change (ADR-0074)

Credibility now counts `resolved` — the same observations the estimate averaged over — and the hard
min-sample floor is removed, leaving Bühlmann `shrinkage-k` as the single continuous thin-sample
defence. It already does that job better: a source with three calls and a t-statistic of +6.93 moves
its weight by 0.56%, a curve instead of a cliff, and symmetric for good and bad readings alike. Hit
rate still excludes flats, where "no bet" is the right reading. `jethro.fusion.weights.min-sample` is
deleted rather than deprecated.

The regression is one test: two sources resolve 40 calls each with byte-identical measured expectancy
and dispersion, differing only in that one landed 30 of 40 inside the flat dead-band. Before, they got
1.0 and 0.310741 — a 3.2× conviction gap on identical measurements, pointing the wrong way. Now they
get the same number. Worked arithmetic for that and for the thin-sample case is in the ADR and encoded
as exact-value tests.

No statistic, hurdle, sizing rule, cost model or money parameter changed, and the deterministic floor —
guardrail, breaker, edge gate, invariant-7 gates — is untouched. Weights remain ratios that
`ForecastCombiner` normalises by their sum, so this rotates conviction between sources and provably
cannot scale gross or net exposure for any given set of forecasts.

## Flagged, not acted on

The pooled prior every thin source shrinks toward is the plain mean of the sources' evidence, so a
source with **zero** observations contributes Φ(0) = ½ to it — adding an unmeasured source raises the
trust of every measured one. Real, but second-order (it moves ratios only), and folding it in here would
have made this change's effect unattributable. Separately: `GOOGL`, `GS`, `BRK.B`, `NFLX`, `ORCL` and
`TSLA` still boot on generic sim-calibration defaults at a near-identical ~$99.9, and GOOGL's 10.05 bps
one-way slippage is a provisional 20 bps refdata spread rather than a measured mega-cap property. Still
declining to repair it in the same breath as anything that reads the cost hurdle.
