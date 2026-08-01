# ADR-0116: The risk cut's σ advances on PRINTS, not on cycles

- **Status:** Proposed
- **Date:** 2026-07-28
- **Deciders:** Oleg
- **Tags:** trading, fusion, risk, market-data

## Context

ADR-0113 established that a continuous sensor must advance on the market's own clock, and gated the two
forecast sensors on a `PrintClock`. Its own deferred row (register, ADR-0113) recorded that the same
defect was left downstream: `FusionLifecycle` feeds `StreamVolatility` — the per-name σ that ADR-0086's
trailing risk cut measures its trigger distance in — from the planner's target prices once per 30 s
planning cycle, whether or not the tape printed. Those prices come from the same last-value mark cache,
so on a quiet tape the identical price arrives at the sensor cycle after cycle.

`StreamVolatility` already refuses to invent a return: its own javadoc says an absent price "advances
nothing, because a fabricated zero return would bias the estimate toward *this name does not move*, which
is the dangerous direction for a control that decides when to cut." The guard was written for a **null**
price. The actual source of fabricated zero returns is the republish, and it walks straight through.

Two things break, and both are in the direction that costs money:

- **The cut distance collapses.** The rule is `cut when e > k·σ_h`, so σ *is* the stop distance. Each
  republish absorbs `r = ln(p/p) = 0`, and the EWMA variance decays by `(1−α)` per cycle. Worked, at
  `span = 4` (α = 0.4), a name warmed on ±1 % steps: σ_Δ = 0.995 %, σ over a 3600 s horizon = 10.90 %,
  so the 3σ trigger sits at **32.70 %**. Twenty republished marks later — ten minutes of quiet, not an
  overnight — σ_Δ = 0.0060 %, and the trigger sits at **0.198 %**: 165× tighter. The desk stops itself
  out of a position on the noise it was sized to sit through, pays the spread, and holds the name
  reduce-only for a full holding horizon afterwards. At the production span of 120 the decay is slower
  per cycle and the exposure window is longer, not shorter: the dangerous band runs from a few minutes of
  quiet out to ~11 h, beyond which the variance underflows to exactly zero and the sensor goes silent —
  so a full weekend is safe by accident while a lunch lull, a halt, a thin name and the after-hours
  stretch are not.
- **The warm-up fills with silence.** `returns++` on every republish, so a name reaches `span` and starts
  speaking a σ built out of observations that never happened — the same manufactured significance
  ADR-0113 found in the telemetry, here in the number that gates a stop.

This is live. The desk is in a fresh LIVE (Alpaca) epoch; its equities stop printing at the 20:00Z cash
close and its futures and FX print 7–20 minutes apart all night (ADR-0114's measurements), against a 30 s
fusion cadence.

## Decision

**We will admit a sample to `StreamVolatility` only when the market's own clock has advanced** — the same
rule and the same `PrintClock` (ADR-0113), applied at the same boundary the σ is consumed from.

- `StreamVolatility.update(id, price, providerTimestamp)` refuses a sample whose provider timestamp is not
  strictly newer than the last one this sensor consumed for that name. `FusionLifecycle.applyRiskCut`
  passes the mark's provider time, which it already holds as `markTimeFor` for the ADR-0071 seed.
- The existing two-argument `update` is unchanged and treats every call as a print — correct for a replay
  of the durable mark series (one point per distinct print) and for a backtest bar, and it keeps every
  existing caller and test byte-identical. A `null` provider timestamp is admitted, per `PrintClock`:
  with no clock to judge by, declining to measure must not silence the sensor.
- The gate is on the **clock, never on price equality**: a name that genuinely printed the same price
  twice has genuinely not moved, and that zero return is information the sensor must keep.

No dial, nothing to calibrate, no number that needs provenance (invariant 7 / ADR-0016) — it is the
parameter-free question "did the tape print since I last looked?", so a live feed, a delayed feed, a
replay and a simulated clock all read correctly with no edit (invariant 9). Provider time because it is
the market's honest clock (invariant 5); ingest time only reflects our poll cadence, which is exactly why
it cannot answer this.

## Alternatives considered

- **A staleness threshold on the mark (reject a price older than N seconds).** Rejected: N is a number
  that gates a risk control and would have to be chosen per asset class against print intervals spanning
  four orders of magnitude on this desk. The correct statement needs no threshold at all.
- **Floor σ at some minimum so a decayed estimate cannot produce a ~0 trigger.** Rejected: it treats the
  symptom with an invented risk number, and it would leave the *measurement* wrong — a floored σ still
  says the name barely moves.
- **Gate the ADR-0089 `StreamCovariance` feed in the same change.** Deferred, not rejected, and it stays
  in the register: a covariance is a statement about *contemporaneous* returns, so a name cannot simply be
  dropped from a synchronised snapshot without breaking the pairing. The carry-forward it does instead is
  the Epps bias already registered, and its fix is a grid/estimator change (Hayashi-Yoshida), not this
  gate. Trigger: unchanged from the existing row.
- **Have the risk cut ignore σ and use a fixed percentage stop.** Rejected: it discards the one property
  that makes the chandelier exit sound — a distance in the name's own units — and replaces a measurement
  with a dial.

## Consequences

- **Intended:** a name whose tape has stopped keeps the σ its last real prints measured, so the trigger at
  the reopen is the pre-close distance rather than ~0. Positions held across a close are no longer cut on
  the first genuine move of the next session.
- **Honest cost, and it is a real loss of protection:** the warm-up now advances at each name's own print
  rate, so a slow name takes materially longer to become measured, and until it is, the risk cut makes no
  claim about it and leaves it exactly as planned (ADR-0086's documented behaviour). On this desk ES
  prints ~1,200 s apart, so at `span = 120` its σ needs on the order of a couple of days of prints from
  cold. The seed (ADR-0071/0114) is what covers that gap, and it is now the *only* thing that covers it.
- **Also intended, and it cuts both ways:** the σ a frozen name carries is genuinely *stale* rather than
  decayed. It is the right number as of the last print, and it is not evidence about what the reopen will
  do. This ADR asserts only that stale beats fabricated.
- **Unaffected:** the pre-trade guardrail, the firm drawdown breaker, the edge gate, the covariance and
  every control sized from it, all backtests, and every sim/replay path (the print rule is feed-agnostic).
- **First sight after a seed** may still absorb one duplicate zero, when the seed's last replayed point is
  the current mark: the clock is empty at that moment and admits it. Bounded at one sample per name per
  process, unchanged from before, and owned by the seed's ADRs rather than this one.
