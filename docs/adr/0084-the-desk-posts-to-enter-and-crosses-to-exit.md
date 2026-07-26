# ADR-0084 — The desk POSTS to enter and CROSSES to exit

- **Status:** Proposed
- **Date:** 2026-07-26
- **Supersedes:** none (refines the execution style of the ADR-0055 sole-origin order path)
- **Related:** ADR-0025 (simulated execution + TCA), ADR-0064 / ADR-0072 / ADR-0075 (the cost-aware
  edge gate), ADR-0080 (holding period = evidence horizon), ADR-0082 (the horizon ladder),
  ADR-0065 (risk-reducing deltas skip the risk-ON controls)

## Context

The fusion layer submits every order as MARKET. That means the desk crosses the spread on both legs
of every round trip, and the round trip is exactly the quantity the ADR-0075 edge gate charges each
name against its measured expectancy. So the desk's execution style is not an implementation
detail — it *is* the hurdle the strategy has to clear.

The live readings that prompted this say so plainly. At the rung the evidence selected (225 s), the
`reversion` source's measured expectancy is **+5.63 bps** with a Fama-MacBeth standard error of
**1.35 bps** over 17 cohorts — a real, well-sampled edge (t = 3.90 against the cheapest round trip,
p = 0.0006). Against that standard error and the shipped confidence dial, the largest round-trip
cost the edge can survive is **1.95 bps**. The desk's own measured round trips are:

| name | measured round trip (bps) | clears? |
|---|---|---|
| ES | 0.35 | ✔ |
| MSFT | 2.87 | ✘ |
| JNJ | 3.88 | ✘ |
| JPM | 4.18 | ✘ |
| AAPL | 4.21 | ✘ |
| GOOG | 5.95 | ✘ |
| SAP | 8.16 | ✘ |
| GOOGL | 20.11 | ✘ |
| (never filled ⇒ desk blend) | 6.34 | ✘ |

Every one of those costs is the instrument's configured **spread**, because a MARKET order pays the
half-spread on each leg and the impact term is negligible at these sizes. So exactly one name clears
— ES — and ES is the one name the ADR-0049 OOS selector never measures (it evaluates 17 equity/FX
names; the futures are outside its universe), so the executor vetoes it for having no verdict. The
book has therefore been at **$0 gross with a frozen PnL for thirteen consecutive cycles**: a desk
holding a statistically strong signal it cannot afford to trade, deadlocked on execution cost rather
than on evidence.

The five preceding changes all tightened or re-specified the *statistics* (ADR-0077, 0079, 0080,
0081, 0082, 0083). None of them touched the other side of the comparison. The cost is where the
headroom is, and it is where a real desk would look first: a mean-reversion signal that pays the
spread to enter is paying away the very premium it is trying to earn. Reversion says the price has
over-extended and will come back — which is a statement that the desk should be *supplying*
liquidity to the flow pushing it away, not demanding liquidity alongside it.

## Decision

**A fusion delta that INCREASES risk is POSTED. A fusion delta that REDUCES risk CROSSES.**

1. **Entry (risk-increasing):** submitted as a `LIMIT` `DAY` order priced at the instrument's
   current mark — the same mark the order module stamps as the order's TCA arrival price. It fills
   only if the market comes to it, and when it fills it fills at that price, so its measured
   implementation shortfall is **zero by construction**.
2. **Exit (risk-reducing, `TargetPlanner.isRiskReducing`):** submitted as `MARKET`, unchanged. A cut
   that waits for a better price is not a cut. This is the same asymmetry ADR-0065 and ADR-0080
   already apply to the gates and to the adjustment rate, now applied to the execution style.
3. **Stale intent is retired every cycle.** At the top of each planning tick — before the book is
   read — the layer cancels every working order carrying its own `fusion:` idempotency-key prefix.
   The target book is about to be recomputed from fresh forecasts and fresh marks; anything still
   resting is a plan the desk no longer holds.
4. **No mark, no post.** With no mark for the instrument the limit price would have to be invented,
   so the order falls back to MARKET and the order path rejects it for want of data exactly as
   before.

The limit price is the **mid**, not the near touch. Posting at the near touch would record *negative*
slippage and credit the edge gate with a price improvement the desk merely hoped for; the mid is the
neutral passive price — it neither pays the spread nor claims a rebate for capturing it.

### The arithmetic

Nothing here is a chosen number. The limit **is** the mark, stated at the price scale the fill and
mark records already carry, rounded away from the side being bought (DOWN for a BUY, UP for a SELL)
so a rounding step can only ever improve the posted price for the desk.

Worked example (finance-math rule), on the live MSFT reading — mark 429.327284, configured spread
2.869181818 bps, half-spread 1.434590909 bps:

```
  touch(BUY) = 429.327284 × (1 + 0.0002869181818 / 2)            = 429.388875
  MARKET BUY → fills at the touch
             → slippage = (429.388875 − 429.327284) / 429.327284 × 10⁴ = 1.4346 bps
  LIMIT  BUY posted at the arrival mark 429.327284
             → fills at the LIMIT once the ask reaches it
             → slippage = (429.327284 − 429.327284) / 429.327284 × 10⁴ = 0.0000 bps
```

A round trip is two such legs, so the desk's measured **price** cost per round trip falls from the
full spread to zero. The separate cash commission is unchanged and still charged on every fill.

### What this does to the gate

Nothing, directly — and that is the point. The hurdle, the α, the Bonferroni haircut, the degrees of
freedom and the sample minimum are all untouched. What changes is the *measured cost* the same
unchanged test is applied to. On the readings above, the identical test that rejects every tradable
name at its crossing cost accepts them at a passive one (t rises from 2.04 to 4.16 on MSFT), because
the desk genuinely got cheaper — not because the bar moved.

## Consequences

**Positive**

- Attacks the binding constraint. The desk has spent thirteen cycles unable to trade a
  well-evidenced signal; the blocker is measured cost, and this is the change that lowers it.
- Improves risk-adjusted PnL on *every* trade the desk ever does, gate or no gate: a cheaper entry
  is more PnL for the same exposure, which is the objective exactly.
- The right execution style for the source that is actually earning. Reversion profits from
  supplying liquidity into an over-extension; crossing to enter was paying away its own premium.
- Turnover cost now scales with fills that *happen*, not with intent. An entry the market never
  comes to costs nothing at all.
- No control is loosened. The firm breaker, the ADR-0049 backtest-support veto, the ADR-0059
  conviction floor, the pre-trade guardrail and the edge gate all still stand between a forecast and
  a fill, unchanged.

**Negative / honest limitations**

- **Fills become uncertain.** A posted entry may not trade. The desk will build positions more
  slowly and will sometimes miss a move entirely. That opportunity cost is real and is **not**
  measured anywhere — TCA records the cost of fills that happened, not the PnL of entries that never
  filled. Tracked in the deferred register.
- **Measured slippage now understates the true cost of an entry.** A passive fill happens precisely
  when the market moved against the order, so the cost migrates from the price (visible in TCA) to
  adverse selection (visible only in realised PnL). For a mean-reversion signal that "adverse" move
  strengthens the forecast, which is why the change is sound *for this source* — but the edge gate
  reads the TCA number, so it will read a lower cost than a fully-loaded one. The realised PnL the
  ADR-0063 loop scores on is the check that keeps this honest.
- **The pre-trade guardrail is checked at submit, not at fill.** A posted order can fill up to one
  planning cycle later against a book that has moved. The exposure window is bounded by the cycle
  (30 s) and by the per-name budget, and every order was individually approved when it was sent —
  but it is a genuine, if small, widening versus a MARKET order whose check and fill are
  simultaneous. Tracked in the deferred register.
- Order churn rises in the order table: an entry that does not fill is cancelled and re-posted each
  cycle. Cancels are cheap and the CAS makes a same-instant fill win cleanly, but the audit trail is
  noisier, so cancellations carry an explicit reason rather than the default "cancelled by user".

**Rejected alternatives**

- *Loosen the significance hurdle so the current costs clear.* This is the fifth consecutive change
  in that area and the accumulated lessons say not to stack a sixth — but more importantly it would
  be buying trades with a weaker test rather than earning them with a cheaper desk. The evidence bar
  should not move because the desk is impatient.
- *Exempt ES from the ADR-0049 backtest-support veto so the one name that clears can trade.* It
  unlocks a single name — the cheapest one, selected *by* its cost rather than by its edge —
  concentrating the whole book into one macro future, and it does so by weakening a risk control.
  Worse trade in every dimension.
- *Post at the near touch (join the bid/offer).* Better fill price, materially worse fill rate, and
  it books a negative measured cost that would flatter the edge gate. The mid is the neutral choice.
- *Cost-capped marketable limits (cross, but no worse than X bps).* Still pays the spread whenever it
  fills, so it does not move the measured round trip; it only truncates the tail. It also needs a
  cap in bps, which is a money number with no provenance.
