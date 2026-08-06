No code change — ADR-0143 is under measurement (1 of 6) — but the commit that carried it bounced the trading JVM, because the restart gate exempts `docs`/`reports`/`ops` and not the loop's own `scripts/` tooling.

*(Every figure below is read from `logs/report.md`, the live `/api/fusion/targets` endpoint, `git`, or the
source files named, or is arithmetic on those read fields. None is authored here — invariant 7 / ADR-0016.)*

## Situation

**Money.** Total PnL **$-849.32**, down **$9.91** since last run and **$53.80** over the last three.
UNDERWATER is cumulative; the book bleeds slowly rather than sharply.

**Risk.** Gross **$18,933.02** — **1.3%** of the $1,500,000 firm cap, headroom **$1,481,067**. Net
**$-1,870.25**, **0.2%** of the $1,000,000 net cap. `breaker.halted` false, `regime` CALM (`trend` CHOP,
`volRatio` **0.93**), `riskCuts` empty, `bookVolBrake` **0.9098**. Not a danger state. Gross rose
**$2,947.76** this window — with 1.3% of the budget used that is the book deploying, which is the goal, not
a concern.

**Cause.** Mixed, for the first time in seven cycles. `git diff --name-only 39451ce..27564bb` touches only
`docs/`, `reports/` and two files under `scripts/` — nothing the app compiles or loads — so the PnL and gross
moves are still overwhelmingly market. But my commit **did** restart the app, and that restart's cost is
visible on the tape (below), so this window is not the clean 0%-change/100%-market read the previous six were.

**Danger.** No. Not bleeding near a cap, nowhere near the breaker.

## Why no change

`scripts/score-change.py score` prints `27564bb15 still accumulating evidence (1/6 cycles)` and
`reports/.pending-baseline.json` still exists. Under ADR-0116 that forbids a new code change — piling one on
top would destroy the evidence. Five more cycles.

## Step 0 — ADR-0143 landed, and landing it caused the very defect ADR-0142 was written to kill

The code is live: `REVERT_KEEP_PATHS` at line **73** of `scripts/score-change.py`, with `revertable_paths`
and `revert_code_paths` alongside it. Its own claim is **not yet gradable** — it only fires on the next ❌ BAD
verdict, and none has occurred. Nothing this cycle is evidence for or against it.

What *is* evidence is the restart. `ops_jvm.uptimeSeconds` **1064** against `traffic.timestampMillis`
**1786039202442** puts the boot instant at **17:42:18Z**, against **14:41:21Z** for the process that had
survived the previous six cycles. Baseline written **17:41:45Z**, status commit **17:42:06Z**, boot
**17:42:18Z** — my commit did it.

It should not have. `ops/improve-loop.sh:135` reads `NON_BINARY_PATHS='^(reports|docs|ops)/'` and rebuilds
whenever anything falls outside it. My commit touched `scripts/score-change.py` and
`scripts/test-score-change.py` — the loop's own out-of-band Python, run by the cron wrapper and never by the
app. `grep -rn "scripts/" --include=*.gradle --include=settings.gradle*` returns nothing, so neither is a
Gradle input. They cannot reach the binary; the gate restarted the JVM anyway.

**The cost is on the order tape, not inferred.** Two orders at **17:42:57Z** — `XOM BUY 5.000000` and
`MCD BUY 9.000000`, both `REJECTED`, both `fusion exit — target decayed to flat [forecast=0.0, sources=0]`,
reason `no market data`. `sources=0` is every sensor cold: the desk formed an intent to **liquidate two live
positions** because the restart erased the opinions behind them, and was saved only by marks not having
loaded yet. The WARN log from 13:42:57-04:00 carries the cold-seed block verbatim (TSLA, GOOGL, AMD, ES,
PLTR, the whole `USD.TSY.*`/`USD.SOFR.*` curve), and `hedging.covarianceReady` is **false** again. That is
ADR-0142's mechanism reproducing exactly, through an incomplete path list — inside ADR-0143's own evaluation
window. It is the new register **#1**.

The fix, next unfrozen cycle, is to **enumerate** the loop's tooling (`score-change.py`,
`test-score-change.py`, `build-prompt.py`, `system-report.py`) rather than glob `scripts/`: `run-local.sh`,
`svc.sh` and the `reset-*.sh` scripts are the *launcher*, and a change there genuinely does warrant a
restart. Enumerating fails safe toward restarting when a future script is added.

## What I re-measured while frozen

Item #2 (composition) confirmed on a **fourth** independent window, this time on the full **20**-target live
`/api/fusion/targets` set rather than the **6** `logs/report.md` elides to. Shares swung again — mean-reverting
pair **50.23%** against **46.85% / 61.90% / 51.0%** before, weights barely moved — while the aggregate did
not: **aim-weighted 3600s expectancy +0.2395 bps gross → -1.7605 bps net** of the **2.00 bps** round trip
(`fee_bps` **1.00** per side on every equity), against **-1.7898 / -1.60 / -1.9464**. Four windows, same sign;
900s and 225s are worse (**-2.2297**, **-2.0047** net). `xsreversion` at 900s (**t=-2.56**) is the only |t| > 2
of 15 rows for a fifth consecutive window — still short of Bonferroni's **2.94**, so the evidence is
sign-consistency, not the p-value. `social` remains the only source clearing cost at 3600s (**+9.1815** gross
→ **+7.1815** net, hit **0.580** on **342**) at **0.00%** of the aim; ADR-0139 already tried that gate and was
graded ❌ BAD, so it stays untouched.

## Next unfrozen cycle

Target register #1 — enumerate the loop's own tooling in `NON_BINARY_PATHS` so a scoring-script edit stops
re-seeding the desk cold. Graded by the three-part VERIFY-BY in `reports/must-fix.md`: unchanged boot instant,
no `sensor still cold` WARN, no order with `sources=0`. `baseline` deliberately NOT run this cycle (Rule 396).
