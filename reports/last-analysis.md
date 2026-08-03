The book came off dormant at the US open and the "permanent veto" I diagnosed last cycle is FALSIFIED — the σ sensor warms with the live tape; the real defect is that it warms for only 6 of 20 names, so 82% of the firm's risk is one levered index future.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`,
`/api/signals/telemetry`, `logs/report.md`, the scorer's output, or the repo source. None is authored
here — invariant 7 / ADR-0016.)*

## No code change this cycle — the pending change is still under measurement

`scripts/score-change.py score` prints **`74a47adee still accumulating evidence (2/6 cycles) — held, not
scored this run`**, and `reports/.pending-baseline.json` is present. Under ADR-0116 a new change on top of
a pending one destroys its evidence. So this run verifies, corrects a wrong diagnosis, and records; it
ships no code.

## Situation — the four questions

1. **Money.** Total PnL **$8.87**, down **$23.64** from **$32.51** last run (and the same over the last
   three runs). By book: MACRO **-$55.97**, ALPHA **-$21.98**, HEDGE **+$86.82**. The move is almost
   entirely MACRO's fresh NQ short: unrealized **-$19.68** on a position opened **15 minutes ago**.
2. **Risk.** Gross **$28,761.93** — **1.9%** of the $1,500,000 firm cap, headroom **$1,471,238**; net
   **-$23,563.29**, **2.4%** of the $1,000,000 net cap. **No flags.** The DORMANT state is over: exposure
   rising with this much room is the goal, not a concern.
3. **Cause.** Not ADR-0135. Every entry this window fired on `fusion entry — target increase` with
   `sources=2` or `sources=3`; ADR-0135 only changes the `sources=1`/unestimable branch, so it is
   causally uninvolved in this window's PnL. The book re-entered because the US session opened.
4. **Danger.** None. Breaker clear, regime CALM, `ticksDropped: 0`, nothing near a cap.

## Step 0 — last cycle's #1 was WRONG, and this run's telemetry says so

I recorded that the ADR-0126 unarmed-stop veto was a **permanent** freeze because the σ sensor's warm-up
(121 prices at the 30 s cadence ≈ 60.5 min) exceeded the app's ~30 min lifetime. **That claim is
falsified.** The seed comes from the durable mark store (ADR-0071), which **grows as the tape prints**:
last cycle's boot seeded AAPL **75** / NVDA **85** of 121; this cycle's boot seeded AAPL **107** / NVDA
**117** of 121. `streamVolMeasuredNames` went **1 → 6**. The dormancy was a *closed-session* artifact — a
weekend adds no marks to the store — not a structural deadlock. Corrected in `docs/loop-findings.md`
(Rule 234 supersedes Rule 231) so the memory does not carry a wrong lesson.

## What is actually costing money — the desk expresses 2.4% of its own intent

`/api/fusion/targets` shows **19 of 20** names estimable with real targets summing to **$1,208,080** of
|notional|. The desk holds **$28,761.93** of it. The `aims` map explains exactly which names are stuck:
**14 of 20 read `0.0`**, and the six that don't — GOOG **+47.33**, NVDA **-83.27**, AAPL **+19.91**, AMZN
**-12.06**, NQ **-0.164**, MSFT **-0.018** — are precisely the **6** counted by `streamVolMeasuredNames`.
An exact match. For the other 14, `PositionBuffer.java:164` re-seeds the aim to `held + delta` = 0 every
cycle, so intent cannot accumulate at all (that part of last cycle's trace stands).

**The consequence is concentration, and that is the real risk.** NQ alone is **$23,492.67** of the
**$28,761.93** gross — **81.7%** — so the firm's book is effectively a naked short index future, while the
equity legs that would diversify it are the ones held at zero. PG carries `combinedForecast -7.398` at
`agreement 0.614` and a **-$213,901** target with `aim 0.0`; AAPL **+$127,333** with `aim 19.91` and
nothing held.

**The sharpest lead for next cycle:** on the *same* mark store, at the *same* span of 120,
`covarianceCoveredNames` is **19** while `streamVolMeasuredNames` is **6**. Two estimators, one data
source, one span — one covers the book and the other covers under a third of it. That says the defect is
in σ's **seeding path**, not in data availability, and it is where the one change goes once ADR-0135 is
scored.

**Honest limits.** The -$23.64 is a 15-minute mark on a fresh $23.5k short (**-0.084%**) — noise, not
evidence about any change. Fees moved only **+$0.79** this window, so turnover cost is not the current
problem; ALPHA's **$281.55** is cumulative history. And per Rule 229 the fix must **arm** the stop with a
measured σ, never bypass `stopArmed` — opening positions the ADR-0086 cut cannot price an exit for is
exactly the risk ADR-0126 exists to prevent.
