The desk's edge gate has been saying "ES is the only name worth paying to trade" for fourteen cycles while the OOS selector said "I have no opinion on ES" — because the backtest could not size a futures contract at all. Fixed the measurement (ADR-0085).

## Situation (read from the live endpoints, not computed here)

1. **Money.** Total PnL is unchanged run-over-run and unchanged across the last three runs — frozen at
   the same figure for a fourteenth consecutive cycle. Not bleeding; *stuck*, and a hard miss on the
   +1%/3-iteration target. `underwater` and `stale` both set.
2. **Risk.** Gross and net exposure are both exactly zero, as they have been all fourteen cycles.
   Nothing held, nothing at risk, nowhere near the drawdown breaker. There is no exposure to cut.
3. **Cause.** Last cycle's change (ADR-0084, passive entries) scored ⚠️ MIXED on a zero move — like
   the five before it, **unmeasured, not refuted**: an execution-style change cannot show up in a book
   that never places an order. It was also correct, and it was not the binding constraint.
4. **Danger.** No. Flat book, no drawdown, no breaker proximity — this is a stuck-desk failure, not a
   risk event, so the right move is to restore a route to a fill, not to de-risk.
5. **Order-level post-mortem.** No orders in the window at all — the last fill was hours ago. Zero
   triggers to attribute, no losing trade to fix.
6. **Change vs market.** Both are exactly zero and separable without ambiguity: with no position and
   no order, **0% of the move is market and 0% is my change** — there was no move.

## Diagnosis — the mechanism, at last

The thing shutting the desk is *not* the statistics of the edge gate (five earlier cycles re-specified
those, and every one worked on the term that was not binding). Reading the live gate: `reversion`
clears decisively at the evidence-selected 225 s rung, and the gate then tests each name against its
**own** measured round trip. Only **ES** clears — it is the cheapest name in the book by an order of
magnitude — and the planner duly produces a non-zero ES delta every single cycle. That delta is then
thrown away by the ADR-0049 backtest-support veto, because the OOS selector has **no verdict** for ES.

It has no verdict because the harness cannot measure a futures contract. `BacktestEngine` sized every
name in whole units, so ES's correct 0.091743-contract position rounded to zero, at which point the
code read "one contract exceeds the order cap" and declared the name unsizeable. **No future has ever
been measured** — 17 of 19 price-quoted names, all equity/FX. Compounding it, the harness charged
every instrument the *equity* per-fill cost, 8.1× what the live executor actually charges ES. So the
desk's own edge gate and its own backtest gate had reached opposite conclusions about the same name,
and the disagreement was an artefact of arithmetic, not of evidence.

## Change

ADR-0085: the OOS backtest sizes each name in its own contract terms (ADR-0078's rule, applied to the
measurement path) and charges each name its own per-fill cost — half its refdata spread plus its class
fee, the same derivation `OrderConfig` hands the live `SimulatedExecutor`, and the one the harness's
own javadoc already claimed. No gate loosened, no hurdle moved, no number invented: multiplier and
`spread_bps` are reference data, the fees are the ADR-0025 config. Replaying the selector's harness,
ES goes from 0 trades / no verdict to 8 trades with a positive momentum median; NQ trades but medians
to zero and stays vetoed, so the fail-closed veto still bites — it just finally has evidence to bite on.

If ES's edge turns out to be a property of the backtest tape rather than of the live stream, that
shows up next cycle as PnL flat with exposure up, is scored ❌ BAD and reverted. What would be refuted
then is ES's edge, not the measurement fix.
