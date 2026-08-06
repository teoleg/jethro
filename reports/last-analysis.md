No code change — the ADR-0116 freeze holds (ADR-0142 at 1 of 6 cycles), and for the first time the freeze had a real no-op to honour; the cycle's product is a quantified answer to "does anything have edge here": the desk trades at a horizon where its measured expectancy is zero and pays a round trip for it.

*(Every figure below is read from `logs/report.md`, `reports/run-status.json`, `reports/.pending-baseline.json`,
`git diff` and the scorer's own output. None is authored here — invariant 7 / ADR-0016.)*

**Money.** Total PnL **-731.27072424**, gross **13570.96679500**, net **-2063.62320500** (`/api/risk`
`.total`). Since last run PnL **-12.14**, gross **+2146.07**; over the last 3 runs PnL **-103.20**, gross
**+13570.97**. The heartbeat at 14:41:10Z reads `pnl_growth_pct` **-14.5%** against `pnl_target_pct`
**1.0%**, `on_track` **False**, `stale` **True**, `underwater` **True**. Bleeding, but slowly, and off the
target.

**Risk — not danger, and the exposure move is the goal.** Gross is **0.9%** of the firm gross cap
$1,500,000 with headroom **$1,486,429**; net is **0.2%** of the $1,000,000 net cap. `breaker.halted`
**false**; `var95` **222.04**, `es95` **330.72**, `var99` **422.79** over **154** observations,
`coveredExposure` **13572.16**, `skippedExposure` **0.00**. `regime` CALM, `trend` CHOP, `volRatio`
**0.92**. Gross rising off a dormant book with that much headroom is deployment, not a risk event;
UNDERWATER is a statement about cumulative PnL, not a live danger state.

**Cause — the cleanest attribution this loop has ever had: 0% change, 100% market-and-existing-code.**
Last cycle's change `39451ce71` (ADR-0142) touched only `ops/improve-loop.sh`, `docs/` and `reports/` —
no Java, no dial, nothing inside the app. So it *cannot* have moved the vector, and the whole PnL
**-12.14** / gross **+2146.07** belongs to the market and to code already live (ADR-0141's band, in since
13:44Z). The scorer holds it: `39451ce71 still accumulating evidence (1/6 cycles) — held, not scored`.

**Step 0 on ADR-0142.** It landed (`NON_BINARY_PATHS='^(reports|docs|ops)/'` is in HEAD) but its own first
VERIFY-BY is **not yet testable**, and honesty requires saying why rather than grading it: the app *did*
restart at **14:41:21Z** (`uptimeSeconds` **1121** against report `timestampMillis` **1786028402702**),
**38 s** after the ADR-0142 commit at 14:40:43Z — because the wrapper process running that cycle had
already parsed the old `deploy_if_code_changed` body before the new one was written. The rule could not
govern its own deploy. **This cycle is its first real test**, and this cycle is the one that can give it:
the commit is confined to `reports/` and `docs/`, and the wrapper now on disk carries the new filter.
Two of its other predictions did move in the right direction — `streamVolMeasuredNames` **2 → 20**,
`insideBuffer` **23 → 14**, `riskCutStoppedNames` **0**, `covarianceCoveredNames` **20** — but ADR-0142
changed nothing in the app and did not prevent that restart, so it gets **no credit** for them. One
prediction is independently ✅: the 14:40:49Z baseline recorded gross **11424.82120000** and gross is
**13570.96679500** now, so the book **survived** the restart rather than returning to **$0.00**. That
weakens the "a restart flattens the book" half of ADR-0142's rationale, and the register records it.

**What the cycle actually found — the edge question, answered with numbers.** The standing priority is
"work on edge, not the combiner". Reading `signals_telemetry` (LIVE) against what a round trip costs:
every source's `avgReturnBps` is indistinguishable from zero at every horizon, and it is *smallest* at
the horizon the desk actually trades on. At **225s**: reversion **+0.013**, trend **-0.003**, xsreversion
**-0.187**, social **-0.325**, momentum **-0.851**. At **900s**: social **+0.962**, trend **+0.060**,
reversion **-0.044**, xsreversion **-2.194**, momentum **-4.593**. At **3600s**: social **+3.654** (37
cohorts, `stdCohortMeanBps` **27.926**), trend **+1.501** (100 cohorts, **14.428**), reversion **-0.530**,
momentum **-4.132**, xsreversion **-4.129** — the largest mean-to-cohort-dispersion ratio in that whole
set is social's, and it is nowhere near the ADR-0049 gate. Against that, cost: `turnover_cost_by_name`
reads `fee_bps` **1.00** per side for every equity, and TCA `avgSlippageBps` reads **0.598** for MSFT
(215 fills), **0.743** GOOG, **0.706** AMZN, **0.695** PFE, **0.753** NEE. A round trip pays each of
those twice. So the desk pays a certain cost per round trip to harvest a **+1.501 bps** expectancy that
only exists at a 3600s horizon — while turning positions over in minutes.

**And it does turn them over in minutes, provably.** MSFT's `combinedForecast` in `recent_orders` went
**-5.647822616241038** (SELL, 14:30:05Z) → **+5.994499373101448** (BUY, 14:42:00Z) → **-6.757956277853291**
(SELL, 14:54:43Z): two sign flips inside ~25 minutes, each paid as a full round trip, on a name whose
`turnover_usd` is **228299.68** across **215** fills to hold **-10.000000** shares. The pattern repeats
elsewhere: ES carries **178** fills and **680011.59** of turnover to hold a **73.24222500** gross hedge,
including a full liquidation at 14:47:10Z (`net equity |0.00| ≤ 0.00 floor — target hedge is zero`, SELL
**0.011552**) rebuilt from 14:49:45Z; and NQ ground out ~25 orders of **0.000040**-ish contracts every
30 s labelled `fusion reduce toward a smaller target`, **42** fills and **92598.21** turnover on a
**672.85957000** position, plus three REJECTED for `no market data for NQ`. Per-name PnL: MSFT
**+328.78192038** and GOOG **+54.99373579** are the winners; UNH **-116.50673218** (the desk kept selling
into it on `sources=2`), KO **-76.70785869** and NQ **-147.62078848** are the losers. ALPHA's `feesPaid`
**386.902978** sits against its `realizedPnl` **-607.49576306** — the cost column is the same order of
magnitude as the entire loss it is trying to explain.

**Decision.** No code change: `reports/.pending-baseline.json` names `39451ce71` at 1 of 6, and piling a
change on top destroys the window — the mistake last cycle was forced into and recorded as Rule 391.
Unlike last cycle, honouring it costs nothing now, which is the whole point of ADR-0142. I have also
**not** run the `baseline` command, because `cmd_baseline` overwrites `PENDING` unconditionally — that is
how ADR-0141's 2-of-6 window was silently discarded at 14:40:49Z, and it is now item #2 on the register.
Next cycle, once ADR-0142 is scored, item #1 is the cost-versus-horizon mismatch above: hold to the
horizon where the expectancy is measured, or do not pay to trade it.
