# ADR-0112: A name the desk cannot price from fills or quotes is priced from its own prints — the blend is a closed loop

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, execution, cost, risk

## Context

ADR-0075 tests every name against **its own** measured round trip before letting the desk put risk on
it. A name with no measurement of its own is charged the desk's **blended** cost. ADR-0099 narrowed
that fallback: where a live two-sided quote exists, the name is charged its own quoted touch instead,
never below the blend.

Neither rung can fire on a feed that publishes trade prints without a book. That is the desk's current
feed: every mark on the live endpoint carries `bid: null, ask: null`, so `quotedRoundTripByInstrument`
returns an empty map on every cycle and ADR-0099 is inert. What is left is the blend — and the blend
is a **closed loop**:

1. a name is only ever measured *after* it has filled;
2. an unmeasured name is charged the blend;
3. when the blend does not clear the ADR-0075 significance hurdle, every unmeasured name is held
   **reduce-only** — so it never fills, so it is never measured, so it is charged the blend.

The desk is inside that loop right now, and the arithmetic is entirely mechanical. At the ADR-0082 rung
the evidence selected (225 s), read off `/api/fusion/targets`:

| quantity | value (live) |
|---|---|
| `reversion` measured expectancy | +1.913961 bps |
| its standard error | 0.403198 bps |
| the most a name may cost and still clear `t ≥ 2` | 1.913961 − 2 × 0.403198 = **1.107565 bps** |
| the desk blend an unmeasured name is charged | **1.326575 bps** |

The blend is dearer than the hurdle, so **every name the desk has not already filled is permanently
reduce-only**, whatever any source thinks of it. Of the 27 names with a live mark, 8 carry a measured
round trip; of those 8 only ES (0.430), AAPL (0.897) and JPM (1.075) sit under 1.107565. The tradable
universe has frozen to whichever names happened to have filled before the hurdle tightened, and no
amount of edge anywhere else can unfreeze it. The book has been at `$0.00` gross for over an hour with
a non-empty target book and every `aim` reading exactly `0.0`.

This is a property of the measurement plumbing, not of the desk's opinion about those names. It is also
the mirror image of the gap ADR-0099 opened its own case with: "a name is only ever measured *after*
it has traded... and only then applies the veto."

## Decision

Add a **third rung** to the per-name cost ladder, below fills and quotes: a name with neither is
charged the **effective spread its own print series implies**, by the standard implicit estimator
(R. Roll, *A Simple Implicit Measure of the Effective Bid-Ask Spread in an Efficient Market*, Journal
of Finance 39(4), 1984; see also Hasbrouck, *Empirical Market Microstructure* ch. 4).

Under Roll's model — an efficient log price following a random walk, plus a half-spread bounce whose
sign is i.i.d. and independent of it:

```
  xₜ = mₜ + (s/2)·qₜ            qₜ = ±1 with equal probability, mₜ a random walk
  rₜ = xₜ − xₜ₋₁
  cov(rₜ, rₜ₋₁) = −(s/2)²       the random-walk term is serially uncorrelated and drops out
  ⟹ s = 2·√(−cov(rₜ, rₜ₋₁))
```

`s` is the full effective spread as a fraction of price; × 10⁴ makes it basis points. That is **one
round trip** in exactly the sense the other two rungs use the term — ADR-0099 states the same identity
for a quote ("crossing to enter pays the half-spread and crossing to exit pays it again"), and the TCA
rung is one-way implementation shortfall doubled. The three are three measurements of the same
quantity, in the same units, so they are directly comparable and interchangeable in the same map.

The ladder, each rung displacing the next only where it exists:

| rung | what it measures | source |
|---|---|---|
| 1 | the name's **own fills** — realised one-way shortfall, doubled | ADR-0075 / 0072 |
| 2 | the name's **own live quote** — the touch, never below the blend | ADR-0099 |
| 3 | the name's **own prints** — Roll effective spread, never below the cheapest paid | **this ADR** |
| 4 | the **desk blend** — what is left when there is no measurement at all | ADR-0075 |

### The floor, and why it makes this per-name only

Rung 3 is floored at `cheapestMeasured = min(blend, min(per-name costs))` — **the cheapest round trip
the desk has actually paid anywhere**. The desk may infer that an unfilled name is cheaper than the
average of the names it already trades; it may not infer that it is cheaper than anything it has ever
executed.

That floor is also the safety proof. `EdgeGate` takes the desk-wide verdict at the **cheapest** round
trip in the map-plus-blend. Passing that same minimum in as the floor means no entry this rung adds can
be below it, so the minimum is unchanged — and with it the desk-wide verdict, every source's
`netEdgeBps`, and the ADR-0101 no-trade buffer's reference cost are **bit-for-bit identical**. Only the
per-name hurdle of a name that had no measurement of its own moves, and only from "the average of the
names the desk already trades" to "what this name's own tape says". `EffectiveSpreadCostTest`
(`deskWideMinimumIsInvariant`) pins that.

Worked example, by hand, on `p = 100.00, 100.02, 100.00, …` (201 prints, 200 steps, sample mean 0):

```
  a   = ln(100.02/100.00) = 1.9998000266…e-4
  r   = (+a, −a, +a, −a, …)                       200 steps, mean exactly 0
  cov = mean of 199 adjacent products, each −a²   = −a²
  s   = 2·√(a²) = 2a = 3.99960005…e-4  → ×10⁴ = 3.9996 bps
```

The true touch here is 0.02 on a mid of 100.01 = 1.9998 bps, so a **perfectly alternating** tape reads
double it. That is Roll's known behaviour when signs alternate more than i.i.d.: the estimator
over-states, which is the safe direction for a cost gate. (The test also pins the odd-step case, where
the non-zero sample mean shades the reading by one part in 2·199².)

### Where it declines to measure

- **Autocovariance ≥ 0** — a trending or runs-driven tape, where Roll's model does not hold. The
  estimator returns *no measurement* and the name falls back to the blend exactly as today. It never
  takes a root of `|cov|`: manufacturing a hurdle out of momentum is the classic misuse of this
  estimator, and the test pins the refusal.
- **Fewer than 60 distinct-price steps** — a second moment on a handful of steps is noise.
- **A hole in the series** — the contiguous tail only; pairing across a gap would fabricate a return.
- **Repeated conflated prints are collapsed, not counted as zero-return steps.** Marks are ~1 Hz
  conflated, so a name that has not traded re-publishes its last price; counting those as zero returns
  drags the autocovariance toward zero and *understates* the spread — the unsafe direction. This is the
  same rule the forecast sensors already apply to a repeated stale price (ADR-0070/0071).

## Consequences

- **The universe unfreezes, priced honestly.** A name whose own tape says it is cheaper than the blend
  can open; one whose tape says it is dearer is charged *more* than it is today and stays shut. Both
  directions are the name's own measurement, which is the point. Expect gross exposure to rise from
  `$0.00`; if the reversion edge does not survive contact with the newly-admitted names, the loss is
  larger, not smaller.
- **This is the first rung that can LOWER a per-name hurdle.** ADR-0099 was deliberately one-way
  (`max(blend, quoted)`) because it had no basis for trading off two different constructions. This rung
  does have one — same quantity, same units, measured on the same stream the sensors consume — and the
  one-way property is preserved where it actually matters, at the desk-wide verdict, by the floor.
- **Known bias, accepted.** Roll under-states when prints are sparse relative to the sampling
  frequency, and a tape whose bounce is negligible reads a near-zero spread rather than declining. The
  floor bounds the damage at the cheapest round trip the desk has paid; it does not eliminate it. There
  is also a selection bias in *which* names get an estimate at all (only those whose autocovariance is
  negative), but not in the estimate itself.
- **Feed-agnostic (invariant 9).** The input is whatever price series the running feed publishes —
  never a level, an asset-class assumption or a configured spread. The same code self-calibrates to a
  quoted venue feed, a print-only feed, or the sim.
- **Cost.** One durable-history range read per still-unpriced price-quoted name per planning cycle
  (30 s), off the ring buffer, bounded by a lookback expressed in the planning cadence
  (`ROLL_LOOKBACK_INTERVALS = 20`, i.e. 10 minutes at the default) and by `MAX_RETURNS = 600`. Names
  already carrying a cost are skipped outright.
- **No invented number.** Every value is measured: the fills, the quote, the prints, the blend. The
  three constants (`MIN_RETURNS`, `MAX_RETURNS`, `ROLL_LOOKBACK_INTERVALS`) are sample sizes and a
  window length — statistical shape, not money, risk or exposure dials, and none of them sizes
  anything. The estimator gates a name and sizes nothing (invariant 7 / ADR-0016). Prices stay exact
  decimal; only the dimensionless log-return ratio becomes a `double`, at exactly the boundary
  `StreamVolatility` already draws (invariant 1).

## Alternatives considered

- **Lower the ADR-0075 t-hurdle so the blend clears.** Rejected: that loosens the test for *every*
  name including the ones already measured as expensive, and it treats a measurement problem as a
  confidence problem.
- **Charge an unmeasured name the cheapest measured cost outright** (no estimator). Rejected: it is a
  number with no relationship to the name it prices — GOOGL measures 20.1 bps against ES's 0.43, so
  the cross-section spans two orders of magnitude and a single stand-in is exactly what ADR-0075
  existed to stop.
- **Let a name trade once "to discover" its cost.** Rejected: it is a deliberate loss with no bound,
  and it is what ADR-0099 already argued against ("the desk... discovers the real figure out of its
  own PnL, and only then applies the veto — that discovery is the expensive part").
- **Corwin–Schultz (2012) high–low spread estimator.** A reasonable sibling and arguably more robust,
  but it needs bar high/low series the desk does not keep for the recent window; Roll runs on the
  print series already durable for the ADR-0071 sensor warm-up. Worth revisiting if the estimate
  proves noisy.
- **Synthesise a quote from the feed.** Rejected outright: it would invent the number the whole ladder
  exists to avoid inventing.
