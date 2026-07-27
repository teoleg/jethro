Gave the fusion desk the one risk response its whole thesis rests on and has never had: a position is now cut when it gives back more than its own measured volatility says a live view should (ADR-0086).

## Situation (read from the live endpoints; every number below is quoted, none computed here)

1. **Money.** Total PnL is **higher** than last run — `-823.80` against `-826.06`, a move of **+$2.26**,
   and the same **+$2.26** across the last three runs. It is the first non-zero move in fifteen cycles.
   Not bleeding. Still `underwater` and still a hard miss on the +1%/3-iteration target.
2. **Risk.** Gross exposure went from exactly zero to **$956.98**, net **−$956.98** — one small ES short
   in MACRO, the only open position on the book; all seven equity lines are flat. `EXPOSURE RISING` is
   set, but from nothing, and historical VaR95 on it is **$11.04**. The breaker is not halted and is
   nowhere near. This is not a danger state.
3. **Cause.** Last cycle's change (ADR-0085, futures sizing in the OOS backtest) scored **❌ BAD** and
   was auto-reverted. The live numbers say it **worked**: it is the only change in fifteen cycles that
   produced a trade at all, and the book it opened is **+$2.32** on **$0.136** of fees over three fills
   (two passive entries at 0.30 and 0.00 bps, one market exit at 0.23 bps). The ❌ is deadband
   arithmetic, not a loss — off a flat book the exposure deadband is 1% of zero, so any position reads
   as "exposure up", and a $2.45 profit sits inside the $50 PnL deadband, so it reads as "PnL not up".
   Any change that ends a flat book is scored BAD unless it earns >$50 in one 30-minute window. **I have
   not touched the scorer or the ledger — flagging the measurement for Oleg, not adjusting my own grade.**
4. **Danger.** No. PnL up, one small position, unrealised **−$0.19**, breaker far away.
5. **Order post-mortem.** The window's only orders are the three MACRO/ES fusion fills. All winners; no
   losing trigger fired. The `-$895.94` in ALPHA is legacy-path churn from before 20:21 and did not move.
6. **Attribution — change vs market.** Unusually clean: every equity line is flat with zero unrealised,
   so the market contributed ~nothing to the firm total this window. **~100% of the move is the direct
   effect of last cycle's change**, ~0% market. Nothing here is overfitting to a market drift.

## Diagnosis and what I changed

The desk's real problem is no longer evidence — `reversion` clears the gate at the 900s rung (+8.09 bps,
184 resolved calls, 8 cohorts, p = 0.0025) — it is that the only place that edge can be expressed is a
book with **no per-name risk control whatsoever**. Every fusion control asks whether risk may be put ON;
nothing asks whether a position already held has gone wrong. The legacy path has had a stop since
ADR-0019; the fusion path, the sole order origin, has none, and the firm breaker is a whole-book halt,
not a per-name exit. That gap is worst for a mean-reversion view, whose payoff is short optionality — a
long run of small gains ended by one large loss.

So this cycle the desk gets a volatility-scaled trailing exit (chandelier): a name is worked flat once
its mark retraces from the best level seen since the position opened by more than `k ×` its own σ over
the desk's own derived holding horizon, then stands aside for one horizon. σ had to be measured from the
**mark stream**, not from daily closes: the daily estimate every other risk control uses covers 3 of 23
planned names and **none of the names actually held** — the parametric VaR currently reports its entire
covered exposure as skipped, so a control keyed on it would be silent exactly where risk sits. The rule
can only ever set a target flat and clamp a delta to a reduction, so it cannot lever the book up, and a
name with no measured σ is left exactly as planned. One dial, `sigma-multiple = 3.0`, marked PLACEHOLDER
— the conservative end of the 2.5–3× ATR convention, deliberately reluctant to cut. Deterministic floor
untouched; worked example pinned as an exact-decimal test; three deferred-register rows filed.
