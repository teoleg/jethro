61 of the desk's 65 round-trips post passively to enter and cross the spread to exit — and the two worst of them, both on delayed-feed names, are more than the whole firm's realized loss.

*(No code change this cycle — the pending change is still under measurement at 5/6 cycles. Every figure
below is read from the live endpoints, the order book, or `reports/run-status.json`, or computed from them
by script; none is authored here — invariant 7 / ADR-0016.)*

## Situation

1. **Money.** Underwater and still bleeding. SITUATION header: total PnL **−$87.19**, **−$6.42** vs last
   run, **−$49.98** over the last 3. `pnl_growth_pct` **−160.16%** against the +1%/3-iteration target;
   `on_track=false`, `stale=true`, `underwater=true`.
2. **Risk.** Gross **$32,961 = 2.2%** of the $1.5M firm cap (headroom **$1,467,039**); net **−$4,094 =
   0.4%** of the $1M net cap. No cap flag, no breaker. Exposure is still rising off dormant, which is the
   goal — **not** the DANGER state.
3. **Cause.** Last change `d9f8969cc` (the closed-market deploy fix + the owner's exploration mode) is
   **still accumulating evidence (5/6)** — no verdict. It did deploy: the desk is routing 12 instruments
   where it previously held nothing, which is exploration mode live. No code change has been made in the
   three cycles since, so **none of this window's move is attributable to a new change of mine** — it is
   the standing exploration configuration trading against the market.
4. **Danger.** No. Bleeding, but with ~98% of the gross cap unused.

## Order-level post-mortem — the execution scheme is one-sided

Reconstructing every LIVE round-trip FIFO from `fills` joined to `orders`:

- **61 of 65 round-trips are LIMIT-entry → MARKET-exit**, aggregating **−11.6 bps** on **$39,363** of
  round-tripped notional (**−$45.53** before fees). Median holding period **27.4 minutes**.
- This is `FusionExecutor.route` working exactly as ADR-0084 specifies — *"an entry POSTS, an exit
  CROSSES"*: a risk-increasing delta rests as a DAY LIMIT **at the mid**, a risk-reducing delta goes
  **MARKET**. So the desk pays the crossing cost on **100%** of exits and captures spread on **0%** of
  entries, at a 27-minute turn rate.
- **Fees are not the story.** Explicit fees are **$10.83** of the **$51.61** realized loss (**21%**);
  the other **79%** is adverse price movement between entry and exit. A limit resting at the mid only
  fills when the market comes *to* it — i.e. when the move went against the view — so the fills are
  adversely selected by construction, and that dwarfs the 0.89 bps of commission.
- Holding-period buckets are **too thin to act on** (n=65; 20–30 min is +7.7 bps while 30–60 min is
  −32.1 bps). Logging, not chasing.

## The loss is still concentrated in the delayed cohort

Splitting the book by whether a name carries real-time `alpaca` symbology (the startup log confirms
*"Alpaca real-time WS for 7 equities + Yahoo (delayed) poll for 21 price-quoted names"*):

- **DELAYED** (ES, GOOGL, NQ, TSLA): **27.6%** of turnover, **67.5%** of total loss — still **2.4×** its
  turnover share, versus 2.8× last cycle. Nowhere near the parity the VERIFY-BY asks for.
- Its realized loss is **three round-trips**: NQ **−84.3 bps** (2 RTs, −$35.65) and GOOGL **−27.7 bps**
  (1 RT, −$19.39) = **−$55.04**, which is *more than the firm's entire realized loss of −$51.61* — the
  rest of the book is net positive on realized.
- **TCA is blind to it, as predicted.** NQ's measured `avgSlippageBps` is **0.033** while its realized
  round-trip cost is −84.3 bps, because `arrival_price` is stamped from the same stale mark the limit was
  posted at. Measured slippage on these names is ~0 by construction.

## The agreement scaler is inverted, not merely degenerate

`/api/fusion/targets` this cycle, single-source versus corroborated names:

| sources | agreement | \|combinedForecast\| |
|---|---|---|
| 1 (NFLX) | 1.000 | **20.00** (the cap) |
| 1 (TSLA) | 1.000 | **18.95** |
| 1 | 1.000 | **18.55** |
| 3 | 0.993 | 10.33 |
| 3 | 0.981 | 6.62 |
| 3 | 0.832 | 5.80 |

The VERIFY-BY asked for single-source magnitudes to sit **below** corroborated ones. They sit **2–3×
above**, at the ±20 cap, because `agreement` returns 1.0 when there is nothing to disagree with. Every
one of those single-source names is driven by `xsreversion` alone — and they are the discovery-promoted
names, which `UniversePromotionService` writes as yahoo-only by design (rule 71). So the two defects
compound: maximum conviction is handed to precisely the names priced on a 15-minute delay. TSLA's target
is 192 shares against a current 1 — the repeated cancel/replan and the `no market data for TSLA` rejects
in `recent_orders` are that oversized target failing to execute.

## Decision

No change — the pending one is being measured (5/6), and piling a change on top would destroy the
evidence. The must-fix register is re-verified and re-ranked: **#1 is the price-age tradability gate**
(the delayed cohort is >100% of realized loss, the fix is a clean gate, the falsifiable check is already
written), **#2 the agreement scaler at `sources=1`** (it sizes exactly those names at the cap), and the
churn item is **re-characterized and demoted to #3** — its original framing, "round-trip *cost* is the
loss", is wrong in emphasis now that fees are shown to be only 21% of it; the real defect is the one-sided
LIMIT-in/MARKET-out execution scheme, which is an ADR-0084 decision and needs a superseding ADR, not a dial.
