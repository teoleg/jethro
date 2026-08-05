The no-trade buffer's freeze has a closed form — it releases an order only once the aim reaches 1/|forecast| of the target, and it reproduces all 26 frozen names exactly.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `/api/social`,
`ops_jvm`, the report's `recent_orders` and the scorer. None is authored here — invariant 7 / ADR-0016.)*

# Loop analysis — 2026-08-05 17:30Z

**NO CODE CHANGE — ADR-0116 measurement freeze.** `reports/.pending-baseline.json` names `e956dcf46` and
the scorer's own `window_since` reports **2 of 6** cycles accrued; the ledger's newest row is still
`d51f179a2`. Last cycle's revert is under measurement and a new change would destroy its evidence.

## Situation — the four questions, answered first

**1. Money.** Total PnL **$-635.46245004** (`realizedPnl` **-621.13277172**, `unrealizedPnl`
**-14.32967832**). Since the last run **-15.01**; over the last three runs **-30.27**. Still UNDERWATER and
off the **1.0%**/3-iteration target (`pnl_growth_pct` **-2.88**, `on_track` false, `stale` true). By book:
`ALPHA` **-598.24172505**, `MACRO` **-56.79950536**, `HEDGE` **+19.57878037** — the hedge is again the only
book in profit. `totalFees` **398.764282** against the firm total remains the dominant single line (Rule 357).

**2. Risk.** Gross **$5,247.70665**, net **$510.61335** — **0.3%** of the **$1,500,000** firm gross cap with
**$1,494,752** of headroom, **0.1%** of the net cap. `riskCuts` empty, `riskCutStoppedNames` **0**,
`bookVolBrake` **1.0**. Flag is `UNDERWATER`, not `NEAR FIRM CAP` and not `DANGER`. Under ADR-0132 this is
under-deployment, not cap pressure. The whole book is two positions: GOOG **8** shares (EQUITY gross
**2,879.16**) and the ADR-0019 ES hedge against it (FUTURE gross **2,368.54665**).

**3. Cause.** No change of mine was live in this window — the revert is 2/6 cycles into measurement, and
Step 0 re-confirms it deployed and holds (below).

**4. Danger.** No. Not near the cap, no risk cut armed, no breaker. The live problem is the inverse: the
desk is not deploying.

## Step 0 — `e956dcf46` (the completed ADR-0139 revert): ✅ VERIFIED, second consecutive boot

Deployment confirmed first, on a **fresh** JVM: `traffic.timestampMillis` **1785951001992** −
`ops_jvm.uptimeSeconds` **1388** = boot **17:06:53.992Z**, well after the revert's **16:38:20Z** commit.
Of the 12 `recent` social posts, all tier `STANDARD`, **all 12** now read `credible: false`;
`counters.corroborated` **7** in **1388 s** against **17/1427 s** on the ADR-0139 boots;
`manipulationSuspected` **39** on `ingested` **3390** / `kept` **800**, so the pump tell is untouched. The
strict conjunction is what the running app applies. The scorer owns the vector verdict, not me.

## What this cycle actually established — must-fix #2 now has a closed form that predicts the live book

`insideBuffer` is **26 of 26** instruments, every `deltaQty` **0.0**, while the plan wants real size:
AMZN `targetQty` **217.187537** at `combinedForecast` **4.89099753538535** on **4** sources against
`currentQty` **0**; CVX **365.706789** on 2 sources; MSFT **121.722257**, AAPL **-191.708027**, JPM
**191.617345**, NEE **333.937475** — all on 3 sources, all against zero held, all released as nothing.

Reading `PositionBuffer.band` (`PositionBuffer.java:338`) with the shipped
`jethro.fusion.position-buffer.fraction=0.10` and `Forecast.TARGET_ABS = 10.0`, the band is
`|target| × TARGET_ABS / |forecast| × width`, so the no-trade test `|aim − held| ≤ band` reduces, from a
flat holding, to **|aim| / |target| ≤ 1 / |forecast|**. Against the live `aims` map that inequality holds
for all six visible names and matches every `deltaQty` **0.0** — AMZN **0.0735** vs **0.2045**, CVX
**0.0142** vs **0.2373**, MSFT **0.0297** vs **0.2550**, AAPL **0.1667** vs **0.3272**, JPM **0.0083** vs
**0.3510**, NEE **0.0861** vs **0.5554**. It is not a partial explanation; it is the rule.

Two consequences follow, and they are what the fix has to answer. The threshold is **1/|forecast|**, so a
*weaker* view must have its aim carried *further* before a single share routes — NEE at forecast **1.8005**
needs the aim past **55%** of target, AMZN at **4.891** past **20%**. And the aim is an EWMA seeded from the
held position in an in-memory map (`PositionBuffer.java:105`), re-seeded to the holding on every JVM boot
and reset to flat by `withinTarget` on every forecast sign flip — while this JVM's uptime is **1388 s**.
So whether a name can ever be opened is a function of how long the process has been up, not of the strength
of the evidence.

## The two must-fix items are the two branches of one `if`

`bufferedDelta` (`PositionBuffer.java:468`) returns the **full, unbuffered** gap when
`target.signum() == 0`, and the buffered near-edge otherwise. Item #1 is the first branch reached by a
degenerate plan — the NVDA `fusion exit — target decayed to flat [forecast=-0.0, sources=1]` that routed
all 7 shares 30 s before the same name replanned at `combinedForecast` **-3.2603756638089805** on **3**
sources with `agreement` **0.872320186445232**. Item #2 is the second branch. That branch's own javadoc
states the intent — "an EXIT is what a control ORDERED, not what the arithmetic happens to read" (ADR-0107)
— but scopes it to the `aim == 0` case only, so a `target == 0` produced by **source collapse** is still
read as an ordered exit. That is the exact line next cycle's change targets, with a unit test reproducing
the 16:59:27 row first (Rule 353's standing precondition).

## Attribution — market, not change

The window's only order was the ADR-0019 auto-hedge ES `SELL 0.003243` at 17:00:28. No alpha order routed.
`ALPHA.unrealizedPnl` moved **+1.48000000 → -14.60000000** on the same unchanged GOOG **8** shares — opened
two cycles ago at `fusion entry — target increase [forecast=6.3413095741475525, sources=3]`, not by any
change of mine. The **-15.01** is mark-to-market on a position no change of mine opened, plus the hedge fee:
**market**, and evidence about neither the market thesis nor a change. I am not crediting or blaming
anything for it.

## Decision

Hold. `e956dcf46` is 2/6 cycles into its evaluation window, so the contract forbids a new change. The
cycle's work went into converting must-fix #2 from a described mechanism into a closed form that predicts
the live plan exactly, and into locating both items on the same line of `bufferedDelta` — so that when the
freeze lifts, the one change is aimed at a defect whose behaviour is already pinned by telemetry and
testable before it ships.
