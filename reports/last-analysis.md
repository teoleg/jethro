Held the code frozen for the pending measurement — and found that the desk's direction is roughly flat while its **turnover cost is 79% of the loss**.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/signals/telemetry`,
`/api/fusion/targets`, `ops_jvm`, `traffic`, `recent_orders`, `orders_by_status`, `turnover_cost_by_name`,
`application.properties`, or the scorer's own output; the decompositions are computed by script. None is
authored here — invariant 7 / ADR-0016.)*

## Step 0 — last cycle's pending change (`026cda49d`, the ADR-0136 revert): ✅ DEPLOYED, ⏳ STILL NOT SCORED

`scripts/score-change.py score` prints `026cda49d still accumulating evidence (5/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still holds its snapshot (`ts 2026-08-04T13:37:23Z`).
Deployment re-confirmed on a warm JVM: `ops_jvm.uptimeSeconds` **`1363`** at `traffic.timestampMillis`
**`1785859202173`**, `traffic.up true`, `ticksIn 13234`, `ticksDropped 0`. Per ADR-0116 that **forbids a
code change this cycle**; I recorded **no baseline**, so its window is intact and it should score next run.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **$-458.07450234** (`/api/risk` `.total`, firm headline incl. hedge) — **down
   $153.84** since last run, **down $143.17** over the last 3. The book is **bleeding**, and `UNDERWATER`
   is the live flag. By book: ALPHA **-$467.76946287**, HEDGE **+$66.49446589**, MACRO **-$56.79950536**.
2. **Risk.** Gross **$71,398.69147500** = **4.8%** of the $1,500,000 firm cap, headroom **$1,428,601**;
   net **$-8,366.62147500** = **0.8%** of the $1,000,000 net cap. Gross rose **+$16,039.48** this window
   and **+$71,398.69** over 3 runs — the book came fully off dormant (21 equity positions + the ES hedge).
   That rise is the **goal**, not a danger: no `NEAR FIRM CAP` flag, `riskCuts []`, `riskCutStoppedNames 0`,
   `bookVolBrake 1.0`, and `breaker` untripped. **Not** a DANGER state — bleeding, but nowhere near a cap.
3. **Cause.** No change has shipped in five cycles, so nothing here is attributable to a code change. What
   IS attributable is the running code's own trading, and decomposing it is the finding of the cycle:
   firm total **-$458.07** = **pre-fee trading -$94.09430134** minus **fees $363.980201**. **Fees are
   79.46% of the loss.** Pre-fee, -$94.09 on $71,398.69 of gross is indistinguishable from flat — the desk
   is not losing on direction, it is losing on **churn**. Summing `turnover_cost_by_name` `turnover_usd`
   over the LIVE epoch gives **$4,120,150.44** against the current gross — **57.71×** the book.
4. **Danger.** No. Bleeding, yes; near the cap or the breaker, no. So the correct response is to fix the
   cost mechanism, **not** to de-risk — cutting gross here would forfeit $1.43M of unused headroom against
   the ADR-0132 objective.

5. **Order-level post-mortem.** `recent_orders` shows an hour-scale forecast routed on a ~30-second replan.
   PFE is the exemplar: built short on `fusion entry — target increase` at `forecast` **-8.6125 → -7.0929
   → -5.8472 → -5.5667 → -5.1482 → -5.1178**, then within ~6 minutes flipped to `fusion reduce toward a
   smaller target` at **+0.0134 → +0.0209 → +0.1017 → +0.5167 → +0.5841**, buying back **3 shares at a
   time** every ~30s. PFE now carries **133 fills / $219,600.58 turnover / $21.9601 fees** against a
   **$9,166.59** position (**23.96×**) and is the **worst name at -$195.29620728**. MSFT is worse on the
   ratio (**$200,516.55** turnover, **192 fills**, **$5,970.66** position → **33.58×**). Book-wide,
   `orders_by_status` is **1,911 CANCELLED** vs **5,179 FILLED** — the `fusion re-plan — passive order
   superseded by a fresh target (ADR-0084)` path re-issuing faster than the signal resolves. The losing
   trigger is not a bad direction call; it is the **cadence** at which a slow forecast is re-planned.
6. **Memory.** Rules 290–293 applied. Rule 293 in particular: this JVM is **warm** (`uptimeSeconds 1363`,
   `insideBuffer 17` of 21, `streamVolMeasuredNames 20` = `volBudgetNames`, `covarianceCoveredNames 20`),
   so the cold-start convergence caveat does not apply to anything read here.
7. **Change vs. market.** No change shipped, so **nothing is claimed or blamed on code**. The window's
   -$153.84 is on names the running code actively traded (PFE, MCD, NVDA, MSFT, JPM, NEE, WMT, UNH, JNJ all
   have fills in this window), so it is the desk's own behaviour rather than drift on untouched positions —
   but it is the *baseline* behaviour the pending change is being measured against, not an effect of it.

## What I decided, and why

**No code change** — `026cda49d` is at 5/6 cycles and ADR-0116 requires the window stay clean. The cycle's
work went into re-ranking `reports/must-fix.md`, and the ranking changed on evidence:

- **New item #1 — turnover cost.** Fees are **79.46%** of the firm loss and cumulative turnover is
  **57.71×** the book. This is a measured, certain, dominant loss term.
- **Item #2 (was #1) — social contributes exactly 0.0.** Re-measured, it still holds and hardened: on this
  run's telemetry `social` @3600s is `avgReturnBps` **+8.7221**, `resolved` 345, `cohorts` 32, `hitRate`
  **0.5977** → clustered t **+1.601**, the **only** source clearing the desk's own **1.5** hurdle
  (`reversion` +1.079, `trend` −0.582, `momentum` −0.701, `xsreversion` −1.130). With the book deployed
  there is now *structural* proof, not just arithmetic: every `contributions` array lists only
  `trend`/`reversion`/`xsreversion`, and `forecastScalars` has **no `social` entry at all** — while
  `weights` still shows `social 1.8401656178888028`, the largest of the five.

**Why cost outranks the edge dial:** social's edge is hour-scale (**+0.1611 → +1.8322 → +8.7221** bps at
225/900/3600s). A book that re-trades itself **57.71×** cannot hold a position long enough to collect an
hour-scale forecast — it would spend the new edge on fees exactly as it is spending the current one. Fixing
the cadence is the **precondition** for the social decision paying, not a competing priority. Next cycle's
one change targets item #1, and item #2 remains an owner-facing superseding ADR (gate and dial together,
Rule 292), not a quiet dial turn.
