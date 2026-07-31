No change this cycle — ADR-0133 is under measurement at 2/6 — and the σ-cold freeze is now quantified: 18 of 21 names sit at aim 0.0 all process long, so the entire firm book is 3 shares of AAPL against $1.5m of headroom.

*(Every figure below is read from the live endpoints, `logs/report.md`, `reports/improvement-ledger.md`
or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

## Why there is no change this cycle

`scripts/score-change.py score` prints `e61c7f5aa still accumulating evidence (2/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still exists. Stacking a second change on top of one
still being measured destroys the evidence, so this cycle is diagnosis only.

## Situation

**Money.** `/api/risk` `.total`: realized **$738.55885736**, unrealized **$1.84500000**, total
**$740.40385736**. The SITUATION header reads PnL **-0.42** since last run and **-41.64** over the last
three. The 14:08:16Z heartbeat has `pnl_growth_pct` **-5.29** against `pnl_target_pct` **1.0**, `on_track`
**false**, `stale` **true**, `underwater` **false**. Off the growth target, not bleeding.

**Risk.** Gross **$906.46500000**, net **-$906.46500000** — **0.1%** of the $1,500,000 firm cap, headroom
**$1,499,091**; **Flags: none**. That headroom line *is* the problem: the firm's entire book is a short in
one name.

**Cause.** Exactly one order in the window — **AAPL SELL 3, FILLED 14:29:27**, about 21 minutes into a
process that the loop tears down every ~30. Nothing else was held, so there is no market leg to separate
out; the window's move is that single position and its cost. This is the σ-warm clock reaching AAPL, not
anything I shipped. I claim neither credit nor blame for it.

**Danger.** None. Not near a cap, breaker untripped, `riskCuts` `[]`, `bookVolBrake` **1.0**.

## Step 0 — grading last cycle's change (`e61c7f5aa`, ADR-0133, the band cap)

Deployed: ✅ — `ops_jvm.uptimeSeconds` **1281** at report generation, **1340** on a later direct read, both
well after the commit landed. Effect: **⚠️ upgraded from vacuous to active-but-unscored.** Last cycle every
`aim` was exactly 0.0 so the band was never consulted; this cycle `/api/fusion/targets` publishes three
non-zero aims — AAPL **-21.458419**, MSFT **5.806959**, AMZN **-6.428999** — and AAPL's published
`currentQty` **-3.0** against `deltaQty` **-15.939691** is strictly narrower than the distance between its
aim and its holding. The band is being evaluated on the warm names. It still cannot be graded: 2/6 cycles,
and only 3 of 21 names ever reach it.

## What the telemetry showed

`streamVolMeasuredNames` = **4** at 22 minutes of uptime, against `volBudgetNames` **19** and
`covarianceCoveredNames` **19**. **18 of 21** published `aims` are exactly **0.0** — including the desk's
largest targets: PFE **2848.93**, NEE **-1250.11**, WMT **878.21**, BAC **-765.91**, NVDA **-578.31**. The
boot logged `risk-cut σ sensor still cold` for **18** names against the **121** prices the sensor needs
(`jethro.fusion.risk-cut.vol-span=120` ⇒ `warmupPrices() = 121`).

**The genuinely new fact, and it corrects last cycle's read.** I called the seed a permanent floor. It is
not — comparing the same names against the previous process's boot log, every one improved: MCD **38 → 57**,
KO **43 → 64**, WMT **43 → 64**, BAC **41 → 62**, NEE **41 → 62**, GOOG **46 → 67**, NVDA **49 → 71**,
MSFT **61 → 83**, AMZN **80 → 101**, AAPL **99 → 103**. The durable mark store is accumulating. But it
gains roughly twenty prices per ~35 minutes of elapsed open market while the loop reboots every ~30, and
`FusionLifecycle.seedVolatility` seeds each name **once per process** (`volSeeded.add`), so within a process
the only further warming is live prints at the 30s cadence — which is why the measured count crawls
0 → 1 → 4 over twenty minutes rather than arriving. The desk therefore spends its entire life able to hold
only its three or four fastest-printing names.

## The decision

This is item #1 in `reports/must-fix.md` and it stays there, ⚠️ STILL-BROKEN. It is upstream of both fixes I
shipped: `stopArmed` false ⇒ `PositionBuffer.mayIncrease` false ⇒ the ADR-0064/0075 reduce-only branch
re-seeds the aim to `held + delta`, so ADR-0132's destination clamp and ADR-0133's band cap are simply not
reached for the 18 frozen names. That is why both graded unverifiable rather than wrong.

Next cycle, once ADR-0133 scores, the change targets the veto's **asymmetry**: a σ sensor that cannot price
a stop is a sound reason not to *open* risk, but it is not a reason to *liquidate* a book the desk held and
was measuring one process earlier — which is what emptied gross **$17,942.67878750 → $0.00000000** in five
fills 19 seconds after the 13:53 boot. That leaves the correct half of ADR-0126 ("do not open what cannot be
stopped") intact and removes only the destructive half, and it is a different lever from ADR-0131
(`efccc6502`, re-seed on the warm-up cadence), which attacked this same root cause, scored **❌ BAD** and was
reverted — a graded-BAD remedy retires the remedy, not the defect. Because it changes when a risk control
fires, it ships with an ADR in the same commit.
