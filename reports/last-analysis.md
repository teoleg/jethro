No code change — the ADR-0116 freeze holds at 5 of 6 — and settling the open question about which field the desk actually routes on invalidated three cycles of my own arithmetic: `targetQty` is the destination, `aims` is the waypoint, and only the aim routes.

*(Every figure below is read from `logs/report.md`, `reports/run-status.json`, `reports/.pending-baseline.json`,
`git diff`, the source files named, and the scorer's own output, or is arithmetic on those read fields. None
is authored here — invariant 7 / ADR-0016.)*

## Situation

**Money.** Total PnL **$-820.28**, down **$24.77** since last run and **$53.02** over the last three.
UNDERWATER is cumulative; the book is bleeding slowly rather than sharply.

**Risk.** Gross **$7,874.70** — **0.5%** of the $1,500,000 firm cap, headroom **$1,492,125**. Net
**$-282.06**, **0.0%** of the $1,000,000 net cap. `breaker.halted` false, `regime` CALM (`trend` CHOP,
`volRatio` **0.93**), `riskCuts` empty, `bookVolBrake` **1.0**. Not a danger state — the opposite failure:
a book at half a percent of an owner-set budget is **dormant**, which CLAUDE.md calls a failure to attack.

**Cause.** Nothing I did. `git diff --name-only 39451ce..HEAD` touches only `docs/` and `reports/` — the
fifth consecutive **0% change / 100% market** window, so the PnL and gross moves earn my changes neither
credit nor blame. ADR-0142 verified a fifth time: `uptimeSeconds` **8320** against `traffic.timestampMillis`
**1786035601280** puts the boot instant at **1786027281280**, the same 14:41:21Z process as the four prior
cycles — the self-inflicted restart is genuinely gone.

**Danger.** No. Not bleeding near a cap, nowhere near the breaker.

## Why no change

`scripts/score-change.py score` prints `39451ce71 still accumulating evidence (5/6 cycles)` and
`reports/.pending-baseline.json` still exists. Under ADR-0116 that forbids a new code change — one more
cycle and it scores.

## What the cycle bought

I settled the ambiguity the register flagged last cycle rather than reasoning further on top of it, and the
answer was expensive. From `PositionBuffer.apply`, **`targetQty` is the planner's end-state destination,
`aims` is the current-cycle waypoint, and the routed `deltaQty` is the gap from held to the *aim*** — not to
`targetQty`. The last three cycles' convergence arithmetic used `targetQty` as the routed quantity and
therefore **overstated the mismatch**. That item is struck and the correction recorded, not quietly dropped.

Re-measuring item #1 on this independent window **confirmed the conclusion and refuted the culprit**. The aim
is still majority mean-reverting — `reversion` **52.19%** + `xsreversion` **9.71%** = **61.90%** against
`trend` **38.09%** — but `xsreversion`'s share moved **23.1% → 9.71%** with its weight unchanged at **0.25**,
so last cycle's proposed VERIFY-BY ("share below 23.1%") is a statistic that swings further than any change
would move it. I replaced it with an aggregate that doesn't: the **aim-weighted 3600s expectancy**, reading
**+0.2102 bps gross** against a measured **2.00 bps** round trip (`fee_bps` **1.00** per side on every
equity) — **-1.7898 bps net**. That sign is stable across both windows; the individual shares are not.

The sharper finding concerns `reversion` itself: **+0.033 / +0.060 / +0.010** bps at 3600s / 900s / 225s with
|t| ≤ **0.10** across **803 / 2657 / 5082** resolved observations. It is not a negative signal — it is
**indistinguishable from zero**, and the desk routes half its aim into it at 2 bps a round trip.
`xsreversion` is negative at all three horizons for a **third** consecutive window (**-0.215 / -2.168 /
-5.306**), its 900s **t = -2.40** again the only |t| > 2 in the table — which **does not clear Bonferroni at
15 tests (2.94)**; the weight of that evidence is sign-consistency across three windows, not the p-value.
`social` remains the only source clearing cost (**+3.296** gross → **+1.296** net) and contributes **0.0%**;
ADR-0139 already tried loosening that gate and was graded ❌ BAD, so it stays untouched.

Settling the ambiguity also promoted a new **#2** with a precise mechanism: `band` is priced off `targetQty`
while the gap it gates is `aim − held`, so a name early on its aim path cannot open at all. BAC, NEE and KO
each carry a nonzero aim against a flat book and route **exactly 0.0000**, at `|aim|/|target|` of **0.0210**,
**0.0358**, **0.0391** — while the only two names routing meaningfully are the two highest ratios
(**0.158**, **0.103**); `insideBuffer` is **18** of **26**. That is the dormancy mechanism. I ranked it
**below** item #1 deliberately: deploying more capital into an aim measured at **-1.79 bps net of cost**
would lose money faster, so composition has to land first.

## Next unfrozen cycle

Target item #1 — cut the mean-reverting pair's grip so the aim reflects measured expectancy net of the
**2.00 bps** round trip, with its ADR in the same commit. Graded on the aim-weighted 3600s expectancy
exceeding **+2.00 bps** and the pair's combined share falling below **50%** — not on any single source's
share. `baseline` deliberately NOT run this cycle (Rule 396).
