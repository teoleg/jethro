# ADR-0091: The desk does not net against its own hedge

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** backend, fusion, hedging, exposure, cost
- **Relates to:** ADR-0055 (fused target book), ADR-0065 (the target book spans held positions),
  ADR-0019 / ADR-0039 (the auto-hedger and its `net = e + h` identity), ADR-0042 (proxy selection)

## Context

The fusion planner sizes a target per name and trades the gap to it:

```
gap = target − current
```

`current` was supplied by `FusionConfig.firmPositions`, a sum of the risk projection's quantities
across **every** book — including `HEDGE`.

The names the planner is *responsible* for came from a different supplier, `heldInRoutedBooks`, which
**excludes** the hedge book. Its javadoc already states exactly why:

> The hedge book is excluded. Its position is not a view — it is the ADR-0019 hedger's own target,
> maintained against the strategy books' residual exposure. Fusion routes by asset class, so
> "unwinding" a hedge position would open an offsetting one in a STRATEGY book: two legs where there
> was one, gross exposure up, and the two loops fighting each other every cycle.

The two suppliers disagreed. One said the hedge book is not ours; the other counted its quantities as
ours. The failure the first one was written to prevent then arrived by the second route — not through
the *span* of the target book, but through the *arithmetic* of the gap.

### The feedback loop

Let `e` be the strategy books' exposure on an axis, `h` the hedger's leg in the proxy, and `s` the
strategy books' own position in that same proxy.

- The hedger (ADR-0039) drives `e + h → floor` by trading `h`. It reads `h` from the hedge book only.
- The planner drives `s + h → target` by trading `s`, because `current = s + h`.

Neither loop sees the other's control variable, and each one's action moves the other's error term.
Every contract the hedger buys raises the planner's `current` by one, so the planner sells one more
into a strategy book; that sale does not change `e` measured over cash equities, so the hedger's own
target is unchanged and its leg stays on. The fixed point is:

```
s = target − h
```

so the firm holds `|target − h| + |h|` of the contract where the economics call for `|target|`, and
the hedger's `h` has been *exactly cancelled* by the strategy leg — the hedge is on the balance sheet
and in the fee bill but does nothing to the firm's net exposure. As `h` grows with the strategy books,
so does the offsetting leg: it is a ratchet with no fixed point in gross.

### It is live, and it is large

At the time of writing, on the ES contract (the ADR-0042 selected equity proxy):

| | quantity | notional (`q × 5450.50 × 50`) |
|---|---|---|
| `MACRO` (strategy) | −0.060884 | −$16,592 |
| `HEDGE` (overlay) | +0.050697 | +$13,816 |
| **firm net** | **−0.010187** | **−$2,776** |
| **firm gross** | | **$30,408** |

The planner's target book for that cycle read `ES: current = +0.011200, target = −0.160100` — a
*positive* current on a contract the strategy book is short, because the hedger's long dominated the
sum. ES gross was 47% of the firm's total gross exposure while carrying 9% of it in net.

The hedge book was also the firm's single worst position — its loss exceeded the whole firm's — and
almost all of it was realised rather than mark-to-market, i.e. paid on round trips rather than lost on
a view.

## Decision

**The fusion planner nets against the books it routes into.** `FusionConfig.firmPositions` becomes
`routedBookPositions(risk, hedgeBook)` and skips positions whose `bookId` is the configured
`jethro.hedge.book`, exactly as `heldInRoutedBooks` already does. The two suppliers handed to the
lifecycle now answer the same question the same way — one says *which* names, the other *how much* of
each — and neither treats a book this layer cannot trade as its own inventory.

Nothing else changes. It is a signed `BigDecimal` sum with one book filtered out: no new dial, no new
money or risk number, no rounding introduced, and every instrument the hedge book does not hold is
unaffected quantity for quantity. The hedger is untouched; so are the pre-trade guardrail, the
ADR-0027 firm breaker and every ADR-0016 gate.

### Worked example (the live ES book above)

With the hedger holding `h = +0.050697` and the planner's target `T = −0.160100`:

| | old (`current = s + h`) | new (`current = s`) |
|---|---|---|
| strategy leg at convergence | `s = T − h = −0.210797` | `s = T = −0.160100` |
| firm net ES | `T = −0.160100` → −$43,634 | `T + h = −0.109403` → −$29,818 |
| firm gross ES | `(0.210797 + 0.050697) × 272,525 = $71,272` | `(0.160100 + 0.050697) × 272,525 = $57,447` |

The gross removed is `h × 272,525 = $13,816` — precisely the hedger's own notional, which the old read
double-counted. The firm's net moves *toward* flat by the same amount, because the hedge now offsets
instead of being traded away. Both halves of the objective improve, and they improve by the same
number for the same reason.

## Consequences

**Good**

- The hedge works. `h` reduces the firm's net exposure instead of being neutralised by a strategy leg
  opened against it.
- Gross exposure falls by the hedger's notional on every proxy it holds, and the *ratchet* stops: gross
  in the proxy is now bounded by `|T| + |h|` rather than growing with `h` each cycle.
- The double round-trip cost — two books crossing the same contract in opposite directions through the
  street — stops. This is a permanent, structural cost, not a one-off.
- One convention, stated once: a position in a book this layer does not route into is not its position.

**Costs / risks**

- The firm may now hold a strategy view and a hedge in the same contract as two book-level legs
  (`|T| + |h|` gross for `|T + h|` net). That is bounded and honest, and it is strictly less than the
  `|T − h| + |h|` the old read converged to whenever the two are on opposite sides. Netting the two
  *intents* into a single firm position is a real improvement on top of this one, but it crosses the
  fusion/hedge module boundary and changes book attribution, so it is deliberately not attempted here.
- The planner's `current` for a proxy jumps by `h` the first cycle after deploy. It is a change of
  measurement, not a trade: the gap is re-measured and worked at the ADR-0080/0090 derived rate in the
  ordinary way, and the ADR-0055 no-trade band applies as always.
- `heldInRoutedBooks` already excludes the hedge book, so a proxy held *only* by the hedger is still
  outside the target book's span — no orphan-unwind path is opened onto a leg this layer must not trade.

## Alternatives considered

1. **Make the hedger's axis exposure count the proxy held in other books.** Risk-correct — the firm's
   equity-factor exposure genuinely includes a futures position in the proxy — but it makes the hedger
   *larger*: it would buy the strategy's short back on top of what it already holds, raising gross by
   more than this change removes and cancelling the desk's view by construction. Rejected: it treats the
   symptom (a mis-measured axis) while worsening the objective.
2. **Forbid the strategy from taking a view in any hedge proxy.** Removes the collision outright, but it
   is a veto on a whole instrument — crude, and it silently deletes a legitimate view. Rejected in favour
   of fixing the accounting that made the two legs *grow*; if the residual two-leg gross proves material
   once the ratchet is gone, netting the intents (see Costs) is the better next step.
3. **Do nothing and cut sizing instead.** Shrinks every position to hide one mechanism. Rejected: the
   defect is a feedback loop, and a loop with no fixed point is not fixed by scaling its inputs.
