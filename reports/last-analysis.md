Orders now record WHY the desk wanted the trade, not only why a status changed — so the orders that actually fill stop being unattributable (ADR-0134).

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/hedging`,
`/api/signals/telemetry`, `logs/report.md`, the scorer's ledger row, or the repo source. None is authored
here — invariant 7 / ADR-0016.)*

## Situation triage (live)

1. **Money.** Total PnL **$153.36**, **+31.01** since last run, **+92.30** over the last 3. The latest
   heartbeat (`2026-07-31T19:04:53Z`) reads `pnl_growth_pct` **15.54** against `pnl_target_pct` **1.0**,
   `on_track` **true**, `stale` **false**, `underwater` **false**. Not bleeding, and on the owner target.
2. **Risk.** Gross **$44,999.32** = **3.0%** of the $1,500,000 firm cap (headroom **$1,455,001**); net
   **$24,004.97** = **2.4%** of the $1,000,000 net cap. `Flags: none`. Under-deployed against the ADR-0132
   budget, nowhere near a cap.
3. **Cause.** The pending change cleared: `4f67f0515` (the manual completion of the failed auto-revert)
   scored **⚠️ INCONCLUSIVE** — risk-adjusted return/cycle **-0.000147** over **7** cycles, **t=-0.37**
   against a **1.5** hurdle. Kept, not reverted, and the hold that blocked the last five cycles is over.
4. **Danger.** None. `/api/risk/breaker` `halted: false`, regime `CHOP`/`CALM`, `volRatio` **0.99**.
5. **Order post-mortem — still impossible, and that is what I changed.** Of this window's 60
   `recent_orders`: **36 of 36 FILLED** orders carry a NULL `reason`; **24 of 24 CANCELLED** carry text.
   The orders that never traded are the only ones explained.
6. **Books.** `/api/attribution` `firmTotal` **153.36244857** = HEDGE **160.19086234** + ALPHA
   **28.99506278** + MACRO **-35.82347655**, on `totalFees` **272.654475** (ALPHA **266.333508**). HEDGE
   and MACRO are again byte-identical to last cycle — neither traded. ALPHA read **-13.33312927** last
   cycle and **28.99506278** now, so the whole run-over-run move is once more the strategy book, this time
   upward. `hedgeMasking` **true**, `/api/hedging` `covarianceReady` **false**.
7. **Change vs market.** The **+31.01** is **unattributed**. It sits entirely in ALPHA, on positions no
   change of mine touched (the pending change was a revert completion, live since 16:35Z), with the breaker
   clear and the tape calm. With no trigger on any fill I cannot separate market from change, so per
   Rule 216 I record it as unattributed rather than crediting it to anything.

## What I changed and why

Item **#1** in the register, and this cycle it was ripe: last cycle traced the *cause* but was under a
scoring hold and could not ship. The framing that mattered — `reason` is a **status-transition** field, so
`OrderService.routeApproveAndFill` writes it only on the failure branches while the happy path
`NEW → ROUTED → FILLED` passes a literal `null`. The column is not failing; it answers a different
question. No patch at the order layer can recover a trigger that was never passed into it.

So the trigger is now threaded from the call sites that decide to trade: `NewOrder` carries a nullable
`originReason`, written into a new `orders.origin_reason` column **at insert** — before any status exists —
and never overwritten by a transition. Every deciding call site passes the sentence it already had
(`signal.rationale()`, the AI sleeve's `thesis()`, the hedge advisor's `rationale()`), and the fusion
planner, which places most of the flow, names four triggers the post-mortem must tell apart: an ADR-0086
trailing-stop cut, an entry, a reduce toward a smaller target, and an exit decayed to flat. ADV child
slices inherit the parent's trigger. A REJECTED order now keeps both the want and the refusal.

**This is telemetry only and it moves no money.** Nothing reads the field back, so a null origin cannot
change what the desk trades; the deterministic floor, all money math and every sizing path are unchanged.
It should be expected to score ⚠️ INCONCLUSIVE — the correct outcome for buying evidence, not a failure.
It is worth a cycle because the register's next item (#2: ALPHA negative net of its own fees while a frozen
hedge masks it) is not diagnosable without knowing which trigger opened the losers, and four consecutive
cycles have now burned themselves guessing at that and then falsifying the guess.

## Edge check (the standing priority) — unchanged, still nothing that can size

`/api/signals/telemetry` at `horizonSeconds` **3600**, as `avgReturnBps` / `cohorts` / `stdCohortMeanBps`:
momentum **5.749961141428572** / **10** / **29.247194899516227**; social **5.743293811097191** / **22** /
**25.910850044717932**; reversion **4.911734698471268** / **63** / **32.17586652023403**; trend
**-2.044825086028045** / **71** / **30.426625198598547**; xsreversion **-6.146473850702721** / **25** /
**33.63312259265042**. Every positive source remains small against its own cohort dispersion and the edge
gate correctly lets none of them size. Unchanged for seven cycles — which is precisely why I spent this
cycle on the evidence gap rather than on another combiner parameter.
