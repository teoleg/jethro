The desk holds a strong signal it cannot afford to trade — so I made it stop paying the spread to enter: entries now POST, exits still CROSS (ADR-0084).

## Situation (read from the live endpoints, not computed here)

1. **Money.** Total PnL is unchanged run-over-run and unchanged across the last three runs — it has
   been frozen at the same figure for thirteen consecutive cycles. Not bleeding; *stuck*, and off the
   +1% / 3-iteration target. `underwater` and `stale` are both set.
2. **Risk.** Gross and net exposure are both exactly zero and have been all thirteen cycles. Nothing
   held, nothing at risk, nowhere near the drawdown breaker. There is no exposure to cut.
3. **Cause.** Last cycle's change (ADR-0083, the volatility-split per-name budget) scored ⚠️ MIXED on
   a zero move — like the four before it, it is **unmeasured, not refuted**, because a sizing change
   cannot show up in a book that never trades. The culprit is not any one of those changes; it is
   that the desk has had **no route to a fill** since 20:21.
4. **Danger.** No — this is the opposite of a danger state. Zero exposure, zero orders. The failure
   mode here is inaction, not risk.
5. **Order-level post-mortem.** The window's orders end at 20:21 with the MSFT churn (BUY 4 / SELL 49
   / BUY 77 within minutes) and its ES hedge legs; nothing since. Every fusion target now shows
   `deltaQty: 0` against `currentQty: 0` — the planner wants a book (SAP, BRK.B, JPM, …) and routes
   none of it.
6. **Change vs market.** Attribution is exact and empty: **0% market, 0% change**. No position was
   opened, closed or resized, so nothing this cycle can be credited or blamed for anything.

## Diagnosis — the mechanism

The edge gate is **open**, not shut: `reversion` clears at the 225 s rung the ADR-0082 ladder
selected, on 391 observations across 17 cohorts, p = 0.0006. ADR-0082 is visibly working. The block
is one layer down, in **ADR-0075's per-name cost test**. Reversion's measured expectancy divided by
its Fama-MacBeth standard error can survive a round trip of at most ~1.95 bps at the shipped
confidence. Every name's *measured* round trip is its full bid/ask spread, because the desk submits
MARKET orders and crosses on both legs: MSFT 2.87, JNJ 3.88, JPM 4.18, AAPL 4.21, GOOG 5.95, SAP
8.16, GOOGL 20.11, unfilled names 6.34. Exactly one name clears — ES at 0.35 bps — and ES is the one
name the ADR-0049 OOS selector never evaluates (its universe is the 17 equity/FX names), so the
executor vetoes it for having no verdict. Every path to a fill is closed, and the last five changes
all worked the *statistics* side of a comparison whose *cost* side was the binding one.

## Decision

Change the execution style, not the evidence bar. A risk-**increasing** fusion delta is now posted as
a DAY LIMIT at the instrument's own arrival mark; a risk-**reducing** delta still crosses as MARKET,
because a cut that waits for a better price is not a cut — the same asymmetry ADR-0065 and ADR-0080
already apply to the gates and the adjustment rate. The layer retires its own working orders at the
top of each planning tick so a fresh plan is never stacked on stale intent. The limit **is** the mark
(no dial, no offset, no invented number), and it is also the right style for the source that is
actually earning: reversion profits by supplying liquidity into an over-extension, and crossing to
enter was paying away its own premium.

The hurdle, the α, the Bonferroni haircut and the degrees of freedom are all untouched — I did not
loosen a single control. What falls is the measured cost the same unchanged test reads. On the
current numbers, the identical test that rejects every tradable name at a crossing cost accepts them
at a passive one. And even if the gate stays shut a while longer, a cheaper entry is more PnL for the
same exposure on every trade the desk ever does, which is the objective exactly.

**The honest caveat, stated up front:** a posted entry may not fill, and a passive fill's cost
migrates from the price (visible in TCA) into adverse selection (visible only in realised PnL). For a
mean-reversion signal that "adverse" move strengthens the forecast, which is why this is sound for
*this* source — but the ledger's realised-PnL verdict is what keeps it honest, and both gaps are
written into the deferred register rather than glossed.
