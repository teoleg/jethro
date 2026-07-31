The σ-cold freeze cleared on its own and the desk deployed a real book — and that exposed a far more expensive defect: 64% of the firm's net equity sits in names with no `hedge_beta`, so the hedge sized itself at under a third of the exposure and reported ON-TARGET.

*(Every figure below is read from `/api/risk`, `/api/hedging`, `/api/fusion/targets`, `logs/report.md` or
the live Postgres. None is authored here — invariant 7 / ADR-0016.)*

## Why there is no code change this cycle

`scripts/score-change.py score` prints `e61c7f5aa still accumulating evidence (3/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still exists. Stacking a second change on top of one
still under measurement destroys the evidence, so this cycle is diagnosis only.

## Situation

**Money.** `/api/risk` `.total` moved during the run as the desk built: **$563.79** total PnL at one read,
**$652.57** at a later one. The SITUATION header (generated at 15:00Z) reads PnL **-163.54** since last run
and **-154.79** over the last three; the 14:35:53Z heartbeat has `pnl_growth_pct` **-5.03** against
`pnl_target_pct` **1.0**, `on_track` **false**, `stale` **true**, `underwater` **false**. Off the growth
target, and the run-over-run fall is the mark-to-market of a book that went from nothing to real size.

**Risk.** Gross **$76,657.72** at the header read and **$86,777.90** later; net **-$74,838.88** → later
**-$42,118.67**. Gross is **5.1%** of the $1,500,000 firm cap, headroom **$1,423,342**; **Flags: none**;
breaker untripped; `var95` **1161.13** on `coveredExposure` **76657.72**. Not danger — this is the
opportunity case finally being taken. Exposure rising here is the goal, not a concern.

**Cause — and the honest split between market and my changes.** The desk went from **3 shares of AAPL** to
**21 equity positions** between 14:41Z and 15:00Z. Neither ADR-0132 nor ADR-0133 caused that: the σ-cold
veto that had frozen 18 of 21 aims cleared because the durable mark store crossed the sensor's warm-up
span. This boot logged `risk-cut σ sensor still cold` at **103–117 of 121** stored prices, against **38–99**
one boot earlier — the accumulation Rule 176 identified simply arrived. So the deployment is the *warm-up*,
and the negative unrealized on it is *market* (a rising tape against a short book). I claim neither credit
nor blame for either (Rules 141/152/174).

**Danger.** None on the cap or breaker. But there is a structural one, below.

## Step 0 — grading last cycle's change (`e61c7f5aa`, ADR-0133, the band cap)

**Deployed: ✅** — `ops_jvm.uptimeSeconds` **1427** at report generation and **1748** on a later direct
read, against a boot logged at 10:36 EDT; the running process is the fix build.
**Effect: ⚠️ still unscored, but no longer starved of input.** Last cycle only 3 of 21 names ever reached
the band; this cycle `/api/fusion/targets` publishes **20** instruments with **8** carrying a non-zero
`deltaQty` and the rest at exactly 0.0, so the band is being exercised across a real book for the first
time. **Regression check: none** — breaker untripped, no new WARN/ERROR classes, no risk cuts.

## What the telemetry showed — the new item #1

`/api/hedging`, EQUITY axis, read live: `netExposureUsd` **-60979.73**, `tier` **STRUCTURAL**, `status`
**ON-TARGET**, `rawTargetNotionalUsd` **18860.33**, `heldProxyQty` **0.050468** vs `targetProxyQty`
**0.050481**, rationale *"largest delta under the 4715.08 no-trade band, holding"*. The hedger is telling
the desk it has neutralized the book while sizing itself at under a third of the exposure.

The reason is in reference data, and it reconciles to the app's own published number. `HedgeMath`
`structuralBetaHedge` sizes `Σ βᵢ·Eᵢ` and, by construction, **skips any name with no assigned beta** — it
has its own test asserting exactly that (`structuralBetaHedgeSkipsNamesWithNoAssignedBeta`). Querying the
live `instrument_attributes`: **only 8 of 28 equity names carry `hedge_beta`** — AAPL 1.25, NVDA 1.75,
AMZN 1.20, JPM 1.10, MSFT 1.10, GOOG 1.05, SAP 1.00, JNJ 0.55, i.e. the original sim-era universe. **BAC,
CAT, CVX, HD, KO, MCD, NEE, PFE, PG, UNH, WMT, XOM** and eight more carry none.

Recomputing `Σ βᵢ·Eᵢ` from the live positions and the live betas gives **-18,856.99** against the hedger's
published `rawTargetNotionalUsd` **18,860.33** — the same number to within seconds of snapshot drift, so
the mechanism is confirmed, not inferred. Of the **-$60,974** net equity, **-$22,077** is beta-covered and
**-$38,897 (63.8%)** is invisible to the hedge. **XOM alone is $24,442 of gross — the largest equity
position on the desk, 28% of the book — and carries no beta at all.**

A missing beta is being read as *"this name has no market exposure"* when it means *"this name's market
exposure is unknown"*. That is the same defect class CLAUDE.md already records from the V31→V34 universe
expansion: instruments added to the master without their attribute rows backfilled in the same change.

## The decision

This becomes **item #1** in `reports/must-fix.md`, above the σ-cold item (which is ⚠️ downgraded, not
closed: it cleared by accumulation this cycle and will re-freeze after any weekend or outage gap). Next
cycle, once ADR-0133 scores, the change makes the structural hedge honest about what it cannot see: derive
each name's beta **in code** from the durable mark history the platform already stores, and surface the
uncovered fraction on the axis so a partially-covered book can never report ON-TARGET. I will **not**
hand-author betas — CLAUDE.md names a `β=1.0` placeholder as a lesson already paid for, and a self-chosen
beta that sizes a hedge is exactly the invented risk number the house rules forbid. Because it changes how
a risk control is sized, it ships with an ADR in the same commit.
