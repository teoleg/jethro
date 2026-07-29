The agreement scaler is degenerate at one source — it hands FULL conviction to the names with the LEAST corroboration, and that is what is building the book's losers.

*Every figure below is read from the live endpoints, `logs/report.md`, `reports/run-status.json` or the
scorer; none is authored here (invariant 7 / ADR-0016). The t-statistics are computed by a script from the
telemetry's own published cohort dispersions (`avgReturnBps / (stdCohortMeanBps/√cohorts)`).*

**0. Scoring state — this decides what I am allowed to do.** `scripts/score-change.py score` prints
`d9f8969cc still accumulating evidence (2/6 cycles) — held, not scored this run`, and
`reports/.pending-baseline.json` is present. **No code change this cycle**, per the contract — a new change
on top would destroy the evidence for the one under measurement. Analysis and memory only.

**1. Money.** Total PnL **−$30.35** live (`/api/risk` `.total`; the report header, taken ~6 min earlier,
read −$32.79). Run-over-run **−$1.75**; over the last three runs **−$33.40**, from the $0.61 that had been
frozen for four days. `pnl_growth_pct` −5172.08 against `pnl_target_pct` 1.0, `on_track=false`,
`stale=true`, **UNDERWATER**. The book is bleeding, but it is bleeding *while trading for the first time*.

**2. Risk.** Gross **$9,291.42** = **0.6%** of the $1.5M firm cap, headroom **$1,490,707**; net **$1,248.36**
= **0.1%** of the $1M net cap. `riskCuts: []`, `riskCutStoppedNames: 0`, `portfolioRiskMultiplier: 1.0`,
`bookVolBrake: 0.597` (the brake is engaging on its own). Gross actually *fell* $1,826 this window.
**Not DANGER**: 0.6% of cap, −$30 against a `maxFirmDrawdown` of $50,000.

**3. Cause — and the answer to the question I set last cycle.** I predicted I would first check whether
JPM's loss *persisted* or *mean-reverted*, because only "the fade was wrong" justifies demoting
`xsreversion`. **It did neither: the model reversed its own sign inside 18 minutes and crystallised the
loss.** JPM went +14 sh long (drip-accumulated 1/30s from 13:54) → `SELL 14 FILLED` at 14:11:58 → further
SELL 2 and SELL 1 → now **−3 sh, realized −$11.53**, unrealized only −$1.57. The paper loss became a
realized loss plus two crossings of the spread. So `xsreversion` was not early, it was wrong — *and* the
combined forecast is sign-unstable on a 30s re-plan against signals measured at 225/900/3600s horizons.
Note **why** it flipped: `reversion` arrived as a source on JPM (contribution −11.54 at weight 2.10, vs
`xsreversion` −3.79) and overrode it. The system self-corrected — but only after paying for the mistake.

**4. Danger.** No. But there is a **queued repeat of it**, which is the real finding.

**5. Order-level post-mortem — where the loss actually sits now.** It has moved off JPM:

- **NQ +0.007661 (MACRO), gross $4,239, PnL −$24.55 — 81% of the entire firm loss.** ≈ −58bps.
- JPM −3 sh, **−$13.10** (−$11.53 of it realized, i.e. already paid).
- JNJ −6 sh, −$3.50. AMZN −$0.19, NVDA −$0.03.
- **AAPL −4 sh, +$9.25 — the only real winner** (+$2.47 realized, +$6.78 open).
- ES (hedge) flat, +$0.96.

**NQ has its own distinct bug.** Between 14:10:28 and 14:13:29 the desk tried to **exit NQ eight times** —
`SELL 0.004134` (the whole position) — and every one came back `REJECTED — no market data for NQ`. By the
time the mark returned, the target had flipped to BUY and it **added** instead (`BUY 0.003527 FILLED`
14:24:02). A data gap silently converted an exit into an accumulation. The pre-trade guardrail was right
to refuse; what is missing is above the floor — a refused *exit* intent is dropped rather than retained
and retried. ES still marks at `ageMillis` ≈ 1.05M, so the futures feed gaps are not hypothetical.

**6. Memory applied.** Rule 63 said a sole-source fade buys more as the price falls. That is exactly what
happened, and I checked its falsification condition before acting on it rather than assuming it.

**7. Change vs. market.** Un-separable this window and I will not guess: the entire book was opened by code
within the last ~40 minutes, so there is no untouched inventory whose move would be pure market. Rule 64
still binds — 40 minutes of PnL is not evidence about a signal. The durable read is the multi-day
telemetry, t computed by script: **`reversion` +0.57 / +1.31 / +1.36** at 225/900/3600s (939/294/86
resolved) — the only source positive at every horizon. **`xsreversion` −0.09 / −1.43 / −1.32**, negative at
every horizon. `trend` −0.33/+0.34/−1.10, `momentum` −0.04/+0.31/−1.45, `social` −0.89/−1.55/+0.21.

**The mechanism I will fix next cycle — a degenerate formula, not a parameter.** `ForecastCombiner`
computes ADR-0119's scaler as `agreement = |Σwᵢfᵢ| / Σwᵢ|fᵢ|`. **At one source that is identically 1.0**,
by construction, whatever the source is. So the mechanism built to shrink conviction when sources fight
awards **maximum** conviction to precisely the names with **no corroboration at all** — and the live book
shows it pointed at the two largest targets, both driven solely by the negative-edge source:
**GOOGL `sources: 1`, agreement 1.0, target −147.25 sh** (already ratcheting live: SELL 1→2→3→4→5→6→7→8,
ROUTED 14:29:34) and **TSLA `sources: 1`, agreement 1.0, target +329.96 sh**, both `xsreversion` alone.
That is the JPM shape, queued up an order of magnitude larger. `n=1` is an absence of evidence and must
score near-minimum conviction, not maximum. Corroboration-aware agreement, with an ADR in the same commit.

**Also watching, not acting on:** AAPL is the book's only winner (short, +$9.25) and its target is now
**+87.54** — the combiner is about to flip a winner, the mirror of the JPM whipsaw. If the agreement fix
does not settle the sign churn, holding-period discipline is the cycle after.
