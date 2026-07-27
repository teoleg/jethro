The book holds nothing — eleven live targets, zero orders — because a source measured at t = −3.89 still carries voting weight and taxes 13.6% off the conviction that would clear the desk's own buffer (ADR-0111).

*Every figure below is quoted from the live endpoints, `logs/report.md` or the ledger; none is authored
here. The ledger's numbers are the scorer's.*

## Situation triage

**1. Money.** Total PnL **$5,654.22**, **−$72.74** since last run and **−$71.35** over the last three.
`run-status.json` reads `pnl_growth_pct 0.02` against a `1.0` target — `on_track` false, **`stale` true**.
The BLEEDING flag is right this time: the fall is real, not a rounding artefact.

**2. Risk.** Gross **$0.00**, net **$0.00**. `/api/var` returns `"note": "no positions"`, `/api/breaker`
reads `halted: false`. Every one of the ten position rows reads `quantity: 0` — three books, futures and
equities, all flat simultaneously. There is no risk on and therefore nothing to earn on.

**3. Cause — and the scored verdict is again void.** The scored commit `6578cf49f` touches `docs/`,
`ops/` and `reports/` and **no runtime code**. It cannot open, close or resize a position, yet it was
charged with −$72.74 of PnL and −$10,401.12 of gross and recorded ⚠️ MIXED. That is finding-rule 17 for
the second consecutive cycle. The numbers are sound; the attribution is not.

**4. Danger.** No. Bleeding, yes — but exposure went **to** zero, not up. This is the inverse of the
danger state: the correct response is not to de-risk, it is to find out why a desk with a full target
book is refusing to put any risk on. Rule 18, applied the other way round: check whether gross went to
`$0.00` before reading a fall in exposure as prudence.

**5. Order-level post-mortem.** The window is one AAPL round trip and little else: bought to ~+35
(18:28–18:35), sold 105 shares across 19:29–19:37 straight through flat to −74, then a single
`BUY 74` at 19:54 that returned it to zero — a full-size unbuffered cover, i.e. a control planned the
name FLAT. Eight orders died on `fusion re-plan — passive order superseded by a fresh target (ADR-0084)`.
HEDGE traded ES twelve times in clips of `0.003`–`0.069` contracts. `orders_by_status` reads FILLED 3084
/ CANCELLED 553 / REJECTED 45. The desk paid a whole round trip on ~$25k of AAPL and ended flat.

**6/7. Change vs market.** Nothing to split, and I will not invent a split: the scored commit contains no
code that reaches the JVM, so **0% of the −$72.74 is attributable to it**. The move is the AAPL round
trip closing and the book flattening — market and prior-cycle code, exactly as last cycle's finding
predicted the vector would be unattributable.

## The mechanism — why a full target book places no orders

`/api/fusion/targets` this cycle: eleven names with live targets (GOOG +149.8, JPM −166.2, NVDA +127.9,
AAPL −134.5, AMZN +147.6, MSFT +82.0, JNJ +83.9), `currentQty` 0 on every one — and **`deltaQty` 0 on
every one**. `insideBuffer: 8`. Seven of the eight `aims` read exactly `0.0`, which is only reachable
through the ADR-0064/0075 reduce-only re-seed; AAPL alone carries a live aim of `−122.704078`.

Two gates, each behaving as designed, compose into a halt:

- **ADR-0075 per-name cost.** `reversion` passes at the ADR-0082-selected 225 s rung with `avgReturnBps
  2.0087`, `stdErrorBps 0.4271`, `tStat 3.696`. A name is admitted only if its own measured round trip
  survives that with significance. The published per-name costs are AAPL `0.897`, JPM `1.075`, JNJ
  `1.204`, MSFT `1.338`, GOOG `1.854`, GOOGL `20.106`, and the `1.327` blend for anything unmeasured.
  **AAPL is the only equity that clears.** That is an honest reading, not a bug: the desk's execution
  cost is close to what its measured edge can pay.
- **ADR-0101 buffer.** AAPL's aim then sits just inside its own no-trade band, so it trades nothing
  either. The band is derived from the *average* position and is forecast-**independent**, while the aim
  is linear in the forecast — so conviction, not size, is what clears it.

And conviction is being taxed. At that same 225 s rung `trend` measures **−1.5033 bps on 451 cohorts
(t = −3.89)** and `momentum` **−2.9177 on 197 (t = −3.02)** — negative at every rung of the ladder, so
not one window's noise. ADR-0097 holds them at the floor weight 0.25 against `reversion`'s 2.899, and
because the combiner normalises by Σweights an opposite-signed floored source subtracts real conviction:
on GOOG (reversion +15.134, trend −13.974) the combined forecast is `13.319070` instead of `15.134` —
**13.6% surrendered to a source the desk has measured as losing money.**

## What I changed

**ADR-0111 (Proposed, same commit): a source measured LOSING is stood down, not floored.** ADR-0097 read
"has not demonstrated an edge" as one verdict and gave the floor both to the source it *cannot judge* and
to the source it *has judged and found losing*. Those are different findings. `EdgeGate.contradictsEdge`
is the identical test — same expectancy, same Fama–MacBeth standard error, same Student's t on
`cohorts−1`, same α at the same rung — with the measured sign reversed, so the classification is total
and mutually exclusive: DEMONSTRATED keeps its measured weight, UNPROVEN keeps the floor and keeps
voting, CONTRADICTED goes to weight 0 and leaves the vote. No second statistic, no new dial, nothing
chosen; it re-reads itself from the stream every cycle, so a source that recovers comes back.

Strictly one-way (`min(w, 0)` only lowers, and floors at zero — a losing source is never inverted into a
contrarian bet), and at least one weight always survives because a stand-down only fires when some source
IS admitted and no source can be both. `./gradlew -Pci test` green, with the GOOG arithmetic above pinned
as an exact worked example.

**What this does not fix, said plainly.** Seven of eight names stay blocked by the cost gate, and that is
the honest number — measured round trips of 0.90–1.85 bps against a measured edge whose lower bound is
under 1 bp. **Reducing execution cost is the next lever and no amount of re-weighting substitutes for
it.** This change also *raises* exposure by design: if the reversion edge does not survive contact, the
loss is bigger, not smaller. The scorer settles it.

## Next levers, in order

1. **Execution cost** — the binding constraint. 553 cancels on ADR-0084 re-plan; GOOGL measured at 20.1
   bps and SAP at 8.2 bps sit inside the blend every unmeasured name is charged.
2. `turnover_cost_by_name` has errored for a **tenth** cycle — the one aggregate that would show cost per
   name is the one that does not run.
3. The HEDGE book: −$3,093.31 realised against ALPHA's +$8,370.53, trading ES in clips that round to
   nothing.
4. Still open: `ALPHA JPM SELL` REJECTED 45× on `no market data for JPM`; the frozen MACRO book; the
   scorer scoring commits it cannot attribute (deferred register).
