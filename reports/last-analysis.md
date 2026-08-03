The pending change scored ❌ BAD with a failed revert — but completing that revert would re-apply ADR-0135, which was ALSO scored ❌ BAD, so I refused the mechanical precedent and instead fixed a live defect the order log caught: below the conviction floor the desk was re-sizing positions it had declared too weak to open.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/signals/telemetry`, the `tca`
section, `recent_orders`, `turnover_cost_by_name`, or the scorer's own output. None is authored here —
invariant 7 / ADR-0016.)*

## Situation — the four questions

1. **Money.** Total PnL **`$-264.50`**, **`+33.98`** since the last run, **`-74.64`** across the last
   three. `UNDERWATER` and off the +1%/3-iteration target. The one-run move is up; the three-run trend
   is still down.
2. **Risk.** Gross **`$54,238.11`** — **3.6%** of the firm cap `$1,500,000`, headroom **`$1,445,762`**;
   net **`$6,915.96`**, **0.7%** of the `$1,000,000` net cap. Gross **rose `+28,429.35`**. The only flag
   is `UNDERWATER`: no cap proximity, no breaker. A book at 3.6% of its budget rebuilding is the
   intended direction (ADR-0132), not a risk event.
3. **Cause.** `c20fb0b70` **scored ❌ BAD** this cycle (`-0.000563` risk-adj return/cycle over 7 cycles,
   t=`-1.54` against a `1.5` hurdle; gross `48,013 → 54,236` [grew]). Its auto-revert **failed on a git
   conflict** and `reports/.pending-baseline.json` is gone, so I am free to change code again.
4. **Danger.** No. `DANGER` is bleeding *near* the cap or the breaker; this is bleeding at **3.6%** of
   gross cap. The answer is to fix what loses money, not to cut exposure that is barely deployed.

## Step 0 — and the reason I did NOT follow the standing precedent

Rule 252 says: when the ledger reports REVERT FAILED, completing that revert by hand is the cycle's one
change. That is right twice over (ADR-0133, ADR-0135) and it is **wrong here**, for a reason no prior
cycle has hit:

> **`c20fb0b70` IS the revert of `74a47adee` (ADR-0135) — and `74a47adee` was itself scored ❌ BAD.**

Reverting the revert re-applies a mechanism the scorer already rejected, which the contract forbids
outright ("never re-attempting the reverted idea"). Both directions of one branch are now graded BAD:

| commit | what it did to the breadth-collapse exit | risk-adj/cycle | t | gross | verdict |
| --- | --- | --- | --- | --- | --- |
| `74a47adee` | unestimable view **holds** instead of liquidating | `-0.000387` | `-1.59` | `0 → 51,059` [grew] | ❌ BAD |
| `c20fb0b70` | **restores** the liquidation | `-0.000563` | `-1.54` | `48,013 → 54,236` [grew] | ❌ BAD |

**A change and its exact inverse cannot both be the cause of the same deterioration.** When A and ¬A
both grade BAD, the graded variable is dominated by something neither touched — here the continuous fee
bleed, which runs at the same rate under both. I am recording that as the finding and leaving the code
where it stands, rather than laundering a rejected mechanism back in through a procedural rule.

## What the numbers say is actually wrong

`ALPHA -329.32225499` on `feesPaid 334.905421`; `HEDGE +121.61984242` on `10.030226`;
`MACRO -56.79950536` on `1.109154`. **`totalFees 346.044801` exceeds the entire deficit
`firmTotal -264.50191793`** — gross of fees the strategy book is roughly flat. `turnover_cost_by_name`
sums to ~`$3.3M` of equity turnover at `fee_bps 1.00` on a book of `$54,238.11`. And on the clustered
denominator (Rule 259) no source is significant at any horizon. **The desk has no measured edge and is
paying a large fee bill to express it**, so the lever is turnover, not weights.

## The defect I fixed — the desk trades a view it has declared too weak to act on

`recent_orders`, BAC held short, all tagged `fusion reduce toward a smaller target`:

| time | order | forecast | floor |
| --- | --- | --- | --- |
| 18:53:15Z | `BUY 1` | `0.3474462086964614` | 5.0 |
| 18:54:15Z | `BUY 1` | `0.5363969925205933` | 5.0 |
| 18:55:16Z | `BUY 1` | `0.7016479414426884` | 5.0 |
| 18:55:47Z | `BUY 1` | `2.835935684486436` | 5.0 |
| 18:56:17Z | `BUY 1` | `1.72006937226672` | 5.0 |
| 18:59:49Z | `BUY 1` | `1.82687778188073` | 5.0 |

Six separate fills, each leaving the short open, none on a view that clears the floor the desk uses to
*open* a position — and the forecast is **rising** across them. `GOOG` (`-1.548`, `-3.930`) and `NVDA`
(`-4.886`, `-3.415`) did the same thing in the same window: **10 sub-floor partial reduces**.

The mechanism is a waiver that over-reached. ADR-0065 waived the ADR-0059 conviction floor for every
risk-**reducing** delta, because applying it there "would permanently trap exactly the positions whose
view has decayed to nothing". That reason is about letting a position **out**; it was keyed on
`isRiskReducing` alone, which is just as true of a partial rebalance. So a name below the floor kept
being walked toward a target computed **from** that sub-floor forecast, every cycle, paying fee and
spread each time.

**ADR-0136 (shipped):** below the floor a name has two states — **held** or **flat** — and is never
re-sized. The waiver now applies to an order that takes the position **exactly flat**, and nothing else.
Keyed on the order landing flat rather than on `targetQty == 0`, because ADR-0118's trapped exit plans
the whole position out with `targetQty` still non-zero. Every control that means *get out* plans the
name flat (ADR-0086 cut, ADR-0065 unwind, ADR-0027 breaker), so each passes untouched — the
deterministic floor is not narrowed by one order. Strictly one-way: it can only ever suppress a trade.

**Scope, stated honestly:** these sub-floor reduces are a **minority** of the window's turnover — the
majority is the breadth-collapse round trip (`BAC BUY 435`, `KO BUY 175`, `MCD BUY 33`, `JNJ BUY 26`),
which this deliberately does not touch, because both remedies tried there scored BAD. This is a real
but partial attack on the cost problem, not a fix for the deficit.

## Change vs market

Nothing shipped last cycle (`c20fb0b70` was under measurement all window), so **none** of the
`+33.98` PnL move is attributable to a code change — it is mark drift on positions the loop did not
touch. The `+28,429.35` gross rise is the desk rebuilding after the 18:05–18:15Z flattens, i.e. the
restored branch of the now-BAD `c20fb0b70` still selecting trades; recorded against it, not excused.
The positions block is cumulative, so no per-name decomposition of the window delta is claimed.
