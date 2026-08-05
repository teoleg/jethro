The desk liquidated a whole position on a one-source zero forecast, then thirty seconds later three agreeing sources wanted the same trade back — the no-trade buffer waves through noise and blocks signal.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `/api/social`,
`signal_observations`, the report's `recent_orders` and the scorer. None is authored here — invariant 7 / ADR-0016.)*

# Loop analysis — 2026-08-05 17:00Z

## Situation — the four questions, answered first

**1. Money.** Total PnL **$-619.47984806** (`realizedPnl` **-621.10758272**, `unrealizedPnl` **+1.62773466**).
Since the last run **+3.37**; over the last three runs **-16.40**. Still UNDERWATER and still off the
**1.0%**/3-iteration growth target. The deficit remains historical, not fresh: `ALPHA` **-582.16172505**,
`MACRO` **-56.79950536**, and `HEDGE` is the only book in profit at **+19.48138235**.

**2. Risk.** Gross **$4,004.439**, net **$1,786.041** — **0.3%** of the **$1,500,000** firm gross cap with
**$1,495,996** of headroom, and **0.2%** of the **$1,000,000** net cap. `riskCuts` is empty,
`riskCutStoppedNames` **0**, `bookVolBrake` **1.0**. The flag is `UNDERWATER`, not `NEAR FIRM CAP` and not
`DANGER`. Per ADR-0132 a book at 0.3% of its allowance is under-deployed, not over-exposed; nothing here
argues for de-risking.

**3. Cause.** Last cycle's change was the completed revert of the graded-BAD ADR-0139. It ✅ **VERIFIED** —
see Step 0 below — and the scorer is 1 of 6 cycles into judging its vector, so its money verdict is not
mine to anticipate. The window's actual event was an NVDA round trip, and it was **not** caused by any
change of mine.

**4. Danger.** No. Bleeding near the cap would be danger; sitting at 0.3% of it is not.

## Step 0 — the revert deployed and did what it claimed ✅ VERIFIED

Deployment first, so this grades code the app actually ran: the revert is authored **16:38:20Z** and the
running JVM booted at `traffic.timestampMillis` **1785949202383** − `ops_jvm.uptimeSeconds` **1260** =
**16:39:02.383Z**, 42 s later. `SocialChannels.isCredible` is back to the strict conjunction in the tree,
and the running app is applying it: of the 12 `recent` posts, all tier `STANDARD`, **11** read
`credible: false`. `counters.corroborated` is **7** in **1260 s** against **17/1427 s** on the ADR-0139
boot — roughly halved, which is the direction a tightening should move it. `manipulationSuspected` **39**
on `ingested` **3090** / `kept` **792**, so the pump tell is untouched.

## The finding this window bought — the buffer is bypassed by exactly the plans it should ignore

Three readings, thirty seconds apart, that only make sense together:

- **16:59:27.901128Z** — ALPHA NVDA `BUY 7.000000` FILLED, reason
  `fusion exit — target decayed to flat [forecast=-0.0, sources=1]`. A full liquidation of the position
  entered at **15:45:50.040557Z** on `forecast=-9.608504615051698, sources=3`.
- **16:59:57.948Z** (`fusion_targets.atMillis` **1785949197948**) — the same name: `combinedForecast`
  **-3.2603756638089805**, `sources` **3**, `agreement` **0.872320186445232**, `targetQty` **-200.400971**,
  `currentQty` **0**, `deltaQty` **0.0**.
- The plan as a whole: **`insideBuffer` 22** of **`instruments` 23**.

`FusionLifecycle.originOf` (`FusionLifecycle.java:428`) derives that label from
`t.targetQty().signum() == 0`, so the planner's NVDA target genuinely was **exactly flat**, computed from a
**single** surviving source reading **-0.0** — a degenerate plan, not a view. It routed the full seven
shares, unbuffered. Thirty seconds later a three-source plan with **0.872** agreement, wanting the desk
**short** the same name, released **nothing**. The buffer gives full-size execution to a dropout and zero to
a confirmed signal. That is backwards, and it is the mechanism I had been missing: I had modelled the band
as the reason the desk cannot build, when it is equally the reason the desk keeps being thrown flat.

It is not an isolated row. The 2026-08-04 20:10–20:17Z window carries the identical reason string across
**seven** names in seven minutes — XOM, CVX, GOOG, AAPL, NVDA, AMZN, MSFT, every one
`[forecast=0.0/-0.0, sources=1]`. That is a simultaneous source dropout flattening the whole book, and it is
the shape behind the ledger rows reading `gross 52,192→0`, `gross 54,093→0` and `gross 93,878→26,441`. The
churn is paid for: NVDA **236** fills on **$201,069.31** turnover, MSFT **200** on **$214,385.43**, GOOG
**176** on **$188,284.68**, with `attribution.totalFees` at **398.739093** against a firm total of
**-619.47984806** — the fees are approaching two-thirds of the entire loss.

So I have promoted this to **must-fix #1** and demoted the entry band to #2. They are two halves of one
asymmetry, but the dropout half is the one that moves size (seven shares at once versus the band's
~0.06/cycle) and the one that fires first; fixing re-entry while a dropout can still liquidate the book at
will would be fixing the back half of a loop whose front half still runs.

## Change vs market — the honest split

**Neither, and I will not dress it up.** No change of mine was live in this window: ADR-0139's revert
committed at the very start of it and is under measurement, so the code that traded is code I did not
author this cycle. Only one alpha position survives — GOOG `8.000000` at `avgCost` **361.72000000** against
`mark` **361.90500000**, `unrealizedPnl` **+1.48000000** — plus the HEDGE ES leg at **-0.002856**
(`unrealizedPnl` **+0.14773466**). The **+3.37** window move is the mark on those, plus the realized result
of a seven-share NVDA round trip and the fees on it. Seven shares over an hour is noise. It is evidence
about **neither** the market nor any change, and the dropout finding above stands on the order reasons and
the plan, not on this PnL.

## Decision — no code change this cycle

`scripts/score-change.py score` reports `e956dcf46 still accumulating evidence (1/6 cycles)` and
`reports/.pending-baseline.json` names that commit. Under ADR-0116 a change now would destroy the evidence
for the revert being measured, so the register, the findings and this analysis are the whole of this
cycle's output. Next cycle, once that row is scored, the one change targets must-fix #1: teach the planner
to tell "the target decayed to zero" apart from "the sources went away", and hold rather than liquidate on
the latter. Per Rule 353 that change ships a unit test reproducing the NVDA 16:59:27 row — `sources=1`,
`combinedForecast=-0.0`, full-size route — **before** it changes any behaviour.
