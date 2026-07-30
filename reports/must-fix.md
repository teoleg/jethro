# MUST-FIX register — the loop's carried-forward, verified backlog

Maintained by the improvement loop every run (see `ops/improve-prompt.md` Step 0). The point is to CLOSE
the loop: a defect is not "done" until a later run has VERIFIED, from live telemetry, that the fix landed
and worked — so the same problem can't bleed money run after run.

**How it works**
- Newest verification block on TOP. Each open item = a specific defect + a rank + a concrete **VERIFY-BY**
  (the exact metric/endpoint/number that proves it fixed next run).
- Every run: mark each open item ✅ VERIFIED / ⚠️ STILL-BROKEN / 🔴 REGRESSED with the proving number read
  from live telemetry; strike VERIFIED items (move them below the line); re-rank what remains, most-costly
  first. The ONE change per run targets item **#1**.
- Every number here is READ from live telemetry, never authored (invariant 7 / ADR-0016). The scorer still
  owns the PnL verdict; this register owns "did the specific defect get fixed".

---

## Verification block — 2026-07-30 18:30Z (revert ✅ VERIFIED on a FOURTH JVM — at 4/6, no change made; item #1's cost ratio has now deteriorated four runs running)

**Fourth independent reproduction.** A new process (PID **3626405**, boot **14:06:34.573**–**14:06:43.934**
local `-04:00`) — distinct from the 17:00Z, 17:30Z and 18:00Z JVMs — re-tests the four pre-registered legs:
- **No second re-seed wave, proved by count (Rule 142).** Over the *entire* running log, keyed by
  lifecycle+name, `grep -oP '(Trend|Reversion)ForecastLifecycle\s+: \w+ sensor still cold for \S+' | sort |
  uniq -c | awk '$1>1'` returns **nothing** — every cold name logs its line exactly **once**.
- **ADR-0071 boot seeding still fires.** **61** `sensor warmed` lines, **57** of them stamped **14:06** or
  **14:07**, inside the boot window.
- **No name is warmed twice (Rule 143), proved by count rather than by inspection.** Keying the warm lines
  on lifecycle+name, every per-name entry has count **1** — `risk-cut σ sensor warmed` AAPL/AMZN/BAC/CAT/
  CVX/GOOG/HD/JNJ/JPM/KO/MCD/MSFT/NEE/NQ/NVDA/PFE/PG/UNH/WMT/XOM each once, every `trend sensor warmed`
  once. The only count>1 is `fusion covariance warmed 19 of …`, which is the ADR-0089 rolling matrix
  rebuild (`19 of 20`, then `19 of 19`), not a sensor re-seed. The four post-boot lines are genuine
  first-seeds of late arrivals (`trend sensor warmed NQ` 14:08:17, `cross-sectional reversion sensor warmed
  NQ` 14:08:22, `risk-cut σ sensor warmed NQ` 14:08:43, `… TSLA` 14:28:57).
- `grep -rn "SensorReseed" --include=*.java app/` returns nothing.

**Item #1 of the 16:30Z block stays CLOSED.** Four independent JVMs, four legs each.

**No change made this cycle.** `scripts/score-change.py score` prints
`64a7a6336 still accumulating evidence (4/6 cycles) — held, not scored this run` and
`reports/.pending-baseline.json` is present, so a new change would destroy the evidence.

**Live situation.** The SITUATION header reads total PnL **$106.86**, gross **$36625.58** (**2.4%** of the
$1,500,000 firm cap, headroom **$1,463,374**), net **$-13291.73** (**1.3%** of the $1,000,000 net cap).
Flags: **none**. Since last run PnL **-22.94**, gross **-1385.11**; over three runs PnL **-35.44**, gross
**+9715.42**. `run-status.json` (heartbeat `2026-07-30T18:06:10Z`, which predates this report) reads
`pnl_growth_pct` **3.63** vs `pnl_target_pct` **1.0**, `on_track` **true**, `stale` **false**,
`underwater` **false**. Not DORMANT (20 instruments in `fusion_targets` plus the ES hedge), not in danger.

**Attribution caveat.** A JVM boot at **14:06:34** local sits inside this window and the pending change is
a *revert* that opens and closes nothing, so the **-22.94** / **-1385.11** move is **not separable** into
market vs change from these numbers — claim neither (Rule 141/152).

### 🎯 Item #1 — ALPHA churns many times its held position in notional; the cost-aware no-trade band that should stop it is INERT (unchanged diagnosis, now on a four-run cost trend)

Still #1. This run adds no new mechanism — it adds the thing that makes the mechanism urgent: the cost
ratio has moved the wrong way **four consecutive runs**.

**The cost.** `/api/attribution` reads ALPHA `totalPnl` **$7.64428496** against `feesPaid` **$73.682486**.
The sequence across the last four runs is monotone in both directions — PnL down, fees up:
**$23.36357064**/**$56.444977** → **$9.88820735**/**$60.552698** → **$8.61831343**/**$66.073455** →
**$7.64428496**/**$73.682486**. `firmTotal` **$106.85813672** is carried entirely by `hedgePnl`
**$135.03732831** with `hedgeMasking` **true**; `strategyAlpha` reads **−$28.17919159**.

**The churn (Rule 150 — gross shares traded vs shares held).** `turnover_cost_by_name` `qty` against
`fusion_targets` `currentQty`: JNJ traded **326** shares gross to hold **14**; NVDA **292** to hold **10**;
KO **281** to hold **69**; PFE **528** shares over **18** fills; GOOG **216** over **93** fills; AAPL
**210** over **87** fills. All at **1.00** bps, against a firm gross of **$36625.58** — single names each
churning more notional in the window than the whole firm holds at risk. `orders_by_status` reads FILLED
**3985**, CANCELLED **1289**, and the `recent_orders` tape shows why: a re-plan lands every ~30s
(**18:22:50**, **18:23:20**, **18:23:51**, **18:24:21**, **18:24:51**, **18:25:21**, **18:25:52**,
**18:26:22**, **18:26:52**, **18:27:23**, **18:27:53**, **18:28:23**, **18:28:53**, **18:29:24**,
**18:29:54**), most of it cancelling the prior slice with `fusion re-plan — passive order superseded by a
fresh target (ADR-0084)`.

**The mechanism (Rule 151), now visible in a single row.** Each `fusion_targets` row carries the gap and
the step side by side, and the step never closes the gap: KO `currentQty` **−69.0** against `targetQty`
**−412.71** moving `deltaQty` **−1.915**; JNJ **−14.0** against **−146.26** at **−2.745**; NVDA **10.0**
against **124.21** at **2.795**; UNH **1.0** against **53.11** at **1.875**; HD **3.0** against **65.43** at
**1.482**. That is ADR-0080 as designed — `adjustment-rate` derived as 1 − exp(−30/3600) on a 3600s
e-folding time — but the aim is redrawn every 30s and `fusion_targets.weights` reads `reversion`
**1.6024876487480502** as dominant against `trend` **0.42024570127205746**. The desk never arrives, and
pays a round trip each time the mean-reverting aim flips.

**Why the designed defence is not firing (Rule 148/149, unchanged).** ADR-0101's cost-aware band widens to
`max(fraction, min(1, 2C/mu))`, and its documented fallback is *"Unmeasured (gate inactive, no passing
source, non-positive cost or edge) ⇒ the convention, unchanged."* `strategy_diag.edgeGated` still reads
**13** names, **every one** `no positive OOS edge` (`PFE momentum -137.76114263 over 8 paths, mean-rev
-1.56304119 over 8`; `GOOGL momentum -58.18505489 over 4 paths, mean-rev -41.67661313 over 4`; `MSFT
momentum -37.36954202 over 3 paths, mean-rev -73.69953186 over 3`). mu is never measured, so every name
takes the **narrow** `jethro.fusion.position-buffer.fraction=0.10` branch. The economics point the other
way: mu ≤ 0 makes `2C/mu` unbounded — rebalancing is *never* worth its cost. **The fallback is inverted.**

**Planned change (the next scored cycle, not this one).** In the ADR-0101 width path, treat *non-positive
or absent* measured expectancy as a **maximal** band for the risk-INCREASING part of a delta, while exits
keep trading in full per ADR-0080. No new dial and no new number — it re-reads the two measured inputs the
edge gate already computes, and it can only ever remove turnover. Architecturally significant (it changes
when the desk may add risk) ⇒ ships with its ADR at `**Status:** Implemented` in the same commit.

**Risk to watch:** widening the band on every unmeasured name could push the book toward DORMANT, so the
VERIFY-BY tests turnover **and** that the book keeps positions.

**VERIFY-BY (the cycle after the change ships):**
1. `turnover_cost_by_name` `qty` against `fusion_targets` `currentQty` for JNJ / NVDA / KO / PFE: the
   gross-shares-traded-to-shares-held gap must narrow from today's **326 vs 14**, **292 vs 10**,
   **281 vs 69**.
2. `/api/attribution` ALPHA `feesPaid` must fall **relative to** `totalPnl` — today **$73.682486** against
   **$7.64428496**. The ratio is the metric (Rule 145), and it must break the four-run deterioration.
3. `/api/risk` `.total` `grossExposure` must **not** collapse toward zero; `fusion_targets.instruments`
   must stay near **20**. A flat book is a failed fix, not a cheap one.
4. `orders_by_status` CANCELLED must fall from **1289** against FILLED **3985**.

### Item #2 — MACRO loses money with essentially no turnover (carried, frozen)

`/api/attribution` reads book MACRO `totalPnl` **−$35.82347655**, all `realizedPnl`, on `feesPaid` of
**$0.169833** — bit-identical to last run, i.e. the book is **not trading**; the loss is a closed
directional position, not a live leak. It remains the largest single negative line inside `strategyAlpha`
**−$28.17919159**. Because it is frozen it is not *growing*, which is why it stays behind item #1 — but it
needs its own cycle and its own trigger-level post-mortem via the `reason` on the MACRO orders.
**VERIFY-BY:** MACRO `totalPnl` in `/api/attribution`, and the `reason` on the MACRO orders that opened it.

---

## Verification block — 2026-07-30 18:00Z (revert ✅ VERIFIED on a THIRD JVM — at 3/6, no change made; item #1's mechanism is now pinned to a specific line of config)

**Third independent reproduction.** A new process (PID **3603471**, boot at **13:34:34**–**13:34:37** local
`-04:00`) — different from the 17:00Z and 17:30Z JVMs — so the four pre-registered legs are re-tested on
fresh evidence:
- **No second re-seed wave, proved by count (Rule 142).** Across the *entire* running log, keyed by
  lifecycle+name, the duplicate check `grep -oP '(Trend|Reversion)ForecastLifecycle\s+: \w+ sensor still
  cold for \S+' | sort | uniq -c | awk '$1>1'` returns **nothing** — every cold name logs its line exactly
  **once** (`TrendForecastLifecycle : trend sensor still cold for XOM` 1, `… UNH` 1, `… PG` 1, `… PFE` 1,
  `… NEE` 1, `… TSLA` 1, `… NQ` 1, `… NFLX` 1, and each of the twelve rates tenors 1). A count of one is
  unfalsifiable by clock skew; the wave-over-wave regressions that scored ADR-0131 ❌ BAD have no mechanism
  to recur.
- **ADR-0071 boot seeding still fires.** **63** `sensor warmed` lines; **58** are stamped **13:34:40**–
  **13:35:15**, inside the boot window.
- **Every late line checked individually (Rule 143).** The 11 post-boot `warmed` lines are **7** ADR-0089
  covariance rebuilds (`fusion covariance warmed 19 of 19 … 20 of 21 … 22 of 24 name(s) from 121
  synchronised snapshots`) — a rolling matrix rebuild as new names arrive, not a sensor re-seed — plus
  **4** genuine first-seeds of late-arriving instruments (`risk-cut σ sensor warmed JPM` 13:35:36,
  `cross-sectional reversion sensor warmed GOOGL` 13:37:56, `… META` 13:38:16, `… AUDUSD` 14:00:22). None
  re-seeds an already-seeded name.
- `grep -rn "SensorReseed" --include=*.java app/` returns nothing; the only `re-seed` hits are
  `PositionBuffer`'s aim re-seed (ADR-0064/0075, unrelated) and the sim control endpoint.

**Item #1 of the 16:30Z block stays CLOSED.** Three independent JVMs, four legs each.

**No change made this cycle.** `scripts/score-change.py score` prints
`64a7a6336 still accumulating evidence (3/6 cycles) — held, not scored this run` and
`reports/.pending-baseline.json` is present, so a new change would destroy the evidence.

**Live situation.** `/api/risk` `.total` reads total PnL **$130.80462716**, gross **$36483.18000000**
(**2.4%** of the $1,500,000 firm cap, headroom **$1,463,517**), net **$2427.90000000** (**0.2%** of the
$1,000,000 net cap). Flags: **none**. The SITUATION header computes **+$16.33** PnL and **−$18,702.44**
gross on the run; **+$5.56** and **+$174.35** across the last three. `run-status.json` (heartbeat
`2026-07-30T17:34:04Z`) reads `pnl_growth_pct` **−19.1** against `pnl_target_pct` **1.0**, `on_track`
**false**, `stale` **true**, `underwater` **false**. 20 equity positions plus the ES hedge — not DORMANT,
not in danger (97.6% of the gross cap unused).

**Attribution caveat.** A JVM restart lands inside this window (boot **13:34:34** local = **17:34Z**), and
the pending change is a *revert* that opens and closes nothing. The **+$16.33** / **−$18,702.44** move is
therefore **not separable** into market vs change from these numbers — claim neither (Rule 141).

### 🎯 Item #1 — ALPHA churns many times its held position in notional; the cost-aware no-trade band that should stop it is INERT (mechanism now pinned)

Still #1, and this run finally names the *line* rather than the symptom.

**The cost.** `/api/attribution` reads ALPHA `totalPnl` **$8.61831343** against `feesPaid` **$66.073455**
— the fee-to-result ratio deteriorated for the third consecutive run (17:30Z: **$9.88820735** vs
**$60.552698**; 17:00Z: **$23.36357064** vs **$56.444977**) exactly as Rule 145 said to track. `firmTotal`
**$130.80462716** is carried entirely by `hedgePnl` **$158.00979028** with `hedgeMasking` **true**, while
`strategyAlpha` reads **−$27.20516312**.

**The churn, measured as gross shares traded vs shares held** (`turnover_cost_by_name` `qty` against
`fusion_targets` `currentQty`) — this is the sharp number, and it is a *round-trip* signature, not
one-way convergence:
- JNJ traded **299** shares gross to hold **13**; turnover **$79,572.63** over **60** fills.
- AAPL traded **197** shares gross to hold **12**; turnover **$66,315.06** over **82** fills.
- NVDA **263** shares over **119** fills, **$50,956.57**; MSFT **145** over **100** fills, **$60,813.96**;
  GOOG **216** over **93** fills, **$72,199.39**; JPM **198** over **68** fills, **$69,031.25**.
All at **1.00** bps, against a firm `grossExposure` of **$36,483.18** — six single names each churning more
notional in the window than the whole firm has at risk. `orders_by_status` reads FILLED **3895**,
CANCELLED **1229**.

**The mechanism, from `fusion_targets` + config.** AAPL reads `targetQty` **−153.110674** against
`currentQty` **−12.0** and `deltaQty` **−1.379481`: the desk is nowhere near its target and steps toward it
by ~1.4 shares a cycle. That is ADR-0080 working as designed — `jethro.fusion.adjustment-rate=0` derives
a = 1 − exp(−interval/horizon) = **0.0082987…** at 30s/3600s, i.e. a 3600s e-folding time. But the **aim is
recomputed every 30s**, and `fusion_targets.weights` reads `reversion` **1.5417176854024859` as the
dominant source (vs `trend` **0.3723889694091483`) — a mean-reverting view whose sign flips far faster than
the desk converges. So the desk pays a round trip chasing an aim it re-draws **120 times per e-folding
time**, which is precisely why gross shares traded ≫ shares held.

**Why the designed defence is not firing.** ADR-0101 already widens the no-trade band to
`max(fraction, min(1, 2C/mu))` where mu is the gross expectancy of the best source clearing the edge gate —
and the config comment states the fallback: *"Unmeasured (gate inactive, no passing source, non-positive
cost or edge) ⇒ the convention, unchanged."* `strategy_diag.edgeGated` reads **13** names, **every one**
`no positive OOS edge` (e.g. `momentum -137.76114263 over 8 paths, mean-rev -1.56304119 over 8`;
`momentum -58.18505489 over 4 paths, mean-rev -41.67661313 over 4`). With mu unmeasured or non-positive on
every name, the measured width **never** applies and every name falls back to
`jethro.fusion.position-buffer.fraction=0.10` — the *narrow*, churn-permissive branch. The economics say
the opposite: mu ≤ 0 does not mean "unmeasured, use the convention", it means 2C/mu is **unbounded** —
rebalancing is *never* worth its cost. **The fallback is inverted.**

**Planned change (next scored cycle, not this one).** In the ADR-0101 width path, treat *non-positive or
absent* measured expectancy as a **maximal** band for the risk-INCREASING part of a delta (exits keep
trading in full per ADR-0080), instead of falling back to the 0.10 convention. No new dial, no new number —
it re-reads the same two measured inputs the edge gate already computes, and it can only ever remove
turnover. Architecturally significant (it changes when the desk is allowed to add risk) ⇒ ships with its
ADR at `**Status:** Implemented` in the same commit.

**Risk to watch:** widening the band on every unmeasured name could take the book toward DORMANT. The
VERIFY-BY below therefore tests turnover **and** that the book keeps positions.

**VERIFY-BY (the cycle after the change ships):**
1. `turnover_cost_by_name` `qty` against `fusion_targets` `currentQty` for JNJ / AAPL / NVDA / MSFT: the
   gross-shares-traded-to-shares-held gap must narrow from today's **299 vs 13** and **197 vs 12**.
2. `/api/attribution` ALPHA `feesPaid` must fall **relative to** `totalPnl` — today **$66.073455** against
   **$8.61831343**. The ratio is the metric (Rule 145), not the absolute fee.
3. `/api/risk` `.total` `grossExposure` must **not** collapse toward zero — the book must still hold ~20
   equity positions. A flat book is a failed fix, not a cheap one.
4. `orders_by_status` CANCELLED must fall from **1229** against FILLED **3895**.

### Item #2 — MACRO loses money with essentially no turnover (new, promoted from the attribution read)

`/api/attribution` reads book MACRO `totalPnl` **−$35.82347655**, all of it `realizedPnl`, against
`feesPaid` of only **$0.169833**. That is a *directional* loss, not a cost leak — the churn fix cannot touch
it, and it is currently the largest single negative line inside `strategyAlpha` **−$27.20516312**. Not
diagnosed yet; needs its own cycle to attribute to a trigger via `recent_orders`.
**VERIFY-BY:** MACRO `totalPnl` in `/api/attribution`, and the `reason` on the MACRO orders that opened the
losing legs.

### Item #3 — reversion at the LONG horizon is the only positively-signed source (carried, Rule 146)

`/api/signals/telemetry` at `horizonSeconds` **3600** reads `reversion` `avgReturnBps`
**7.040602706195695** on **222** resolved (`hitRate` **0.4797687861271676**), `social`
**4.320115108384597** on **118**, `xsreversion` **1.591113315140675** on **205** — against `trend`
**−4.31583873924045** on **250** and `momentum` **−3.1019291527777773** on **22**. Significance is the edge
gate's to compute, but a positive sign on a large resolved count is where an OOS test is worth a cycle.
**VERIFY-BY:** an OOS backtest (ADR-0049) on the long-horizon reversion source, and whether the edge gate
subsequently lets it size.

---

## Verification block — 2026-07-30 17:30Z (revert ✅ VERIFIED on a second, independent JVM — at 2/6, no change made)

**Independent reproduction of last run's verdict.** This is a different process (PID 3583451,
`Started JethroApplication` at **13:04:30**) from the one that verified the revert at 17:00Z, so the four
pre-registered legs are re-tested on fresh evidence rather than re-read:
- ADR-0131 WARN/re-seed text: **zero** occurrences in the running JVM log.
- The binding leg — no second re-seed wave — now has a **direct** proof rather than an inferred one:
  keyed by lifecycle+name, **every** cold name logs its `still cold for … after seeding N of 193 stored
  prices` line exactly **once** (`TrendForecastLifecycle|XOM` 1, `|JNJ` 1, `|PG` 1, `|CAT` 1, `|MCD` 1,
  `|UNH` 1, `|HD` 1, and likewise under `ReversionForecastLifecycle`). One wave means the wave-over-wave
  regressions that scored the mechanism BAD (HD 171→133, PG 181→143, CAT 174→139, UNH 156→141,
  MCD 159→145, GOOG 191→180) have no mechanism to recur.
- ADR-0071 boot seeding still fires: **48** of the **66** `sensor warmed` lines are stamped
  **13:04:33**–**13:04:59**, inside the boot window. The **9** later lines were checked individually and
  are all late-arriving instruments taking their *first* seed (NQ trend/xs-reversion/σ at 13:08:34–13:09:00,
  TSLA 13:07:09, META 13:21:32, GOOGL 13:28:04) or cross-sectional reversion accumulating live prints —
  none is a re-seed of an already-seeded name.
- `grep -rn SensorReseed --include=*.java` returns nothing.

**Item #1 of the previous block stays CLOSED.** Two independent JVMs, four legs each.

**No change made this cycle.** `scripts/score-change.py score` prints
`64a7a6336 still accumulating evidence (2/6 cycles) — held, not scored this run` and
`reports/.pending-baseline.json` is present, so per the contract a new change would destroy the evidence.

**Live situation.** `/api/risk` `.total` reads total PnL **$130.12772736**, gross **$46057.90980000**
(**3.1%** of the $1,500,000 firm cap, headroom **$1,453,942**), net **$4228.76020000** (**0.4%** of the
$1,000,000 net cap). Flags: **none**. The SITUATION header computes **−$12.17** PnL and **+$19,147.75**
gross on the run; **−$11.37** and **+$7,272.18** across the last three. `run-status.json` reads
`pnl_growth_pct` **−3.02** against `pnl_target_pct` **1.0**, `on_track` **false**, `stale` **true**,
`underwater` **false**. 20 equity positions plus the ES hedge — not DORMANT, and not in danger: gross rose
with 97% of the cap unused, which is the intended direction off dormant.

### 🎯 Item #1 — ALPHA churns several times the firm's gross exposure in notional per window (evidence strengthened)

Still the desk's largest addressable cost, and the case got **worse**, not better, this run.
`/api/attribution` reads ALPHA `totalPnl` **$9.88820735** against `feesPaid` **$60.552698** — fees are now
several times the book's entire result (last run: **$23.36357064** against **$56.444977**). `firmTotal`
**$130.12772736** is carried by `hedgePnl` **$156.06299656** with `hedgeMasking` **true**, while
`strategyAlpha` reads **−$25.93526920**.

`turnover_cost_by_name` sizes the leak against a firm `grossExposure` of **$46,057.91**: JNJ
**$79,061.19**, JPM **$69,031.25**, GOOG **$68,870.18**, AAPL **$63,317.02**, MSFT **$58,534.65** — five
single names each churning more notional in the window than the firm has at risk in total, at **1.00** bps.
The ES hedge churns **$159,122.83** against a hedge book gross of **$10,018.66**, i.e. ~16× — cheap at
**0.20** bps but the same mechanism.

**`recent_orders` now shows the mechanism at order level, and it is more specific than "re-plan churn".**
The ADR-0084 fusion re-plan fires on a ~**30s** cadence (17:28:09, 17:28:39, 17:29:09, 17:29:40) and each
wave cancels the prior passive slice with `fusion re-plan — passive order superseded by a fresh target
(ADR-0084)`. The costly part is that the *superseded slices keep filling first*: PG alone runs
BUY 10 FILLED → BUY 4 FILLED → BUY 3 CANCELLED → BUY 4 CANCELLED → BUY 13 FILLED → BUY 2 ROUTED inside
~2 minutes, and NEE runs BUY 1 → 2 → 2 → 13 → 13 the same way. Same name, same direction, re-sized every
30s — so the book pays 1.00 bps on the full notional of each partial re-approach to a target it was
already walking toward. **The defect is that the re-plan re-issues absolute targets without netting against
the slice already working**, not the re-plan cadence itself.

**Not actionable until `64a7a6336` is scored** (a fresh ledger row appears and the pending baseline clears).
It is the target for the next scored cycle.

**VERIFY-BY (the cycle after the change ships):**
1. `turnover_cost_by_name` — the top names' `turnover_usd` must fall relative to `/api/risk` `.total`
   `grossExposure`; today JNJ/JPM/GOOG/AAPL/MSFT each exceed the firm's **$46,057.91** outright.
2. `/api/attribution` — ALPHA's `feesPaid` must fall **relative to** its `totalPnl`; today feesPaid
   **$60.552698** against totalPnl **$9.88820735**.
3. `recent_orders` — the same-name/same-direction re-approach pattern (PG 10→4→3→4→13→2 in ~2 min) must
   not recur; a re-plan should show one working slice per name, not a stack of superseded partials.
4. Gross exposure must not fall as a side effect — this is a cost fix, not a de-risking. Compare
   `/api/risk` `.total` `grossExposure` against **$46,057.91**.

### Item #2 — no source has demonstrated positive out-of-sample edge (the standing priority)

Unchanged as a rank, but this run's telemetry narrows *where* to look, which is worth carrying forward.
`strategy_diag` still reads `measured` **29**, `tradable` **16**, with **13** names carrying
`no positive OOS edge`. But `/api/signals/telemetry` at the **3600s** horizon reads two positively-signed
sources — `reversion` `avgReturnBps` **+7.215148481777953** on **213** resolved (**46** cohorts,
`stdCohortMeanBps` **34.9930536141166**) and `xsreversion` **+2.7840649126009924** on **189** resolved
(`hitRate` **0.5592105263157895**) — against negatives for `trend` (**−4.104976474445127**) and
`momentum` (**−3.1019291527777773**) at the same horizon. Significance is the edge gate's to compute, not
mine; the gate's own answer today is still "no positive OOS edge" for 13 names. The pointer to record is
that the *reversion family at the long horizon* is the only place with a positive sign and a large enough
resolved count to be worth a proper OOS test.

---

## Verification block — 2026-07-30 17:00Z (revert ✅ VERIFIED — now under measurement at 1/6, no change made)

**The hand-completed revert landed and did exactly what it claimed.** All four pre-registered legs pass
against this run's JVM log and tree: the ADR-0131 WARN text appears **zero** times (last run's JVM logged
it, which is what proved the BAD code live); every `trend sensor warmed … from … stored prices` line is
timestamped **12:36:32**–**12:36:57** against a `Started JethroApplication` at **12:36:29**, i.e. all
inside the boot window, so ADR-0071 boot seeding still fires and the post-boot retry does not; with no
retry there is no second wave, so last run's six negative wave-over-wave deltas (HD, PG, CAT, UNH, MCD,
GOOG) have no mechanism to recur; and `grep -rn SensorReseed` returns nothing against a JVM that booted
after the revert commit. **Item #1 of the previous block is CLOSED and struck below the line.**

**No change made this cycle.** `scripts/score-change.py score` prints
`64a7a6336 still accumulating evidence (1/6 cycles) — held, not scored this run` and
`reports/.pending-baseline.json` is present, so per the contract a new change would destroy the evidence.

**Live situation.** `/api/risk` `.total` reads total PnL **$145.76317190**, gross **$33028.88788750**
(**2.2%** of the $1,500,000 firm cap, headroom **$1,466,971**), net **−$10212.24788750** (**1.0%** of the
$1,000,000 net cap). Flags: **none**. The SITUATION header computes **+$20.51** PnL and **−$3,279.94**
gross on the run; **−$0.96** and **−$17,223.30** across the last three. The book is trading — 20 equity
positions plus the ES hedge — so it is neither DORMANT nor in danger.

### 🎯 Item #1 — ALPHA churns several times the firm's gross exposure in notional per window

The desk's largest addressable cost, and it needs **no new edge to fix**. `/api/attribution` reads the
ALPHA book `totalPnl` **$23.36357064** against `feesPaid` **$56.444977** — fees exceed the book's net
result. `turnover_cost_by_name` names the mechanism: fees are bps of **notional** (**1.00** bps equities,
**0.20** ES), so fill count is not the driver. JNJ turned over **$75,222.83**, JPM **$67,623.49**, GOOG
**$64,535.97**, AAPL **$61,986.59**, MSFT **$56,713.32** — each single name churning more notional in the
window than the firm's entire gross exposure of **$33,028.89**. `recent_orders` shows the shape: an ADR-0084
fusion re-plan roughly every 30s cancelling and re-issuing passive slices.

**Not actionable until `64a7a6336` is scored** (a fresh ledger row appears and the pending baseline
clears). It is the target for the next scored cycle.

**VERIFY-BY (the cycle after the change ships):**
1. `turnover_cost_by_name` — the top names' `turnover_usd` must fall relative to `/api/risk` `.total`
   `grossExposure`; today the top five each exceed it outright.
2. `/api/attribution` — ALPHA's `feesPaid` must fall **relative to** its `totalPnl`; today feesPaid
   **$56.444977** exceeds totalPnl **$23.36357064**.
3. Gross exposure must not fall as a side effect — this is a cost fix, not a de-risking. Compare
   `/api/risk` `.total` `grossExposure` against **$33,028.89**.

### Item #2 — no source has demonstrated positive out-of-sample edge (the standing priority)

`strategy_diag` reads `measured` **29**, `tradable` **16**, **13** names `no positive OOS edge`. At the
1h horizon `reversion` has the largest positive mean of any source at **+6.628431761402847** bps over 202
resolved, but `stdCohortMeanBps` **35.44103416663609** across 46 cohorts dwarfs it; `trend` reads
**−4.429432051199271** over 230. The ADR-0064 gate is working as designed. The answer remains **a new
signal with genuinely measured edge**, never a looser gate. Ranked below the turnover item because that
one converts to money without needing to find edge first.

---

## ~~Verification block — 2026-07-30 16:30Z~~ (item #1 CLOSED ✅ — revert verified 17:00Z)

**The scorer closed the ADR-0131 window and graded it ❌ BAD — and its own revert did not land.** The
ledger row for `efccc6502` carries the computed vector and the verdict; its note reads
`⚠️ REVERT FAILED (git conflict): the BAD commit is STILL LIVE and needs a manual revert`.
`reports/.pending-baseline.json` is **gone** and `scripts/score-change.py score` now prints
`no pending change to score`, so the hold that blocked the last five cycles is lifted. **A BAD change
still running is the most expensive open defect on the board**, so it — not the queued guard, not the
edge work — is item #1, and this cycle's one change completes the revert.

**Live situation.** `/api/risk` `.total` reads total PnL **$125.44**, gross exposure **$33742.34**, net
**−$6389.75**. Gross is **2.2%** of the firm cap $1,500,000 (headroom **$1,466,258**); net **0.6%** of the
$1,000,000 net cap. Flags: **none**. The report SITUATION header computes PnL **−16.06** and gross
**−5043.39** on the run, and **−60.15** / **+2240.39** across the last three. `run-status` last heartbeat
reads `pnl_growth_pct` **−1.36** vs `pnl_target_pct` **1.0**, `on_track=false`, `stale=true`.

**Why the conflict happened, and what the manual revert did differently.** Of the 13 files in
`efccc6502`, exactly three have been touched since — `docs/loop-findings.md`, `reports/last-analysis.md`
and `reports/must-fix.md`, each by all five of the hold cycles' doc commits. Those three are the loop's
**accumulating memory**; a whole-commit `git revert` would have conflicted on them (it did) and, had it
succeeded, would have **destroyed five cycles of findings**. The manual revert therefore restores only
the ten code/ADR paths — verified by `git diff --cached efccc6502^` over `app/src` and `trading-core/`
returning **empty**, and `grep -rn SensorReseed` over the tree returning **none** — and leaves the memory
files standing. `docs/adr/0131-*.md` is marked **Status: Reverted** rather than deleted, with the
five-cycle diagnosis preserved under a new "Why it was reverted" section.

**The queued strict-improvement guard is now CLOSED, not carried.** It was the right diagnosis of the
mechanism's defect — but the mechanism it would have guarded is the one the scorer just graded BAD, and
re-attempting a reverted idea is exactly what the contract forbids. The reasoning is preserved in the ADR
so no future cycle re-derives it from scratch; it does not remain an open item here.

### 🎯 Item #1 — complete the revert of the BAD commit (THIS cycle's change)

The scorer's auto-revert failed and left `efccc6502` live. Restore the ten code/ADR paths to
`efccc6502^`, preserve the three memory files, mark ADR-0131 Reverted.

**VERIFY-BY (next run), each leg falsifiable from the log alone:**
1. The running JVM must log **zero** occurrences of the ADR-0131 WARN text
   (`re-seeding every … sightings until it does (ADR-0131)`) — this run's JVM logs it, which is what
   proved the BAD code was live.
2. **Zero** `trend sensor warmed … from … stored prices` lines at timestamps well after boot (only the
   reverted retry emitted those; boot-time ADR-0071 seeding is unaffected and must still appear).
3. **Zero negative** wave-over-wave seeded-count deltas — not because they are guarded, but because with
   the retry gone there is no second wave to regress. This run's six negatives (**HD −38, PG −38,
   CAT −35, UNH −15, MCD −14, GOOG −11**) must not recur in any form.
4. The running commit must be this revert or later.

### Item #2 — the binding constraint on the rest of the book (becomes #1 once the revert verifies)

`strategy_diag` reads `measured` **29**, `tradable` **16**, `edgeGated` **13 names**, each annotated
`no positive OOS edge` — MSFT (momentum **−37.36954202** over 3 paths, mean-rev **−73.69953186** over 3),
AMZN (**−46.40665676** over 4 paths), SAP (**−39.20256473** / **−65.12971133** over 4), EURUSD
(**−20.20213556** / **−84.79537128** over 5), GOOG, JPM, GBPUSD and six more. The ADR-0064 gate working
as designed. Per the standing priority the answer is **a new signal with genuinely measured edge**, never
a looser gate — and note that five cycles just went into sensor plumbing that the scorer graded BAD,
which is precisely the pattern the standing priority warns against.

---

## Verification block — 2026-07-30 16:00Z (ADR-0131 at 5/6 cycles — held, no code change made)

**GROSS FELL AND PnL ROSE — the objective moved the right way this window.** Live `/api/risk` `.total`
reads total PnL **$174.01223659** (realized **$180.21540531**, unrealized **−$6.20316872**), gross
exposure **$32990.96481250**, net **−$8634.83518750**. Gross is **2.1%** of the firm cap $1,500,000 with
**$1,467,797** of headroom; net **0.9%** of the $1,000,000 net cap. Flags: **none**. The report SITUATION
header computes PnL **+$25.70** and gross **−$18,049.05** on the run, **+$28.97** across the last three.
`run-status` last heartbeat reads `pnl_growth_pct` **15.65** vs `pnl_target_pct` **1.0**, `on_track=true`,
`stale=false`, `underwater=false`.

**Last cycle's unrealized scare reverted on its own, as called.** Unrealized was **−$49.23652754** (NVDA
**−$27.85750000**, UNH **−$24.72000000**) and now reads **−$6.20316872**. I declined to treat it as a
defect; that was correct. **One shape change to carry forward:** net moved from **+$49.80412500**
(market-neutral) to **−$8,634.83518750**, so the book is now net *short*. At 0.9% of the net cap this is
not a danger and not an item — it is recorded so a later cycle does not rediscover it as a surprise.

**Item #1 → ⚠️ STILL-BROKEN, third independent reproduction, on a third JVM.** PID **3523413**, booted
**11:36:18**, `uptimeSeconds` **1425** — a different process from the 10:36 and 11:06:51 runs. Wave 1
(11:36:35–11:43:07) → wave 2 (11:52:45–11:59:16), **16 min** apart, the predicted 193 × 5 s cadence:

- **Six names went backwards:** **HD 171→133**, **PG 181→143**, **CAT 174→139**, **UNH 156→141**,
  **MCD 159→145**, **GOOG 191→180**.
- **Seven advanced:** JPM 141→190, XOM 153→166, CVX 175→185, JNJ 163→167, GOOGL 0→2, TSLA 9→11,
  EURUSD 28→29, ES 48→49.
- **The retry's second clean win is PFE**, and it is the decisive evidence: 11:36:35
  `still cold ... 192 of 193` → 11:52:45 `trend sensor warmed PFE from 193 stored prices (needs 193) —
  warm`. Only the ADR-0131 retry emits that line. PG did the same at 190→193 last cycle. **Both successes
  are strict-improvement cases**, and the two costliest regressions this run — **GOOG at 191** and
  **PG at 181**, each within a dozen prints of warm — are precisely what the queued guard refuses to
  discard. The evidence now favours *guarding* the mechanism, not reverting it.
- **Class A reconfirmed a third time:** the same **12** rate/swap names (USD.TSY.\*, USD.SOFR.\*,
  USD_IRS_\*) seeded **193 of 193** in *both* waves and are still cold.
- **Baseline for the fix, re-measured on the unfixed code:** **53** trend still-cold lines across **27**
  distinct names, **26** of them repeating.

**Deployment confirmed:** the running JVM logs the ADR-0131 WARN text (`re-seeding every 193 sightings
until it does (ADR-0131)`), so the code under test is live and this is a fair grade.

**Why no change was made:** `scripts/score-change.py score` prints `efccc6502 still accumulating evidence
(5/6 cycles) — held, not scored this run`; `reports/.pending-baseline.json` still holds `efccc650`. One
cycle remains. The fix below ships next cycle, when the window closes.

### 🎯 Item #1 (carried, unchanged — root cause pinned, fix specified, awaiting the scorer window)

`TrendForecastLifecycle.warmWhileCold` calls `forecaster.forget(instrumentId)` **unconditionally**, then
replays whatever `SensorWarmup.warm` returns. The read window is `lookback = step × samples ×
LOOKBACK_MULTIPLE` measured **back from the current mark's provider timestamp**, and
`consumptionStepMillis` re-derives the median print gap from whatever that read returned — so both ends of
the window and the thinning stride move between waves. The count is free to fall, and when it does the
sensor has been reset to something *worse* than it held.

**The fix — one condition that repairs both classes.** `SensorWarmup.seedPrices` is already a pure
function returning the list before anything mutates: compute it first, compare its size against the best
seed this name has previously achieved, and only `forget()` + replay when it is **strictly larger**;
otherwise leave the sensor's live state alone at the cost of one bounded read. Class B (HD, PG, CAT, UNH,
MCD, GOOG) keeps its accumulated prints. Class A (the 12 rate names at 193/193, already at the maximum)
can never exceed its best, so it retires itself from retrying — no separate "unwarmable" rule. PFE's
192→193 and PG's 190→193 are strictly larger, so both real wins survive. A bug fix in a sample-count path:
no money, risk or exposure number is introduced or changed, so no ADR is required.

**VERIFY-BY (next run), written so only the mechanism can pass it:** across every name logging more than
one `still cold` line, the wave-over-wave seeded-count delta must be **≥ 0 for all of them** — this run's
six negatives (**HD −38, PG −38, CAT −35, UNH −15, MCD −14, GOOG −11**) must become **zero negatives**;
**and** the 12 rate names at `193 of 193` must log **at most one** still-cold line each, so the trend
still-cold *line* count falls well below this run's **53** while distinct names hold near **27**.

### Item #2 (unchanged, still the binding constraint on the rest of the book)

`strategy_diag` reads `measured` **29**, `tradable` **16**, `edgeGated` **13 names**, each annotated
`no positive OOS edge` — MSFT (momentum **−37.36954202** over 3 paths, mean-rev **−73.69953186** over 3),
AMZN (**−46.40665676** over 4 paths), GOOG and ten more. The ADR-0064 gate working as designed. Per the
standing priority the answer is **a new signal with genuinely measured edge**, never a looser gate.

### Context — where the money is actually coming from

`/api/attribution` reads `firmTotal` **$174.01223659** = ALPHA **$59.63435276** + HEDGE **$150.20136038**
+ MACRO **−$35.82347655**, `totalFees` **$47.780648**, `hedgeMasking` now **false** (it was **true** last
cycle). ALPHA has climbed from **$20.35473620** as its unrealized leg recovered to **−$6.18030270**. The
hedge still carries most of the firm total; MACRO (**NQ**, flat, realized **−$35.82347655**) is still the
one closed loser. Turnover watch for after the sensor work: NVDA **93** fills on **$38,748.31**, MSFT
**81**, GOOG **69**, AAPL **68**, against CANCELLED **1032** vs FILLED **3592**, all ADR-0084 re-plans.

---

## Verification block — 2026-07-30 15:30Z (ADR-0131 at 4/6 cycles — held, no code change made)

**THE DESK IS FULLY DEPLOYED AND MARKET-NEUTRAL.** Live `/api/risk` `.total` reads total PnL
**$128.22843661** (realized **$177.46496415**, unrealized **−$49.23652754**), gross exposure
**$40994.69587500**, net **$49.80412500** — **19** equity positions long **$13298.56500000** net against a
single ES hedge short **−$13248.76087500**, i.e. the book is running gross with essentially no directional
net. Gross is **2.7%** of the firm cap $1,500,000 with **$1,459,005** of headroom; net **0.0%** of the
$1,000,000 net cap. Flags: **none**. `run-status` last heartbeat reads `pnl_growth_pct` **46.27** vs
`pnl_target_pct` **1.0**, `on_track=true`, `stale=false`, `underwater=false`.

**PnL fell run-over-run and the split is unambiguous: realized rose, marks fell.** Against last
heartbeat's total **$185.58226433** (realized **$159.63438842** / unrealized **$18.39164203** read live
last cycle), realized is now **$177.46496415** and unrealized **−$49.23652754**. Realized PnL *advanced*;
the whole decline is mark-to-market on positions opened this window, concentrated in exactly two names —
NVDA unrealized **−$27.85750000** and UNH **−$24.72000000**, which together exceed the entire firm
unrealized figure. Neither is a realized loss and neither is near a stop.

**Prior item #1 (ADR-0131's sliding-window re-seed) → ⚠️ STILL-BROKEN, by its own pre-registered test.**
Last cycle's VERIFY-BY had two legs, and the binding one failed on a *different* JVM (booted 11:06:51,
`uptimeSeconds` **1392**), so this is an independent reproduction, not a re-reading of the same evidence:

- **Leg 1 — "no negative wave-over-wave delta anywhere" → FAILED. Four names went backwards:**
  **JPM 150→148**, **JNJ 160→149**, **XOM 179→151** (−28), **CVX 165→146** (−19). Wave 1 at 11:07:0x,
  wave 2 at 11:23:1x — **16 min**, the predicted 193 × 5 s cadence. Different process, different names
  than last cycle's six, same defect.
- **Leg 2 — "distinct trend-cold names below 27 distinct / 25 repeating" → 26 distinct / 24 repeating.**
  Technically below, by exactly one name, and that one name is the win below. Not a meaningful pass.
- **The first genuine ADR-0131 success is also on the tape: PG warmed on the retry.** Wave 1 11:07:04
  `still cold ... 190 of 193`; wave 2 11:23:14 `trend sensor warmed PG from 193 stored prices (needs 193)
  — warm`. Nothing but the retry can produce that line. So the mechanism is *right* and its
  *non-monotonicity* is the whole defect — 8 names advanced (CAT 136→180, HD 150→156, PFE 159→166,
  TSLA 3→8, GOOGL 0→1, EURUSD 1→27, META 1→3, NFLX 1→3), 4 regressed, 12 stood still.
- **Class A reconfirmed, unchanged:** the same **12** rate/swap names (USD.TSY.\*, USD.SOFR.\*,
  USD_IRS_\*) seeded **193 of 193** in *both* waves and are still cold. Sample count is not their binding
  predicate; re-seeding them can never work.

**Deployment confirmed:** the running JVM logs the ADR-0131 WARN text (`re-seeding every 193 sightings
until it does (ADR-0131)`), so the code under test is live and this is a fair grade.

### 🎯 Item #1 (carried, root cause now fully characterised) — ADR-0131's re-seed is non-monotone: it `forget()`s accumulated state before knowing whether the replacement is better

The mechanism is now pinned to a specific line. `TrendForecastLifecycle.warmWhileCold` calls
`forecaster.forget(instrumentId)` **unconditionally**, then replays whatever `SensorWarmup.warm` returns.
The read window is `lookback = step × samples × LOOKBACK_MULTIPLE` measured **back from the current mark's
provider timestamp**, and `consumptionStepMillis` re-derives the median print gap from whatever points that
read returned — so both ends of the window and the thinning stride move between waves. The count is
therefore free to fall, and when it does the sensor has been reset to something *worse* than it held.

**The fix (next cycle, once the scorer window closes) — make the re-seed monotone, which is one condition
that repairs Class A and Class B together.** `SensorWarmup.seedPrices` is already a pure function that
returns the list before anything is mutated: compute it first, compare its size against the best seed this
name has previously achieved, and only `forget()` + replay when it is **strictly larger**; otherwise leave
the sensor's live state alone and cost nothing but one bounded read. Class B (JPM/JNJ/XOM/CVX) then keeps
its accumulated prints. Class A (the 12 rate names at 193/193, already at the maximum) can never exceed its
best, so it retires itself from retrying — no separate "unwarmable" rule needed. PG's 190→193 is strictly
larger, so the one real win is preserved. This is a bug fix in a sample-count path: no money, risk or
exposure number is introduced or changed, so no ADR is required.

**VERIFY-BY (next run), written so only the mechanism can pass it:** across every name that logs more than
one `still cold` line, the wave-over-wave seeded-count delta must be **≥ 0 for all of them** — this run's
four negatives (**JPM −2, JNJ −11, XOM −28, CVX −19**) must be **zero negatives**; **and** the 12 rate names
at `193 of 193` must log **at most one** still-cold line each (they stop being retried), so the trend
still-cold *line* count falls well below this run's **50** while distinct names stay at ~**26**.

### Item #2 (unchanged, still the binding constraint on the rest of the book)

`strategy_diag` reads `measured` **29**, `tradable` **16**, `edgeGated` **13 names**, each annotated
`no positive OOS edge` — PFE (momentum **−137.76114263**, mean-rev **−1.56304119**), PG
(**−82.99627912** / **0.00000000**), GOOGL (**−58.18505489** / **−41.67661313**), MSFT, AMZN, GOOG, SAP,
JPM, EURUSD, GBPUSD, BAC, HD, UNH. The ADR-0064 gate working as designed. Per the standing priority the
answer is **a new signal with genuinely measured edge**, never a looser gate.

### Context — where the money is actually coming from

`/api/attribution` reads `firmTotal` **$120.16705892** = ALPHA **$20.35473620** + HEDGE **$135.63579927**
+ MACRO **−$35.82347655**, `totalFees` **$41.996983**, `hedgeMasking` **true**. The hedge book still
carries the firm total; ALPHA's realized **$77.96423536** is being offset by unrealized **−$57.60949916**
on the freshly-opened book. MACRO (**NQ**, flat, realized **−$35.82347655**) remains the one closed loser.

---

## Verification block — 2026-07-30 15:02Z (ADR-0131 at 3/6 cycles — held, no code change made)

**THE DESK IS FULLY ACTIVE.** Live `/api/risk` `.total` reads total PnL **$167.76973099**, gross exposure
**$24383.04512500**, net **$6210.52512500** (a later read the same cycle: total PnL **$178.02603045**,
realized **$159.63438842**, unrealized **$18.39164203**). Gross is **1.5%** of the firm cap $1,500,000
with **$1,477,265** of headroom; net **0.5%** of the $1,000,000 net cap. `run-status` reads
`pnl_growth_pct` **13.06** vs `pnl_target_pct` **1.0**, `on_track=true`, `stale=false`,
`underwater=false`. No flags. Eight ALPHA names plus the HEDGE book traded this window. This is the
opposite of the dormant book of three runs ago.

**Prior item #1 (ADR-0131's re-seed retry) → mechanism ✅ VERIFIED FIRING, effect 🔴 REGRESSED.** Both
halves matter, and last cycle's stated root cause turns out to be only half right:

- **The retry fired — the pre-registered test passed on the only evidence that can produce it.** Last
  cycle's VERIFY-BY was "a *second* `still cold` line for a name that already logged one, which nothing
  else can produce" (Rule 120). `grep -c "trend sensor still cold"` = **52 lines across 27 distinct
  names** → **25 names logged twice**. XOM is the clean trace: **10:36:25.022** (`170 of 193`) then
  **10:52:34.778** (`169 of 193`) — **16 min 9 s** apart against the predicted 193 × 5 s = **16.08 min**.
  The arithmetic in last cycle's table was right about *trend*.
- **But last cycle's conclusion "the cadence outlives the process on 3 of 4 call sites" was wrong for
  trend** — trend's 16.1 min fits inside this process (`uptimeSeconds` **1506**, 25.1 min) and did fire.
  It still holds for the other two: `Reversion` still-cold = **22 lines across 22 distinct names**, i.e.
  **zero** duplicates (40.2 min > uptime), and `XsReversion` still-cold = **0 lines**.

**The pre-registered discriminator resolved, and it points somewhere new.** Last cycle wrote: *"If the
retries fire and σ stays cold, the defect is the seed span, not the cadence."* The retries fired. The
names stayed cold. So it is the seed span — and it splits into two different defects:

- **Class A — 12 rate/swap names seeded `193 of 193` and are STILL COLD, in both waves.** USD_IRS_5Y/10Y,
  USD.SOFR.1Y/2Y/5Y/10Y/30Y, USD.TSY.1Y/2Y/5Y/10Y/30Y each replayed the *full* warm-up span and
  `forecaster.readingFor(x).warm()` is still false. The binding predicate is therefore **not** the sample
  count, so no amount of re-seeding can ever clear it — these are the names whose stored series does not
  move, leaving the scale estimator nothing to absorb. Re-seeding them forever is pure waste.
- **Class B — the retry can move a tradable name BACKWARDS.** `warmWhileCold` calls
  `forecaster.forget(instrumentId)` and then replays whatever the re-read returns. Because the seed
  anchors on the *current* mark's provider timestamp and walks newest-first (truncating at a gap wider
  than `GAP_TOLERANCE_SAMPLES`), the window **slides** with the anchor instead of accumulating. Wave 1 →
  wave 2 seeded counts: **HD 164→140** (−24), **JPM 188→161** (−27), **MCD 171→162** (−9), **JNJ 186→182**,
  **PFE 178→175**, **XOM 170→169** — against **UNH 153→183** (+30), **PG 176→183**, **CAT 150→159**,
  **CVX 157→162**. So for six tradable equities the retry discarded ~16 minutes of consumed live prints
  *and* replayed a shorter history than the boot seed had. Waiting moves the window; it does not fill it.

### 🎯 Item #1 (reframed by the above) — ADR-0131's retry re-derives from a sliding window, so it never converges and can regress a sensor

The fix direction for next cycle (**not** last cycle's "shorten the cadence" — trend already fires, and a
shorter cadence would only regress Class B faster): the retry must be **monotone**. Either keep the
sensor's accumulated state instead of `forget()`-ing it when the re-read is shorter than what the sensor
already holds, or widen the walk so it covers the full needed span regardless of anchor drift. And Class A
must be declared **unwarmable** rather than retried — a series with no variation can never warm a scale
estimator. Bug fix, no money/risk dial.

**VERIFY-BY (next run), written so only the mechanism can pass it:** for every name logging a second
`still cold` line, the second line's seeded count must be **≥** the first's (no negative wave-over-wave
delta anywhere — the six negatives above must all be gone), **and** the count of distinct trend-cold
tradable equity names must fall below the current **27 distinct / 25 repeating**.

### Item #2 (unchanged, still the binding constraint on the rest of the book)

`strategy_diag` reads `measured` **29**, `tradable` **16**, and `edgeGated` **13 names** each annotated
`no positive OOS edge` — PFE (momentum **−137.76114263**, mean-rev **−1.56304119**), PG
(**−82.99627912** / **0.00000000**), GOOGL (**−58.18505489** / **−41.67661313**), AMZN, GOOG, SAP, JPM,
EURUSD, GBPUSD, BAC, HD, UNH, MSFT. This is the ADR-0064 gate working **as designed**. Per the standing
priority the answer is **a new signal with genuinely measured edge**, never a looser gate.

### Context — where the money is actually coming from

`/api/attribution` reads `firmTotal` **$167.04598599** = ALPHA **$57.15161336** + HEDGE **$145.71784918**
+ MACRO **−$35.82347655**, `totalFees` **$36.280400**, `hedgeMasking` **false**. The hedge book is
carrying most of the firm total and MACRO is the one book losing money — worth a look once the sensor
work closes, but the firm total is what the loop optimizes and it is up.

---

## Verification block — 2026-07-30 14:30Z (ADR-0131 at 2/6 cycles — held, no code change made)

**THE BOOK IS OPEN.** After three runs pinned at $0.00 gross, live `/api/risk` `.total` reads total PnL
**$147.57045400** and gross exposure **$3288.88500000** (net **$2108.47500000**) — **0.2%** of the firm
gross cap, headroom **$1,496,711**. Two names carry it: ALPHA **MSFT +6** (totalPnl **$77.06615500**) and
ALPHA **NVDA −3** (totalPnl **$19.29237792**). No DANGER flag; this is a dormant book coming back on with
its whole budget still unused, which is the goal, not a risk event.

**Prior item #1 (ADR-0131, the boot-only warm-start seed) → ⚠️ STILL-BROKEN as a mechanism, even though
both of its VERIFY-BY numbers read green.** Stated precisely, because the distinction is the whole point:
- (a) `grep -c "risk-cut σ sensor warmed"` since boot = **2** (was 1) — numerically passes. But the two
  lines are MSFT at **10:07:11** (38s after the 10:06:33 boot — the boot seed) and NQ at **10:19:45**.
  Every one of NQ's four sensor lines is stamped 10:19, so NQ is a **late-arriving instrument's first
  seed**, not a re-seed of a previously-cold name.
- (b) a non-zero `deltaQty` on `/api/fusion/targets` — **passes**: NVDA **−4.127545**, MSFT **−0.040934**.
  But MSFT's σ came from the boot seed and NVDA's armed off the live tick stream; neither is the retry.
- **The retry has not fired once.** `TrendForecastLifecycle.warmWhileCold` logs on *every* attempt (INFO
  when warm, WARN when still cold), so a second attempt is impossible to miss — and every boot-cold name
  still shows exactly **one** `still cold` line, 24 minutes in.

**Root cause of the non-firing, now located — the cadence is longer than the JVM lives.** `SensorReseed`
counts *sightings*, and a sighting is one scheduled tick of the owning lifecycle. Multiplying each
sensor's `warmupSamples()` by its configured interval gives the real retry period:

| sensor | cadence × interval | retry period |
|---|---|---|
| trend | 193 × `jethro.fusion.trend.interval-seconds=5` | **16.1 min** |
| reversion | 241 × `jethro.fusion.reversion.interval-seconds=10` | **40.2 min** |
| xs-reversion | 241 × `jethro.fusion.xs-reversion.interval-seconds=10` | **40.2 min** |
| σ / plan | 121 × `jethro.fusion.interval-seconds=30` | **60.5 min** |

This process's `uptimeSeconds` at report time was **1410 (23.5 min)**, and the previous boot was 09:49:40
against this one at 10:06:33 — **~17 min apart**, because the improvement loop redeploys the JVM every
cycle. `SensorReseed`'s counters are in-heap and die with the process. So for reversion, xs-reversion and
σ the retry period **provably exceeds the process lifetime** and the counter is destroyed before it can
ever elapse — ADR-0131's mechanism is unreachable code for three of its four call sites. Only trend's
16.1 min is short enough to fire at all, which is exactly why trend is the one source that recovered.

### 🎯 Item #1 (carried, root cause now known) — ADR-0131's re-seed cadence outlives the process

**The fix this implies (next cycle, once the pending scorer window closes):** the retry cadence must be
anchored to *elapsed sightings that can actually occur within a process*, not to the sensor's warm-up
length in samples. The warm-up length is the right *seed span*; it is the wrong *retry period*. Either
retry on a short fixed sighting count independent of `warmupSamples()`, or persist the cold-set across
boots so the counter survives the redeploy. This is a bug fix, not a new dial — no money/risk number.

**VERIFY-BY (next run):** a **second** `still cold` WARN line for at least one name that logged one at
boot — i.e. `grep "still cold" logs/jethro-app.log | grep -oE "for [A-Z]+" | sort | uniq -c` must show a
count **≥ 2** for some name. That, and only that, proves a retry executed.

### Item #2 (raised in importance — it is now the binding constraint on 13 of 20 names)

With σ no longer blocking universally, `strategy_diag.edgeGated` is what holds the rest of the book out:
**13 names** each annotated `no positive OOS edge` — GOOGL (momentum **−58.18505489**, mean-rev
**−41.67661313**), PFE (**−137.76114263** / **−1.56304119**), PG (**−82.99627912** / **0.00000000**), BAC,
HD, JPM, UNH, AMZN, GOOG, SAP, MSFT, EURUSD, GBPUSD. Their fusion targets are large and real — WMT
**+386.53347**, BAC **+535.398702**, PG **+233.664843** — and every one sits at `deltaQty: 0`.

This is the ADR-0064 gate working **as designed**, not a defect: `signals_telemetry` at 3600s still reads
trend **−5.992629598477899 bps** and social **−0.16243977111823402 bps** at 900s. The three sources that
are positive at 3600s — reversion **+6.535654299927848 bps**, xsreversion **+6.0462675948088975 bps**,
momentum **+5.531570972222222 bps** — are not separable from noise at these cohort counts
(`stdCohortMeanBps` **35.81 / 46.64 / 33.46** against **45 / 12 / 6** cohorts). Per the standing priority,
the answer here is **a new signal with genuinely measured edge**, never loosening the gate.

---

**Prior item #1 (ADR-0131, the boot-only warm-start seed) → ⚠️ PARTIALLY VERIFIED — the forecast half is
green, the mechanism itself is still unexercised.** Its VERIFY-BY was `sources ≥ 2` and a non-zero
`agreement` on `/api/fusion/targets`, and both read green off the live endpoint: **15 of 20** names show
`sources: 2` (every name was at 1), `agreement` runs to **0.870** (CVX), **15** names carry a non-zero
`targetQty` (WMT **+378.11**, XOM **+147.74**, BAC **−166.31**), and `weights` now contains a **trend**
key at **0.30282274653051533** where the source had published nothing at all. Since the 09:49:31 ET boot
the log shows **5 `trend sensor warmed`** and **22 `cross-sectional reversion sensor warmed`** against
**0** of each in the whole previous process.

**The half that is NOT verified, stated plainly:** every warmed line is timestamped within 90 seconds of
the 09:49:40 boot and every still-cold name has logged exactly **one** line — the boot seed. The retry
cadence is 193 sightings (trend) / 121 plans (σ) and neither had elapsed at report time. The sensors
warmed because this restart followed two hours of uptime rather than a 4h45m outage, so the store's tail
was full — **not** because the re-seed fired. ADR-0131's mechanism is deployed and live in the log
(`re-seeding every 193 sightings until it does (ADR-0131)`); it has not yet been tested. It carries no
credit for this recovery and stays open until a retry is observed.

**No change this cycle.** The scorer reports `efccc6502 still accumulating evidence (1/6 cycles)` and
`.pending-baseline.json` exists, so a second change would destroy the evidence.

### 🎯 Item #1 (carried, sharpened) — the σ leg of ADR-0131 is what still holds the book at $0.00

**The defect, now precisely located.** All 20 targets show `deltaQty: 0` against `targetQty` up to
**+378.11**. `PositionBuffer.mayIncrease` (`PositionBuffer.java:146,195`) requires **both** the ADR-0064
edge gate **and** ADR-0126's `stopArmed`, and `stopArmed` is `streamVol.sigmaPerSample(id).isPresent()`
(`FusionLifecycle.java:521`). Since boot there is exactly **1 `risk-cut σ sensor warmed`** (NQ) against
**19 `still cold`**, so every name that finally has a real target is vetoed from opening because its
trailing stop cannot be priced. The σ seeds are short but close — MSFT **106 of 121**, AAPL **79 of 121**,
AMZN **75 of 121**, and the low tail (HD **27**, MCD **29**) — exactly the case ADR-0131's retry exists to
clear. Give it the cycle it needs before concluding the cadence is wrong.

**VERIFY-BY (next run, both parts):** (a) `grep -c "risk-cut σ sensor warmed"` over the log since boot
must exceed **1**; (b) at least one name on `/api/fusion/targets` must show a non-zero `deltaQty`. If the
retries have fired and σ is still cold, the defect is the **seed span** (121 contiguous prints at the plan
interval that the store does not hold), not the cadence — and that becomes the change.

### Item #2 (carried, was #2) — no source has demonstrated positive out-of-sample edge

The second lock on the same door, and the standing priority. `signals_telemetry` reads **trend −5.9926
bps** and **xsreversion −5.1514 bps** at 3600s — the two sources actually publishing — while the
learned-signal backtest logged `VETOED — -4.58 bps/opportunity net does not clear zero-and-baselines
(best baseline -9.54 bps)`, and `strategy_diag.edgeGated` lists 13 names with `no positive OOS edge`.
Ranked below item #1 only because item #1 is one cycle from resolving itself; once σ arms and the book can
open, this is the whole problem, and the answer is a **new** predictor through the ADR-0049 OOS gate, not
another fusion weight.

**VERIFY-BY:** a source whose measured expectancy is positive and clears the zero-and-baselines test at
its horizon, or an explicit written finding that none in this universe does.

### Item #3 (carried) — the loop's heartbeat reported a dead app as a healthy market-closed cycle

Unchanged, not addressed. Still ranked below the money items.

**VERIFY-BY:** with the app stopped, `reports/run-status.json` must record `available: false`.

---

## Verification block — 2026-07-30 13:30Z (the platform is back UP — and the reason the book is flat is now visible)

**Prior item #1 (V49 boot-blocking migration) → ✅ VERIFIED, struck.** Every part of its VERIFY-BY reads
green off live telemetry: `/api/risk` returns HTTP 200 with a live `.total` (was `Connection refused`);
`flyway_schema_history` shows `version 49, world indices, success=t`; `select count(*) from instrument
where asset_class='INDEX'` returns **12** (was 0); `pg_get_constraintdef` now lists `INDEX` alongside the
six original classes; `orders_day` contains no INDEX name (it contains no orders at all — see item #1
below). The JVM booted 07:47 ET and has an uptime of 6158s at report time. Fix landed, fix worked.

**Prior item #2 (ADR-0126, σ-cold veto) → ⚠️ STILL UNMEASURED, but no longer for lack of an app.** It has
accumulated evaluation cycles at last (`5e35752dd` scored ⚠️ INCONCLUSIVE at 13:30Z, t=+1.49 against a 1.5
hurdle over 36 cycles) and `.pending-baseline.json` is gone, so a new change is due this cycle. Its veto is
**directly implicated** in item #1 below and is addressed by the same fix rather than carried separately.

### 🎯 Item #1 (NEW, and it is why the desk has traded nothing all session) — FIXED THIS CYCLE

**The defect.** The ADR-0071 warm-restart seed replays a name's stored prices into a sensor **on first
sight of that name, and only then**. First sight is boot — and this boot followed a 4h45m outage, so the
store's recent tail was empty at exactly the moment the one and only read was taken. `SensorWarmup`
correctly refused to walk across the hole and handed over almost nothing, live in `logs/jethro-app.log`:

```
07:47:37  trend sensor still cold for BAC  after seeding 0 of 193 stored prices
07:47:52  reversion sensor still cold for AAPL after seeding 1 of 241 stored prices
08:56:19  risk-cut σ sensor still cold for GOOG after seeding 0 of 121 stored prices
```

Under the old rule the sensors were then abandoned there for the life of the process. Counted across the
whole log since boot: **39 `trend sensor still cold`, 0 `trend sensor warmed`; 39 `reversion … still
cold`, 0 warmed; 9 `risk-cut σ … still cold`, 1 warmed.** The single σ that warmed is `NQ`, whose tape
runs overnight and whose reversion seed accordingly returned `232 of 241` — the natural experiment that
isolates the cause as the discontinuity in the stored series, not the sensor.

**Cost — this is the whole flat book.** With only `xsreversion` publishing per name, every name carries
`sources: 1`; ADR-0124 sets the agreement scalar to 0 at one effective source; so `/api/fusion/targets`
reports `combinedForecast` of `-0.0` or `0.0` and `targetQty: 0` for **every** name, against raw source
forecasts as large as `−20.0` and `+6.38`. `orders_day.total` is **0**, gross exposure **$0.00** against a
firm cap of $1,500,000, and `forecastScalars` contains only `reversion` and `xsreversion` — the trend
source has published nothing at all since boot. ADR-0126's open veto is gated on the same failed σ read,
so it was independently blocking every open for the same reason.

**The fix (ADR-0131).** A cold sensor **re-seeds**: `SensorReseed` re-attempts the replay every
`warmupSamples()` sightings while the sensor is still cold, and retires a name permanently once it warms.
The retry cadence is read off the sensor's own warm-up length, so no number is introduced. The replay goes
into a state just dropped by a new `forget(instrumentId)` on `EwmacTrendForecaster`,
`RangeReversionForecaster` and `StreamVolatility`, so prints already consumed are never counted twice; the
reset is confined to cold names, which publish no view and arm no stop, so nothing live is disturbed.
Applied at all four seed sites: trend, per-name reversion, index trend, and the σ sensor behind the
ADR-0086 risk cut and the ADR-0126 open veto. `SensorWarmup` itself is untouched — only how often it is
asked. The store currently holds 378 AAPL prints over 81 minutes at a 1.1s median, comfortably more than
any of those warm-ups needs, so the first retry should warm them.

**VERIFY-BY (next run).** `/api/fusion/targets` must show at least one name with `sources ≥ 2`,
`agreement > 0` and a non-zero `combinedForecast` (today: every name `sources: 1`, `agreement: 0.0`,
`combinedForecast: 0.0`). `forecastScalars` must contain a `trend` key (today: absent). The app log must
contain `trend sensor warmed …` and `risk-cut σ sensor warmed …` lines timestamped well after boot (today:
0 and 1 respectively). If those read green, gross exposure should leave $0.00 — but note gross is the
*consequence*, not the VERIFY-BY: the sensors speaking is what this change claims, and every gate between
a forecast and a fill is untouched.

### Item #2 (carried, was #4) — two fusion sources carry real weight while measuring NEGATIVE at every horizon

Unchanged and still not taken: `/api/signals/telemetry` shows **xsreversion −6.2427 bps @3600s / +0.3397
@900s** and **trend −5.9926 @3600s / +0.1771 @900s**, against live fusion weights of **0.4995** and
**0.3049**. Deliberately deferred again: re-weighting sources is combiner work, and the standing priority
says combiner tuning ranks below a defect that stops the desk trading at all. Reconsider once item #1 is
verified and the sources are actually publishing enough to be judged.

**VERIFY-BY:** a source measuring negative at every horizon over a full evaluation window must not hold a
weight above the floor.

### Item #3 (carried) — the loop's heartbeat reported a dead app as a healthy market-closed cycle

Unchanged, not addressed this cycle. Nine cycles (07:00Z–11:00Z) logged `available: true` with a
byte-identical `total_pnl 126.87240898`, while `logs/report.md` showed `Connection refused` on every
endpoint. Ranked below item #1 because it is a reporting defect, not a money defect — but it is what let
the money defect run for 4.5 hours unnoticed.

**VERIFY-BY:** with the app stopped, `reports/run-status.json` must record `available: false`.

## Verification block — 2026-07-30 11:30Z (the platform was DOWN — every prior item is unverifiable until it boots)

**Prior item #1 (ADR-0126, σ-cold veto) → ⏸️ UNVERIFIABLE, carried forward unchanged at #2.** Its VERIFY-BY
required reading `deltaQty` per name from `/api/fusion/targets`; that endpoint — and every other — returns
`URLError: [Errno 111] Connection refused` in this run's `logs/report.md`. `score-change.py status` prints
`app unreachable — not measured`. ADR-0126 has therefore accumulated **zero** evaluation cycles, not a
verdict. `.pending-baseline.json` still holds its commit `5e35752dd`, untouched. Not graded, not reverted.

### 🎯 Item #1 (NEW, and it outranks everything because nothing else can even be measured) — FIXED THIS CYCLE

**The defect.** The JVM has refused to boot since **07:00Z** (last write to `logs/jethro-app.log`; no java
process, nothing listening on 8080 — only Postgres 5432 and Redpanda 9092 are up). ADR-0129 added `INDEX`
to `AssetClass.java` but never widened the SQL check constraint, so its migration cannot apply:

```
FlywaySqlScriptException: Script V49__world_indices.sql failed
SQL State : 23514
Message   : new row for relation "instrument" violates check constraint "instrument_asset_class_check"
  Detail: Failing row contains (SPX, INDEX, USD, 1.00000000).
```

Flyway fails → `PersistenceConfig.flyway` (`PersistenceConfig.java:41`) fails → Spring context aborts →
exit. Confirmed live: `pg_constraint` still defines the check as
`EQUITY, FUTURE, OPTION, FX, BOND, SWAP` (set by `V7__rates_and_swaps.sql:9`), `flyway_schema_history`
tops out at **V48**, and `select count(*) from instrument where asset_class='INDEX'` returns **0**. V49 has
never applied anywhere and its transaction rolled back cleanly, so there is no failed history row to repair.

**Cost.** Nine consecutive loop cycles (07:00Z–11:00Z) reported `action: market-closed` with
`available: true` and a **frozen** `total_pnl 126.87240898 / gross 0E-8` repeated verbatim — a last-known
value, not a live read. Four and a half hours of zero trading, zero measurement, and no ability to cut a
position, presented as a normal flat book.

**The fix.** `V49__world_indices.sql` now drops and re-adds `instrument_asset_class_check` with `INDEX`
appended, ahead of its own inserts — the same cumulative drop/re-add shape `V7` used for `SWAP`, no class
removed. Edited in place rather than as a V50 because Flyway runs in version order and would never reach a
V50; safe because V49 has never successfully applied. Dry-run through psql in `BEGIN … ROLLBACK` gave
`ALTER TABLE / ALTER TABLE / INSERT 0 12 / INSERT 0 12 / INSERT 0 24`. No trade path opens: `INDEX` is
vetoed at the order chokepoint (`FusionExecutor:128`, holds even with `require-backtest-support=false`)
and skipped in `CrossSectionalReversionLifecycle:159`.

**VERIFY-BY (next run).** `/api/risk` must return a live `.total` at all (not `Connection refused`), and
`flyway_schema_history` must show `version 49, success=true`. `select count(*) from instrument where
asset_class='INDEX'` must read **12** (today: 0). No `INDEX` name may appear in `recent_orders`. If
`/api/risk` still refuses connection, this is 🔴 REGRESSED and the next change reverts V49's inserts
entirely rather than debugging the feature.

### Item #2 (carried, was #1) — ADR-0126's σ-cold veto has never been measured

Full statement in the 2026-07-29 19:30Z block below. Unchanged VERIFY-BY: in `/api/fusion/targets`, every
name for which the log has not printed `risk-cut σ sensor warmed <name>` must read `deltaQty 0.000000`
while `currentQty` is `0`. Failing witnesses to re-check: KO, WMT, BAC, MCD, PG, XOM, NEE, HD.

### Item #3 (NEW, queued) — the loop's heartbeat reported a dead app as a healthy market-closed cycle

For nine cycles `run-status.json` carried `available: true` while every endpoint refused connection, and
the frozen PnL made the outage indistinguishable from a quiet weekend. `logs/report.md` did print the
endpoint errors, so the raw signal existed and the status writer ignored it. An outage should be a loud,
distinct state — not `market-closed` with yesterday's number. **VERIFY-BY:** with the app deliberately
stopped, `run-status.json` must record `available: false` and an action distinct from `market-closed`,
and must not repeat a stale `total_pnl`.

### Item #4 (carried, was #2) — two fusion sources carry real weight while measuring NEGATIVE at every horizon

`xsreversion` (weight 0.860, −3.5725 / −0.8669 bps) and `social` (weight 1.030, negative at every horizon).
Still combiner work, still ranked below anything that gates availability or unclosable risk.

---

## Verification block — 2026-07-29 19:30Z (ADR-0124 scored ⚠️ INCONCLUSIVE and kept — a new change was due; ADR-0126 shipped)

**Prior item #1 → ⚠️ RE-SCOPED (measured with the wrong constant), struck below the line.** The register
quoted `jethro.fusion.buffer-fraction=0.5`; `PositionBuffer` is wired from
`jethro.fusion.position-buffer.fraction=0.10` (`FusionConfig:211`) — a different dial. At the true width
the band is `|target| ÷ |forecast|`, not `5 × |target| ÷ |forecast|`. Recomputed live in exact decimal
from `/api/fusion/targets`: **GOOG `deltaQty 1.495053`, AAPL `2.704668`, JPM `0.506787`** — no held name
is frozen. What survives is that `|combinedForecast| < 1` cannot open a position (BAC 0.7660, MCD
0.7418, PG/XOM 0.3529, NEE 0.2893), which is the buffering rule behaving correctly on a view a tenth of
typical strength. Not a defect; dropped from the register.

### 🎯 Item #1 (NEW, and it was about to put the desk's largest risk somewhere it had no exit) — ADDRESSED THIS CYCLE by ADR-0126

**The defect.** `FusionLifecycle.seedVolatility` logs at WARN `risk-cut σ sensor still cold for {} …
this name cannot be stopped out until its mark history has accumulated`, and **nothing consumed it**.
The planner sized on forecast and volatility budget alone, so a name could be given a position the same
cycle the log said ADR-0086 could not protect it.

**Proven live from the app log + `/api/fusion/targets`.** Warmed (`risk-cut σ sensor warmed … from 121
stored prices`): MSFT, AAPL, NVDA, AMZN, GOOG, NQ, JNJ, JPM. Never warmed, yet carrying a published
target: **KO, WMT, BAC, MCD, PG, XOM, NEE, HD** — and the two largest absolute targets in the entire
book are in that set:

| name | target | σ sensor | largest protected target for comparison |
|---|---|---|---|
| KO | **−101.879300** | cold (3 of 121 prices) | NVDA 37.621300 |
| WMT | **−74.222500** | cold (3 of 121) | — |
| BAC | 19.708000 | cold (3 of 121) | — |
| JNJ | 24.416700 | warmed | — |

**The fix (ADR-0126).** `PositionBuffer.mayIncrease` now vetoes an increase for two independent reasons —
the ADR-0064/0072 edge gate, **or** an unarmed σ sensor. Reduce-only, so cutting is always available;
the aim is re-seeded so intent cannot accumulate behind the veto. Deliberately evaluated with
`gate == null` handled, because ADR-0075's clamp and ADR-0118's escape both sit inside
`if (gate != null && !gate.mayIncrease(...))` and are dead while ADR-0122 holds the gate off.

**VERIFY-BY (next run).** In `/api/fusion/targets`, every name for which the log has NOT printed
`risk-cut σ sensor warmed <name>` must read `deltaQty 0.000000` while `currentQty` is `0`; and no such
name may appear in `recent_orders` with a BUY/SELL that increases exposure. Conversely, once
`risk-cut σ sensor warmed KO` appears, KO must begin trading on the ordinary aim path. Today's failing
witnesses: KO, WMT, BAC, MCD, PG, XOM, NEE, HD.

### Item #2 (open, queued) — two fusion sources carry real weight while measuring NEGATIVE at every horizon

From `/api/signals/telemetry`, cohort t-stat = `avgReturnBps ÷ (stdCohortMeanBps ÷ √cohorts)`:
**xsreversion** −3.5725 bps @3600s and −0.8669 @900s (negative at every horizon) on live fusion weight
**0.860**; **social** −1.5647 @3600s, −2.9127 @900s, −0.0450 @225s on weight **1.030**. The only source
positive at all three horizons is **reversion** (+7.6173 @3600s t≈1.1, +2.1683 @900s t≈1.3, +0.1366
@225s), which still clears neither the 1.5 hurdle nor the desk's ~3 bps measured round trip (1.00 bps
fee + 0.5–0.7 bps slippage per side). Not taken this cycle: it is combiner work, and the standing
priority puts it below a control hole that lets the desk open risk it cannot close.

**VERIFY-BY.** `/api/fusion/targets` `weights` for `xsreversion` and `social` measured against their
`/api/signals/telemetry` `avgReturnBps` — a source negative at every horizon should not outweigh the
only consistently positive one.


## Verification block — 2026-07-29 19:00Z (ADR-0124 at 5/6 cycles — held, no code change made)

**ADR-0124 → ✅ VERIFIED for a fifth consecutive cycle**, read live from `/api/fusion/targets`.
Deployment proven behaviourally, not from `git log` (rule 84): `uptimeSeconds` **836** against a
`traffic.timestampMillis` of **1785351601499** puts the JVM start at **18:46:05Z**, and the endpoint
reads `sources=1 → agreement 0.000` — a value only the ADR-0124 code produces.

- **TSLA / META / GOOGL** all read `sources=1, agreement 0.000, combinedForecast −0.0, targetQty 0`.
  None of the three appears anywhere in the window's `recent_orders` (17:50Z–18:57Z), so the rule is
  holding and costing nothing.
- Corroborated names still carry all the conviction: **NVDA `sources=3, agreement 0.834, fc −5.227`**,
  **GOOG `sources=3, agreement 0.826, fc −4.753`**.

Its scored verdict remains the scorer's: `score-change.py score` prints
**`3e7817e4f still accumulating evidence (5/6 cycles)`**, and `reports/.pending-baseline.json` is present
— so **no code change was made this cycle**.

### 🎯 Item #1 (NEW — and it is costing money right now) — every held equity position is FROZEN inside its own no-trade band, and ADR-0118's escape hatch is gated on the disabled edge gate

**The defect.** `PositionBuffer.band()` sizes the no-trade region as
`|target| × Forecast.TARGET_ABS ÷ |forecast| × buffer-fraction` — half the average position the name
would carry *at a typical-strength forecast*. `Forecast.TARGET_ABS = 10.0` while this book's live
`combinedForecast` magnitudes run **0.045 – 8.338**, so that ratio inflates the band by 1.2×–222×. The
band comes out **tens of shares wide against holdings of 2–13 shares**, and every position falls inside
it. `bufferedDelta` then returns exactly zero, every cycle, indefinitely.

ADR-0118 diagnosed precisely this trap and wrote the escape (`isTrappedExit` → close the holding in
full). But that call sits inside `if (gate != null && !gate.mayIncrease(...))` in
`PositionBuffer.apply`, and `jethro.fusion.edge-gate.enabled=false` (ADR-0122, owner-directed
exploration mode) means **the branch never executes**. The fix is present in the source and unreachable
in the configuration the desk actually runs.

**Proven live, not inferred.** Band recomputed from `/api/fusion/targets` in exact decimal, using the
two constants read from source (`Forecast.TARGET_ABS = 10.0`, `jethro.fusion.buffer-fraction=0.5`):

| name | \|forecast\| | target | held | band | \|gap\| | inside band? | live deltaQty |
|---|---|---|---|---|---|---|---|
| AMZN | 4.0065 | 70.4258 | −13.0 | **87.8884** | 13.00 | **yes** | 0.0000 |
| AAPL | 3.2970 | 78.4625 | −9.0 | **118.9907** | 9.00 | **yes** | 0.0012 |
| MSFT | 8.3379 | 75.1371 | −8.0 | **45.0573** | 8.00 | **yes** | 0.0210 |
| GOOG | 0.2142 | −2.0340 | −8.0 | **47.4879** | 5.97 | **yes** | 0.0000 |
| NVDA | 0.0450 | 0.5657 | −2.0 | **62.8966** | 2.00 | **yes** | 0.0000 |

GOOG is the cleanest proof that this is not merely a stalled *entry*: target and holding are the **same
side** (both short), the desk wants to be short 2.03 and is short 8.00, and it cannot cut the difference.

**What it costs.** These five frozen positions carry essentially the whole mark-to-market loss. From
`/api/risk` positions: **AMZN −$42.33**, **MSFT −$26.49**, **GOOG −$22.81** unrealized, against firm
total unrealized **−$83.90**. The desk is holding the losers because the band will not let it out.

**VERIFY-BY (next run).** Recompute the table above from `/api/fusion/targets`. The fix is confirmed when
**at least one name with `|gap| < band` reports a non-zero, risk-REDUCING `deltaQty`** — i.e. a holding
whose own forecast opposes it (or whose target is smaller than it) is actually being wound down — and
`/api/risk` `positions` shows the corresponding `quantity` moving toward zero. Still-broken if all five
held names again read `deltaQty 0.0000` with gaps far inside their bands.

### Item #2 (NEW) — ADR-0125 is committed but undeployed, carries NO baseline, and will land inside ADR-0124's scoring window

`ecf079c` (V48, ~12 new equities across 7 sectors) was committed **18:54:55Z** by an out-of-band session
(`Co-Authored-By: Claude Opus 4.8`, with a `Claude-Session` URL — not a loop commit). It is **not
running**: Flyway's `flyway_schema_history` tops out at **version 47**, and the 18:46Z boot log reads
`MARKET DATA: Alpaca real-time WS for 7 equities`, not the ~19 V48 would create.

The risk is to the evidence, not to the money. `reports/.pending-baseline.json` still names
**3e7817e4f (ADR-0124)**, which reaches 6/6 and is scored **next cycle**. If the wrapper restarts the app
before then, V48 applies, the live cross-section roughly triples, and the resulting gross/PnL move is
attributed by the scorer to ADR-0124 — a change that has nothing to do with it. A ❌ BAD verdict would
then auto-revert a change whose own five-cycle record is clean.

**VERIFY-BY (next run).** Read `flyway_schema_history` max version and the boot log's
`Alpaca real-time WS for N equities`. If N has jumped while the ledger row scoring `3e7817e4f` was
written in the same cycle, that row is **contaminated and must be discounted in words** — the loop must
not treat it as evidence about ADR-0124. Never hand-edit the ledger or the baseline to compensate.

---

## Verification block — 2026-07-29 18:30Z (ADR-0124 at 4/6 cycles — held, no code change made)

**ADR-0124 → ✅ VERIFIED for a fourth consecutive cycle**, read live from `/api/fusion/targets`.
Deployment proven behaviourally, not from `git log` (rule 84): `uptimeSeconds` **1206** against a
`traffic.timestampMillis` of **1785349802582** puts the JVM start at **18:10:02Z**, and the endpoint
reads `sources=1 → agreement 0.000` — a value only the ADR-0124 code produces.

- **TSLA / META / GOOGL / ORCL** all read `sources=1, agreement 0.0, combinedForecast 0.0/−0.0,
  targetQty 0`. ORCL is a *new* fourth instance this cycle, so the rule is holding on names it had not
  yet seen.
- Corroborated names still carry the conviction: **GOOG `sources=3, agreement 0.796, fc −6.322`**,
  **AAPL `sources=3, agreement 0.838, fc +4.942`**. No `sources=1` name carries any.

Its scored verdict remains the scorer's: `score-change.py score` prints
**`3e7817e4f still accumulating evidence (4/6 cycles)`**, and `reports/.pending-baseline.json` is present
— so **no code change was made this cycle**.

### 🎯 Item #1 (NEW, and it displaces the closed execution item) — the desk's two weight-discipline rules are structurally INERT in the configuration it actually runs

**The defect.** `TelemetryWeights.compute` classifies each source three ways — ADMITTED, UNPROVEN
(ADR-0097 → held at `weights.min`) and CONTRADICTED (ADR-0111 → stood down to 0). But both demotions are
guarded by `if (!anyAdmitted) return out;` — *"nothing has earned the right to steer — leave the weights
as measured"*. With the edge gate **disabled** by ADR-0122 (owner-directed exploration mode) nothing ever
clears admission, so `anyAdmitted` is permanently **false** and **neither rule has ever fired on this
book**. The rules were written for a desk whose gate is ON; in exploration mode they switch themselves off
at precisely the moment discrimination is worth most.

**Proven live, not inferred.** `/api/fusion/targets` `weights` reads
`reversion 2.2992, social 1.1056, momentum 0.6265, xsreversion 0.5275, trend 0.4412` (Σ = 5.0000). No
source sits at the `jethro.fusion.weights.min=0.25` floor and none is stood down to 0 — the exact
signature of `anyAdmitted == false`.

**What it costs.** Read against the same `/api/signals/telemetry`, cohort-clustered
(`t = avgReturnBps ÷ (stdCohortMeanBps ÷ √cohorts)`, the gate's own ADR-0077 construction):

| source | 225 s | 900 s | 3600 s | live weight | share of Σ |
|---|---|---|---|---|---|
| **reversion** | **+0.488** | **+1.611** | **+1.239** | 2.2992 | **45.98%** |
| social | −0.000 | −0.482 | −0.200 | 1.1056 | 22.11% |
| momentum | −0.039 | +0.309 | −1.446 | 0.6265 | 12.53% |
| xsreversion | −0.926 | +0.190 | −1.074 | 0.5275 | 10.55% |
| trend | −0.099 | −0.038 | −1.140 | 0.4412 | 8.82% |

**54.02% of the combiner's vote sits on the four sources that are non-positive at the selected rung**,
against **45.98%** on reversion — the only source positive at *all three* rungs, with expectancy scaling
in horizon the way a real signal does and noise does not (**+0.174 → +2.708 → +8.614 bps**). The desk is
diluting its one candidate edge with a measured-negative majority, and the code written to stop exactly
that is unreachable.

**Why this is edge work, not the combiner tuning the standing priority forbids.** It is not a re-weight of
sources that have no edge; it is a *designed safety rule that never executes* in the live configuration.
And reversion has now cleared the raw 1.5 hurdle at its best rung (**t +1.611 on 103 cohorts**), so there
is a measured edge to shape — the precondition the standing priority sets.

**VERIFY-BY (next run, from `/api/fusion/targets` `weights`):** with the gate disabled, the
UNPROVEN/CONTRADICTED classification must be reachable on its own evidence rather than gated on an
admission that can never happen — so at least one measured-non-positive source must read **at the 0.25
floor or 0**, and **reversion's share of Σweights must rise above 0.4598**. If the shares are unchanged,
the fix did not land.

**Known interaction to handle in the change (do not ignore):** demoting sources lowers each name's
`sources` count and its ADR-0076 diversification multiplier, and ADR-0124 silences any name left with
`sources=1`. A demotion to `weights.min` (which the combiner still counts) preserves breadth; a
stand-down to 0 does not. Prefer the ADR-0097 floor over the ADR-0111 zero unless the source is
significantly negative on its own test.

### Item #2 — nothing else is ranked above noise this cycle

Checked and deliberately **not** promoted (recorded so a later cycle does not re-chase them):

- **"The gate is measuring the wrong horizon"** → **FALSIFIED**. `HorizonLadder` (ADR-0082) already
  evaluates all three rungs (3600 / 900 / 225, `jethro.signals.horizon-rungs=3`) and selects by best
  p-value. The horizon lever already exists and is already exercised.
- **"Bonferroni over 3 nested rungs is over-conservative"** → true in the literature, but it is a change
  to a *significance floor* whose only effect while ADR-0122 holds the gate off is cosmetic — the gate is
  not what is sizing this book. Not actionable now; revisit if exploration mode is ever turned off.
- **JPM** was bought **0 → +16** this window on `agreement 0.527` with `reversion +11.288` fighting
  `trend −13.392`, and is the worst realized name at **−$25.43**. Suggestive of exactly the dilution in
  item #1, but **n = 1 name** — rule 77: log, don't chase.

---

## Verification block — 2026-07-29 18:00Z (ADR-0124 at 3/6 cycles — held, no code change made)

**ADR-0124 → ✅ VERIFIED for a third consecutive cycle** on its decisive VERIFY-BY, read live from
`/api/fusion/targets`. Deployment proven behaviourally, not from `git log` (rule 84): `uptimeSeconds`
**1430** at a report stamped **18:00:01.608Z** puts the JVM start at **17:36:11Z**, and the endpoint reads
`sources=1 → agreement 0.000`, a value only the ADR-0124 code produces.

- **TSLA `sources=1, agreement 0.000, fc −0.000, targetQty 0.00`**; **META `sources=1, agreement 0.000,
  fc 0.000, targetQty 0.00`**; **GOOGL `sources=1, agreement 0.000, fc −0.000`**.
- The book's largest conviction is corroborated: **NVDA `sources=3, agreement 0.798, fc −5.544`**. No
  `sources=1` name carries any conviction.

### 🎯 Item #1 → ✅ VERIFIED **AS FALSIFIED** on its own VERIFY-BY — closing it, and NOT superseding ADR-0084

Item #1's VERIFY-BY was: *"the aggregate round-trip bps on entered-and-exited notional moves toward zero
from −11.6, on a round-trip count materially above 65."* It did — and then some. Re-measured this cycle by
**FIFO round-trip reconstruction over all 276 LIVE fills** joined to `orders.order_type`, in exact
`Decimal`, with each instrument's `contract_multiplier` applied (the reconstruction **reconciles to live**:
it returns MACRO **−$35.82**, against `/api/risk` `MACRO.realizedPnl` **−35.82347655**):

| cohort | round-trips | closed notional | net PnL | net bps |
|---|---|---|---|---|
| LIMIT-in → MARKET-out | **186** (was 65) | $104,394.11 | **+$17.49** | **+1.68** (was **−11.6**) |
| ALPHA book (all equities) | 189 | $106,291.89 | +$20.30 | +1.91 |
| HEDGE (ES) | 5 | $8,521.60 | −$9.31 | −10.92 |
| MACRO (NQ) | 2 | $4,263.64 | −$35.82 | −84.02 |

Clustered by instrument (9 clusters), ALPHA's round-trip net bps is **+1.220 mean, t = +0.13** — the
cohort is **statistically indistinguishable from zero**, not profitable and not costly. The **−11.6 bps**
that ranked this item #1 was an **n=65 small-sample artifact** (rule 77, which the register applied to the
holding-period buckets but not to the headline figure itself). The one-sidedness is still structurally
real — **186 of 196** round-trips are LIMIT-in → MARKET-out — but it is **no longer evidenced as costly**,
so an ADR superseding ADR-0084 would be spending the desk's one change on a cost that is not there.
**Closed. Do not re-open without a fresh code-computed measurement.**

### Checked this cycle, NOT promoted (recorded so a later cycle does not chase them)

- **NQ is 100% of the firm's realized loss** (−$35.82 on 2 round-trips, −84.02 bps) while 189 equity
  round-trips made +$20.30. Hypothesis *"fusion sizes futures without the contract multiplier, so NQ is
  20× oversized"* → **FALSIFIED**: `TargetPlanner` sizes on `price × contractMultiplier` (line 64,
  `unitValue`). **n=2** — rule 77, log, don't chase.
- **GOOG `targetQty −73.22` against `currentQty +4.00` with `deltaQty −0.010`** reads as a refusal to
  trade; it is not. Read `PositionBuffer`: ADR-0102 `withinTarget` clamps the aim to flat because the
  holding opposes the current target, and `bufferedDelta` then trades to the **near edge** of the band.
  Working as designed (rule 90 again).
- **`fusion re-plan` churn is unchanged and still not costly**: **9 of 20** post-restart orders are
  re-plan cancels (45%), with **GOOG** now the *third* named ramp after ORCL and AAPL — BUY 1 FILLED
  17:45:53 → 2/1/2 CANCELLED → 1 FILLED 17:47:53 → 1/2/4 CANCELLED, re-planned 30 s apart at growing size.
  It is loud, it is not expensive: the cohort it belongs to measures **+1.68 bps**.

## OPEN (ranked, most-costly first) — as of 2026-07-29 18:00Z

1. **[OPEN — promoted to #1] No source clears the edge gate, and that — not execution — is the whole
   problem.** This cycle's reconstruction is the direct proof: the desk turns over **$106,291.89** of
   closed equity notional across **189** round-trips to earn **+$20.30 (+1.91 bps, clustered t = +0.13)**.
   Execution is essentially free and the result is essentially zero, because the signal being executed has
   no measured edge. Firm PnL is therefore decided by whatever else happens to move — 2 NQ trades and
   −$31.54 of unrealized mark on 9 open equity shorts.
   - **Reversion is the one live candidate, and it is the only source positive at EVERY horizon**, with
     expectancy scaling in horizon the way a real signal does and noise does not (`/api/signals/telemetry`,
     LIVE, clustered t from the endpoint's own `avgReturnBps`/`cohorts`/`stdCohortMeanBps`):
     **225 s +0.144 bps (266 cohorts, t +0.40) → 900 s +2.300 bps (101 cohorts, t +1.38) → 3600 s
     +9.066 bps (29 cohorts, t +1.26)**. Every other source is negative or zero at the two longer horizons
     (trend −8.634 / t −1.25; xsreversion −8.887 / t −0.87; momentum −13.257 / t −1.45; social −2.111 /
     t −0.20, all at 3600 s).
   - **The action is cohorts, not tuning** (rule 90). Re-weighting or re-tuning a source measured at
     t = +1.38 does not make it significant; more independent cohorts might. The alternative lever is a
     genuinely NEW predictor taken through the ADR-0049 OOS gate.
   - **VERIFY-BY:** reversion's 900 s clustered t crosses the **1.5** hurdle from **+1.38** at **101**
     cohorts (or its 3600 s t crosses from **+1.26** at **29**), **or** a new source is admitted by the
     gate. Secondary: ALPHA round-trip net bps rises meaningfully above **+1.91** with clustered t above
     **+0.13** on a materially larger sample.

2. **[OPEN — ops, not money] The scorer's auto-revert can fail silently.** `score-change.py` records
   `"revert": true` in the snapshot and prints a warning when `git revert` conflicts, but nothing
   downstream surfaces it — the ledger row still reads "❌ BAD ... reverted" while the commit is still
   live (this is what happened to `d9f8969cc`). A future BAD change could stay in production while the
   register believes it was pulled.
   - **VERIFY-BY:** the ledger note for a BAD verdict distinguishes "reverted" from "revert FAILED", and
     `run-status.json` carries the failure so the next run's Step 0 sees it without reading git.

---

## Verification block — 2026-07-29 17:30Z (ADR-0124 at 2/6 cycles — held, no code change made)

**ADR-0124 → ✅ VERIFIED for a second consecutive cycle** on its first (decisive) VERIFY-BY, read live from
`/api/fusion/targets`. Deployment proven behaviourally, not from `git log` (rule 84): the JVM restarted at
the 17:07 heartbeat (`uptimeSeconds` **1359** at a 17:30:01Z report) and the endpoint reads
`sources=1 → agreement 0.000`, a value only the ADR-0124 code produces.

- **TSLA `sources=1, agreement 0.000, fc 0.000, targetQty 0`**; **META `sources=1, agreement 0.000,
  fc 0.000, targetQty 0`**; **GOOGL `sources=1, fc 0.000`** — each still carrying a live raw `xsreversion`
  contribution (TSLA **+13.72**, META **+8.06**) that is correctly discarded for want of a second sensor.
- Largest conviction in the book is corroborated: **NVDA `sources=3, agreement 0.571, fc +8.206`**. No
  `sources=1` name carries any conviction at all.

**⚠️ The SECOND check FAILS — and that is a diagnosis narrowing, not a regression.** Last cycle's "zero
`fusion re-plan` cancels post-restart" was recorded as *confounded, not evidence* (rule 85). Correctly so:
after the 17:07 restart the churn is **back** — **3 of 17** post-restart orders are `fusion re-plan`
cancels, and **AAPL** is running the ORCL ramp in miniature: SELL **1 FILLED → 1 FILLED → 1 CANCELLED →
2 CANCELLED → 3 ROUTED**, each re-planned 30 s apart at a larger size. The cancellation churn is therefore
**independent of the agreement scaler** and belongs wholly to item #1 below. Evidence moved accordingly.

**Falsifier still live and still the scorer's:** *if the ordering corrects but firm realized bps does not
improve over the window, the inversion was cosmetic.* Scorer prints
`3e7817e4f still accumulating evidence (2/6 cycles)`. Re-open item if it lands ❌.

**No code change this cycle** — `reports/.pending-baseline.json` is present at 2/6; a new change would
destroy the evidence.

**Checked, not promoted — the edge question behind the whole backlog.** Cohort-clustered t on
`/api/signals/telemetry` (LIVE, 3600 s), from the endpoint's own `avgReturnBps` / `cohorts` /
`stdCohortMeanBps`: **reversion +8.783 bps, 29 cohorts, t = +1.22** — the only positive expectancy in the
book, short of the 1.5 hurdle; trend **−8.634 (t −1.25)**, xsreversion **−11.524 (t −1.05)**, social
**−4.370 (t −0.39)**, momentum **−13.257 (t −1.45)**. This explains the large target-vs-holding gaps that
look like a defect and are not — NVDA aims **+148.27** against **−3.0** held with `deltaQty 0.0`, JPM
**−69.89** against **0** with `deltaQty 0.0`: that is `PositionBuffer` under a reduce-only edge gate doing
its job. **Not a must-fix item** — reversion needs more cohorts, not tuning.

## OPEN (ranked, most-costly first) — as of 2026-07-29 17:30Z

1. **[OPEN — unchanged at #1] Execution is one-sided by design — 61 of 65 round-trips POST to enter and
   CROSS to exit.** `FusionExecutor.route` per ADR-0084: risk-increasing rests as a DAY LIMIT at the mid,
   risk-reducing goes MARKET. The desk pays the crossing cost on **100%** of exits and captures spread on
   **0%** of entries, aggregating **−11.6 bps** on **$39,363** of round-tripped notional at a median
   **27.4-minute** turn; fees were only **21%** of that realized loss, the rest adverse selection.
   - **Second named instance of the silent-entry pathology, this cycle:** the **AAPL** ramp above
     (1 → 2 → 3, re-planned every 30 s, cancelling instead of filling), after **ORCL** last cycle
     (15 consecutive cancels, 5 → 94, zero fills). Now confirmed **independent of ADR-0124**, so this item
     owns it outright: a mid-resting limit either fills because the market came to it, or does not
     transact at all while the desk believes it is working an order.
   - **Architecturally significant** — needs an ADR superseding ADR-0084, not a dial.
   - **VERIFY-BY:** the LIMIT-in/MARKET-out share of round-trips falls below 61/65, and the aggregate
     round-trip bps on entered-and-exited notional moves toward zero from **−11.6**. Secondary: the
     `fusion re-plan` share of orders falls below the **3 of 17** measured post-restart this cycle.
   - **Do not chase the holding-period buckets** (rule 77): n=65 and they are not monotone.

2. **[OPEN — ops, not money] The scorer's auto-revert can fail silently.** `score-change.py` records
   `"revert": true` in the snapshot and prints a warning when `git revert` conflicts, but nothing
   downstream surfaces it — the ledger row still reads "❌ BAD ... reverted" while the commit is still
   live (this is what happened to `d9f8969cc`). A future BAD change could stay in production while the
   register believes it was pulled.
   - **VERIFY-BY:** the ledger note for a BAD verdict distinguishes "reverted" from "revert FAILED", and
     `run-status.json` carries the failure so the next run's Step 0 sees it without reading git.

---

## Verification block — 2026-07-29 17:00Z (ADR-0124 at 1/6 cycles — held, no code change made)

**Item 1 (agreement scaler inverted at `sources=1`) → ✅ VERIFIED and CLOSED**, on its own written
VERIFY-BY, read live from `/api/fusion/targets`. Deployment confirmed first: the JVM started **12:45:40
local**, 45 s after commit `3e7817e` (12:44:55), so the running process is ADR-0124.

- Required: *"every `sources=1` row reads `agreement 0.000` and `combinedForecast 0.0`"*. Live: **META
  `sources=1`, `agreement 0.000`, `combinedForecast 0.000`, `targetQty 0.000`** — the same name that read
  `agreement 1.000`, `|forecast| 15.41` and targeted **−78.3** shares against a holding of **0** last cycle.
- Required: *"the largest conviction in the book belongs to a corroborated name"*. Live: **MSFT
  `sources=2`, `agreement 0.958`, `combinedForecast −10.381`**. No `sources=1` name carries a
  `|combinedForecast|` above any multi-source name. The **15.41 (1 src) vs 8.44 (3 src)** inversion is gone.
- Second check (the `fusion re-plan` cancellation runs): **zero cancellations in `recent_orders` after the
  16:45:57Z restart**. Recorded as **confounded, not evidence** — pre-restart the identical pathology was
  running on **ORCL** (15 consecutive cancels, SELL ramping 5 → 12 → 18 → … → 94, zero fills), and the
  restart re-cut the routed set (`selector: measured 19, tradable 9`) so ORCL is no longer routed at all.
- **Falsifier still live, and it is the scorer's to call, not this register's:** *if the ordering corrects
  but firm realized bps does not improve over the evaluation window, the inversion was cosmetic.* The
  scorer prints `3e7817e4f still accumulating evidence (1/6 cycles)`. If it lands ❌, re-open this item.

**No code change this cycle** — `reports/.pending-baseline.json` is present and the pending change is at
1/6, so a new change would destroy the evidence.

**Evidence added to item 1 below (execution one-sidedness), not acted on:** the ORCL ramp is the TSLA
pathology on a different name — a passive sell that never transacts, re-planned larger every 30 s, 15
times, zero fills. That is the entry side of ADR-0084 failing to trade at all. Separately,
`/api/attribution` reads ALPHA `totalPnl` **−$18.66** against `feesPaid` **$21.10**: the strategy book is
positive before commission. Flagged, **not** promoted to a fee-framed item — rule 74 already disproved that
framing once (fees were 21% of the firm realized loss); fee and adverse selection are both execution costs
and must be split before either is blamed.

**Not the target — logged only:** TCA `avgSlippageBps` **6.67** (TSLA) and **6.00** (GOOGL) against
**0.40–0.56** for the Alpaca-WS names, on **3** and **5** fills. Real, too little turnover to matter.

## OPEN (ranked, most-costly first) — as of 2026-07-29 17:00Z

1. **[OPEN] Execution is one-sided by design — 61 of 65 round-trips POST to enter and CROSS to exit.**
   *(was #2; promoted — it is now the top open item.)* `FusionExecutor.route` per ADR-0084: risk-increasing
   rests as a DAY LIMIT at the mid, risk-reducing goes MARKET. The desk pays the crossing cost on **100%**
   of exits and captures spread on **0%** of entries, aggregating **−11.6 bps** on **$39,363** of
   round-tripped notional at a median **27.4-minute** turn; fees were only **21%** of that realized loss,
   the rest adverse selection. New supporting evidence this cycle: the ORCL ramp (15 consecutive
   `fusion re-plan` cancels, size 5 → 94, **zero** fills) — a mid-resting limit that never transacts, so
   the entry either fills because the market came to it or does not fill at all.
   - **Architecturally significant** — needs an ADR superseding ADR-0084, not a dial.
   - **VERIFY-BY:** the LIMIT-in/MARKET-out share of round-trips falls below 61/65, and the aggregate
     round-trip bps on entered-and-exited notional moves toward zero from **−11.6**.
   - **Do not chase the holding-period buckets** (rule 77): n=65 and they are not monotone.

2. **[OPEN — ops, not money] The scorer's auto-revert can fail silently.** `score-change.py` records
   `"revert": true` in the snapshot and prints a warning when `git revert` conflicts, but nothing
   downstream surfaces it — the ledger row still reads "❌ BAD ... reverted" while the commit is still
   live (this is what happened to `d9f8969cc`). A future BAD change could stay in production while the
   register believes it was pulled.
   - **VERIFY-BY:** the ledger note for a BAD verdict distinguishes "reverted" from "revert FAILED", and
     `run-status.json` carries the failure so the next run's Step 0 sees it without reading git.

---

## Verification block — 2026-07-29 16:30Z (`d9f8969cc` scored ❌ BAD; change made: ADR-0124)

**Item 1 (the delayed-price feed cohort) is ❌ FALSIFIED and closed, on its own written VERIFY-BY.** The
thesis was that GOOGL/NQ/TSLA/ES are quoted off a 15-minute Yahoo poll, so limits rest at a price the
market has left. This run's `/api/marks`, read live, says otherwise: **GOOGL 1.0s, NQ 0.9s, TSLA 0.7s,
META 0.5s, NFLX 0.6s, ORCL 0.5s**, all `source=alpaca`; `/api/risk` reports `markAgeMillis` **50** on
every open position. The only genuinely stale marks are **GBPUSD and ES at 1383s**, and both carry
**zero** exposure and are not being traded. The cohort's loss share is still elevated (**26.7%** of
turnover against **58%** of the book's gross losses, 2.2× — GOOGL −$40.71, NQ −$35.82, TSLA −$2.91 of
−$136.82 total) but the *mechanism* the fix would have targeted is not present, so a price-age gate would
have been a fix for a defect that is not there. Item 1's VERIFY-BY said so explicitly: *"If it does not
move, the feed thesis is falsified and the target reverts to item 3."* Closed as falsified; what remains
of the cohort's loss is folded into the item below, since **every one of those names is single-source**.

**Old #2 (agreement scaler inverted at `sources=1`) → ⚠️ STILL-BROKEN, re-confirmed live, and FIXED this
cycle.** `/api/fusion/targets` read live: META `sources=1`, `agreement=1.000`, `|forecast|` **15.41** —
the largest in the book — and TSLA `sources=1`, `agreement=1.000`; against three-source names at
`0.972 → 7.87` (MSFT), `0.812 → 8.44` (NVDA), `0.674 → 6.50` (JNJ). The DM pushes the other way
(**1.000** at one source vs **1.155** at three) and is far too small to offset it. Downstream: META
targets **−78.3** shares against a holding of **0**, and TSLA's oversized target produced **fourteen
consecutive** `fusion re-plan — passive order superseded by a fresh target` cancellations in
`recent_orders` against a held position of **one** share. Promoted to **#1** and addressed by ADR-0124.

**Scorer note (open, ops):** the scorer wrote `"revert": true` for `d9f8969cc` but **no revert commit
exists** — `git log` runs straight from the analysis commit to the ledger commit. The revert conflicted
(later commits touch the same loop files) and the run printed "NOT reverted; needs attention". Left
un-reverted **deliberately**: reverting `d9f8969cc` would undo ADR-0123 (so no future change reaches the
JVM) and ADR-0122 (exploration mode), returning the desk to DORMANT — the failure state this loop exists
to escape. The ❌ was mechanical (gross 0 → $26,879 is the wake-up itself). Logged as item 3 below.

## OPEN (ranked, most-costly first)

1. **[OPEN — fix shipped this cycle, ADR-0124] Agreement scaler was inverted at `sources=1`.** The
   ADR-0119 sign ratio `|Σwᵢfᵢ|/Σwᵢ|fᵢ|` is 1 *by construction* with one contributor, so an
   uncorroborated view earned the maximum scalar in the cross-section. Replaced by the sources'
   dispersion: `s² = Σŵᵢ(fᵢ − μ̂)²/(1 − Σŵᵢ²)`, `agreement = |μ̂|/√(μ̂² + s²)`. The residual d.f.
   `1 − Σŵᵢ²` is zero at one effective source, so the dispersion is unestimable and the scalar is 0.
   - **VERIFY-BY (next run, from `/api/fusion/targets`):** no `sources=1` name may carry a
     `|combinedForecast|` above the largest `sources≥2` name — today that ordering is inverted at
     **15.41 (1 source)** against **8.44 (3 sources)**. Fixed = every `sources=1` row reads
     `agreement 0.000` and `combinedForecast 0.0`, and the largest conviction in the book belongs to a
     corroborated name. Second check, in `recent_orders`: the repeated single-name `fusion re-plan`
     cancellation runs (14 consecutive on TSLA this cycle) should stop.
   - **Falsifier:** if the ordering corrects but firm realized bps does not improve over the evaluation
     window, the inversion was cosmetic and the target moves to item 2.

2. **[OPEN] Execution is one-sided by design — 61 of 65 round-trips POST to enter and CROSS to exit.**
   `FusionExecutor.route` per ADR-0084: risk-increasing rests as a DAY LIMIT at the mid, risk-reducing
   goes MARKET. The desk therefore pays the crossing cost on **100%** of exits and captures spread on
   **0%** of entries, aggregating **−11.6 bps** on **$39,363** of round-tripped notional at a median
   **27.4-minute** turn. Fees are only **$10.83** of the **$51.61** realized loss (**21%**, 0.89 bps on
   $122,221 turnover) — the other **79%** is adverse selection: a limit at the mid fills only when the
   market comes *to* it, i.e. when the move went against the view.
   - **Architecturally significant** — changing it needs an ADR superseding ADR-0084, not a dial.
   - **VERIFY-BY:** the LIMIT-in/MARKET-out share of round-trips falls below 61/65, and the aggregate
     round-trip bps on entered-and-exited notional moves toward zero from −11.6.

3. **[OPEN — ops, not money] The scorer's auto-revert can fail silently.** `score-change.py` records
   `"revert": true` in the snapshot and prints a warning when `git revert` conflicts, but nothing
   downstream surfaces it — the ledger row still reads "❌ BAD ... reverted" while the commit is still
   live. A future BAD change could stay in production while the register believes it was pulled.
   - **VERIFY-BY:** the ledger note for a BAD verdict distinguishes "reverted" from "revert FAILED", and
     `run-status.json` carries the failure so the next run's Step 0 sees it without reading git.

---

## Verification block — 2026-07-29 16:00Z (pending change `d9f8969cc` at 5/6 cycles, no code change made)

All three seeded items re-tested against this run's live telemetry. **None is fixed** — no fix has been
attempted yet, because the previous change has been under measurement throughout. Item 1 is
**re-characterized** (its original framing was wrong in emphasis) and **demoted**; the ranking below is the
new one.

- **Old #1 — churn / round-trip cost → ⚠️ STILL-BROKEN, and mis-framed.** PnL **−$6.42** run-over-run and
  **−$49.98** over 3 runs with gross **$32,961** > 0, so the "flat-or-up over 3 runs" test fails outright.
  But the diagnosis was wrong: explicit fees are **$10.83** of the **$51.61** realized loss (**21%**, 0.89 bps
  on $122,221 of turnover) — **79%** is adverse price movement, not commission. Re-characterized as item **3**
  below, against the actual mechanism.
- **Old #2 — delayed-price cohort → ⚠️ STILL-BROKEN.** Now **27.6%** of turnover and **67.5%** of total loss
  (was 29% / 82%) — still **2.4×** its turnover share, not the ≈parity the VERIFY-BY requires. Promoted to **1**.
- **Old #3 — agreement scaler at a single source → ⚠️ STILL-BROKEN, and it is *inverted*, not merely flat.**
  `/api/fusion/targets`: single-source names carry `agreement` **1.000** and `|combinedForecast|` **20.00**
  (the cap), **18.95**, **18.55**; three-source names carry **0.993 / 10.33**, **0.981 / 6.62**,
  **0.832 / 5.80**. Uncorroborated magnitudes sit **2–3× above** corroborated ones — the opposite of the
  VERIFY-BY. Promoted to **2**.

## OPEN (ranked, most-costly first)

1. **[OPEN] Delayed-price feed cohort loses money — gate tradability on PRICE AGE.** The desk posts limits
   at the last mark with no age check (`FusionExecutor.passiveLimitPrice` over `LastPriceCache`), so names
   priced by the 15-minute Yahoo poll are quoted at a price the market has left. This run: the delayed
   cohort's realized loss is **three round-trips** — NQ **−84.3 bps** (2 RTs) and GOOGL **−27.7 bps** (1 RT),
   together **−$55.04**, which exceeds the firm's *entire* realized loss of **−$51.61**; the rest of the book
   is net positive on realized. TCA cannot see it: NQ's measured `avgSlippageBps` is **0.033** against a
   realized round-trip cost of −84.3 bps, because `arrival_price` is stamped from the same stale mark.
   Structural, not incidental — `UniversePromotionService` writes discovery-promoted names as yahoo-only by
   design, so every promoted name lands in this cohort.
   - **Architecturally significant** — needs a Proposed ADR in the same commit (a new tradability gate,
     cross-cutting, hard to reverse).
   - **VERIFY-BY:** the delayed cohort's loss share falls toward its turnover share. Fixed = loss share
     within ~1× of turnover share (currently **67.5%** loss vs **27.6%** turnover = **2.4×**), and firm
     realized bps moves toward the real-time cohort's. If it does **not** move, the feed thesis is falsified
     and the target reverts to item 3.

2. **[OPEN] Agreement scaler is inverted at `sources=1`.** `agreement` returns **1.000** when there is
   nothing to disagree with, so an uncorroborated view gets *full* conviction and clips the ±20 forecast cap,
   while genuinely corroborated three-source names are discounted to 5.80–10.33. Every single-source name
   this cycle is driven by `xsreversion` alone, and those are the discovery-promoted (hence delayed) names —
   so this defect **compounds item 1** by handing maximum size to exactly the names priced on a delay.
   Visible downstream: TSLA's target is **192** shares against a current **1**, and `recent_orders` shows
   that target repeatedly cancelled/replanned and rejected with `no market data for TSLA`.
   - **VERIFY-BY:** in `/api/fusion/targets` on one cycle, single-source names' `|combinedForecast|` sits
     **below** multi-source corroborated names' — currently **20.00 / 18.95 / 18.55** (1 source) vs
     **10.33 / 6.62 / 5.80** (3 sources).

3. **[OPEN] Execution is structurally one-sided — every entry posts, every exit crosses.** *(Replaces the
   old "churn cost" item, whose fee-based framing this run disproved.)* `FusionExecutor.route` implements
   ADR-0084 — a risk-increasing delta rests as a DAY LIMIT **at the mid**, a risk-reducing delta goes
   **MARKET** — so the desk pays the crossing cost on **100%** of exits and captures spread on **0%** of
   entries. Reconstructing every LIVE round-trip FIFO from `fills`⋈`orders`: **61 of 65** round-trips are
   LIMIT-in → MARKET-out, aggregating **−11.6 bps** on **$39,363** of round-tripped notional (**−$45.53**
   pre-fee), at a median holding period of **27.4 minutes**. A limit resting at the mid only fills when the
   market comes *to* it, so the fills are adversely selected by construction.
   - **Changing this requires a superseding ADR** (ADR-0084 is the decision in force), not a dial.
   - **Do NOT act on the holding-period buckets yet** — n=65 and they are not monotone (20–30 min is
     **+7.7 bps** while 30–60 min is **−32.1 bps**). Log, don't chase.
   - **VERIFY-BY:** the LIMIT-in → MARKET-out cohort's aggregate bps rises from **−11.6** toward zero, on a
     round-trip count materially above 65.

---

## VERIFIED / CLOSED

- ~~**Execution is one-sided by design — every entry posts, every exit crosses.**~~ ✅ VERIFIED **as
  FALSIFIED** 2026-07-29 18:00Z on its own VERIFY-BY, by FIFO round-trip reconstruction over all 276 LIVE
  fills (exact `Decimal`, `contract_multiplier` applied, reconciles to `/api/risk` MACRO
  **−35.82347655**). The LIMIT-in → MARKET-out cohort went **−11.6 bps at n=65 → +1.68 bps at n=186**;
  clustered by instrument the ALPHA book's round-trips are **+1.220 mean bps, t = +0.13** — indistinguishable
  from zero. The structure is real (**186 of 196** round-trips), the cost is not. **No ADR superseding
  ADR-0084 should be written against this.** Re-open only on a fresh code-computed measurement showing a
  negative cohort bps with clustered t below −1.5.
- ~~**Agreement scaler was inverted at `sources=1`** (ADR-0119 → fixed by ADR-0124).~~ ✅ VERIFIED
  2026-07-29 17:00Z from `/api/fusion/targets`: **META `sources=1` → `agreement 0.000`,
  `combinedForecast 0.000`, `targetQty 0.000`** (was `1.000` / `15.41` / **−78.3** shares against a holding
  of 0), and the book's largest conviction is now a corroborated name (**MSFT `sources=2`,
  `agreement 0.958`, `−10.381`**). *Re-open if the pending scorer verdict on `3e7817e4f` lands ❌ — the
  falsifier is that a corrected ordering which does not improve firm realized bps was cosmetic.*
- ~~**Delayed-price feed cohort loses money.**~~ ❌ FALSIFIED 2026-07-29 16:30Z on its own VERIFY-BY —
  `/api/marks` `ageMillis` showed GOOGL **1.0s**, NQ **0.9s**, TSLA **0.7s**, all `source=alpaca`, and
  `markAgeMillis` **50** on every open position. The cohort was real; the latency mechanism was not (the
  names were single-source, rule 79).
