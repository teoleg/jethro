82% of the firm's loss sits in one cohort — the names priced off a 15-minute-delayed feed, where the desk posts limit orders at a price the market left a quarter-hour ago.

*(No code change this cycle — the pending change is still under measurement at 4/6 cycles. Every figure
below is read from the live endpoints, the order book, or `reports/run-status.json`, or computed from them
by script; none is authored here — invariant 7 / ADR-0016.)*

## Situation

1. **Money.** Underwater. The SITUATION header has total PnL **−$58.29** (+$12.46 vs last run, **−$27.25**
   over the last 3); a live read mid-analysis showed **−$69.04**, so the book is swinging within the window
   rather than trending. `pnl_growth_pct` is far below the +1%/3-iteration target, `on_track=false`,
   `stale=true`, `underwater=true`.
2. **Risk.** Gross **$31,727 = 2.12%** of the $1.5M firm cap (headroom **$1,468,272**); net **−$4,148 =
   0.41%** of the $1M net cap. No cap flag, no breaker. Exposure is *rising off dormant*, which is the goal
   — this is **not** the DANGER state, so the correct response is to fix the bleed mechanism, not de-risk.
3. **Cause.** Last change `d9f8969cc` (the closed-market deploy fix + the owner's exploration mode) is
   **still accumulating evidence (4/6)** — no verdict yet, and it did not open these positions. The loss is
   not attributable to it.
4. **Danger.** No. Bleeding, but with ~98% of the gross cap unused.

## What the orders say

Splitting the book by market-data feed — the 7 names carrying real-time `alpaca` symbology vs everything
priced only by the delayed `yahoo` poll — separates the P&L almost perfectly, and the two halves reconcile
to the firm total exactly:

- **REALTIME** (AAPL, AMZN, GOOG, JNJ, JPM, MSFT, NVDA): realized **+$11.13**, total **−$12.60**;
  **+1.81 bps** of its $61,481 turnover.
- **DELAYED** (GOOGL, NQ, TSLA, ES): realized **−$56.67**, total **−$56.44**; **−23.04 bps** of its
  $24,592 turnover.

**29% of the turnover is producing 82% of the loss**, and it is almost entirely *realized* — round-trips,
i.e. trading logic, not market drift on untouched inventory.

## Mechanism

`FusionExecutor.passiveLimitPrice` posts the limit **at the last mark**, read from `LastPriceCache` — a
last-value map with **no age check** (`FusionExecutor.java:180-190`, `LastPriceCache.java:21-37`). For
yahoo-only names that mark only steps every ~15 minutes, so the desk posts DAY limits at a price the market
left a quarter-hour ago. The order-book evidence is unambiguous — over a 3-hour window, distinct limit
prices vs number of reposts:

| | reposts | distinct prices | fill rate |
|---|---|---|---|
| GOOGL / ORCL / NFLX / META | 19 / 16 / 5 / 29 | 1 / 1 / 1 / 2 | 5% / 0% / 0% / 0% |
| NVDA / JPM / MSFT / AAPL | 11 / 18 / 22 / 13 | 11 / 18 / 21 / 11 | 100% / 83% / 77% / 77% |

29 META orders over 52.9 minutes collapsed onto **2** prices; NVDA got a distinct price on all 11. Same
market, same 30-second cadence — that is a property of the feed, not of the market. A stale limit only
trades when the market comes *back* to it, i.e. when the move went against the view, so fills are adversely
selected by construction. And it is structural, not incidental: `UniversePromotionService.java:67-73`
deliberately writes yahoo-only symbology, so **every** discovery-promoted name (GOOGL, META, ORCL, NFLX,
TSLA) lands in the delayed cohort by design.

This also contaminates the measurement the edge gate runs on: **29.7%** of resolved observations on delayed
names have `exit_mark == entry_mark` exactly, versus **3.7%** on real-time names. Roughly a third of the
evidence on those names is a frozen non-observation booked as a flat return. The standing "no source has
demonstrated edge" conclusion is therefore partly a data-quality artifact — you cannot measure a
225–3600s expectancy on a price that steps every 15 minutes. TCA is blind to it too, because
`arrival_price` is written from the same stale mark, making measured slippage ~0 by construction.

## Decision

No change — the pending one is being measured. **Queued for the next cycle** (architecturally significant,
so with a Proposed ADR): gate tradability on *price age* — a name whose mark is older than the horizon the
desk plans on is reduce-only, and/or a discovery-promoted name must earn real-time symbology before it
becomes tradable. This supersedes the rule-68 explanation: the newly-promoted names are not primarily
losing because they are uncorroborated, they are losing because they are **priced on a 15-minute delay**.
