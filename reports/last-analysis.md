Gave the concentration control a correlation it can actually measure: the fusion desk now prices how much of its book is one bet from the mark stream, because the daily-close covariance it was reading covers none of this book (ADR-0089).

## Situation (read from the live endpoints; every number below is quoted, none computed here)

1. **Money.** Total PnL is **lower** than last run — the situation header reads `-45.30` on the window,
   leaving the firm total negative — and the book is flagged **BLEEDING**. Over the last three runs PnL
   is up (`+119.21`), so the 3-iteration growth target is still met on paper, but the latest window went
   the wrong way and the book is `underwater`.
2. **Risk.** This is the emergency. Gross exposure went from about ten thousand to `184,719.88` in one
   window — `+171,034.78` — with net `-75,988.06`: the desk is now a large one-sided short. Historical
   VaR95 is `1,157.61`, the firm gross limit is `1,500,000` and the drawdown breaker is untripped, so no
   configured limit is close. But PnL per unit of exposure is collapsing, and that is the objective.
   Flags: **DANGER — bleeding AND adding exposure.**
3. **Cause.** Last cycle's change (ADR-0088) scored **❌ BAD** and the scorer already reverted it. That
   verdict is not the mechanism, though: exposure was ramping the same way *before* it and has kept
   ramping after. The culprit is not one change — it is the ADR-0080 partial-adjustment ramp walking the
   desk toward a **planned book** far larger than anything the risk controls priced.
4. **Order post-mortem.** The window's fills are a thrash: MSFT bought 49, sold down through 27/16/12,
   then bought back 34/16/11/9; AAPL bought 5/9/8 then sold 56/36/27/23/19 four minutes later. `reversion`
   sits at its ±20 forecast cap on most names and flips sign inside minutes, and each flip pays a round
   trip — fees are a large share of the realised loss, nearly all of it in the ALPHA book.
5. **The structural read — what I acted on.** The planner's targets are short *nearly every name it
   covers*, each at a full per-name budget. The two controls that would price that — the ADR-0079
   concentration multiplier and the ADR-0083 volatility budget, the only two that look at the book rather
   than at a name — both read a `daily_close` covariance, and under ADR-0073 that series admits an
   instrument only after several consecutive sessions **in the running feed mode**. This stream has two.
   So the estimate covers nothing (the parametric VaR built on the same covariance reports its entire
   exposure as *skipped*), both controls silently no-op, and a book that is arithmetically one position
   carries N independent budgets of risk. That is not the market moving against us; it is a control
   absent exactly where the concentration is — ADR-0086's lesson about σ, one layer up, on the pairs.

## The change

Measure the covariance on the **mark stream** — the series ADR-0086 already fell back to for σ, for this
exact coverage reason: an EWMA of return cross-products over synchronised per-cycle mark snapshots, fed
the same prices the plan was made from, seeded from the durable mark history replayed on one bucket grid
of the feed's own clock (a covariance of returns taken at different instants measures the misalignment).
One estimator per cycle, chosen by which covers more of the book being planned, ties to the incumbent
daily estimate, never a blend. Both consumers are homogeneous of degree zero in the covariance, so no
trading-day convention is invented and the sampling period cannot move a size; both are one-way — the
multiplier is capped at 1, the budget at the gross it replaced — so this can only ever **shrink** the
book, which is the right shape of change for a danger state. No money dial, no gate loosened, no
deterministic floor touched. Full suite green.

**Attribution, honestly.** My change gets credit for none of this window: it went in after the
measurement. The window's PnL fall is mostly *market*, on positions the ramp had already opened; the
exposure rise is *mechanism* — the ramp, not the market. What I expect next is gross falling on an
unchanged view. If exposure comes down and PnL is not worse, the correlation haircut is working; if
gross does not move at all, the estimator is not covering the book and the **seed** is the first suspect,
not the control.
