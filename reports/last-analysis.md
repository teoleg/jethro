Freed a trapped position: the desk was short a name its own model wanted long, forbidden to rebuild it and — because of a no-trade band sized by a target it may never reach — unable to close it either.

*Every figure below is read from this run's `logs/report.md`, the live endpoints or the ledger; none is
authored here (invariant 7 / ADR-0016 — the scorer owns every number that gates money). The band and gap
arithmetic quoted at the end is a JUnit assertion computed by code, not by me.*

## Situation — the four questions, in plain numbers

**1. Money.** Total PnL `$0.87`, **up** `+$2.04` on the last run; `+$1.50` over the last three. Realized
is fees only (`$0.04` across the two LIVE fills). The book is not bleeding. But the growth is not a
result — it is mark drift on two legs that have not changed size since 13:40:54Z, so it is not
attributable to anything the loop did.

**2. Risk.** Gross `$759.71`, net `$85.55`, gross **down** `-$0.34`. VaR95 `$8.22`, ES95 `$12.08`,
breaker `halted: false` — nowhere near it. Two positions: short 1 AAPL (`$337.98`) and the `0.001136` ES
hedge (`$421.78`). Note the shape: gross is more than twice net, because the ES leg exists solely to
neutralise the AAPL short.

**3. Cause.** Last cycle's change (the report's cost/turnover post-mortem lens) scored ⚠️ MIXED. It was
report-only, so the scorer could not see it, and the PnL move since is not attributable to it. Its own
prediction **verified**: `turnover_cost_by_name` and `recent_orders` now show LIVE rows for the first
time — per-name cost is a loop input at last.

**4. Danger.** No. Not bleeding, exposure not rising, breaker far away. Zero orders again this window
(`orders_day.total: 2`, both from 13:40Z), so per rule 37 the exposure delta is a mark, not a decision.

**5. Order-level post-mortem.** No new orders, so nothing to attribute. The two that built the book are
the whole story — and looking at what they left behind is what found this cycle's defect.

**7. Change vs market.** **100% market, 0% change.** Two legs, no orders, mark drift only.

## What I found — and it is not a market call

`/api/fusion/targets` publishes AAPL with `targetQty: 6.031064`, `currentQty: -1.0`, `deltaQty: 0`. The
desk is **short a name its own combined forecast wants it long**. The edge gate is honestly shut
(reduce-only: at the 3600 s rung reversion has 4 resolved observations against `minSample: 30`, trend is
negative — forcing that gate open is the one move here that reliably loses money, and I did not). So the
only trade available in AAPL is the cut. It was not happening, and it was never going to.

The reason is a composition failure between three controls, none of which is wrong alone. ADR-0102 clamps
the desk's *intent* to flat when the holding opposes the current view — correct. The ADR-0094 buffer then
treats that flat intent as an ordinary rebalance and measures it against a band scaled by the **target's**
implied average position — a position the shut gate forbids the desk ever to take. So the no-trade region
is sized by a position it may not hold, and any wrong-side holding smaller than that region is frozen at
`deltaQty: 0` **every cycle, indefinitely**. Not wound down slowly — stuck. And the desk had hedged that
unwanted short with ES, so it was paying gross exposure on *both* legs for a position no control wanted.

Worked and asserted as a test (`aWrongSideHoldingUnderAShutGateIsExitedNotFrozen`): average position
`6.031064 × 10 / 0.436598559661783 = 138.137520`, band `0.10 × 138.137520 = 13.813752`, gap
`0 − (−1) = 1.000000`, and `1.000000 ≤ 13.813752` ⇒ no order. Ever.

## The change (ADR-0118, Proposed)

A flat aim **in a name the edge gate has put reduce-only** is an exit, and is worked in full — the same
way a flat *target* already is (ADR-0090). One conjunctive condition in `PositionBuffer.apply`; it
resolves to flat and nothing else, so it can only ever take exposure off, never open, enlarge or flip.

The conjunction is the whole point. Reverting to "work every reduction in full" is ADR-0080, which lost
money precisely because a mean-reverting forecast crosses the holding many times per horizon and each
crossing paid a round trip — but that needs the desk to be able to **rebuild**, and a shut gate has taken
rebuilding away. A test pins that an *open* gate still buffers and rates a wrong-side holding exactly as
before. I also rejected scaling the band by the aim (it frees nothing: the gap would then be rated at
`a = 0.0083` and rounded toward zero into no order at all) and a residual accumulator (the suppressed
delta here is exactly zero, so it would carry nothing) — that second trap is real but separate, and is
registered in the deferred register rather than folded in.

Expect the desk to buy back the 1 AAPL, go flat, and the ES hedge to unwind behind it. Gross exposure
should fall on both legs; PnL should move only by the round trip and whatever the short is carrying.
`./gradlew -Pci test` green.
