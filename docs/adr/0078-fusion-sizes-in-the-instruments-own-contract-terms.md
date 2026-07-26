# ADR-0078: The fusion order path sizes and rounds in the instrument's own contract terms

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** trading, fusion, sizing, exposure, risk, reference-data

## Context

The fusion layer (ADR-0055) is the sole order origin. It turns a combined forecast into a target
position with a Carver vol-targeting shape, in `TargetPlanner.targetQuantity`:

```
target = (forecast / TARGET_ABS) × unitNotional / price
```

`unitNotional` is **cash** — `jethro.fusion.unit-notional-usd`, the deterministic economic exposure the
desk wants in a name at a typical forecast. Turning cash into a quantity requires dividing by the money
value of **one unit of the instrument**. That is `price` only for a name quoted per unit of currency: an
equity share, or a unit of foreign currency. For everything else it is `price × contractMultiplier`.

The rest of the platform already knows this and says so in three places:

- `PositionRisk`: `netExposure = quantity · mark · multiplier`, `grossExposure = |netExposure|`.
- `Positions.applyFill` (ADR-0008): realized PnL is `(p − avgCost) · (−q) · m`.
- The ADR-0039 hedge advisor sizes its proxy leg as `qty = −Σ βᵢ·Eᵢ / (price × multiplier)` — the same
  arithmetic, on the other order path, on the same instrument (ES).

So the fusion sizer was the one place that divided by price alone. The consequence is not a position
that is somewhat too big or too small: it is a position whose economic exposure is exactly
`contractMultiplier ×` the cash that was asked for. On the live instrument master that is **50× on ES,
20× on NQ, 1,000× on the ZB/ZF/ZN note futures, 2,000× on ZT, and 45,000–80,000× on the USD IRS legs**.
Equities and FX carry multiplier 1 and were, and remain, correct.

This was live and about to fire. The fusion target book at the time of writing carried `ES targetQty
−8.799423` against a `unitNotional` of $50,000 and a `combinedForecast` of −9.5932 — i.e. an intended
$47,966 of short S&P exposure — with a partial-adjustment delta of `−4.399712` contracts queued for this
cycle. At ES 5451.06 × 50, that single delta is **$1.2M of gross exposure**, on a firm whose entire
recorded exposure history peaks around $415k. It was queued rather than dormant for a specific reason:
the ADR-0075 edge gate admits a name only when a source's measured expectancy clears **that name's own**
measured round trip with significance, and ES — at 0.174 bps one-way, the cheapest thing the desk trades
— was at that moment the **only** name in the book that cleared it. The gate's cost test therefore
selects, systematically and by construction, precisely the futures and rates contracts where the sizing
error is largest. Cheapness to trade and largeness of multiplier are the same property of an index
future, so the two mechanisms compose in the worst direction.

There is a second half to the same assumption. `FusionExecutor` rounded every delta with
`setScale(0, RoundingMode.DOWN)` — whole units. That is right for a share and wrong for a contract: at a
$50k unit notional one ES contract ($272k) is over five times the whole target, so a *correctly* sized
ES position is inherently a fraction of a contract and whole-unit rounding erases it. Fixing the sizing
alone would therefore have silently removed every future, bond future and swap from the tradable set —
a large behavioural change arriving disguised as a rounding rule. The two are one assumption ("every
instrument is one share of an equity") and are corrected together.

## Decision

The fusion order path consults the instrument master's contract specification, end to end.

**1. Sizing.** `TargetPlanner.targetQuantity` takes the contract multiplier and divides by the money
value of one unit:

```
unitValue = price × contractMultiplier
target    = (forecast / TARGET_ABS) × unitNotional / unitValue
```

so that `|target| × price × multiplier` — the same expression `PositionRisk` uses for `netExposure` —
equals the cash that was asked for, for every asset class. `FusionPlanner.plan` gains a
`multiplierFor` lookup alongside its existing `priceFor`, wired in `FusionConfig` to
`InstrumentRefSource` — the same source `risk-pnl` values the resulting position with, so the sizer and
the risk engine cannot disagree about what a contract is.

**2. Unknown spec ⇒ no size.** A name absent from the instrument master has no contract spec, and
asserting one would be a number without provenance (ADR-0016 / invariant 7). Its target is `ZERO`, which
is reduce-only by construction, and matches what `FusionExecutor` does with such a name anyway (it
vetoes it as "not in the instrument master"). This is the same treatment `targetQuantity` already gave a
missing or non-positive price.

**3. Rounding.** `TargetPlanner.tradableQuantity(delta, multiplier)` rounds **toward zero** — so
rounding may only ever trade *less* than planned — and rounds in the instrument's own terms: whole units
when the multiplier is 1 (byte-identical to the previous behaviour for every equity and FX name in the
universe, dust suppression included), and at the quantity scale the order, fill and position records
already carry otherwise. The latter is not a new capability: the ADR-0039 hedge advisor has been
submitting fractional ES (0.0069, 0.085, 0.135 contracts) on this book all along.

**No new dial, and no minimum-size number.** Suppressing small orders remains the job of the ADR-0055
no-trade band (`jethro.fusion.buffer-fraction`) and the ADR-0059 conviction floor. A minimum notional
would be a money number requiring provenance, and none is introduced here.

**The deterministic floor is untouched** — the pre-trade guardrail, the firm drawdown breaker and the
ADR-0049 backtest-support veto all sit downstream and are unchanged. This ADR only corrects what the
layer above them asks for.

## Worked example (exact decimal; asserted in `FusionEngineTest`)

Unit notional $50,000, forecast at `TARGET_ABS` (10) ⇒ fraction 1.0.

| Instrument | Price | Multiplier | Old qty (÷ price) | Old exposure | **New qty** (÷ price×mult) | **New exposure** |
|---|---|---|---|---|---|---|
| Equity | 200 | 1 | 250 | $50,000 ✔ | **250** | **$50,000 ✔** |
| ES future | 5,000 | 50 | 10 | $2,500,000 ✘ (50×) | **0.2** | **$50,000 ✔** |
| ZF note future | 100 | 1,000 | 500 | $50,000,000 ✘ (1,000×) | **0.5** | **$50,000 ✔** |

The property under test is the last column: `|qty| × price × multiplier = unitNotional`, for every asset
class. Equities and FX are unchanged exactly, not approximately.

## Consequences

- **Exposure falls, and only on the names that were wrong.** For multiplier > 1 the target shrinks by
  exactly the multiplier; for multiplier 1 nothing changes at all. The universe's multipliers are all
  ≥ 1, so on this book the change is exposure-reducing everywhere it bites — but the guarantee being
  claimed is the correctness one ("target exposure equals the cash asked for"), not a monotonicity one:
  an instrument specified with a multiplier below 1 would correctly size *larger*.
- **Futures and rates become tradable at their true size rather than untradable or 50× oversized.** With
  the rounding fix, the ES delta the gate currently permits routes as a fraction of a contract worth the
  cash intended, instead of four whole contracts worth $1.2M.
- **The measured cost of a futures round trip becomes comparable to its measured edge.** The ADR-0075
  gate divides an expectancy in bps by a cost in bps; both are ratios and neither was wrong. But the
  *money* those bps applied to was 50× the intended stake, so the gate's arithmetic was right about a
  position the desk did not mean to hold. It is now right about the one it does.
- **Diagnostic surfaces correct themselves.** `/api/fusion` `targets[].targetQty` has been reporting
  contract counts that would never have been the desk's intent (`ZF −688.096564`, i.e. $73.9M against a
  $50k unit); operators reading that book were reading a fiction for futures.
- **A new dependency**: the fusion planner now needs reference data to size at all. That is deliberate
  and matches CLAUDE.md's refdata-as-universe rule — an instrument in the master without a contract spec
  cannot be sized, and per the house convention futures/swaps must carry their specs. The failure mode
  is fail-safe (plan flat), not fail-open.

## Alternatives considered

- **Fix the sizing only, leave whole-unit rounding.** Correct as far as it goes, but it silently removes
  every non-equity from the tradable set — including the one name the edge gate currently permits — and
  presents that as a rounding detail. Rejected: same root assumption, so it belongs in the same change.
- **Introduce a minimum order notional instead of scale-based rounding.** A cleaner general rule, but it
  requires a money number with no provenance behind it. Deferred; the no-trade band and conviction floor
  already serve the purpose. Tracked in `docs/deferred-register.md`.
- **Apply the multiplier in `FusionExecutor` at submission instead of in the planner.** Rejected: the
  target book shown on `/api/fusion` and used by the no-trade band would still be in fictional units, and
  the partial-adjustment delta is computed against `currentQty`, which is in real contracts. The units
  have to be right where the target is formed.
