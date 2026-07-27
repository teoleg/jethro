# ADR-0085 — The OOS gate measures each instrument in its own contract terms and at its own cost

- **Status:** Proposed
- **Date:** 2026-07-26
- **Supersedes:** none (corrects the measurement the ADR-0049 backtest-support gate reads)
- **Related:** ADR-0049 (backtest support is the hard gate on putting risk on a name), ADR-0043 /
  ADR-0027 (the per-instrument OOS selector and its multi-seed median harness), ADR-0025 (simulated
  execution cost per asset class, per-name spread from refdata), ADR-0078 (size in the instrument's
  own contract terms on the live order path), ADR-0075 (the per-name cost test in the edge gate),
  ADR-0084 (post to enter, cross to exit)

## Context

ADR-0049 makes the out-of-sample backtest the hard gate on risk-taking: the fusion executor will not
open or grow a position in a name unless the OOS selector has found a positive-edge algo for it, and
that veto is deliberately **fail-closed** — an unmeasured name is refused, not defaulted through.
That is the right polarity. It also means the gate is only as good as the measurement behind it, and
a name the harness *cannot* measure is barred forever without anyone deciding to bar it.

Two defects in the harness meant it modelled every instrument as if it were an equity share.

**1. It sized in whole units.** `BacktestEngine.decideQuantity` divided the target notional by the
money value of one unit and rounded to scale 0. For a share that is right and unchanged. For a name
whose unit is a **contract** it is not: one ES contract is worth `price × 50`, so a correctly-sized
position in it is a fraction of one contract. The whole-unit rounding produced 0, the code then read
"one unit exceeds the order cap" and returned *unsizeable*, and the name never traded on any seed.
This is exactly the arithmetic ADR-0078 corrected on the live order path — `TargetPlanner
.tradableQuantity` keeps the contract scale for a multiplier ≠ 1, and the ADR-0039 hedge advisor has
always submitted fractional ES — but the correction was never applied to the measurement path.

**2. It charged one blended cost.** `BacktestWiring` passed `execution.perFillCostBps("EQUITY")` —
the equity half-spread plus fee — and the engine charged it to every instrument, while the class
javadoc asserts the opposite ("Backtest per-fill cost derives from the same numbers so the two P&L
sources agree by construction"). The live `SimulatedExecutor` charges each name **its own** refdata
spread (`OrderConfig` reads `InstrumentRef.spreadBps()`, class config as fallback) plus its class
fee. On this universe those disagree by an order of magnitude in both directions.

The consequence is not academic. **No FUTURE has ever received an OOS verdict.** The selector's last
run measured 17 names — 14 equities and 3 FX — out of a 19-name price-quoted universe; ES and NQ were
absent, and the fail-closed veto therefore refused them. Meanwhile the ADR-0075 edge gate had reached
the opposite conclusion about exactly those names: at the evidence-selected 225 s rung, the desk's
measured `reversion` expectancy survives a round trip of well under a basis point, and **ES, at a
measured 0.35 bps round trip, is the only name in the book that clears it.** The gate says "this is
the one name worth paying to trade"; the selector says "I have no opinion, so no". The book has sat
at exactly **$0 gross exposure with a frozen PnL for fourteen consecutive cycles**, planning a
non-zero ES delta every 30 seconds and discarding it — while every equity the selector *did* bless
is priced 8× to 57× above what its edge can carry.

So the binding constraint was never the statistics of the edge gate (five prior cycles re-specified
those) nor, on its own, the execution style (ADR-0084). It was that the desk's cheapest names were
structurally invisible to the gate that decides what it may trade.

## Decision

The OOS backtest measures each instrument the way the desk would actually trade it.

1. **Size in the instrument's own contract terms.** A name quoted per unit of currency (multiplier 1
   — every equity and FX pair here) keeps whole-unit rounding, unchanged. A name whose unit is a
   contract sizes at the quantity scale the order, fill and position records already carry. Rounding
   stays `DOWN` in both cases, so it can only ever size *less* than the target notional asks for.
   This is ADR-0078's rule, applied to the measurement path so the backtest takes the position the
   live path would take.
2. **Charge each name its own per-fill cost.** `BacktestConfig.Instrument` carries a `costBps`;
   `BacktestService` derives it exactly as `OrderConfig` derives the live one — half the instrument's
   own refdata spread (class-configured spread as the fallback) plus its class fee — and the engine
   charges that name that rate. The config-wide `costBps` remains the charge for a name nothing can
   price, and an explicit cost passed by a caller (the REST what-if) still replaces every per-name
   figure, so that path is unchanged.

No gate is loosened, no hurdle moved and no number invented. Every input already exists with
provenance: the multiplier and `spread_bps` are reference data, the fees are the ADR-0025 execution
config. What changes is that a measurement which was structurally impossible becomes possible, and
the futures earn a verdict on their own evidence — pass or fail.

### Worked examples

*Sizing.* ES at 5450 with multiplier 50: one unit is worth `5450 × 50 = 272,500`. A 25,000 target
notional is `25,000 / 272,500 = 0.091743…` contracts → `DOWN` at scale 6 → **0.091743**, an exposure
of `0.091743 × 5450 × 50 = 24,999.97` — the size that was asked for. Before: `DOWN(0.0917, 0) = 0`,
then `272,500 > 50,000` order cap ⇒ *unsizeable*, zero trades, no verdict, permanently vetoed.
AAPL at 190 with multiplier 1: `DOWN(25,000 / 190, 0) = 131` shares — identical to before.

*Cost.* ES: `0.46 bps spread ÷ 2 + 0.2 bps FUTURE fee = 0.43 bps`. On the 24,999.97 fill above that
is `24,999.97 × 0.43 / 10,000 = $1.075`. The old flat equity charge of `5 ÷ 2 + 1 = 3.5 bps` billed
`$8.75` — **8.1× what the live executor would charge for that same fill**. In the other direction,
GOOGL's own spread is 20 bps, so its per-fill cost is `20 ÷ 2 + 1 = 11 bps`, more than three times
the blend it used to be flattered by: the change makes the gate *harder* for the expensive names and
easier only for the cheap ones, which is the point.

*Measured effect on the verdict.* Replaying the selector's harness (9 OOS seeds × 25,000 ticks, both
algos, live params) over the same universe: ES goes from **0 trades / no verdict** to **8 trades with
a positive momentum median**, i.e. from vetoed to backtest-supported. NQ trades but medians to zero,
so it stays out — the fail-closed veto still does its job on a name with no measured edge.

## Consequences

- The fusion desk gains a route to a fill on the one name whose measured cost its own edge gate says
  is worth paying. Expected exposure is small: the planner's current ES delta is ~0.0099 contracts
  (≈ $2.7k) against a target of ~0.079 (≈ $21.6k), inside the MACRO book's limits.
- The direct strategy path is not auto-executing, so this reaches money only through the ADR-0055
  sole-origin path, behind the firm breaker and the pre-trade guardrail, both untouched.
- Fusion routes futures to the **MACRO** book; the hedge advisor trades its ES proxy in **HEDGE** and
  reads its held-hedge feedback from that book alone (`jethro.hedge.book`), so the two do not collide
  — the separation this ADR relies on is the one ADR-0039 already designed for.
- Existing equity/FX verdicts may change, because several were measured at a cost 1.75× too high
  (AAPL, JNJ, JPM at 2.0 bps rather than 3.5) or roughly 3× too low (the 20 bps names at 11 rather
  than 3.5). That is the measurement becoming correct, not a relaxation; and the ADR-0075 per-name
  gate still refuses every equity here on its own measured round trip regardless of the verdict.
- **Risk of being wrong:** if ES's OOS edge is a property of the backtest's independent-random-walk
  tape rather than of the live correlated sim, the desk will take a small position that does not pay.
  That shows up in the next ledger verdict as PnL flat with exposure up (❌ BAD) and is auto-reverted.
  The measurement fix itself would still stand; what would be refuted is ES's edge, not the change.

## Alternatives considered

- **Relax the ADR-0049 veto for names with no verdict (fail-open).** Rejected: fail-closed is the
  correct polarity for "may I put risk on this name", and it is the deterministic backtest gate the
  whole autonomy story rests on. The defect was the missing measurement, not the veto.
- **Raise `max-order-notional` so a whole ES contract fits.** Rejected: it would let the harness size
  one $272k contract where the desk wants $25k of exposure — measuring a position 11× the intended
  one, and quietly raising the order cap for every other name too.
- **Fix only the sizing, leave the blended cost.** Rejected: ES would then be measured at 8.1× the
  cost the live executor charges it, so a positive-edge name could be declared NO_TRADE by an
  overcharge — which is the same class of defect one layer down.
- **Correct ADR-0084's round-trip arithmetic first** (the gate still charges `2 ×` one-way slippage
  measured under a policy where both legs crossed, though entries now rest). Real, and now the
  clearest remaining lever — but it is a separate change, and on its own it does not open a single
  name: halving every measured cost still leaves the cheapest equity at roughly twice what the edge
  can carry. Tracked for the next cycle.
