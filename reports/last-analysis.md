Stopped the desk from reading a change of mind as a danger cut: only a FLAT target now exits at full speed, because the fee bill from that ratchet is the large majority of the firm's loss (ADR-0090).

## Situation (read from the live endpoints; every number below is quoted, none computed here)

1. **Money.** Total PnL is **higher** than last run — the situation header reads `-323.92` against
   `+62.18` on the window and `+468.58` over the last three runs. The book is still `UNDERWATER` in
   absolute terms but it is climbing, and the 3-iteration growth target is comfortably met.
2. **Risk.** Exposure **fell hard**: gross `13,626.76` against `-65,530.23` on the window, net `394.39`.
   Historical VaR95 is `126.31`, the firm gross limit is far above this level and the drawdown breaker
   is untripped. This is the opposite of last cycle's danger state — the desk is small and balanced.
3. **Cause.** Last cycle's change (`dd1ae8584`, the ADR-0089 mark-stream covariance) scored **✅ GOOD**
   and stays in. Honest attribution: most of the exposure collapse is **mechanism, not market** — but
   not the mechanism the ADR claimed. The book was flattened at the process restart, when every sensor
   was still cold, so the held names had no view and were unwound down the ADR-0065 orphan path. The
   PnL improvement over the window is largely **market** on positions the loop did not touch. I am
   crediting ADR-0089 with the *absence* of a re-ramp rather than with the cut itself; that is the
   honest read, and it is weaker than the ledger row implies.
4. **Danger.** No. Not bleeding, exposure falling, nowhere near the breaker. So this is a cycle to
   attack the structural cost problem rather than to de-risk.

## The order-level post-mortem — what the window's fills actually did

The `recent_orders` trace is one shape repeated on every name. AAPL is bought in 3-share steps once
every 30-second cycle for seven straight minutes, then sold **101 shares in a single order**, then
immediately begins grinding back the other way. JPM: five small buys, then `-20`, `-13`, `-14`. JNJ:
two buys, one `-14`, then six buys. There is no losing *trigger* to name here — the trigger is the
policy itself. Set against the attribution: total fees are the **large majority of the firm's total
loss**, and gross-of-fees the desk is close to flat. The desk is not losing on its views; it is paying
its views away in turnover.

## Diagnosis — the mechanism

ADR-0080 derived the partial-adjustment rate so exposure e-folds toward target in exactly one
measurement horizon (`a = 1 − e^(−c/h)`, ~0.0083 at the shipped 30 s cycle and 3600 s horizon) — one
round trip per horizon of return, which is the trade the edge gate prices. But it applied that rate to
only the risk-*increasing* half of the gap and traded every *reduction* in full, justified as "a cut
that waits is not a cut". `orderDelta` never sees **why** the target moved, so it reads a mere change
of view as a danger cut. `reversion` — the one source with strong measured expectancy, and the
dominant weight — is mean-reverting by construction and crosses the held position repeatedly inside
one horizon. Every crossing liquidates the entire accumulated position at once.

So `τ_in = h` and `τ_out = 0`. The desk enters at the rate the evidence justifies and exits at infinite
rate on a wobble: it pays the **full** cost of a round trip while the position **never reaches the
size** at which a single-digit-bps expectancy could pay for it. Cost scales with turnover, which is
unconstrained; edge scales with size, which is a small fraction of what was planned. That is exactly
the "N round trips against one horizon" over-permissiveness ADR-0080 was written to remove,
reintroduced on the other side of the trade.

## The change (ADR-0090, Proposed, same commit)

One branch in `TargetPlanner.orderDelta`: a reduction toward a **flat** target is an exit and trades in
full; a reduction toward a **non-zero** target is a rebalance and is worked at the same rate as an
increase. Every control that actually means "get out" already says so by setting the target flat — the
ADR-0086 chandelier stop plans against a literal zero, the ADR-0065 orphan unwind is an implicit zero,
and the ADR-0027 breaker and pre-trade guardrail sit below this code entirely — so all of them are
unchanged, bit for bit. What is left is the exponential smoother ADR-0080's own identity describes,
e-folding toward the aim in one horizon **in both directions**, pinned as a test over three legs (up,
down, through flat). Expected: turnover and the fee bill fall sharply, and gross should fall too,
because an aim that oscillates smooths to a small position rather than a sawtooth that keeps
rebuilding to full size.

Stated trade-off, in the ADR: the ADR-0083 volatility budget and the ADR-0079/0089 concentration
multiplier *scale* targets rather than flattening them, so their de-risking is now worked over one
horizon instead of one cycle. They are sizing controls, not danger detectors; the danger detectors
flatten, and flattening is untouched. No new dial, no new money or risk number — only which half of
the existing rate applies. Full suite green.
