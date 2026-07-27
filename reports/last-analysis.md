The desk plans 23 names but only 7 can ever be entered — so the two controls that decide how big the book gets have been measuring a book that is three-quarters fiction, and the real names are sized up on a diversification they will never have (ADR-0096).

## Situation — read off the live endpoints; every figure below is quoted, none computed here

**1. Money.** Total PnL `$216.91` on `/api/risk` `.total`. The report's SITUATION header puts the
window at `+32.13` and the last three runs at `+100.81`; `run-status.json` has `on_track` true,
`pnl_growth_pct` far ahead of the 1% target, `underwater` and `stale` both false. The book is **not
bleeding** and the numerator is doing fine.

**2. Risk — this is the problem.** Gross exposure `$29,590.68` against net `$307.81` on the first read
of this cycle and `$40,891.67` against `−$1,577.16` on a read eight minutes later; `run-status` had
`19,038.18` at 05:50. The header flags **EXPOSURE RISING** at `+11,094.71` on the window. VaR95
`$290.71` / ES95 `$415.54`; the firm drawdown breaker is `halted: false` and nowhere near tripping.
So: PnL rising, exposure rising much faster. Not a danger state — but the ratio the owner is measured
on is deteriorating every run, and the ramp is structural rather than a market move.

**3. Cause.** Last cycle's change (`8aea7d1b4`, the hedge measuring its own effectiveness, ADR-0095)
scored **❌ BAD** and the scorer auto-reverted it before I started, so no de-risk override applies and
I have not re-attempted that lever.

**4. Danger?** No. PnL is up, the breaker is far, VaR is a small fraction of gross. The cycle was free
to go at a mechanism — and the mechanism is the denominator.

**5. Order post-mortem.** `/api/orders/day`: 100 orders over 24 minutes, 86 FILLED, 14 CANCELLED (all
of them the ADR-0084 re-plan sweep, which is working as designed). Every ALPHA order is one of **five**
names — AAPL, GOOG, JNJ, JPM, MSFT — plus the hedge's ES/NQ. Meanwhile `/api/fusion/targets` publishes
`instruments: 23`. **Eighteen planned names have never produced an order.** Not one.

**7. Change vs market, honestly.** I claim and blame nothing for the PnL move. ADR-0095 touched only
the hedge, yet the scored exposure rise shows up in ALPHA as well, whose ramp predates it and continued
after the revert — so the exposure leg cannot be attributed to that change alone from these numbers,
and I am not going to guess a split. The ramp I am acting on is visible independently of it, in the
target book itself.

## Diagnosis — the mechanism

`/api/ops/jvm` says `selector: measured 17, tradable 7`. The ADR-0049 OOS selector permits risk on
seven names; the planner plans twenty-three. The sixteen it cannot enter — AMZN, NVDA, TSLA, GS, BRK.B,
ORCL, NFLX, the three FX crosses and the ZB/ZN/ZT/ZF/ES/NQ sleeve — get a target and a delta every
thirty seconds and get vetoed every thirty seconds, because that veto is asked **per order, at the last
step, inside `FusionExecutor.route`**. Costless in fees, and therefore invisible for nine cycles.

It is not costless in **sizing**, because two controls aggregate over exactly that set. ADR-0079's
`PDM = min(1, σ_indep/σ_actual)` is computed over the planned book and applied **uniformly to every
covered name** — a phantom name still contributes `eᵢ²Σᵢᵢ` to `σ_indep` while contributing little to
`σ_actual`, so a phantom sleeve in a weakly correlated asset class *raises* the measured
diversification and the names that do trade are scaled up on risk the desk was never going to take.
Live: `portfolioRiskMultiplier: 0.487` over `covarianceCoveredNames: 23`, with FX and futures carrying
roughly half the planned notional. ADR-0083's volatility budget has the same scope error in its
harmonic-mean reference σ (`volBudgetNames: 23`). Both controls exist for one purpose — to stop the
**name count**, which nobody set as a dial, from levering Oleg's per-name `unit-notional-usd` — and both
have been fed a book that is three-quarters unreachable.

The confirming tell costs nothing and is sitting in the payload: the ADR-0094 aims have wound up on the
unreachable names until they sit on the **opposite side of zero from their own target** — `GS +103.02`
against a target of `−87.51`, `BRK.B +49.57` against `−150.05`. Integral windup on names that can never
fill.

## Change

`FusionLifecycle.actionable` (ADR-0096, Proposed, same commit): filter the fresh forecast set through
`FusionExecutor.mayOpen` — the executor's **own** ADR-0049 predicate, called as a method reference and
never restated, because the ADR-0091 defect was two code paths deciding one scope separately — unioned
with the held set, before `FusionPlanner.plan` and therefore before either book-level control measures
anything. It can never let a name trade that could not trade before: the executor's veto still runs
unchanged on every order. A held name is always kept whatever the selector says and keeps its own
forecast, so the orphan unwind, the trailing cut and the breaker still see every position. No new dial,
no new formula, no money number — the same statistics over a truthful set of names. Worked example
pinned as a test: two perfectly correlated tradable names plus one uncorrelated phantom move PDM from
`1/√2` to `√0.6`, sizing the names that carry all the risk 9.54% larger. `./gradlew -Pci test` green.

**What I expect next run:** gross stops ramping and falls, with PnL roughly on its trend. The two
effects do **not** point the same way — removing the low-σ futures raises ADR-0083's harmonic reference
and nudges the equity budgets up, partly offsetting the PDM reduction — so this is an expectation, not
an identity. If gross rises anyway, this is the wrong lever and should be reverted rather than tuned,
and the next suspect is the per-name budget itself rather than the set it is measured over.
