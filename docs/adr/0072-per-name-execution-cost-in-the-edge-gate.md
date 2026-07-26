# ADR-0072: Execution cost is a per-name property — the edge gate must spend edge where it survives

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** execution, tca, fusion, risk

## Context

The ADR-0064 edge gate stands at the sole order origin and asks one question every cycle: does any
source's **measured** expectancy beat the desk's **measured** round-trip execution cost, with
significance? If not, fusion goes reduce-only. The construction is right and it has already paid for
itself — the one time the loop replaced it with a softer, continuous risk appetite, exposure exploded
with no PnL gain and the change was reverted.

But the gate charges **one blended cost to every name**, and that blend is a fiction. The desk's own
TCA for this feed mode, one-way implementation shortfall against arrival price, spans two orders of
magnitude across the names it actually trades:

| instrument | fills | measured one-way slippage (bps) | round trip = 2× |
|---|---|---|---|
| GOOGL | 32 | 10.0528 | 20.1056 |
| SAP | 21 | 4.0805 | 8.1610 |
| GOOG | 27 | 2.9763 | 5.9525 |
| AAPL | 21 | 2.1038 | 4.2075 |
| JPM | 26 | 2.0908 | 4.1816 |
| JNJ | 25 | 1.9412 | 3.8824 |
| MSFT | 21 | 1.4637 | 2.9275 |
| ES | 20 | 0.1456 | 0.2912 |

The blended figure the gate currently uses is a round trip of **6.9270 bps**. Compare that against the
tails: it charges ES **69× its true cost** and charges GOOGL **a third of its true cost**. Both errors
cost money, in opposite directions:

- **Over-charging the cheap names suppresses real edge.** A rates future that gives up 0.29 bps on a
  round trip is being asked to clear a 6.93 bps hurdle — a bar it will fail on almost any honest
  short-horizon signal, so the trade that would have kept nearly all of its expectancy is never made.
  This is not hypothetical: the gate has held the desk flat for many cycles, and every source is being
  measured against a hurdle that is wrong for most of the universe.
- **Under-charging the expensive names admits certain losers.** The moment the gate does open on a
  source measured at, say, +18 bps gross, the planner sizes *every* name it has a forecast for —
  including GOOGL, where the round trip costs 20.11 bps. That position loses **2.11 bps per round
  trip, by arithmetic**, at the very expectancy that opened the gate. Nothing downstream stops it: the
  conviction floor reads forecast magnitude, the guardrail reads risk limits, the breaker reads
  drawdown. None of them reads cost.

This is standard transaction-cost discipline, not a refinement. Grinold's Fundamental Law
(IR ≈ IC·√breadth, *JPM* 1989) — the argument ADR-0064 already leans on — is a statement about edge
**net of the cost of expressing it**; Grinold & Kahn (*Active Portfolio Management*, 2nd ed., ch. 16)
and Almgren–Chriss (*J. Risk*, 2000) both frame the trading decision per instrument, against that
instrument's own cost curve, precisely because pooling a heterogeneous cost cross-section destroys the
comparison. A blended hurdle is only defensible when costs are homogeneous. Here they are 69:1.

Note what is *not* being proposed: nothing here loosens the desk-wide verdict, changes the
significance hurdle, or re-litigates ADR-0064. The desk-wide test still decides whether the desk has
any edge worth paying for at all.

## Decision

**Once a source has passed the desk-wide test, re-test each name against its own measured round-trip
cost before allowing risk to be put on in it.**

For an instrument *i* and the set *P* of sources that passed the ADR-0064 hurdle this cycle:

```
grossEdgeBps   = max over s in P of avgReturnBps_s      // the edge the desk may claim, before cost
rtCostBps_i    = 2 × measured one-way slippage_i         // this name's own round trip, from its fills
mayIncrease(i) = mayIncrease  AND  ( rtCostBps_i unmeasured  OR  grossEdgeBps > rtCostBps_i )
```

A name that fails is **reduce-only**, exactly as the whole desk is when the gate is shut: existing
positions may be cut or closed, none opened or grown. The per-name test can only ever *subtract*
permission from the desk-wide verdict — it never grants one, so a shut gate stays shut everywhere and
this cannot become a back door to more risk.

Three properties make this safe to run unattended:

1. **Gross vs. its own cost, not net vs. a blend.** `grossEdgeBps` is deliberately the *gross*
   expectancy of a passing source: the desk-wide test already subtracted the blended cost to establish
   that an edge exists; charging it twice would be double-counting. The per-name comparison then puts
   the same expectancy against the cost the name will actually incur — the identical comparison
   ADR-0064 makes, at the granularity cost is incurred.
2. **Only a passing source's edge is claimable.** A source with a loud mean and a thin sample never
   passed, so its expectancy is not money the desk may spend on an expensive name.
3. **An unmeasured cost is not a cost.** A name the desk has never filled in this feed mode has no
   measured round trip, so no veto is asserted for it — inventing one would be a money-gating number
   without provenance (ADR-0016 / invariant 7). Rate-quoted instruments are excluded from the cost map
   entirely: their slippage "bp" is an additive basis point of *rate*, not a fraction of notional, and
   is not comparable with an expectancy in bps of price. This is the same unit rule the desk-wide
   average already applies.

### Worked example (the desk's own 2026-07-26 TCA, against a source measured at +18.00 bps gross)

Source `reversion`, 36 resolved observations, mean +18.00 bps, sd 30.00 bps
→ se = 30.00/√36 = **5.00**; net of the blended round trip 6.9270 = **+11.0730**;
t = 11.0730/5.00 = **2.2146 ≥ 2.0** → the desk-wide gate **opens**.

| name | round trip (bps) | kept per round trip = 18.00 − cost | ADR-0064 alone | with ADR-0072 |
|---|---|---|---|---|
| ES | 0.2912 | **+17.7088** | open | open |
| MSFT | 2.9275 | **+15.0725** | open | open |
| GOOG | 5.9525 | **+12.0475** | open | open |
| SAP | 8.1610 | **+9.8390** | open | open |
| GOOGL | 20.1056 | **−2.1056** | open — and loses on every round trip | **reduce-only** |

The row that matters is the last one. Under ADR-0064 alone the desk puts risk on in a name where the
arithmetic guarantees a loss; under this decision it does not. Both axes of the objective improve at
once: expected PnL rises (a negative-expectancy position is removed) and gross exposure falls (that
notional is never put on).

### Scope

The gate keeps its existing dials — `jethro.fusion.edge-gate.min-sample` (30) and `t-hurdle` (2.0),
both statistical conventions, unchanged. **This decision introduces no new dial and no new number.**
Every quantity in it is measured: expectancy from the ADR-0055 signal telemetry, cost from the
ADR-0025 TCA table, both scoped to the running feed mode (ADR-0029).

## Consequences

**Good.**
- The desk stops paying more to enter a name than the edge it entered on — structurally, not by
  someone noticing.
- Cheap, liquid names stop being penalised for the spreads of expensive ones. As the desk's cost
  cross-section is measured more finely, the gate gets *more* permissive where it should and *less*
  permissive where it should, without anyone tuning a threshold.
- The verdict stays fully recomputable: `bestGrossEdgeBps` and the per-name cost map are both surfaced
  on the fusion target book alongside the existing per-source arithmetic.

**Costs and risks.**
- **A wide-spread name can be locked out for a long time.** Its measured cost only falls if it trades,
  and it will not trade while its cost exceeds the claimable edge. That is the correct outcome while
  the edge really is smaller than the spread, but it does make the measurement self-reinforcing. It is
  the right trade for now — refusing a knowably-negative round trip beats keeping the sample fresh —
  and the exit is a better *ex-ante* cost estimate (refdata `spread_bps` + ADV), not a weaker gate.
- **Measured cost is a lower bound.** `execution_quality` stores the cash commission without the
  contract multiplier needed to express it in bps of notional, so the fee is excluded from both the
  blended and the per-name figure (already tracked in the deferred register). The veto is therefore
  *conservative in the wrong direction* — it under-states cost and so under-vetoes. It never vetoes a
  name that was actually cheap.
- **`grossEdgeBps` is pooled across names.** The claimable edge is a source's expectancy over its whole
  cross-section, not its expectancy in the specific name being tested. Splitting expectancy per
  (source, name) is the statistically natural next step, but at current sample sizes it would leave
  every cell far below the minimum sample and shut the desk permanently — classic overfitting dressed
  as rigour. Pooled edge against per-name cost is the honest compromise: the estimate that has the
  sample stays pooled, the quantity that genuinely varies per name is measured per name.
- Nothing here touches the deterministic floor. The pre-trade guardrail, the firm drawdown breaker and
  the ADR-0049 backtest-support veto all still stand between this and a fill, unchanged.

## Alternatives considered

- **Leave the blended hurdle alone.** Cheapest, and wrong in a way that is guaranteed to cost money the
  moment the gate opens: it would authorise the GOOGL row above.
- **Raise the desk-wide hurdle to cover the worst name.** Safe against the expensive tail, but it
  charges ES 20 bps to protect against GOOGL and would close the desk essentially forever. Solving a
  dispersion problem with a level is the mistake that created it.
- **Charge each telemetry observation its own name's cost and re-derive the source's net expectancy.**
  Statistically the most correct version of this idea, and the natural successor: it fixes the
  *desk-wide* test as well, not just the per-name permission. It needs the signal-telemetry store to
  return the instrument alongside each resolved return, which is a wider change than this one, and it
  is two-sided (it can open the gate as well as close it) where this decision is purely protective.
  Deferred deliberately, not rejected.
- **Gate per (source, name) with a per-cell t-test.** Correct in the limit, unusable now — 69
  observations across 35 names is ~2 per cell. It would read as rigour and function as a permanent
  no-trade rule.
