Held the code frozen for the pending measurement — and found the answer to the standing question: one source DOES have edge, and it is multiplied to exactly zero by an owner-set dial.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/signals/telemetry`,
`/api/fusion/targets`, `/api/tca`, `ops_jvm`, `traffic`, `recent_orders`, `application.properties`, or the
scorer's own output. None is authored here — invariant 7 / ADR-0016.)*

## Step 0 — last cycle's pending change (`026cda49d`, the ADR-0136 revert): ✅ DEPLOYED, ⏳ STILL NOT SCORED

`scripts/score-change.py score` prints `026cda49d still accumulating evidence (4/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still holds its snapshot (`ts 2026-08-04T13:37:23Z`).
Deployment re-confirmed: `ops_jvm.uptimeSeconds` **`1316`** at `traffic.timestampMillis` **`1785857401923`**
→ a fresh JVM booted well after the commit. Per ADR-0116 that **forbids a code change this cycle**; I
recorded **no baseline**, so its window is intact. What follows aims next cycle's one change.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **$-297.16** (`/api/risk` `.total`, firm headline incl. hedge) — **+$33.00** since
   last run, **+$17.75** across the last three. Still `UNDERWATER`, but rising. Books: `ALPHA -329.04`
   (realized **-374.65**, unrealized **+45.61**), `HEDGE +88.67`, `MACRO -56.80`.
2. **Risk.** Gross **$51,339.05**, net **$-20,634.40** — **3.4%** of the $1,500,000 firm cap, headroom
   **$1,448,661**; net **2.1%** of the $1,000,000 net cap. The book has come decisively **off dormant**
   ($0.00 → $11,313.99 → $51,339.05 over three cycles). With this much room that is the goal, not a concern.
3. **Cause.** Not attributable to any change. 23 fills today, all `fusion entry — target increase` on
   CAT/AMZN/GOOG/JPM/PFE/UNH/NEE/JNJ plus the follow-on `auto-hedge EQUITY (ADR-0019)` ES sells — the
   normal entry path warming up. The **+$45.61 of ALPHA unrealized** is mark movement on positions this
   window opened; **market, not code**. Nothing is claimed for the pending revert.
4. **Danger.** No. `DANGER` needs bleeding near the cap; gross is at 3.4% of it, `breaker.halted` **false**,
   `riskCuts` **`[]`**, `riskCutStoppedNames` **0**, `routing` **true**.

## Last block's item #1 is falsified — again by state, not by code (the fourth time)

Its claim was that the ADR-0094 band `|target|/|forecast|` structurally prevents a flat name from opening.
It reconciled 6-of-6 at the time, yet **20 names traded this window with no trading commit in between**.
`insideBuffer` fell **22 → 20 → 16** of 21 as `streamVolMeasuredNames` reached **20** = `volBudgetNames`
and `covarianceCoveredNames` **20**; the aims grew with it (`PFE -824.47`, `NEE -191.52`, `KO +169.12`,
`JPM +80.45`, `JNJ +79.50`). The band was never a wall — it was a **convergence lag** on a cold JVM.
Rule 287 exactly, and I am striking the item rather than prescribing into it: three ❌ BAD changes
(ADR-0135, its revert, ADR-0136) were each built on reading warm-up as structure.

## The real finding: the only source with measured edge contributes a forecast of exactly 0.0

The standing priority asks "does ANY signal predict returns here?" Computing the desk's own ADR-0077/0081
clustered statistic — `avgReturnBps ÷ (stdCohortMeanBps ÷ √cohorts)` — over all 15 (source, horizon) pairs
in `/api/signals/telemetry`, one stands clear of the field:

| horizon | source | resolved | cohorts | avgReturnBps | clustered t |
| --- | --- | --- | --- | --- | --- |
| 3600 | **social** | 344 | 32 | **+9.4817** | **+1.75** |
| 3600 | reversion | 649 | 87 | +2.7012 | +0.93 |
| 3600 | trend | 696 | 98 | −0.7778 | −0.30 |
| 3600 | xsreversion | 634 | 39 | −4.9878 | −1.03 |
| 900 | social | 675 | 67 | +1.6706 | +0.90 |
| 900 | trend | 2347 | 316 | +0.2513 | +0.36 |
| 225 | trend | 5873 | 500 | +0.0298 | +0.13 |

Social is the **highest t of all fifteen**, its expectancy is 3.5× the next-best, and it rises monotonically
with horizon (**+0.28 → +1.67 → +9.48 bps** at 225/900/3600s) — the shape of information that pays out over
about an hour. Against measured cost it is not marginal: `/api/tca` gives sub-bps slippage on the liquid
names (`MSFT 0.594`, `PFE 0.633`, `AMZN 0.658`, `GOOG 0.746`) and `turnover_cost_by_name` gives **1.00 bps**
equity fee per side, so a round trip is roughly **3.3 bps** — social's 1-hour expectancy clears it ~3×.

**And the desk cannot act on any of it.** `jethro.fusion.social.per-channel` is **`0`**, and
`SourceForecasts.fromSocial` computes `strength = max(1, corroboratingChannels) × perChannel` — so **every**
social forecast is exactly `0.0`, whatever its direction or corroboration. The `weights` map showing
`social 1.725995` (the largest of the five, above `reversion 1.500`, `momentum 0.761`, `trend 0.713`,
`xsreversion 0.300`) is **decorative**: the telemetry weighting is optimising a source that is multiplied
out downstream. So the book is sized entirely off trend / reversion / xsreversion / momentum — **not one of
which has a significantly positive expectancy at any horizon** — while the one source that does reads zero.

One clean property worth stating: because social has never sized, its telemetry is an **uncontaminated
out-of-sample measurement** of following it. That is the honest version of the OOS evidence ADR-0049 asks for.

## Why I am not flipping that dial, and what has to happen instead

`per-channel=0` is **owner-set** (`ADVISORY-ONLY ENFORCEMENT (Oleg, 2026-07-27)`) and restores **ADR-0049**
— "a social subject can NEVER originate an order". That is a settled decision, not a tuning knob, so it
needs a **superseding ADR and the owner's call**, never a quiet edit. Two things must be said honestly
alongside the evidence:

- The comment's own stated justification — *"Social's own OOS edge was measured negative anyway"* — is what
  the live telemetry now contradicts. That is new evidence against a premise, which is exactly the ground on
  which an ADR may be superseded.
- But the comment also sets the condition: *"Restore to 4.0 ONLY to deliberately let corroborated social
  size again (still behind the OOS edge gate)."* That gate is currently **`jethro.fusion.edge-gate.enabled=false`**
  (ADR-0122 exploration mode). So restoring `4.0` today would let social size **without** the safeguard the
  owner's own sentence assumed — a materially different decision from the one that comment authorises. I
  will not conflate the two (CLAUDE.md: one concept per decision request).
- And t=**1.75** on **32** cohorts is suggestive, not proven: it sits below the desk's own `t-hurdle=2.0`.
  Two cycles of cohort growth will tell us whether it holds. That is the VERIFY-BY now at #1 in
  `reports/must-fix.md`, and it costs nothing to watch while `026cda49d` finishes its window.

I deliberately did **not** re-rank the fee bill: `totalFees` **360.289492** still exceeds the **-297.16**
deficit, but Rule 289's test settles it with this window's own numbers — fees grew about **$2.60** while
gross grew **$32,374.63**, so it is a sunk figure over **5148** lifetime fills, not a live bleed.
