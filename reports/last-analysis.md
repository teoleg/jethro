Found the desk netting its target against its own hedge book, so every contract the hedger bought was answered by an equal short opened in a strategy book — the hedge was exactly cancelled and its notional counted twice in gross (ADR-0091).

## Situation (read from the live endpoints; every number below is quoted, none computed here)

1. **Money.** Total PnL is **higher** again — the situation header reads `-224.60` against `+89.91` on
   the window and `+399.03` over the last three runs. Still `UNDERWATER` in absolute terms, but the
   3-iteration growth target is met several times over. Not bleeding.
2. **Risk.** Exposure **rose hard**: gross `64,650.20` against `+33,984.70` on the window — it roughly
   doubled — with net `-14,733.72`. Historical VaR95 is `516.76`, the breaker is untripped and the firm
   gross limit is far above this level, so nothing is close to binding. The flag that matters is
   `EXPOSURE RISING`, and it is the trajectory, not the level.
3. **Cause.** Last cycle's change (`b14caf54a`, ADR-0090) scored **⚠️ MIXED** — PnL up, gross up — and
   stays in. That is the outcome last cycle's own note predicted and named the follow-up for. Honest
   attribution: ADR-0090 did what it was built to do (positions now persist instead of being zeroed on
   every forecast crossing), and persistence is *why* gross grew. But it is not the reason gross grew
   this much, and the real reason predates it.
4. **Danger.** No — PnL rising, nothing near a limit. So this is a cycle to attack the exposure
   mechanism rather than to de-risk.

## The order-level post-mortem — what the window's fills actually did

The trace splits cleanly in two. On the equities the ADR-0090 grind is visible and much calmer than
last window: MSFT and JPM step in single-digit lots, JNJ in twenties, and the 100-share single-order
reversals are gone. That part is working.

The futures are a different story, and it is the whole story. `MACRO` sells ES on almost every cycle —
`-0.021379`, `-0.021984`, `-0.018927`, `-0.023809` — while `HEDGE` **buys** ES on almost every cycle,
`+0.030490`, `+0.057041`, `+0.026591`, four seconds apart from MACRO's sells. Two books of the same
firm crossing the same contract in opposite directions through the street, all window.

The book that results: `MACRO` short `0.060884` ES, `HEDGE` long `0.050697` ES. That is `$30,408` of
gross exposure — **47% of the firm's total** — carrying `-$2,776` of net, **9%** of it. And the hedge
book is the firm's single worst position: its loss, `-244.03`, is larger than the firm's entire loss of
`-224.60`, while the two strategy books together are positive. Almost all of it is realised rather than
mark-to-market — paid on round trips, not lost on a view.

## Diagnosis — the mechanism

`FusionConfig` hands the fusion lifecycle two suppliers that disagree about who owns the hedge book.
`heldInRoutedBooks` **excludes** it, and its javadoc says exactly why: "two legs where there was one,
gross exposure up, and the two loops fighting each other every cycle." `firmPositions`, three lines
below, **summed every book** — so the quantity the planner measured its gap against included the
hedger's leg. The failure the first supplier was written to prevent arrived by the second route: not
through the *span* of the target book, through the *arithmetic* of the gap.

That closes a loop. The hedger drives `e + h → floor` by trading `h`, reading `h` from its own book.
The planner drives `s + h → target` by trading `s`, because `current = s + h`. Neither sees the other's
control variable, and each one's action moves the other's error term: every contract the hedger buys
raises the planner's `current` by one, so the planner sells one more into a strategy book — and that
sale does not touch the cash-equity exposure the hedger measures, so the hedger's target is unchanged
and its leg stays on. The fixed point is `s = target − h`. The firm holds `|target − h| + |h|` where the
economics call for `|target|`; the hedge is exactly cancelled, present in gross and in the fee bill and
contributing nothing to net; and the offsetting leg grows with `h`, so there is no fixed point in gross
at all. The planner's own target book gives it away: it reported `ES: current = +0.011200` — a
*positive* current on a contract the strategy book is short by five times that.

## The change (ADR-0091, Proposed, same commit)

`firmPositions` becomes `routedBookPositions(risk, hedgeBook)` and skips the configured
`jethro.hedge.book`, exactly as its neighbour already did. The two suppliers now answer the same
question the same way — one says *which* names the desk is responsible for, the other *how much* of each
it holds — and neither treats a book this layer cannot trade as its own inventory.

It is a signed `BigDecimal` sum with one book filtered out: no new dial, no new money or risk number, no
rounding introduced, and every instrument the hedge book does not hold is unaffected quantity for
quantity. The hedger, the pre-trade guardrail and the firm breaker are untouched. Worked example, pinned
as a test on the live figures: at convergence the old read settles the strategy leg at `target − h =
-0.210797` and the new one at `target = -0.160100`; the gross removed is exactly `h`, the hedger's own
notional, and the firm's net moves toward flat by the same amount because the hedge finally offsets
instead of being traded away. Stated trade-off in the ADR: the firm may still hold a view and a hedge in
the same contract as two book-level legs — bounded and honest, and strictly smaller than what the old
read converged to. Netting the two *intents* into one firm position is the better next step but crosses
the fusion/hedge boundary and changes book attribution, so it is deliberately not attempted here. Full
suite green.
