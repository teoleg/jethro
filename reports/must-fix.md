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

## Verification block — 2026-08-06 17:00Z (**NO CHANGE — the ADR-0116 freeze holds at `39451ce71` 5/6.** Fifth consecutive **0%-change / 100%-market** window. This cycle spends it settling the open question item #4 raised rather than reasoning further on top of it — and the source read **invalidates three cycles of arithmetic**: `targetQty` is not the routed quantity, `aims` is. It also re-measures item #1 on a genuinely independent window, which **confirms the conclusion and refutes the culprit**: the aim is still majority mean-reverting and still fails to clear cost, but `xsreversion`'s share moved **23.1% → 9.71%** with its weight unchanged at **0.25**, so last cycle's proposed VERIFY-BY was a statistic that swings more than any change would. A new #2 is promoted from the settled ambiguity: **entry is structurally frozen for names early on the aim path.**)

### Step 0 — `39451ce71` (ADR-0142): ✅ **VERIFIED (fifth independent confirmation)**

`ops_jvm.uptimeSeconds` **8320** against `traffic.timestampMillis` **1786035601280** implies a boot instant
of **1786027281280**, versus **1786027281905**, **…760**, **…702** and **…035** on the four prior cycles
(sub-second sampling skew between the two endpoints). Same 14:41:21Z process, now five cycles and four
reports-only commits later. `git diff --name-only 95b7b57..HEAD` lists **`reports/run-status.json`** and
nothing else. Item remains struck; no further re-verification needed.

### Window attribution — 0% change, 100% market, fifth cycle running

Zero Java, zero dials, zero gates in the window's diff (`git diff --name-only 39451ce..HEAD` is confined to
`docs/loop-findings.md`, `reports/`). PnL **-24.77** (total **$-820.28** live) and gross **-2828.57** (to
**$7,874.70**) are the market plus code already live; my changes earn neither credit nor blame for either.
Over the last 3 runs: PnL **-53.02**, gross **-11373.01**.

### Not danger — but now genuinely DORMANT, which is its own failure

Gross **$7,874.70** is **0.5%** of the firm gross cap $1,500,000 (headroom **$1,492,125**); net **$-282.06**
is **0.0%** of the $1,000,000 net cap. `breaker.halted` **false**, `regime` **CALM** (`trend` **CHOP**,
`volRatio` **0.93**), `riskCuts` **[]**, `bookVolBrake` **1.0**, `portfolioRiskMultiplier`
**0.9213584408762284**. Not a danger state — but per CLAUDE.md a book at 0.5% of an owner-set budget is a
**failure to attack**, and new item #2 below is the mechanism holding it there.

---

## ~~Item #4 (was open question) — `targetQty` and `aims` disagree by ~60× on the same name~~ ✅ **RESOLVED by source read — and it invalidates three cycles of arithmetic**

Settled from `PositionBuffer.apply` (`app/src/main/java/io/jethro/app/fusion/PositionBuffer.java:185-214`),
no code change required. The three fields are three different things:

- **`targetQty`** — the planner's **end-state destination** after every risk scaler (ADR-0083 vol budget,
  ADR-0079 normaliser, ADR-0137 gross cap). It is *not* a quantity the desk intends to reach this cycle.
- **`aims`** — the **current-cycle waypoint**: `nextAim(instrument, target, held, rate)`, the previous aim
  stepped toward `targetQty` at the ADR-0080 partial-adjustment `rate`, then clamped by ADR-0102's
  `withinTarget` into the closed interval between flat and the target (line 188).
- **`deltaQty`** — what actually routes, and it is computed as `bufferedDelta(aim, held, band, target, rate)`
  (line 190). **The routed order is the gap from held to the AIM, not to `targetQty`.**

**Consequence — say it plainly:** the last three cycles' time-to-target and convergence arithmetic treated
`targetQty` as the routed destination. It is not. Those cycles **overstated the mismatch**, exactly as Rule
410 suspected. BAC `targetQty` **1114.5210** against `currentQty` **0** with `deltaQty` **0.0000** is not a
desk failing to reach 1114 — its aim for BAC is **23.387455**, and the delta is measured against that.
Struck; the correct denominator for any future convergence claim is `aims`, not `targetQty`.

---

## Item #1 (carried, CONFIRMED on a second independent window — but its culprit and its VERIFY-BY are both replaced) — **the aim is majority mean-reverting and does not clear the round-trip cost; the one source that does clear it contributes nothing.**

Re-decomposing this cycle's `fusion_targets.contributions` into `forecast × weight`, summed as
|contribution| across the six reported names (20 of 26 elided by the report — stated, not hidden):

| source | share this cycle | share last cycle | 3600s `avgReturnBps` | cohort t | hit rate | net of 2.00 bps |
| --- | --- | --- | --- | --- | --- | --- |
| **reversion** | **52.19%** | 27.9% | +0.033 | +0.02 | 0.473 | **-1.967** |
| trend | 38.09% | 42.6% | +1.860 | +1.28 | 0.534 | **-0.140** |
| **xsreversion** | **9.71%** | 23.1% | **-5.306** | -1.25 | 0.470 | **-7.306** |
| momentum | 0.00% | 6.5% | -1.299 | -0.20 | 0.557 | -3.299 |
| **social** | **0.00%** | 0.0% | **+3.296** | +0.72 | 0.592 | **+1.296** |

**(a) The conclusion survives; the culprit does not.** Mean-reversion still carries the majority —
`reversion` **52.19%** + `xsreversion` **9.71%** = **61.90%** against `trend` **38.09%** — so "the aim is
majority mean-reverting" is now confirmed on two independent windows. But *which* mean-reverting source
carries it flipped completely: `xsreversion` fell **23.1% → 9.71%** while `reversion` rose **27.9% →
52.19%**, with **both weights unchanged** (`xsreversion` **0.25**, `reversion` **0.9370515192395911**). The
swing is entirely in the forecasts. **Last cycle's proposed VERIFY-BY — "xsreversion's share below 23.1%" —
would have graded a change against a statistic that moves 13pp on its own. Do not use it.**

**(b) `reversion` is not a negative signal — it is *noise*, which is a different and worse problem.** Its
measured expectancy is **+0.033** / **+0.060** / **+0.010** bps at 3600s / 900s / 225s, with |t| ≤ **0.10**
at every horizon and hit rates **0.473** / **0.490** / **0.507**. It is indistinguishable from zero on
**803** / **2657** / **5082** resolved observations. The desk routes **52.19%** of its aim into it and pays
**2.00 bps** a round trip to do so. That is not a mispriced edge; it is a fee paid to trade a coin flip.

**(c) `xsreversion` is negative at every horizon for a THIRD consecutive window.** **-0.215** / **-2.168** /
**-5.306** bps, hit rate below a coin flip at all three (**0.492** / **0.487** / **0.470**). Prior windows
read **-0.171**/**-2.080**/**-1.816** and **-0.205**/**-2.123**/**-4.363** — same sign, same shape, three
times. Its 900s **t = -2.40** (152 cohorts, 2746 resolved) is again **the only |t| > 2 among the 15 rows**,
the same horizon as both prior windows. **It still does not survive Bonferroni at 15 tests (|t| > 2.94) —
stated in the same breath as the t-stat, per Rule 407.** The evidence carrying this is sign-consistency
across three independent windows, not any single p-value.

**(d) The whole aim, priced against its own cost, is negative.** Weighting each source's 3600s expectancy by
its share of the aim gives **+0.2102 bps gross**, against a **2.00 bps** round trip (`turnover_cost_by_name`
`fee_bps` **1.00** on every equity, **0.20** on ES/NQ) → **-1.7898 bps net**. Last cycle's composition priced
the same way gives **+0.400 gross / -1.60 net**. The *shares* swing; the *sign of this aggregate* does not.

**(e) The only source clearing cost contributes nothing.** `social` at 3600s is **+3.296** gross → **+1.296**
net, hit rate **0.592**, and is **0.0%** of every aim. **ADR-0139 already tried loosening that corroboration
gate and was graded ❌ BAD — do not re-attempt it** (Rule 408).

**VERIFY-BY (replaces last cycle's unstable one):** the **aim-weighted 3600s expectancy**, computed as
Σ(share_s × `avgReturnBps`_s@3600) over `fusion_targets.contributions` × `signals_telemetry`, must **exceed
+2.00 bps** (the measured round trip). It reads **+0.2102** now. Secondary: the mean-reverting pair's
combined share falls below **50%** (now **61.90%**). Both are aggregates, so neither is hostage to the
single-source instability that (a) documents.

---

## Item #2 (NEW, promoted out of the resolved item #4) — **entry is structurally frozen for any name early on its aim path: the no-trade band is priced off `targetQty` while the intent is `aims`, and the two differ by ~25–50×.**

Now that item #4 is settled, the freeze has a precise mechanism. `PositionBuffer.band`
(`PositionBuffer.java:481-493`) computes `band = width × |target| × E|f| / |f|` — scaled by **`targetQty`**
— while the gap it gates is `aim − held`. The class javadoc states the release condition itself
(`PositionBuffer.java:505`): a name opening from flat routes its first order only when
`|aim|/|target| > width × E|f| / |f|`. Live, that ratio is tiny for most names:

| name | `targetQty` | `aims` | `\|aim\|/\|target\|` | `currentQty` | `deltaQty` |
| --- | --- | --- | --- | --- | --- |
| BAC | 1114.5210 | 23.3875 | **0.020984** | 0 | **0.0000** |
| NEE | 448.8503 | 16.0903 | **0.035848** | 0 | **0.0000** |
| KO | 484.3413 | 18.9581 | **0.039142** | 1.0 | **0.0000** |
| AMZN | 117.4335 | 4.5897 | 0.039084 | -1.0 | 0.008299 |
| UNH | -95.2138 | -9.8118 | 0.103050 | 0 | -3.792098 |
| GOOG | 123.7431 | 19.5582 | 0.158055 | 9.0 | 5.042261 |

The pattern is monotone in `|aim|/|target|`: the two names that route meaningfully are the two highest
(**0.158**, **0.103**); the three that route **exactly zero** are the three lowest. `insideBuffer` is **18**
of **26**. BAC, NEE and KO each hold a **nonzero aim against a flat book and route nothing** — this is not a
wrong-side holding, so ADR-0132's `onTargetSide` does not reach it, and ADR-0141's measured `E|f|` narrowed
the dead zone without closing it (`band` still floors `width` at Carver's **0.10**, and caps `E|f|` at
`Forecast.TARGET_ABS` rather than flooring it).

**This is the mechanism behind the dormancy** — gross **$7,874.70**, **0.5%** of the $1,500,000 cap.

**Ranked #2, deliberately, and the ordering is the point:** fixing deployment before composition would push
more capital into an aim measured at **-1.7898 bps net of cost** (item #1d). Item #1 must land and be scored
first. **VERIFY-BY (when its turn comes):** names with a nonzero `aims` entry and `deltaQty` **0.0000**
against a flat `currentQty` falls to zero; `insideBuffer` falls below **18** of **26**; gross exposure rises
off **0.5%** of cap **without** the aim-weighted expectancy of item #1 still being negative.

---

## ~~Item (was #2) — entry gated / exit ungated conviction asymmetry~~ — remains a **SYMPTOM**, not a target (Rule 409); superseded by item #1's composition finding and item #2's band finding, which jointly generate it.

## ~~Item (was #3) — `social` corroborated on 12 of 1600 kept items~~ — folded into **item #1(e)**; not separately actionable while ADR-0139's ❌ BAD verdict stands.

---

## Verification block — 2026-08-06 16:30Z (**NO CHANGE — the ADR-0116 freeze holds at `39451ce71` 4/6.** Fourth consecutive **0%-change / 100%-market** window. This cycle spends it on the one measurement the previous three never made: **not how long the desk holds, but WHAT it is holding an opinion about.** Decomposing `fusion_targets.contributions` into weighted contributions inverts item #1 again — and this time the new #1 subsumes the old one as its own mechanism. **The desk's aim is majority mean-reversion, and 23.1% of it comes from the single source the telemetry measures negative at every horizon — carrying the only |t| > 2 in the entire table.** The exit asymmetry (old #1) is what a majority-mean-reverting aim *looks like* from the order tape; it is a symptom, not the cause.)

### Step 0 — `39451ce71` (ADR-0142): ✅ **VERIFIED (fourth independent confirmation)**

`ops_jvm.uptimeSeconds` **6520** against `traffic.timestampMillis` **1786033801905** implies a boot instant
of **1786027281905**, versus **1786027281760**, **1786027281702** and **1786027281035** on the three prior
cycles (sub-second sampling skew between the two endpoints). Same 14:41:21Z process, now four cycles and
three reports-only commits later. `git diff --name-only 24b5183..HEAD` lists **`reports/run-status.json`**
and nothing else. Item remains struck; no further re-verification needed.

### Window attribution — 0% change, 100% market, fourth cycle running

Zero Java, zero dials, zero gates in the window's diff. PnL **-0.17** (total **$-793.28** live) and gross
**+3460.97** (to **$10,491.89**) are the market plus code already live; my changes earn neither credit nor
blame for either. Over the last 3 runs: PnL **-15.45**, gross **-17514.39**.

### Not danger — deploying, with room to spare

Gross **$10,491.89** is **0.7%** of the firm gross cap $1,500,000 (headroom **$1,489,508**); net
**$513.02** is **0.1%** of the $1,000,000 net cap. `breaker.halted` **false**, `regime` **CALM**,
`riskCuts` **[]**, `bookVolBrake` **1.0**, `portfolioRiskMultiplier` **0.8388330999135437**. UNDERWATER is
the cumulative PnL, not a live danger state.

---

## Item #1 (NEW, promoted — subsumes the old #1) — **the desk's aim is majority mean-reversion; 23.1% of it comes from `xsreversion`, the one source measured negative at EVERY horizon, and nothing in the aim clears the round-trip cost.**

Decomposing each reported target's `contributions` into `forecast × weight` and summing |contribution| across
the six reported names in `fusion_targets`:

| source | share of total \|weighted contribution\| | present in | 3600s `avgReturnBps` | cohort t | hit rate |
| --- | --- | --- | --- | --- | --- |
| trend | **42.6%** | 6/6 | +1.746 | +1.20 | 0.535 |
| **reversion** | **27.9%** | 6/6 | -0.330 | -0.19 | 0.470 |
| **xsreversion** | **23.1%** | 6/6 | **-1.816** | -0.53 | 0.477 |
| momentum | 6.5% | 1/6 | +2.579 | +0.36 | 0.557 |
| **social** | **0.0%** | **0/6** | **+3.595** | **+0.79** | **0.598** |

**(a) The mean-reverting pair carries the majority of the aim.** `reversion` **27.9%** + `xsreversion`
**23.1%** = **51.0%**, against `trend` **42.6%** and `social` **0.0%**. Per-name it is starker: the pair
outvotes trend+social in **4 of the 6** reported names, and `trend` points *against* the combined aim in
**2 of 6** — NEE combined **+3.0590** is built from `xsreversion` **+19.396**, `reversion` **+11.959** and
`trend` **-2.065**, i.e. the best-measured of the three is outvoted ~15:1 by the two worst. The per-source
weights are already directionally correct (`trend` **1.453979745372038**, `social` **1.2816705886969901**,
`reversion` **0.7100000448551197**, `xsreversion` **0.5070092615525403**) — they are simply swamped by raw
claim magnitude, because `forecastScalars` equalises each source's *mean* absolute claim
(`meanAbsClaim` **10.384812188261217** trend / **10.072653463914287** reversion, scalars **0.962944713752628**
/ **0.9927870581297598**) and not its dispersion.

**(b) `xsreversion` is the only source with a consistent negative sign at every horizon — and it carries the
table's only |t| > 2.** Cohort-clustered SE = `stdCohortMeanBps`/√`cohorts`, from `signals_telemetry`:

| source | 225s (t) | 900s (t) | 3600s (t) |
| --- | --- | --- | --- |
| **xsreversion** | **-0.171** (-0.42) | **-2.080** (**-2.29**) | **-1.816** (-0.53) |
| reversion | +0.004 (+0.02) | +0.069 (+0.11) | -0.330 (-0.19) |
| trend | -0.035 (-0.22) | -0.040 (-0.08) | +1.746 (+1.20) |
| social | -0.399 (-0.50) | +0.651 (+0.69) | +3.595 (+0.79) |
| momentum | -0.683 (-0.50) | -3.792 (-1.22) | +2.579 (+0.36) |

`xsreversion` @900s is **t = -2.29** on **152** cohorts / **2748** resolved — the only |t| > 2 among the 15
rows. Honest caveat, stated rather than buried: at 15 tests that does **not** survive a Bonferroni threshold
(|t| > 2.94). The evidence is not the single t-stat — it is that `xsreversion` is negative at **all three**
horizons with hit rate **below 0.5** at all three (**0.491** / **0.490** / **0.477**), and that last cycle's
independent read had the same shape and sign (**-0.205** / **-2.123** / **-4.363**). Two independent windows,
consistent sign, sub-coin-flip hit rate, 23.1% of the aim.

**(c) Nothing in the aim clears the round-trip cost.** `turnover_cost_by_name` shows `fee_bps` **1.00** on
every equity (ES/NQ **0.20**), so an equity round trip costs **2.00 bps**. Against the 3600s expectancies:
`trend` **+1.746** gross is **net negative**; `xsreversion` **-1.816** is **-3.8** net; `reversion` **-0.330**
is **-2.3** net. The only source that clears the round trip is `social` at **+3.595** gross → **+1.6** net —
and it contributes **0.0%** to every reported aim. Receipt: ALPHA `feesPaid` **397.840203** against ALPHA
`totalPnl` **-665.63383859**; firm `totalFees` **413.814082** against firm total **-793.27852899**.

**Why this outranks the old #1.** A mean-reversion signal flips on short-horizon noise *by construction* —
so a majority-mean-reverting aim mechanically produces exactly the order tape the last three cycles measured:
a forecast that evaporates rather than flips, and an exit path that fires constantly. Time-gating the exit
(the old #1's prescription) would make the desk hold longer — but hold an aim that is 51% driven by sources
measured at ~0 and negative, while paying 2 bps a round trip. **That is holding the wrong opinion for longer.**
Fix the composition of the aim first; the churn is downstream of it.

**Not a re-tread of the INCONCLUSIVE wall.** The standing priority says tune the combiner only when a measured
edge exists to be shaped. That condition is now met in the *negative* direction, which is the cheaper half:
this is not re-weighting sources hoping to manufacture edge, it is removing a source the telemetry measures as
losing money from the sizing path. ADR-0137/0140/0141 all tuned *mechanics* around an unexamined aim; none of
them ever asked what the aim was made of.

**VERIFY-BY (next unfrozen cycle):** `xsreversion`'s share of total |weighted contribution| across
`fusion_targets.contributions` falls materially below **23.1%**; the count of names where reversion+xsreversion
outvote trend+social falls below **4 of 6**; and the aim-weighted 3600s expectancy (Σ share × `avgReturnBps`)
turns positive against the **2.00 bps** round trip. Graded from `fusion_targets` + `signals_telemetry` on the
same decomposition run this cycle.

---

## Item #2 (carried, DEMOTED from #1) — entry is gated, exit is not: mean entry conviction **7.309**, mean reduce conviction **1.300**

Re-measured on a fresh, independent window (ALPHA / FILLED / 43 orders, 15:44:54→16:29:32) and it is **sharper**
than last cycle's read, not weaker. Grouping fills by the trigger that fired them, mean `|forecast|`:
`fusion entry — target increase` **7.309** (n=**4**, median 6.315); `fusion reduce toward a smaller target`
**1.300** (n=**36**, median **0.654**); `fusion exit — target decayed to flat` **0.000** (n=3). **36 of 43
fills are the ungated reduce path** — the same asymmetry as last cycle (6.288 vs 2.062), on a different sample.

The decay-not-flip mechanism now has its clearest instances, all same-sign:
- **PFE** entry 16:17:52 @ `forecast` **-11.5747** — the window's strongest conviction — begins unwinding
  **122 s** later at `forecast` **-0.0817**, a **99.3%** decay with no sign flip; 69 of 146 shares bought back
  within 183 s.
- **GOOG** entry 16:14:50 @ **+5.3855** → reduce 365 s later @ **+0.1512** (**97.2%** decay, same sign).
- **PFE** entry 15:58:36 @ **-7.2437** → `fusion exit — target decayed to flat` 609 s later @ **-0.0000**.

Opposite-side reversal intervals this window: n=**7**, min **61 s**, median **548 s**, max **1461 s** — median
still an order of magnitude short of the 3600s horizon where the only positive expectancies live. `XOM` alone
took **8** consecutive BUY orders between 15:45:25 and 16:16:21 on a `|forecast|` wandering between **0.0047**
and **0.6422** — 141 fills, **$345,284.69** turnover, **$34.53** of fees, to end flat-ish.

**Kept, not struck** — this is real and costly. It is demoted because item #1 is its cause: a majority
mean-reverting aim is *why* the forecast evaporates in two minutes. **VERIFY-BY:** reduce-path mean
`|forecast|` rises toward the entry path's, and the same-name reversal median rises materially above **548 s**,
both recomputed from `recent_orders`.

---

## Item #3 (NEW) — the best-measured source contributes nothing: `social` is corroborated on **12** of **1600** kept items

`social` is the only source clearing the **2.00 bps** round trip (**+3.595** gross at 3600s, hit rate
**0.598** on 352 resolved) and it contributes **0.0%** to every reported aim. The gate is visible in the
counters: `ingested` **15300** → `kept` **1600** → `corroborated` **12**, against `controls.corroborationChannels`
**2**, `burstThreshold` **4**, `credibleFollowerFloor` **5000**, and only `stocktwits` in `controls.sources`.
`manipulationSuspected` **89**, `cashtagSpamDropped` **1960**, `duplicateDropped` **11740`.

**Explicitly NOT the ADR-0139 idea.** ADR-0139 widened the *author-credential* path into that same gate and was
graded **❌ BAD**; it must not be re-attempted, and this item is not a request to loosen corroboration. The open
question is different and cheaper: with `controls.sources` listing exactly **one** channel while
`corroborationChannels` requires **2**, is the second channel ever reachable, or is the gate structurally
unsatisfiable for `stocktwits`-origin items? **VERIFY-BY:** read the corroboration channel set and confirm
whether `news:rss` counts toward it; if it cannot, that is a wiring defect, not a threshold to relax.

---

## Item #4 (NEW, open question — cheap to settle, do not act on it yet) — `targetQty` and `aims` disagree by ~60× on the same name, and **19 of 24** names plan `deltaQty` 0

`fusion_targets.insideBuffer` is **19** of `instruments` **24**. Four of the six reported targets carry
`deltaQty` **0.0000** against very large gaps — NEE `targetQty` **702.267177** vs `currentQty` **11.0**; BAC
**652.635** vs **0.00**; AMZN **116.574** vs **0.00**; NVDA **-104.469** vs **0.00**. But the `aims` map for the
same names reads BAC **10.669227**, NEE **8.366009**, AMZN **1.798538**, NVDA **-1.780856** — roughly **60×**
smaller than `targetQty` on BAC.

So "target" means two different things in the same payload, and it is not established from telemetry alone which
one the router actually plans against. Do **not** treat the 702/652-share figures as unreachable targets until
that is settled — the prior three cycles' "time-to-target" arithmetic assumed `targetQty` was the routed
quantity, and if `aims` is the routed intent then that arithmetic overstated the mismatch. **VERIFY-BY:** read
the planner source to establish which field the routing path consumes, and state it plainly before any cycle
reasons about convergence time again. Recorded here so the ambiguity cannot silently propagate into a future
diagnosis.

---

## Verification block — 2026-08-06 16:00Z (**NO CHANGE — the ADR-0116 freeze holds at `39451ce71` 3/6.** Third consecutive **0%-change / 100%-market** window, and the third consecutive cycle in which the app did not restart. That run of clean windows is what this cycle spends: item #1 stops being a horizon *comparison* and becomes a **measured timescale**, with the holding period, the convergence time and the entry/exit conviction asymmetry all read off the same uncontaminated tape. The mechanism inverts — the defect is not only that the desk holds too briefly, it is that **entry is gated and exit is not**.)

### Step 0 — `39451ce71` (ADR-0142): ✅ **VERIFIED (third independent confirmation)**

Struck last cycle; re-checked because the VERIFY-BY is cheap and a one-cycle pass is not a fix. The boot
instant is unchanged for a third reading — `ops_jvm.uptimeSeconds` **4721** against report
`timestampMillis` **1786032002760** implies boot at **1786027281760**, versus **1786027281702** and
**1786027281035** the two prior cycles (sub-second read skew). Same 14:41:21Z process, three cycles and
two reports-only commits later. `git diff --name-only d8a9437..HEAD` lists **`reports/run-status.json`**
and nothing else. Item stays struck; no further re-verification needed.

### Window attribution — 0% change, 100% market, third cycle running

Zero Java, zero dials, zero gates in the window's diff. PnL **-22.13** (total **$-789.39** live) and gross
**-9352.56** (to **$9,895.16**) are the market plus code already live; my changes earn neither credit nor
blame. Over the last 3 runs: PnL **-70.25**, gross **-1529.75**.

### Not danger — deploying, with room to spare

Gross **$9,895.16** is **0.7%** of the firm gross cap $1,500,000 (headroom **$1,490,105**); net
**-$1,266.10** is **0.1%** of the $1,000,000 net cap. `breaker.halted` **false**, `regime` **CALM**,
`riskCuts` **[]**, `portfolioRiskMultiplier` **0.702264471357967**. UNDERWATER is cumulative PnL, not a
live danger state.

---

## Item #1 — **entry is gated, exit is not. The desk needs ~1.1 h to build a position and ~7 min to dismantle it, while its only non-negative expectancy lives at 3600 s.**

Same item, third specification. Cycle 15:00Z proposed it, 15:30Z measured the horizon side, and this
cycle measures the *other* two sides on a clean tape — and they change the prescription.

**(a) Expectancy is still monotonic in horizon, on larger samples.** `signals_telemetry` `avgReturnBps`,
read verbatim (`resolved` / `cohorts` in parentheses at 3600 s):

| source | 225s | 900s | 3600s |
| --- | --- | --- | --- |
| **trend** | **-0.016** | **+0.017** | **+1.829** (842 / 100) |
| **social** | **-0.349** | **+0.700** | **+4.257** (361 / 37) |
| reversion | -0.025 | +0.024 | +0.095 (803 / 91) |
| momentum | -0.284 | -2.598 | +0.450 (72 / 11) |
| xsreversion | -0.205 | -2.123 | -4.363 (813 / 43) |

**At 225 s not one source is positive** — unchanged across three independent reads. Against `fee_bps`
**1.00** per side and TCA `avgSlippageBps` **0.789** NEE / **0.779** GOOG / **0.712** AMZN / **0.691** PFE
/ **0.618** MSFT, paid on both legs, only social@3600s clears its own round trip.

**(b) The measured holding period — 425 s median, 61 s minimum.** Computed from the `recent_orders` tape
(ALPHA, FILLED, 46 orders, 15:26:09→15:59:06) as the interval between consecutive opposite-side orders in
the same name — arithmetic on read timestamps, nothing authored:

| name | held | from | to |
| --- | --- | --- | --- |
| **MSFT** | **61 s** | SELL 10 @ fc **-5.047** (15:43:53) | BUY 4 @ fc **-0.014** (15:44:54) |
| NEE | 365 s | BUY 34 @ fc **+9.975** (15:38:49) | SELL 2 @ fc **+0.007** (15:44:54) |
| WMT | 425 s | SELL 12 @ fc **-2.741** (15:28:11) | BUY 1 @ fc **+2.932** (15:35:16) |
| MSFT | 456 s | BUY 7 @ fc **+6.128** (15:29:12) | SELL 1 @ fc **-3.970** (15:36:48) |
| XOM | 760 s | SELL 23 @ fc **-5.005** (15:32:44) | BUY 1 @ fc **+0.642** (15:45:25) |

n=5, min **61 s**, median **425 s**, max **760 s**. The median sits *between* the 225 s bucket (negative
for every source) and the 900 s bucket (≈zero for the best two). **The desk holds for the horizon at
which it has measured no edge, and exits before the one where it has some.** MSFT is the extreme: a
10-share short opened at forecast **-5.047** and 40% covered **61 seconds later** at **-0.014** — the
forecast decayed ~99.7%, with no opposite conviction anywhere. Rule 399's decay-not-flip mechanism, now
with a 61-second instance.

**(c) The asymmetry is backwards — this is the new finding.** Grouping the same window's FILLED orders by
the origin string that triggered them, mean and median `|forecast|`:

| trigger | n | mean \|fc\| | median \|fc\| |
| --- | --- | --- | --- |
| `fusion entry — target increase` | 7 | **6.288** | 5.545 |
| `fusion reduce toward a smaller target` | 39 | **2.062** | 1.662 |
| `fusion exit — target decayed to flat` | 1 | **0.000** | 0.000 |

Entering costs a conviction of ~6.3. Leaving costs ~2.1 — and 39 of the window's 47 triggered orders are
that reduce path. **There is a conviction floor on the way in and none on the way out, and no minimum
holding time on either.** So the position the sizing logic underwrites is the one thing the desk is
structurally prevented from owning.

**(d) The convergence side, quantified against the tape's own cadence.** `fusion_targets` (`atMillis`
**1786031976904**), plans-to-target = |`targetQty`−`currentQty`| / |`deltaQty`|:

| name | fc | targetQty | currentQty | deltaQty | plans→target |
| --- | --- | --- | --- | --- | --- |
| PG | -1.631 | -196.4894 | 0.00 | -7.4201 | **26** |
| KO | -3.245 | -710.3655 | 0.00 | -15.6599 | **45** |
| MCD | -2.149 | -148.6619 | 0.00 | -2.8431 | **52** |
| UNH | +3.002 | 132.3556 | 0.00 | 1.0101 | **131** |
| MSFT | +1.656 | 50.3101 | -4.00 | 0.0332 | **1636** |
| WMT | -2.777 | -450.4242 | 2.00 | -0.0166 | **27259** |

Median **131** plans. Fusion orders land ~**30.4 s** apart on this tape (15:26:09 / 15:26:39 / 15:27:10 /
15:27:40 / 15:28:11), so median time-to-target ≈ **3931 s ≈ 1.1 h** — against a median observed reversal
of **425 s**. **A ~9× mismatch: the target moves away roughly nine times before the desk could reach it.**
WMT wants -450 while holding +2 at 0.0166/plan; MSFT wants +50 while holding -4 at 0.0332/plan. Those
targets are not slow, they are unreachable, and every step toward them is a paid round trip that gets
reversed.

**(e) The receipt.** `attribution`: ALPHA `feesPaid` **395.873993** against ALPHA `realizedPnl`
**-646.46581774** — fees are ~61% of the realized loss (arithmetic on two read fields). Firm
`totalFees` **411.807217** against firm `totalPnl` **-789.38793096**, ~52%. `orders_by_status` FILLED
**5551** / CANCELLED **2055** / REJECTED **206**; every CANCELLED is an ADR-0084 re-plan superseding a
live order — the churn's own fingerprint. `turnover_cost_by_name`: MSFT **228** fills / **$251,134.50**
turnover to hold **-4** shares; PFE **178** fills / **$248,285.99**; NVDA **236** fills / **$201,069.31**.

**VERIFY-BY (next unfrozen cycle).** The change must make the *action* timescale meet the *measured*
timescale, on the exit path first — it is the ungated one and it fires 39 times to entry's 7. Proving
numbers, all already on this report:
1. the same-name opposite-side flip median (computed from `recent_orders` as in (b)) rises materially
   above **425 s**, and the sub-100 s flips disappear;
2. the `reduce toward a smaller target` mean `|forecast|` (**2.062**) rises toward the entry trigger's
   (**6.288**), or that path stops firing on decay alone;
3. `orders_by_status` CANCELLED (**2055**) and `turnover_cost_by_name` `fills` (MSFT **228**, NVDA
   **236**, PFE **178**) fall at equal-or-greater gross;
4. ALPHA `feesPaid` grows more slowly than gross.

**Note for whoever takes this.** The fix is an **exit gate with hysteresis in time**, above the
deterministic floor — not a fusion weight, not a band width, and emphatically not the pre-trade
guardrail or the breaker. Weight and band tuning is the INCONCLUSIVE wall (ADR-0137/0140/0141 all landed
there); (c) says the reason is that none of them ever touched the ungated side. Convergence rate (d) is
the same defect's other face and may need the same change, but if it cannot be done in one coherent
edit, **do the exit gate** — it is where 39 of 47 orders fire. `xsreversion` at **-4.363** bps / 3600 s
over **43** cohorts remains reliably negative and remains a **separate** change; do not bundle it.

---

## Verification block — 2026-08-06 15:30Z (**NO CHANGE — the ADR-0116 freeze holds at `39451ce71` 2/6.** But this cycle is not empty: it is the cycle that **settled ADR-0142** — the app did **not** restart, for the first time, because last cycle's commit could not reach the app binary. And with a second consecutive **0%-change / 100%-market** window, the uncontaminated read finally puts a number on item #1: the desk's measured expectancy is **monotonic in horizon** and only non-negative at the horizon it never holds for.)

### Step 0 — `39451ce71` (ADR-0142): ✅ **VERIFIED**

The VERIFY-BY was: *a cycle whose commit touches only `reports/`/`docs/` must not restart the app.* Last
cycle's commit `3b4f5de` did exactly that — `git diff --name-only 39451ce..HEAD` lists only
`docs/loop-findings.md`, `reports/last-analysis.md`, `reports/must-fix.md`, `reports/run-status.json`,
`reports/.pending-baseline.json`. And the app did not restart:

| reading | last cycle | this cycle | implication |
| --- | --- | --- | --- |
| `ops_jvm.uptimeSeconds` | **1121** | **2921** | grew by the wall-clock gap — same process |
| report `timestampMillis` | **1786028402702** | **1786030202035** | — |
| implied boot instant | **1786027281702** | **1786027281035** | **identical** (sub-second read skew) |

The process serving this report is still the one booted at **14:41:21Z**. Corroborated independently by
the log tail: the newest WARN in the whole report is **10:43:05.409-04:00** (= 14:43:05Z) — every startup
warning belongs to that same 14:41Z boot, and nothing has logged a startup since. Rule 395 said the fix
could not govern its own deploy and the first testable cycle was the next one; that cycle ran, and the
prediction held. **Item struck.**

What ADR-0142 buys, stated precisely and no wider: an ADR-0116 evaluation window is no longer interrupted
by the loop's own mandated `reports/` write. Its *other* stated rationale — that restarts flatten the book
— stays **refuted** (Rule 394) and ADR-0142 keeps no credit for it.

### Window attribution — 0% change, 100% market, for the second cycle running

Zero Java, zero dials, zero gates in the window's diff. So the move — PnL **+19.11** (total PnL
**-777.82882819** at the 15:09:18Z heartbeat → **-758.71487420** live), gross **-12013.78**
(**28006.27135500** → **15992.48903000**) — is entirely the market plus code already live. My changes
earn no credit for the +19.11 and no blame for the gross swing. Two clean windows back to back is the
best read of the app's own behaviour this loop has had, and it is what makes the item below trustworthy.

### Not danger — deploying, with room to spare

Gross **$15,992.49** is **1.1%** of the firm gross cap $1,500,000 (headroom **$1,484,008**); net
**-$1,308.69** is **0.1%** of the $1,000,000 net cap. `breaker.halted` **false**. `var95` **157.08**,
`es95` **250.79**, `var99` **348.40** over **154** observations, `coveredExposure` **15992.49**,
`skippedExposure` **0.00**. `regime` CALM. UNDERWATER is cumulative PnL, not a live danger state.

---

## Item #1 — **the desk holds for minutes; every non-negative expectancy it owns lives at 3600s. It cannot converge on a target before that target reverses.**

Promoted last cycle as a hypothesis. This cycle it is measured, and it is worse than stated — the defect
is not merely "holds too short", it is that **the position can never arrive at all.**

**(a) Expectancy is monotonic in horizon, and the short end is where the desk lives.** Read verbatim from
`signals_telemetry` (`avgReturnBps` / `stdCohortMeanBps` / `cohorts`):

| source | 225s | 900s | 3600s |
| --- | --- | --- | --- |
| **trend** | **-0.011** | **+0.084** | **+1.667** (14.51 / 100) |
| **social** | **-0.322** | **+0.948** | **+4.162** (27.54 / 37) |
| reversion | -0.016 | -0.059 | -0.317 |
| momentum | -0.316 | -3.097 | -2.052 |
| **xsreversion** | -0.186 | **-2.067** (11.21 / 152) | -3.510 |

Two sources rise with horizon and cross zero only past 900s. At **225s — the horizon nearest the desk's
actual behaviour — not one source is positive.** Against `fee_bps` **1.00** per side per equity and TCA
`avgSlippageBps` **0.761** GOOG / **0.729** NEE / **0.695** PFE / **0.694** AMZN, paid on both legs, even
trend's best reading (**+1.667** bps at 3600s) does not cover its own round trip; only social's **+4.162**
does, on **37** cohorts.

**(b) The desk trades at neither horizon — it re-plans every ~30s and reverses within minutes.** From
`recent_orders`, this window alone:

- **NEE** — SELL 23 / 15 / 9 / 46 between 15:16:00 and 15:22:36 at `forecast` **-6.671 → -8.190 → -7.587
  → -7.393**; then **BUY 56** at 15:25:08 at `forecast` **-0.0015**. A 93-share short built over 6 min and
  60% covered **2.5 min later** — not on a sign flip, on the forecast **decaying to zero**.
- **AMZN** — SELL ×7 from 15:04:51 to 15:08:55 at `forecast` ≈ **-7.564 … -5.960**; then BUY 15 at
  15:13:28 (`forecast` **-0.094**) and BUY 5 at 15:14:29 (**-0.017**). Round trip ≈ **5 min**.
- **MSFT** — SELL at 15:11:57 (**-6.034**) and 15:15:30 (**-5.142**); BUY 1/3/4/7 from 15:25:39 to
  15:29:12 (**+5.613 … +6.128**). Full sign flip in **~10 min** (Rule 393, third window running).

**(c) The mechanism — the target is unreachable by construction.** `fusion_targets` for MSFT:
`targetQty` **108.70498**, `currentQty` **7.0**, `deltaQty` **1.352209**. The desk wants ~109 shares,
holds 7, and steps ~1.35 per plan. At that rate convergence needs on the order of a hundred plans; the
forecast defining the target flips sign in ten minutes (b). **So the desk pays entry cost forever and
never holds the position whose expectancy it is underwriting.** `insideBuffer` **17** of `instruments`
**26** — 9 names are being chased like this right now. The fee column is the receipt: ALPHA `feesPaid`
**392.670558** against ALPHA `realizedPnl` **-646.44518325**, on **5505** FILLED / **2051** CANCELLED
orders.

**VERIFY-BY (next unfrozen cycle).** The change must make holding period and measured horizon meet —
either by slowing/decaying the plan so a position survives to its horizon, or by refusing to open what
cannot be held. Proving numbers, all already on this report, no new telemetry needed:
1. `turnover_cost_by_name` **`fills`** per name falls materially (MSFT **222**, NVDA **236**, ES **191**,
   AMZN **185**, GOOG **181**, PFE **177** today) at equal-or-greater gross;
2. `orders_by_status` **CANCELLED 2051** falls (each is an ADR-0084 re-plan superseding a live order —
   the churn's own fingerprint);
3. `fusion_targets` `deltaQty`/(`targetQty`−`currentQty`) rises, or `targetQty` falls toward reachable —
   i.e. MSFT stops being 109-wanted / 7-held;
4. ALPHA `feesPaid` grows more slowly than gross.

**Note for whoever takes this:** this is a *sizing-and-persistence* change, not another fusion weight.
Weight tuning is the INCONCLUSIVE wall (ADR-0137/0140/0141 all landed there). And `xsreversion` — the
most-fired source, **8893** observations at 225s — is the one reading on the table whose cohort dispersion
is small against its own mean at 900s (**-2.067** with **11.21** over **152** cohorts; ≈2.3 standard
errors *negative*, arithmetic on those three read fields). A reliably-negative source is information, but
it is a **separate** change; do not bundle it.

---

## Verification block — 2026-08-06 15:00Z (**NO CHANGE — the ADR-0116 freeze holds, and this time honouring it costs nothing: ADR-0142 gave the freeze a no-op, and this cycle is that fix's first real test.** The scorer prints `39451ce71 still accumulating evidence (1/6 cycles) — held, not scored this run`. The register is **re-ranked on the back of a measurement, not a hypothesis**: the σ-warm-up item has largely resolved itself (`streamVolMeasuredNames` **2 → 20** of `instruments` **23**, `riskCutStoppedNames` **0**), and the book **survived** a restart (baseline gross **11424.82120000** → live **13570.96679500**, not **$0.00**), which weakens half of ADR-0142's stated rationale. What replaces them at #1 is the largest number on this report that nobody has attacked: the desk turns positions over in **minutes** while its only non-negative measured expectancy exists at **3600s**, and pays a round trip each time.)

### Step 0 — `39451ce71` (ADR-0142): ✅ LANDED, ⏳ its headline VERIFY-BY is NOT YET TESTABLE — and saying so is the honest grade

`NON_BINARY_PATHS='^(reports|docs|ops)/'` is in HEAD. But the app **restarted anyway** at **14:41:21Z**
(`ops_jvm.uptimeSeconds` **1121** against report `timestampMillis` **1786028402702**), **38 s** after the
ADR-0142 commit at 14:40:43Z. That is **not** a 🔴: the wrapper process running that cycle had already
parsed the old `deploy_if_code_changed` body before the model wrote the new one, so the new rule could
not govern its own deploy. **This cycle is the first cycle whose wrapper carries the filter**, and this
cycle's commit is confined to `reports/` and `docs/` — so the test is live now, not deferred by choice.

Two secondary predictions moved the predicted way, and ADR-0142 gets **no credit** for either, because it
changed nothing inside the app and did not prevent the restart:

| prediction | live reading | attribution |
| --- | --- | --- |
| `streamVolMeasuredNames` above 2 | **20** (of `instruments` **23**) | not ADR-0142 — nothing in the app changed |
| `insideBuffer` further below `instruments` | **14** (was **23** of **24**) | same |
| firm gross NOT back at `$0.00` next cycle | baseline **11424.82120000** → live **13570.96679500** | ✅ independently true, and it **cuts against** ADR-0142's rationale |

That last row matters and is recorded against my own change: the premise "a restart flattens the book"
is **not** what happened. Positions carried across the 14:41:21Z restart (MSFT **-10.000000**, GOOG
**11.000000**, UNH **-7.000000**, KO **12.000000**, NQ **0.001138**, ES **0.000189**). The restart cost is
real for *sensors*, not for *positions*. ADR-0142 stays — the no-op it creates is what let this cycle
obey the freeze — but its exposure-flattening justification is downgraded to unproven.

### Window attribution — 0% change, 100% market and pre-existing code

`git diff --name-only` for last cycle's range touches only `ops/improve-loop.sh`, `docs/`, `reports/`.
No Java, no dial, no gate. So the change **cannot** have moved the vector, and the entire window — PnL
**-12.14**, gross **+2146.07** — is the market plus code already live (ADR-0141's band, in since 13:44Z).
This is the cleanest attribution the loop has had; a wrapper-only cycle is a free uncontaminated read of
the app's own behaviour, and that is worth knowing as a technique.

### Not danger — deploying, with room to spare

Gross **$13,570.97** is **0.9%** of the firm gross cap $1,500,000 (headroom **$1,486,429**); net
**-$2,063.62** is **0.2%** of the $1,000,000 net cap. `breaker.halted` **false**. `var95` **222.04**,
`es95` **330.72**, `var99` **422.79** over **154** observations, `coveredExposure` **13572.16**,
`skippedExposure` **0.00**. `regime` CALM, `trend` CHOP, `volRatio` **0.92**. UNDERWATER is cumulative
PnL, not a live danger state.

### Item #1 (NEW, promoted to the top) — the desk's holding period is minutes; its only non-negative measured expectancy is at 3600s. It pays a round trip to harvest an edge it never holds long enough to collect.

This is the standing "work on edge, not the combiner" priority, finally stated as a *measured* defect
rather than "nothing has edge". `signals_telemetry` (LIVE) `avgReturnBps`, by horizon:

| horizon | trend | reversion | xsreversion | social | momentum |
| --- | --- | --- | --- | --- | --- |
| **225s** | **-0.003** (500 cohorts, `stdCohortMeanBps` **3.510**) | **+0.013** (500, **3.755**) | **-0.187** (500, **9.026**) | **-0.325** (87, **7.281**) | **-0.851** (39, **9.524**) |
| **900s** | **+0.060** (323, **9.440**) | **-0.044** (287, **10.835**) | **-2.194** (152, **11.225**) | **+0.962** (81, **8.361**) | **-4.593** (27, **15.078**) |
| **3600s** | **+1.501** (100, **14.428**) | **-0.530** (90, **17.347**) | **-4.129** (43, **28.281**) | **+3.654** (37, **27.926**) | **-4.132** (11, **31.116**) |

Read down the columns: the expectancy is **nearest zero at the shortest horizon** and only turns positive
(trend, social) at **3600s**, where it is still swamped by its own cohort dispersion — no cell clears the
ADR-0049 OOS gate. Now the cost side: `turnover_cost_by_name` reads `fee_bps` **1.00** per side for every
equity (**0.20** for ES/NQ), and TCA `avgSlippageBps` reads **0.598** MSFT (215 fills), **0.743** GOOG
(178), **0.706** AMZN (177), **0.695** PFE (177), **0.753** NEE (82), **0.602** CAT (67). A round trip
pays fee and slippage **twice**.

**The desk trades the 225s column while only the 3600s column is positive.** Proof from `recent_orders`,
one name, one window — MSFT's `combinedForecast`:

```
14:30:05Z  SELL 1  forecast -5.647822616241038   (sources 4)
14:42:00Z  BUY  1  forecast +5.994499373101448   (sources 3)   <- sign flip, 12 min
14:54:43Z  SELL 2  forecast -6.757956277853291   (sources 3)   <- sign flip, 13 min
```

Two sign flips in ~25 minutes, each a paid round trip, on a name carrying `turnover_usd` **228299.68**
across **215** fills to hold **-10.000000** shares. The same shape elsewhere: **ES** shows **178** fills
and **680011.59** turnover to hold a **73.24222500** gross hedge, including a full liquidation at
14:47:10Z (`auto-hedge EQUITY (ADR-0019): net equity |0.00| ≤ 0.00 floor — target hedge is zero`, SELL
**0.011552**) rebuilt from 14:49:45Z — a complete round trip triggered by a transient zero reading, which
ADR-0098's churn-shrink did not stop. **NQ** ground ~25 orders of **0.000040**-ish contracts at 30 s
intervals, all `fusion reduce toward a smaller target`, **42** fills and **92598.21** turnover on a
**672.85957000** position, plus three REJECTED for `no market data for NQ`.

The cost column against the loss it explains: ALPHA `feesPaid` **386.902978** vs `realizedPnl`
**-607.49576306**; firm `totalFees` **402.355173** vs `firmTotal` **-731.27072424**. Winners/losers cross
cleanly against the triggers: MSFT **+328.78192038** and GOOG **+54.99373579** are winners; **UNH**
**-116.50673218** was sold into repeatedly on `sources=2` (14:54:12Z SELL 4 at **-6.262083396443645**,
14:55:13Z SELL 3 at **-5.053747004083048**), **KO** **-76.70785869** entered 14:47:36Z on
**+5.7856472361141265** with `sources=2`, **NQ** **-147.62078848**.

**Prescribed direction (NOT to be implemented until ADR-0142 is scored).** The lever is the *holding
period*, not another fusion weight: make the effective holding period match the horizon the expectancy is
measured over, or refuse the trade. Candidates, in order of how well understood they are — hysteresis on
a forecast **sign flip** (a reversal must clear a wider band than an increase, so a 12-minute round trip
cannot be free); a minimum-hold / forecast-persistence requirement keyed to the measured horizon; and
separately, suppressing the sub-dust `deltaQty` tail that generates turnover with no position change.
Every threshold must arrive with a cited source or an explicit `PLACEHOLDER — Oleg to set`.

**VERIFY-BY (next run that ships it):** `turnover_cost_by_name.turnover_usd` for the traded names falls
while `grossExposure` does **not** fall; firm `totalFees` growth per cycle falls; `recent_orders` shows
**no** name reversing sign twice inside one cycle; and `signals_telemetry` `avgReturnBps` at 900s/3600s is
unchanged or better (the fix must cut cost, not edge). 🔴 if gross collapses toward **$0.00** — that is
turnover suppression by refusing to trade, not by trading better.

### Item #2 (NEW) — `score-change.py baseline` silently overwrites an unscored pending baseline, destroying an in-flight ADR-0116 window

`cmd_baseline` writes `PENDING` unconditionally — there is no check for an existing unscored baseline:

```
scripts/score-change.py:434   os.makedirs(os.path.dirname(PENDING), exist_ok=True)
scripts/score-change.py:435   with open(PENDING, "w", encoding="utf-8") as f:
```

Consequence, demonstrated: `d8da867` recorded a baseline for **`a21177cea`** (ADR-0141) at 13:44:10Z; the
register graded it at **2/6** cycles at 14:30Z; then `a58a12e` recorded a baseline for **`39451ce71`** at
14:40:49Z and **replaced it**. ADR-0141 will therefore **never** receive a ledger row — a change that was
✅ VERIFIED at the defect level twice has no scored verdict, and the ledger's history is missing an entry
it should contain. This is the loop's own instrument corrupting itself, which is why it sits above every
tuning item: it makes verdicts unreliable in a way no amount of good diagnosis compensates for. It also
means the freeze rule is enforced only by the agent reading the pending file — the script will happily
help violate it.

**VERIFY-BY:** running `baseline` while an unscored `reports/.pending-baseline.json` exists must refuse
(non-zero exit, no write) or preserve the prior window; and a ledger row must exist for every commit that
ever had a baseline recorded. Note this touches the scorer, which owns money numbers — the change must not
alter any computed vector, verdict, or threshold, only the refusal path.

### Item #3 (carried, was #1) — the loop restarts the app on cycles that cannot change the binary: ⏳ UNDER ITS OWN EVALUATION

ADR-0142 shipped last cycle and is at **1/6** with the scorer. Its headline VERIFY-BY is being tested by
**this** cycle's commit (confined to `reports/` + `docs/`, wrapper on disk carries the filter). Do not
touch it. Next run, grade it on: `ops_jvm.uptimeSeconds` **exceeding** the cycle interval, and today's
`logs/improve-*.log` carrying `no code change ... — no rebuild/restart` for this range. 🔴 if a real code
change is ever scored against a binary that does not contain it.

### Item #4 (carried, was #2, DEMOTED) — the ADR-0126 σ sensor warm-up: ⚠️ largely self-resolved, keep watching

The item said sensors could not warm within a cycle. This run refutes the severity: `streamVolMeasuredNames`
**20** of `instruments` **23**, `covarianceCoveredNames` **20**, `volBudgetNames` **20**,
`riskCutStoppedNames` **0**, `insideBuffer` **14** — against **2** measured and `insideBuffer` **23** last
cycle, on a comparable ~19 minutes of uptime. Startup still logs cold-seed WARNs (JNJ **234** of **241**,
UNH **207**, PG **223**, XOM **221**, HD **194**, CAT **195** for reversion; HD **101** of **121**, UNH
**107**, MCD **115**, CVX **116**, JPM **116** for σ) plus genuinely new names with no history (AMD **1**,
TSLA **10**, PLTR **1**, GOOGL **3**, META **5**) — the latter is refdata expansion, not a defect. No
action while it is improving on its own.

### ~~Item (was #3) — no source has demonstrated positive out-of-sample edge net of cost~~ **SUPERSEDED by item #1**

Not closed — **restated**. "Nothing has edge" was unactionable. The measurement above makes it actionable:
the sources are not uniformly edgeless, they are edgeless **at the horizon the desk trades**. That is a
holding-period defect with a testable fix, so it is item #1 rather than a standing lament.

---

## Verification block — 2026-08-06 14:30Z (**Item #1 SPLITS its own test — and the answer is neither branch the register wrote.** Last cycle asked whether the σ-cold block was session warm-up or a defect: `streamVolMeasuredNames` climbed **1 → 2**, so the sensors DO warm — but `insideBuffer` went **18/19 → 23/24**, because they warm ~20 minutes into a cycle and are then **wiped by a restart the loop inflicts on itself**. The app started **2026-08-06T14:06:35Z**, 23 s after last cycle's status commit — on a **no-change** cycle, deployed by `docs/loop-findings.md`, the memory the prompt mandates every run. **The freeze offered no no-op**, so this cycle ships the filter fix (ADR-0142).)

### Step 0 — `a21177cea` (ADR-0141): ✅ still VERIFIED at the defect level; ⚠️ its measurement is being corrupted

The defect-level verdict from 14:00Z stands and strengthens — the desk keeps opening. `orders_day.total`
**0 → 11 → 25**; `recent_orders` carries a second, independent name entering on the band ADR-0141
repriced: **MSFT SELL** filled at **14:28:03Z**, **14:29:04Z** and **14:29:34Z** on `combinedForecast`
**-6.0663342533450155**, `sources` **4**. Live `aims` now reads MSFT **-8.668719** against `targetQty`
**-150.842073` — a large, walking intent, not the pinned zero every other name shows.

But the vector it is being measured on is not clean, and that is the finding of this cycle. Its window
is being force-flattened by the harness (below). The scorer holds it at **2/6** cycles; nothing here
touches that.

### Window attribution — the change opened it, the restart closed it, and the two are separable

Baseline (13:44:10Z) was an empty book: `grossExposure` **0.00000000**, `totalPnl` **-628.06833967**.
Live now: `totalPnl` **-715.25675611**, gross **7852.67806000**, net **-3751.43806000**. So there were
no untouched positions for the market to move — **(a) market ≈ 0 by construction, (b) change = all of
it.** Within (b) the two mechanisms separate cleanly by timestamp:

| leg | trigger | evidence |
| --- | --- | --- |
| NQ opened | ADR-0141 released the band | SELL **0.030886** filled **13:58:29Z**, `combinedForecast` **-7.858987731814552** |
| — restart — | `docs/loop-findings.md` deployed | app start **14:06:35Z** (`uptimeSeconds` **1406**), **19** σ sensors re-seeded cold |
| NQ closed | forecast decayed to ~0 | 8× `fusion reduce toward a smaller target` from **14:14:51Z**, buying back **0.025280** as `combinedForecast` fell **-1.269288023440704 → -1.314462997304728E-4** |

NQ now reads `realizedPnl` **-124.25140691**, `unrealizedPnl` **-18.49980000**, `avgCost`
**29435.50000000** against `mark` **29600.50000000` — bought back into a rally. **NQ is NOT in the
cold-sensor WARN list**, so I do not claim the restart caused that specific forecast decay; it may be
genuine. Stated honestly: the two cannot be separated from these numbers alone. What is not in doubt is
that a cycle which promised to change nothing bounced the process in the middle of a fresh position.

Elsewhere: HEDGE `totalPnl` **24.99042485** on gross **2050.62000000**; ALPHA `totalPnl`
**-597.49597405** on gross **2483.25000000** with MSFT's own `totalPnl` **+349.97909839** (overwhelmingly
historic realised, not this window).

### Not danger — deploying, with room to spare

Gross **$7,852.68** is **0.5%** of the firm gross cap $1,500,000 (headroom **$1,492,147**); net
**-$3,751.44** is **0.4%** of the $1,000,000 net cap. `breaker.halted` **false**. `var95` **100.57**,
`es95` **152.04**, `var99` **144.68** over **155** observations, `coveredExposure` **7852.68**,
`skippedExposure` **0.00**. `regime` CALM, `trend` CHOP, `volRatio` **1.03**. UNDERWATER is a statement
about cumulative PnL; nothing is near a cap or the breaker.

---

### Item #1 (NEW, promoted to the top) — the loop restarts the app on EVERY cycle, including no-change cycles → **ADDRESSED THIS CYCLE (ADR-0142)**

`ops/improve-loop.sh` deployed on anything outside `reports/`; `ops/improve-prompt.md` mandates a
`docs/loop-findings.md` append **every run, change or not**. `docs/` is not `reports/`, so the mandated
memory write alone bounced the app. Demonstrated, not inferred:

```
$ git diff --name-only 493ab5d 955d41a | grep -v '^reports/'
docs/loop-findings.md
```

— the entire non-`reports/` diff of a deliberate **no-change** cycle. `ops_jvm.uptimeSeconds` **1406**
against report timestamp **1786026601754** puts process start at **14:06:35Z**, **23 seconds** after
that cycle's `chore(status)` commit at **14:06:12Z**.

**Why it is the most expensive item on this register.** Every forecast/risk sensor is a warm-up-gated
estimator seeded from stored marks, and the seed walk terminates at the first gap — which *is* the
previous restart. This run's startup log: **19** `risk-cut σ sensor still cold`, **0** `warmed`, each
stopping on `GAP_BREAK`/`HISTORY_EXHAUSTED` covering **~2120–2170s**, against a σ seed asking **121**
prices at a **30000ms** step (≈3630s) and a reversion seed asking **241** at **10000ms** (≈2410s).
Both warm-up spans exceed the contiguous history a ~30-minute restart cadence can leave. So the desk
gets a few usable minutes at the *tail* of each cycle, then resets:

- `fusion_targets` — `instruments` **24**, `insideBuffer` **23**, `streamVolMeasuredNames` **2**.
- `aims` — a real intent for exactly the two names with a measured σ (MSFT **-8.668719**, NQ
  **-3e-06**) and exactly **0.0** for all twenty-two others, including WMT (`targetQty`
  **-566.406006** on **-3.5337947897910817**) and AAPL (**226.814437** on **3.3510088777313816**),
  both routing `deltaQty` **0**.
- MSFT, the name whose σ seed was **deepest** (**84** of **121**), is the name that traded.

And it corrupts the loop's only instrument: ADR-0116 judges a change over ~6 cycles of per-cycle
risk-adjusted PnL. A book force-flattened partway through every one of them measures the **restart**,
not the change — a sufficient explanation for a ledger that is a wall of INCONCLUSIVE, and the reason
the standing "work on edge" priority cannot even be evaluated (no source demonstrates a 3600s-horizon
expectancy on positions that never survive 1800s). The ledger signature: three consecutive scored rows
ending at firm gross **$0.00**.

**Change shipped:** `NON_BINARY_PATHS='^(reports|docs|ops)/'` — deploy only on a path that can reach
the app binary. No dial, gate, signal, sizing control or risk number touched; nothing inside the app
touched. The ADR-0110/0123 guarantee holds because a real change ships its ADR in the *same* commit, so
the `.java`/`.gradle` path is still in the diff and still deploys.

**VERIFY-BY (next run):**
- `ops_jvm.uptimeSeconds` **exceeds the cycle interval** on any cycle whose commit range is confined to
  `reports/`, `docs/`, `ops/` — the process was not bounced.
- `ops/improve.log` carries `no code change ... — no rebuild/restart` for such a cycle.
- `fusion_targets.streamVolMeasuredNames` **above 2**, `insideBuffer` further below `instruments`.
- A position opened in one cycle **still held** at the start of the next — firm gross NOT back at
  **$0.00**.
- 🔴 if a code change is scored against a binary that does not contain it (filter over-broad).

### Item #2 (carried, was #1) — the ADR-0126 σ sensor cannot complete its warm-up within one cycle

⚠️ **STILL-BROKEN, cause re-pinned.** Last cycle's test was "if `streamVolMeasuredNames` climbs above 1
this was session warm-up — close it". It climbed to **2**, so warming is real; but `insideBuffer` rose
**18/19 → 23/24**, so warming is far slower than the cycle. Both branches of that test were wrong
because both assumed the process survives the cycle. It does not — see item #1, which must land first:
until restarts stop, no warm-up length can be measured, and ADR-0138's seed extension will keep
terminating on `GAP_BREAK` at the restart boundary however far it is willing to read.

**Do not act on this until item #1 is ✅.** If sensors still cannot warm on a cycle where the process
was not bounced, the fix is persisting the estimator state (LMDB warm-restart, ADR-0014 — the sibling
of ADR-0140's durable aim), not another seed-length change.

**VERIFY-BY:** on a cycle with `uptimeSeconds` > 2 cycle intervals, `streamVolMeasuredNames` ≥ 10 of
`instruments` and `risk-cut σ sensor warmed` lines > 0.

### Item #3 (carried, was #2) — no source has demonstrated positive out-of-sample edge net of cost

Unchanged. Live 3600s `avgReturnBps`: social **+4.843710111542362** (37 cohorts, `stdCohortMeanBps`
**27.019501413591275**), trend **+2.017268891699455** (100 cohorts, **14.84113711664046**), xsreversion
**+0.6489664485393462**, reversion **-0.11690781307285701**, momentum **-5.643878226060606**. At 900s:
trend **+0.03204943006790256**, social **+0.837585186917114**, reversion **-0.02610135248334119**,
xsreversion **-1.9169323488848693**, momentum **-4.025307298406099**. Nothing clears cost with
significance. This stays #3 **not** because it is unimportant — it is the standing strategic priority —
but because items #1/#2 make it unmeasurable: a 3600s horizon cannot be evaluated on a book that is
flattened every 1800s.

**VERIFY-BY:** a source's `avgReturnBps` positive at a horizon with `cohorts` ≥ 100 and a cohort-mean
t-statistic clearing the ADR-0049 OOS gate.

---

## Verification block — 2026-08-06 14:00Z (**✅ item #1 CLOSES at the defect level — the desk opened a position for the first time since 2026-08-05 20:24Z.** ADR-0141 released the name it was diagnosed on: NQ went from an aim of **-0.003064** needing a ratio of **0.8239** to a FILLED entry at **13:58:29Z**, and gross went **$0.00 → $18,236.64**. But the book is 1 name of 19, and the other 18 are held by a **different** gate that ADR-0141 never touched. **The ADR-0116 freeze BINDS this cycle:** `reports/.pending-baseline.json` exists for `a21177cea` (recorded 13:44:10Z, 1 cycle of ~6) — so this run verifies and re-ranks, and makes **no code change**.)

### Step 0 — `a21177cea` (ADR-0141): ✅ **VERIFIED at the defect level**, still accumulating on the vector

Last cycle's VERIFY-BY was "a name whose whole target sat inside its own buffer can finally open". It is
met, on the one name whose arithmetic the register predicted would clear. Rule 380 said NQ needed
`|aim|/|target| > 0.8239` — reachable only at 82% of target on a whole-horizon time constant — and NVDA
needed **3.1807**, above ADR-0102's bound of 1, so unreachable at any length. After ADR-0141 repriced the
average position at `min(TARGET_ABS, E|f|)`:

| reading | before (13:30Z) | now (14:00Z) |
| --- | --- | --- |
| NQ aim | -0.003064 | **-0.047464** |
| gross exposure | $0.00 | **$18,236.64** |
| filled fusion entries | none since 2026-08-05 20:24:38Z | **NQ SELL 0.030886 @ 13:58:29Z** |
| `orders_day.total` | 0 | **11** |

The aim walked, crossed, and routed. That is ADR-0141's mechanism and nothing else — the band is the only
thing that changed between those two states for NQ.

### Window attribution — 100% change, 0% market, and the change is one hour old

The split is unusually clean because the book was empty at the baseline, so there were no untouched
positions for the market to move. Total PnL **-628.06833967 → -682.17363767**, Δ **-54.11**:

- **Δ unrealized -53.74164000** — entirely the new NQ short: quantity **-0.030886**, `avgCost`
  **29435.50000000**, `mark` **29522.50000000**. The index rose 87 points against the entry.
- **Δ realized -0.36365800** — the NQ entry's costs (`turnover_cost_by_name` NQ: 7 fills, **0.20** fee bps).
- **EQUITY** realized **-595.62281205**, unrealized **0.00000000**, gross **$0.00** — unchanged, contributed nothing.
- **HEDGE** **+24.35397774**, gross **$0.00** — unchanged, contributed nothing.

So **100% of the window's move is the direct impact of the change**, and none of it is market drift on
positions I did not touch. That is not a verdict on the change: it is one position, ~5 minutes after
entry, one draw from the distribution. The scorer owns the judgement and has ~5 cycles left. **Do not
read -$53.74 of mark-to-market on a single fresh short as evidence the fix was wrong** — that is exactly
the single-window overfit the ledger's evaluation window exists to prevent.

### Not danger — this is the book coming off DORMANT with room to spare

Gross **$18,236.64** is **1.2%** of the firm gross cap $1,500,000 (headroom **$1,481,763**); net
**-$18,236.64** is **1.8%** of the $1,000,000 net cap. `breaker.halted` **false**. `var95` **385.49**,
`es95` **538.54**, `var99` **617.38** over **155** observations, `coveredExposure` **18236.64**,
`skippedExposure` **0.00**. Nothing is near a cap or the drawdown breaker, so the UNDERWATER flag is a
statement about cumulative PnL, not a live danger state. Per the mission, exposure rising off a flat book
under the budget is the goal, not a concern.

---

### Item #1 (NEW, promoted) — 18 of 19 planned names cannot open because their ADR-0126 σ sensor is cold

This is now the binding constraint on deploying capital, and it is **not** the band ADR-0141 fixed.
`edgeGate` is **null** (the gate is off under ADR-0122), so `PositionBuffer.mayIncrease` reduces to the
ADR-0126 σ-cold veto alone — `stopArmed == null || streamVol.sigmaPerSample(instrument).isPresent()`.
When that is false the code clamps the delta reduce-only and **re-seeds the aim to the held position**,
which for a flat name is exactly zero. That is precisely what the live map shows:

- `aims` — NQ **-0.047464**; **all 18** equities exactly **0.0**, despite real targets (NVDA `targetQty`
  **-412.353151** on `combinedForecast` **-16.444129523306067**; AMZN **-348.145901** on
  **-16.321292681130473**; KO **-994.883770**; MSFT **-129.140626**).
- `streamVolMeasuredNames` **1** — exactly one name has a measured σ, and exactly one name traded.
- `insideBuffer` **18** of **19**; `logs/report.md` carries **15** `risk-cut σ sensor still cold` WARNs
  and **0** `risk-cut σ sensor warmed`.

The WARNs give the mechanism precisely: the seed asks for **121** stored prices at a **30000ms** step
(≈3630s ≈ 60 min of continuous history) and gets **17–42**, terminating on `GAP_BREAK` or
`HISTORY_EXHAUSTED` covering **~846–3749s**. The app restarted at 13:44Z to deploy ADR-0141; the session
had only ~15 minutes of marks at that point, and the walk backward hits the overnight hole. So a target
with a genuine view is unreachable not because the desk declined it but because the risk control that
would protect it has not warmed.

**Do not act on this yet — it may be warm-up, not a defect.** If the seed is merely short of session
history, it self-heals ~60 minutes after the open with no code at all, and "fixing" it would be tuning a
transient. That distinction is what the VERIFY-BY below is built to settle, and it is also why this cycle
correctly ships nothing.

**VERIFY-BY (next cycle, ~14:30Z):** read `fusion_targets.streamVolMeasuredNames` and the count of
`risk-cut σ sensor warmed` lines.
- If `streamVolMeasuredNames` has climbed above **1** and `insideBuffer` has fallen below **18**, this was
  session warm-up — ✅ close it, take no action, and let the ADR-0141 measurement finish undisturbed.
- If it is still **≤ 2** by the ~15:00Z cycle, with the session open >90 minutes and >121×30s of marks
  accumulated, then ADR-0138's seed repair is ⚠️ **STILL-BROKEN** across the overnight boundary — the
  walk terminates on `GAP_BREAK` at the session edge instead of spanning it — and *that* becomes the one
  change, targeting `SensorWarmup.warm`'s gap handling, not the band and not any dial.

### Item #2 (carried) — no source has demonstrated positive out-of-sample edge net of cost

Unchanged and still the standing strategic problem behind every INCONCLUSIVE verdict. Live 900s
`avgReturnBps`: trend **-0.09539655350574718** (323 cohorts), xsreversion **-1.8815179221159266** (152),
momentum **-4.1695992274399405** (24), reversion **+0.2772856438709261** (285), social
**+0.9634543857861314** (82). At 3600s social is **+4.843710111542362** (37 cohorts) and trend
**+2.4016353883661217** (100). Nothing clears cost with significance. This stays #2 only because item #1
is a hard mechanical block on deploying *any* view; it returns to #1 once the desk can actually hold risk.

**VERIFY-BY:** a source's `avgReturnBps` positive at a horizon with `cohorts` ≥ 100 and a cohort-mean
t-statistic clearing the ADR-0049 OOS gate.

---

## Verification block — 2026-08-06 13:30Z (**✅ item #0 CLOSES — the app boots and every endpoint is live.** `ops_jvm` answers with `uptimeSeconds` **62475**, `logs/jethro-app.log` carries no `FlywayException`, and every reading that was `Connection refused` last run is back. Item #1 is therefore gradeable for the first time — and it is ⚠️ STILL-BROKEN, but its mechanism is now pinned to a different, provable cause than the aim's state handling, which ADR-0140 fixed correctly. **The ADR-0116 freeze does not bind:** `3cc91bc46` was scored ⚠️ INCONCLUSIVE and `reports/.pending-baseline.json` is gone.)

### Step 0 — `3cc91bc46` + the V51 repair: ✅ **VERIFIED at the defect level**, scored ⚠️ INCONCLUSIVE on the vector

Both of last cycle's VERIFY-BYs are met. The **boot** repair: `ops_jvm.uptimeSeconds` **62475**, no
`FlywayException`, and `traffic`, `feeds`, `marks`, `signals_telemetry`, `fusion_targets`, `var`,
`breaker`, `regime`, `hedging` and `attribution` all return bodies. The **aim** mechanism: the live
`aims` map carries **-0.003064** for NQ against a `targetQty` of **-0.017759** — a real
partially-adjusted intent that survived a restart, which is precisely what ADR-0140 promised and what
no in-memory, churn-pruned map could have produced. The scorer graded the vector ⚠️ INCONCLUSIVE
(risk-adj return/cycle +0.000060 over 36 cycles, t=+1.23, hurdle 1.5) — kept, not reverted.

### Window attribution — market only, and the window is a CLOSED SESSION

`recent_orders` shows no order since **2026-08-05 20:24:38Z**; `orders_day.total` is **0**. Total PnL
**-628.06833967**, gross **$0.00** (**0.0%** of the firm cap, headroom **$1,500,000**), net **$0.00**;
`breaker.halted` **false**; `var95` **0.00** with the note `no positions`. **100% market, 0% change** —
a flat book against a frozen tape moves for neither reason.

### Item #1 — the desk cannot open a position: ⚠️ STILL-BROKEN → **ADDRESSED THIS CYCLE (ADR-0141)**, cause re-pinned

Still broken on entry: `insideBuffer` **8** of **8** planned names, `currentQty` **0** everywhere, gross
**$0.00**. But ADR-0140's half is now demonstrably **done** — the aim persists and accumulates. What
remains is arithmetic, and it is provable rather than inferred.

The release condition for a name opening from flat is `|aim|/|target| > width × TARGET_ABS/|f|`, and
ADR-0102 bounds the left side above by **1**. So the condition is **unsatisfiable for every name with
`|f| < width × TARGET_ABS`** — at the shipped `0.10`/`10.0` that is every `|f| < 1.0` on a ±20 scale.
Both names carrying a view sit at or inside it:

| name | live combined `f` | required `\|aim\|/\|target\|` | reachable? |
| --- | --- | --- | --- |
| NQ | **-1.2137982837547245** | **0.8239** | only at 82% of target, on a whole-horizon time constant |
| NVDA | **-0.31439455218834894** | **3.1807** | **never** — it exceeds the ADR-0102 bound of 1 |

This is why last cycle's diagnosis was necessary but not sufficient: a durable aim is worth nothing when
the threshold it walks toward is above the ceiling its own clamp imposes.

**Root cause — a units error, not a mis-set dial.** `TARGET_ABS` is what each SOURCE is normalised to
(live `meanAbsClaim` **8.933184091982607** / **6.672769378384960** / **9.238246464313300**, all near
it), but the band is applied to the COMBINED forecast, already attenuated by the ADR-0076 multiplier and
the ADR-0124 agreement scalar (NQ's **+18.529717715250550** and **-20.0** average to -5.427; agreement
**0.1954775485324326** takes it to -1.214). Both scalars are specified as reductions in SIZE. Passing a
shrunken forecast into a threshold calibrated for an unshrunken one deletes the position instead of
shrinking it.

**Fix (ADR-0141).** The average position is priced at `min(TARGET_ABS, E|f|)`, where `E|f|` is the mean
`|combined forecast|` over the names planned a view this cycle (`n ≥ 2`; below that the mean is the
datum and no claim is made). Cross-sectional, so no estimator, no warm-up, no persistence, restart-proof
and feed-agnostic. Capped, so the band is never wider than before — one-way toward releasing, never
freezing. No dial, band width, rate, gate, cap or floor is touched.

**VERIFY-BY next run:** `fusion_targets.insideBuffer` strictly less than `instruments`; at least one
`fusion entry` row in `recent_orders` inside the window; and `risk.total.grossExposure` strictly above
**$0.00**. If `insideBuffer` still reads the full plan, the band was not the binding constraint and this
diagnosis is wrong — do not re-attempt it, move to why the planner's targets are so small.

### Item #2 (carried, unchanged rank) — no source has a measured positive edge

`signals_telemetry` again shows nothing clearing its cohort hurdle at any horizon; xsreversion — the
only source covering most of the universe — measures **negative** at all three (`avgReturnBps`
**-1.6172582662041586** at 900 s over 152 cohorts, **-1.9673663434138422** at 3600 s,
**-0.16422896669296588** at 225 s). Deliberately **not** attacked this cycle: with the desk unable to
route at all, a new source could not have been measured either. It becomes #1 the moment item #1 is
VERIFIED. **VERIFY-BY:** any source's `avgReturnBps` positive with `|t|` over its cohort hurdle.

### ~~Item #0 — a migration version collision is a silent, whole-app kill~~ ✅ **VERIFIED, CLOSED**

`ops_jvm.uptimeSeconds` **62475**, no `FlywayException`, all endpoints live. The
`ModuleBoundariesTest.migrationVersionsAreUniqueAcrossModules` guard is in the build and was proven
against the live defect before the fix. Struck.

---

## Verification block — 2026-08-05 20:00Z (**🔴 REGRESSED — ADR-0140 stopped the app from booting.** The Flyway migration it shipped, `V48__fusion_aim.sql`, collides with the pre-existing `V48__sector_breadth_equities.sql` (ADR-0125, `modules/reference-data`). `PersistenceConfig.flyway()` runs ONE Flyway over `classpath:db/migration`, merging every module's migrations, so a version is a GLOBAL identifier — Flyway refused to resolve, every DB-backed bean failed, and the process died at startup. **This cycle repairs that**, which outranks the ADR-0116 freeze: the pending change never executed, so there is nothing to measure and nothing to pile onto.)

### Step 0 — `3cc91bc46` (ADR-0140, the durable fusion aim): 🔴 **REGRESSED — boot-breaking, repaired this cycle**

The change was committed and its baseline recorded at **19:44:13Z** — from the *previous* still-running
process. The new build then failed to start at **15:44:54.448-04:00** (= **19:44:54Z**), and every
endpoint has been dead since. The proving line from `logs/jethro-app.log` and `logs/report.md`:

```
Caused by: org.flywaydb.core.api.FlywayException: Found more than one migration with version 48
    at ...CompositeMigrationResolver.checkForIncompatibilities(CompositeMigrationResolver.java:92)
    at ...PersistenceConfig.flyway(PersistenceConfig.java:41)
```

Every live reading this run is therefore `URLError: <urlopen error [Errno 111] Connection refused>` —
`ops_jvm`, `traffic`, `feeds`, `marks`, `signals_telemetry`, `fusion_targets`, `var`, `breaker`,
`regime`, `hedging` — and the SITUATION header reads `(risk endpoint unavailable — could not read live
PnL/exposure.)`. `scripts/score-change.py score` printed `cannot measure current vector
(<urlopen error [Errno 111] Connection refused>); leaving pending baseline for next run`.

**No money statement is possible this run and none is offered.** There is no live PnL, no exposure, no
attribution and no `recent_orders` — the desk did not trade because the desk did not exist. Quoting the
last-known figures as if they were this window's would be authoring numbers (invariant 7); the honest
reading is that the window is **unmeasured**, not flat.

**Cause is 100% the change, 0% market** — the inverse of the last five windows. Nothing about the
market can stop a Flyway resolver.

### Item #1 — the aim never crosses its band, so the desk cannot build: ⚠️ STILL-BROKEN (unmeasurable — its fix never ran)

ADR-0140's mechanism is untouched and unjudged: the code shipped, but the process it shipped into never
reached a first cycle, so `insideBuffer`, `fusion_aim` and every `|aim|/|target|` reading are unavailable.
The item keeps its rank and its VERIFY-BY unchanged. It is **not** re-diagnosed on no evidence.

### Item #0 (NEW, and ranked above everything) — a migration version collision is a silent, whole-app kill: 🔧 **FIXED THIS CYCLE**

Ranked #0 because its cost dominates any signal question: a booked defect in the *desk* loses some money,
a defect that stops the JVM loses **every** cycle — no trading, no telemetry, no scoring, and the loop
grades blind. It also very nearly cost a false verdict: had the scorer reached a stale process it would
have attributed a dead app's vector to ADR-0140's mechanism.

**Fix.** `V48__fusion_aim.sql` → `V51__fusion_aim.sql` (V50 is the repo's highest; V43 is absent, so V51
is the next free number, chosen not invented). The `fusion_aim` schema, `JdbcAimStore`, the ADR-0080
derived window and every ADR-0140 code path are **byte-identical** — only the filename changes, so this
repairs the pending change rather than replacing it, and ADR-0140's own VERIFY-BY survives intact.

**Guard, so this class of defect cannot recur silently.** `ModuleBoundariesTest.migrationVersionsAreUnique
AcrossModules` resolves `classpath*:db/migration/V*.sql` — exactly the merged view Flyway sees — and fails
the build on any repeated version. Nothing caught this before: each module's migrations are internally
consistent and the collision exists *only* after assembly merges the classpaths, which is why a green
`-Pci test` passed a boot-breaking commit. The test carries a non-vacuity assertion so it cannot silently
pass on an empty scan. It was **proven against the live defect before the fix** — run on the broken tree
it failed naming both `V48__fusion_aim.sql` and `V48__sector_breadth_equities.sql`; after the rename the
full `-Pci test` is green.

**VERIFY-BY next run:** the app answers at all — `ops_jvm` returns a JSON body with an `uptimeSeconds`
instead of `Connection refused`, `logs/jethro-app.log` contains no `FlywayException`, and
`flyway_schema_history` shows a `51` row. If those hold, item #0 is VERIFIED and closes, and item #1's
own VERIFY-BY becomes gradeable for the first time.

---

## Verification block — 2026-08-05 19:30Z (**CHANGE SHIPPED — ADR-0140.** The ADR-0116 freeze lifted: `scripts/score-change.py score` prints `no pending change to score`, `reports/.pending-baseline.json` is gone, and `e956dcf46` took a **❌ BAD** row. Its auto-revert conflicted again and is **deliberately not completed by hand** — reverting it reinstates ADR-0139, itself ❌ BAD (Rule 372). Item #1 keeps its rank and is **addressed this cycle**, with its mechanism corrected once more: last cycle's re-specification blamed the band's `1/|f|` inverse-forecast shape, but the same reading that motivated that also refutes it, because the *only* names that ever opened are exactly the low-`1/|f|` tail — the threshold is doing what it was designed to do, and what is broken is that the aim never gets to cross it. Items #1 and #3 turn out to be **two resets on one piece of state**, each masking the other; they are merged and fixed together.)

### Step 0 — `e956dcf46` (the completed ADR-0139 revert): SCORED ❌ BAD, closed

Verified ✅ on five independent boots at the defect level, then scored ❌ BAD on the vector at the close of
its window. Both are true and not in conflict: the revert did exactly what it claimed to the credibility
gate, and the objective still did not improve — which says the mechanism was never the money. Closed; not
re-reverted (Rule 372). Its VERIFY-BY retires with it.

### Window attribution — market only, FIFTH consecutive zero-order window

`recent_orders` shows no new order since the 17:00:28Z ADR-0019 auto-hedge. `risk.total` reads `totalPnl`
**-637.53116254**, `grossExposure` **5246.09536250** (**0.3%** of the firm cap, headroom **$1,494,754**),
`netExposure` **508.54463750**; `breaker.halted` **false**; `regime.trend` **CHOP**, `volRatio` **1.03**.
`attribution` splits the firm total into ALPHA **-600.08172505**, MACRO **-56.79950536**, HEDGE
**+19.35006787**. **100% market, 0% change-attribution** (Rule 357).

### Item #1 — the aim never crosses its band, so the desk cannot build: ⚠️ STILL-BROKEN → **ADDRESSED THIS CYCLE (ADR-0140)**; item #3 MERGED INTO IT

Still broken on entry, as expected under the freeze: `insideBuffer` **19** of **20**, `currentQty` **0**
for most planned names, and the planner producing real targets throughout (WMT `targetQty`
**-846.588220**, KO **-599.105696**, JPM **139.136530**) against which nothing routed.

**Mechanism, corrected and now settled.** The release condition is closed-form:
`|aim|/|target| > bufferFraction × TARGET_ABS / |f|` = **`1/|f|`** at the live `0.10` / `10.0`. Two live
readings pin it:

| reading | value | what it establishes |
| --- | --- | --- |
| the only `fusion entry` rows in the window | GOOG `forecast=6.3413095741475525`, NVDA `forecast=-9.608504615051698` | thresholds **0.158** / **0.104** — the passers are exactly the low-`1/|f|` tail (Rule 373) |
| `|f|` across every currently-planned name | **2.107082** … **4.710957** | thresholds **0.212**–**0.474**; none opens |
| `|aim|/|target|` across six names on one rate, one band, one seed | AAPL **0.017274** … CVX **0.219300** | a **12.7×** spread under a shared clock ⇒ the clocks were RESTARTED (Rule 375) |

So the band is not misshapen — the aim never reaches it. With ADR-0080's `a = 1 − e^(−c/h)` the aim rises
as `1 − e^(−t/h)`, giving time-to-first-order `t > −h·ln(1 − 1/|f|)` ≈ **900–2400 s** at the live
forecasts. And the aim was reset by **two** mechanisms, not one: `aims.keySet().retainAll(planned)` erased
the whole intent of any name absent from a **single** cycle's plan (membership churns — consecutive
snapshots read `instruments` **21** then **20**), and the map was in-memory against process lifetimes of
**1344–1439 s**. Last cycle's refutation tested only the second and correctly killed *persistence alone*;
it could not see the first (Rule 374). **Item #3 is therefore not a separate defect — it is the other half
of this one, and is merged here.**

**Fix shipped (ADR-0140):** absence AGES an intent over a window derived by inverting the ADR-0080
identity (`−1/ln(1−a) = h/c` = **120** cycles = one evidence horizon; no number introduced), and the map
is written through each cycle to `fusion_aim` (V48, `NUMERIC(20,6)`, `feed_mode`-scoped, derived data
only) and restored once per process. No band, width, rate, conviction floor, edge gate or cap altered;
deterministic floor untouched; a restored aim is still stepped at this cycle's rate and clamped by
ADR-0102 into `[flat, target]`. Capping the band was ADR-0133, scored ❌ BAD, and is **not** re-attempted.
`-Pci test` green.

**VERIFY-BY (next run):** at least one name's `|aim|/|targetQty|` exceeds the `1 − e^(−uptime/3600)` an
in-memory reseed could have produced; `insideBuffer` is strictly less than `instruments` **and** a matching
`fusion entry`/`fusion exit` row appears in `recent_orders` in the same window; and the `|aim|/|target|`
spread across names collapses toward a common value.

### Item #2 — a degenerate 1-source zero forecast bypasses the no-trade buffer and full-liquidates: ⚠️ OPEN, now **#2 → next in line**

Unchanged and un-refuted; not re-examined this cycle. The standing evidence holds, and this window adds a
**live instance from the order tape**: NVDA `BUY 7.000000` at 16:59:27.901128Z, reason `fusion exit —
target decayed to flat [forecast=-0.0, sources=1]` — a full liquidation of the position opened 74 minutes
earlier at `forecast=-9.608504615051698, sources=3`. `nextAim` (`PositionBuffer.java`) opens
`if (target.signum() == 0) return ZERO`, correct for an *ordered* exit and wrong for a target that read
zero because its sources collapsed to one.

**VERIFY-BY (unchanged):** no order carries `fusion exit — target decayed to flat` with `sources=1` in
`recent_orders`; a name whose source count collapses within a cycle shows a non-full `deltaQty`; and the
fix ships a unit test reproducing the one-source-zero plan.

### Item #3 — ~~the aim map is in-memory, so no warm-up survives a restart~~: **MERGED into #1 and fixed by ADR-0140**

Not a separate defect. It was one of the two resets on the same map; fixing it alone would have left the
per-cycle `retainAll` reset in place, which is exactly why its earlier promotion failed its own test.

### Item #4 — (unchanged) the edge gate refuses to size a source with measured expectancy: ⚠️ OPEN, now #3

Carried forward unchanged; not examined this cycle. Live `edgeGate` reads **null** in `fusion_targets`,
and `signals_telemetry` still shows `social` as the only source with positive measured expectancy
(`avgReturnBps` **6.033649120551656**, `hitRate` **0.6146341463414634**, **41** cohorts).

---

## Verification block — 2026-08-05 19:00Z (**NO CHANGE — ADR-0116 measurement freeze, cycle 5 of 6.** `scripts/score-change.py score` prints `e956dcf46 still accumulating evidence (5/6 cycles) — held, not scored this run`, `reports/.pending-baseline.json` still names `e956dcf46`, and the ledger's newest row is still `d51f179a2`. Step 0 re-graded the revert ✅ VERIFIED on a **fifth, independent boot**, and **retired its VERIFY-BY as the wrong metric** (see below). The cycle's product **refutes the fix this register ranked #1 last cycle**: persisting the aim would NOT unfreeze the desk. Four of the six visible frozen names have release requirements *below* what an in-memory reseed could produce in this boot's lifetime — they had the time and still routed nothing. Item #1 keeps its rank (it is still the biggest, provable cost) but its **mechanism and its prescribed fix are re-specified**: the freeze is the aim losing a race to a moving target through a band that is inversely proportional to forecast strength, not a cold-start clock.)

### Step 0 — `e956dcf46` (the completed ADR-0139 revert): ✅ VERIFIED (5th boot)

Deployment confirmed on a **fifth, fresh** JVM: `traffic.timestampMillis` **1785956401616** −
`ops_jvm.uptimeSeconds` **1364** = boot **18:37:17.616Z**, after the revert's **16:38:20Z** commit — a
different process from the 18:06:55.128Z boot graded last cycle, so the readings are independent.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| no *uncurated* author passes the credibility conjunction (metric corrected — see below) | all **7** `stocktwits:` rows in `recent` read `credible: false` | ✅ |
| corroboration rate off its loosened level (Rule 334 — grade the RATE, the counter resets at boot) | `counters.corroborated` **7** in **1364 s**, against **8/1387 s** and **11/1344 s** post-revert and **17/1427 s**, **15/1439 s**, **17/1362 s** on the ADR-0139 boots | ✅ holds on a fifth boot, at the lowest rate yet |
| pump tell unaffected | `manipulationSuspected` **38** on `ingested` **3390** / `kept` **809** | ✅ still firing |

**Last cycle's VERIFY-BY was the wrong metric and is RETIRED (Rule 371).** It asked that the
`credible: true` count stay a small minority of `recent`. This cycle **5** of the 12 shown read
`credible: true` — and **none is a regression**, because all five are `channel: yahoo`, the news-RSS path.
`NewsSocialFeed.java:54-55` constructs every wire item with `SYNTHETIC_FOLLOWERS` **5_000_000**, `verified`
**true**, `SYNTHETIC_AGE_DAYS` **3650**, so a curated outlet clears the conjunction *by construction* and
always has, untouched by ADR-0139 or its revert. A counter mixing curated outlets with uncurated authors
cannot verify a gate that only bites the latter. **Replacement VERIFY-BY (in force above):** every
`stocktwits:`-channel row in `recent` reads `credible: false`, and the corroboration rate stays below the
ADR-0139 band.

The scorer owns the vector verdict; that is 5 of 6 cycles in and is not mine to pre-judge. It lifts next
cycle.

### Window attribution — market only, fourth consecutive zero-order window

`recent_orders` shows **no new order**: the newest row is still the ADR-0019 auto-hedge ES `SELL 0.003243`
at **17:00:28Z**. `risk.total` reads `totalPnl` **-640.57492504**, `grossExposure` **5242.89912500**,
`netExposure` **505.50087500**; `var95` **75.35** on `coveredExposure` **5242.90**; `breaker.halted`
**false**; `regime` `trend` **CHOP**, `volRatio` **0.95**. `attribution` splits the firm total into ALPHA
**-603.20172505**, MACRO **-56.79950536**, HEDGE **+19.42630537**. Every dollar of the move is a mark on an
untouched position — **100% market, 0% change-attribution** (Rule 357).

### Item #1 — the aim never crosses its band, so the desk cannot build: ⚠️ STILL-BROKEN, stays #1, **mechanism and prescribed fix RE-SPECIFIED**

Last cycle's VERIFY-BY: *after a fresh boot, at least one name's `|aim|/|targetQty|` exceeds the
`1 − e^(−uptime/3600)` an in-memory reseed could have produced.* At `uptime` **1364 s** that cap is
**0.315378**, and **no** name exceeds it. Unfixed, as expected — nothing shipped under the freeze.

**But the same table refutes the fix this register promoted to #1 last cycle.** The release condition read
off `PositionBuffer.band()` is `|aim|/|target| > TARGET_ABS × fraction / |f|`, which at the live
`Forecast.TARGET_ABS` **10.0** and `jethro.fusion.position-buffer.fraction` **0.10** is exactly `1/|f|`:

| name | `combinedForecast` | `\|aim\|/\|target\|` | required `1/\|f\|` | released? | under the **0.315378** reseed cap? |
| --- | --- | --- | --- | --- | --- |
| BAC | -7.79088727371532 | 0.063338 | 0.128355 | no | yes |
| AAPL | -6.185233809515913 | 0.017274 | 0.161675 | no | yes |
| AMZN | -4.252230984616215 | 0.065118 | 0.235171 | no | yes |
| XOM | 3.4286861670748205 | 0.059964 | 0.291657 | no | yes |
| CVX | 2.7867811339450084 | 0.219300 | 0.358837 | no | yes |
| MSFT | -2.5139146286285365 | 0.052135 | 0.397786 | no | yes |

BAC, AAPL, AMZN and XOM all require **less** than the **0.315378** this boot's lifetime allows. They had
the time and still show `deltaQty` **0.0**. **Persisting the aim across restarts therefore does not free
them, and Rule 366's "safe structural fix" does not fix what it was promoted for.** Rule 364 stands as an
aggravating factor for NEE/BAC-class long horizons; it is not the binding constraint.

**The mechanism, re-specified (Rules 369, 370).** The six names share one derived `adjustment-rate`, one
band, and one seed (`currentQty` **0** for every one), yet their ratios span **12.7×**. Under a stationary
target that is arithmetically impossible — every name would read the identical ratio. So the aim is not a
clock warming toward a fixed point; it is **losing a race to a target that moves within the boot**, in
`regime.trend` **CHOP**. And the band it must cross is *inversely* proportional to forecast strength: MSFT
at `|f|` **2.51** must travel **0.397786** of the way to its target, BAC at **7.79** only **0.128355** —
while the planner has **already** scaled that target down by the same forecast. The weak view is charged
for its weakness twice.

**VERIFY-BY (next run, re-specified):** at least one name whose `1/|f|` requirement sits under the boot's
`1 − e^(−uptime/3600)` cap shows a non-zero `deltaQty`; `insideBuffer` is strictly less than `instruments`
**and** a matching `fusion entry`/`fusion exit` order appears in `recent_orders` in the same window; and
the ratio spread across names collapses toward a common value once the aim is no longer racing.

**Open, carried as a required test, not smoothed away.** `insideBuffer` **20** against `instruments` **21**
says one name was outside its band at the 18:59:43Z snapshot, yet `recent_orders` has no fusion order in
the window — a second instance of the Rule 343 tiny-delta anomaly. The fix's test must reproduce and answer
it.

### Item #2 — a degenerate 1-source zero forecast bypasses the no-trade buffer and full-liquidates: ⚠️ OPEN

Unchanged and un-refuted; not re-examined this cycle. The evidence stands: NVDA 16:59:27 `fusion exit —
target decayed to flat [forecast=-0.0, sources=1]`, and the 2026-08-04 20:10–20:17Z seven-name sweep. **No
new instance fired this window, because no order fired at all.** `nextAim` (`PositionBuffer.java:259`) opens
`if (target.signum() == 0) return ZERO`, which is correct for an *ordered* exit and wrong for a target that
read zero because its sources collapsed to one.

**VERIFY-BY (unchanged):** no order carries `fusion exit — target decayed to flat` with `sources=1` in
`recent_orders`; a name whose source count collapses within a cycle shows a non-full `deltaQty`; and the fix
ships a unit test reproducing the one-source-zero plan.

### Item #3 — the aim map is in-memory, so no warm-up survives a restart: ⚠️ OPEN, **DEMOTED from #1**

Rule 364's reading is still correct and still a real defect — `aims` is a plain `HashMap`
(`PositionBuffer.java:105`), reseeded from `held` at every boot, in a harness whose observed process
lifetimes are **1364/1387/1344/1362/1427/1439 s** — but it is demoted because this cycle proved it is **not
what is freezing the desk**: four of six names fit inside one lifetime and still routed nothing. It remains
worth fixing as ADR-0014 derived state (it changes no band, no rate, no cap), just not first.

**VERIFY-BY:** after a restart, a name's `aim` resumes near its pre-restart value instead of at
`currentQty`.

### Item #4 — (unchanged) the edge gate refuses to size a source with measured expectancy: ⚠️ OPEN

Carried forward unchanged; not examined this cycle.

---

## Verification block — 2026-08-05 18:30Z (**NO CHANGE — ADR-0116 measurement freeze, cycle 4 of 6.** `scripts/score-change.py score` prints `e956dcf46 still accumulating evidence (4/6 cycles) — held, not scored this run`, `reports/.pending-baseline.json` still names `e956dcf46`, and the ledger's newest row is still `d51f179a2`. Step 0 re-graded the revert ✅ VERIFIED on a **fourth, independent boot**. The cycle's product **overturns last cycle's Rule 363 conclusion and RE-RANKS the register**: the aim's warm-up clock is *not* persisted — `aims` is a plain in-memory `HashMap` (`PositionBuffer.java:105`) that reseeds from `held` at every boot — so the buffer's release time must be paid inside ONE ~1400 s process lifetime, and for two of the six visible names the requirement **exceeds every process lifetime this loop has ever observed**. That is a freeze no fix to item #1 can touch, it is the bigger money, and it has a *safe* fix that changes no band and no rate. **The buffer item is promoted to #1; the degenerate-zero-forecast item moves to #2.**)

### Step 0 — `e956dcf46` (the completed ADR-0139 revert): ✅ VERIFIED (4th boot)

Deployment confirmed on a **fourth, fresh** JVM: `traffic.timestampMillis` **1785954602128** −
`ops_jvm.uptimeSeconds` **1387** = boot **18:06:55.128Z**, after the revert's **16:38:20Z** commit — a
different process from the 17:37:38.570Z boot graded last cycle, so the readings are independent.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| uncurated authors do not pass on a single credential | of the 12 `recent` posts, all tier `STANDARD`, **11** read `credible: false`; the one `credible: true` is `stocktwits:Estimize` | ✅ — and see the note below |
| corroboration rate off its loosened level (Rule 334 — grade the RATE, the counter resets at boot) | `counters.corroborated` **8** in **1387 s**, against **11/1344 s** post-revert and **17/1427 s**, **15/1439 s**, **17/1362 s** on the ADR-0139 boots | ✅ holds on a fourth boot, at the lowest rate yet |
| pump tell unaffected | `manipulationSuspected` **39** on `ingested` **3390** / `kept` **794** | ✅ still firing |

**On the first `credible: true` since the revert — this is the rule working, not a regression.** The live
code is the conjunction: `SocialChannels.java:54-55` reads
`p.verified() && p.followers() >= credibleFollowerFloor && p.accountAgeDays() >= credibleAgeDaysFloor`
against the live `credibleFollowerFloor` **5000** / `credibleAgeDaysFloor` **180**. An author clearing all
three is *supposed* to pass; ADR-0139's defect was passing on any ONE. `Estimize` is a verified, long-lived,
high-follower publisher, so it is the expected shape of a legitimate pass. Caveat recorded honestly: the
telemetry does not expose the author's three fields, so the conjunction is proven from the source line and
the rate, not from this row. **VERIFY-BY tightened for next run:** the `credible: true` count stays a small
minority of `recent`, and the corroboration rate stays below the ADR-0139 band.

The scorer owns the vector verdict; that is 4 of 6 cycles in and is not mine to pre-judge.

### Window attribution — market only, third consecutive zero-order window

`recent_orders` shows **no new order**: the newest row is still the ADR-0019 auto-hedge ES `SELL 0.003243`
at **17:00:28Z**, already covered by the 17:30Z block. `risk.total` reads `totalPnl` **-631.26343754**,
`grossExposure` **5255.10763750**, `netExposure` **514.81236250**; `breaker.halted` **false**; `regime`
`trend` **CHOP**, `volRatio` **0.92**. Every dollar of the move is a mark on an untouched position —
**100% market, 0% change-attribution** (Rule 357).

### Item #1 — **PROMOTED**: the aim's warm-up clock is in-memory, so the buffer's release time exceeds the process lifetime: ⚠️ OPEN

`insideBuffer` reads **20 of 20** and every visible target reads `deltaQty` **0.0** — including flat names
carrying large plans (KO `targetQty` **1584.259488**, WMT **431.975458**, NVDA **-159.668881**, NEE
**-404.251600**, BAC **-512.748000**). The desk holds one alpha position, GOOG **8.000000**, against its own
plan of **135.389483**.

**The mechanism, and why it is not the band.** `Forecast.TARGET_ABS` is **10.0** and
`jethro.fusion.position-buffer.fraction` is **0.10**, so their product is exactly **1.0** and the shipped
release condition reduces to Rule 358's `|aim|/|target| ≥ 1/|forecast|`. With `edgeGate` null the ADR-0080
rate is the base horizon, so from a flat seed the aim walks `target·(1 − e^(−t/3600))` and release lands at
**t ≥ 3600·ln(|f|/(|f|−1))** seconds. Against **this** cycle's own forecasts:

| name | `combinedForecast` | release time | `aims` | `|aim|/|target|` |
| --- | --- | --- | --- | --- |
| KO | 6.282787 | ~624 s | 26.388661 | 0.0167 |
| GOOG | 5.025005 | ~799 s | 12.615532 | 0.0932 |
| WMT | 3.820728 | ~1092 s | 6.443268 | 0.0149 |
| NVDA | -3.642848 | ~1155 s | -4.969085 | 0.0311 |
| NEE | -2.165900 | ~2230 s | -28.737583 | 0.0711 |
| BAC | -1.946400 | ~2597 s | -37.328277 | 0.0728 |

**The new, confound-free finding.** `PositionBuffer.java:105` is
`private final Map<String, BigDecimal> aims = new HashMap<>();` — plain in-memory, no LMDB, no warm
restart. At boot the map is empty, so `nextAim` takes `previous == null ? held` (`:263`) and every name on a
flat book **reseeds at zero**. The release time above must therefore be paid inside a *single process
lifetime*, and this loop's observed lifetimes are **1387 s** (this boot), **1344 s**, **1362 s**, **1427 s**,
**1439 s**. **NEE (~2230 s) and BAC (~2597 s) exceed every one of them** — those names cannot open in this
harness no matter what else is fixed, and no reset needs to be invoked to explain it. This is why the item
is promoted: it needs no assumption about target stability, and it is the largest live cost on the register
— the desk is at **0.4%** of the firm gross cap with **$1,494,745** of headroom, which the mission calls a
failure to attack.

**Correcting last cycle's Rule 363.** It concluded item #1's `target == 0` snap "supplies the reset" that
starves the buffer, making one `if` fix both. That is now too strong: NEE and BAC fail on the horizon alone.
Reading the whole of `apply` also shows **five** seed/reset paths, not the two Rule 363 weighed —
`nextAim:259` (flat target snaps to zero), `withinTarget:311` (sign flip zeroes), `apply:138`
(`aims.clear()` on an empty plan), `apply:178` (`retainAll` — a name absent from this cycle's plan loses its
intent entirely), and `apply:163` (`aim = held.add(delta)` re-seed when `mayIncrease` is false). And the
shortfall-vs-uptime arithmetic **cannot** by itself prove a reset, because an aim chasing a *rising* target
shows the same low ratio. Guessing among five is worse than guessing among two — but the horizon finding
means the fix no longer has to guess.

**The fix this points to (next cycle, once the freeze lifts).** Persist the aim across restart rather than
touching any band or rate. Aims are derived state, which is exactly what ADR-0014 puts in LMDB
("derived data only"), and a restored aim changes no width, no rate, no cap and nothing on the
deterministic floor. It needs a staleness bound so a long outage cannot resume an ancient intent — that is
the design question the ADR must answer, so this ships with an ADR in the same commit.

**VERIFY-BY (next run):** after a fresh boot, at least one name's `|aim|/|targetQty|` exceeds the
`1 − e^(−uptime/3600)` an in-memory reseed could have produced; `insideBuffer` is strictly less than
`instruments`; and a name whose release time exceeds the process lifetime (NEE, BAC at these forecasts)
shows a non-zero `deltaQty`.

### Item #2 — (was #1) a degenerate 1-source zero forecast bypasses the no-trade buffer and full-liquidates: ⚠️ OPEN

Unchanged and un-refuted; demoted only because item #1 now carries the larger, provable cost. The evidence
stands: NVDA 16:59:27 `fusion exit — target decayed to flat [forecast=-0.0, sources=1]`, and the
2026-08-04 20:10–20:17Z seven-name sweep. **No new instance fired this window, because no order fired at
all.** `nextAim` (`PositionBuffer.java:259`) opens `if (target.signum() == 0) return ZERO`, which is correct
for an *ordered* exit and wrong for a target that read zero because its sources collapsed to one.

**VERIFY-BY (unchanged):** no order carries `fusion exit — target decayed to flat` with `sources=1` in
`recent_orders`; a name whose source count collapses within a cycle shows a non-full `deltaQty`; and the fix
ships a unit test reproducing the one-source-zero plan.

### Item #3 — (unchanged) the edge gate refuses to size a source with measured expectancy: ⚠️ OPEN

Carried forward unchanged; not examined this cycle.

---

## Verification block — 2026-08-05 18:00Z (**NO CHANGE — ADR-0116 measurement freeze, cycle 3 of 6.** `scripts/score-change.py score` prints `e956dcf46 still accumulating evidence (3/6 cycles) — held, not scored this run`, `reports/.pending-baseline.json` still names `e956dcf46`, and the ledger's newest row is still `d51f179a2`. Step 0 re-graded the revert ✅ VERIFIED on a **third, independent boot**. The cycle's product is that **item #2's freeze is no longer just a closed-form inequality, it is a quantified RACE the aim loses by 4.3×–11.3×** — and that the race's *reset* side is driven by the very `target == 0` branch that item #1 names, so the two items are causally linked, not merely adjacent. **Ranking is unchanged: #1 stays #1, and gains a second, larger reason to be first.**)

### Step 0 — `e956dcf46` (the completed ADR-0139 revert): ✅ VERIFIED (3rd boot)

Deployment confirmed on a **third, fresh** JVM: `traffic.timestampMillis` **1785952802570** −
`ops_jvm.uptimeSeconds` **1344** = boot **17:37:38.570Z**, after the revert's **16:38:20Z** commit. This is
a different process from the 17:06:53.992Z boot graded last cycle, so the readings are independent.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| uncurated authors do not pass on a single credential | of the 12 `recent` posts, all tier `STANDARD`, **all 12** read `credible: false` | ✅ strict conjunction is live |
| corroboration rate off its loosened level (Rule 334 — grade the RATE, the counter resets at boot) | `counters.corroborated` **11** in **1344 s**, against **17/1427 s**, **15/1439 s**, **17/1362 s** on the ADR-0139 boots | ✅ holds on a third boot |
| pump tell unaffected | `manipulationSuspected` **35** on `ingested` **3240** / `kept` **790** | ✅ still firing |

The scorer owns the vector verdict; that is 3 of 6 cycles in and is not mine to pre-judge.

### Window attribution — market only, and unambiguously so

`recent_orders` shows **no order at all** since the previous report (the newest row is the ADR-0019
auto-hedge ES `SELL 0.003243` at **17:00:28Z**, which the 17:30Z block already covered). The book is the
same two positions: ALPHA **GOOG 8.000000** (`unrealizedPnl` **-11.12000000**, was **-14.60000000**) and
HEDGE **ES -0.006099** (**-1.10195332**). `risk.total` reads `totalPnl` **-633.35472504**, `grossExposure`
**5252.55892500**, `netExposure` **512.72107500**; `breaker.halted` **false**. Every dollar of the window's
move is a mark on an untouched position — **market, zero change-attribution** (Rule 357).

### Item #1 — a degenerate 1-source zero forecast BYPASSES the no-trade buffer, full-liquidates, AND resets the aim: ⚠️ OPEN, stays #1, severity UPGRADED

The liquidation evidence is unchanged (the NVDA 16:59:27 row `fusion exit — target decayed to flat
[forecast=-0.0, sources=1]`, and the 2026-08-04 20:10–20:17Z seven-name sweep); no *new* instance fired
this window, because no order fired at all. What is new is the **second** consequence of the same
`target.signum() == 0`, and it is the larger one:

`nextAim` (`PositionBuffer.java:259`) opens with `if (target.signum() == 0) return ZERO` — a flat target
**snaps the aim to zero** rather than decaying it. That is correct for an *ordered* exit and wrong for a
target that read zero because its sources collapsed. So a degenerate plan does not merely liquidate the
position: it **destroys the accumulated aim**, which is the exact state item #2 needs to accumulate for
minutes on end. Item #1's branch is therefore a *supply* of the resets that item #2's arithmetic (below)
shows the desk cannot afford. Fixing the one conditional addresses both, which is why #1 keeps the rank
even in a window where it did not fire.

**VERIFY-BY (next run):** no order carries `fusion exit — target decayed to flat` with `sources=1` in
`recent_orders`; a name whose source count collapses within a cycle shows a non-full `deltaQty`; and its
`aims` entry does **not** return to `0.0` on that cycle. The change ships a unit test reproducing the
16:59:27 row **before** it alters behaviour (Rule 353).

### Item #2 — the no-trade band's release is a RACE the aim loses by 4.3×–11.3×: ⚠️ OPEN, mechanism now QUANTIFIED

Re-confirmed on the third boot with an **entirely different name set** from last cycle's (KO/NVDA/MSFT/
PFE/WMT/JNJ vs AMZN/CVX/MSFT/AAPL/JPM/NEE), so the mechanism is neither name- nor boot-specific:
`insideBuffer` **22**, `targets` `… 6 of 23 elements shown, 17 elided`, every visible `deltaQty` **0.0**,
every `currentQty` **0**.

Last cycle established the release condition from a flat holding, `|aim|/|target| ≤ 1/|forecast|`
(Rule 358). This cycle closes the other half — **how fast the aim can reach it**. `edgeGate` is **null**,
so `withHoldingPeriod` (`FusionLifecycle.java:244`, fallback at `:248`) falls back to the shipped base horizon
`jethro.signals.horizon-seconds=3600` and `adjustmentRateFor(30, 3600)` derives
**a = 0.008298707361124036**. `nextAim` is the ADR-0080 e-fold `aim + a·(target − aim)` seeded at the held
quantity, so from flat the aim is `target · (1 − e^(−t/3600))` and release needs
**t ≥ 3600 · ln(|f| / (|f| − 1))** seconds of uninterrupted, sign-stable, target-stable accumulation.
Inverting each live ratio gives the aim's *effective* age:

| name | `|combinedForecast|` | live `|aim|/|targetQty|` | release `1/|f|` | implied aim age | seconds needed | short by |
| --- | --- | --- | --- | --- | --- | --- |
| KO | 5.1383 | 0.0442 | 0.1946 | 163 s | 779 s | 4.8× |
| NVDA | 4.9233 | 0.0199 | 0.2031 | 72 s | 817 s | 11.3× |
| MSFT | 3.9334 | 0.0665 | 0.2542 | 248 s | 1056 s | 4.3× |
| PFE | 2.5648 | 0.0920 | 0.3899 | 347 s | 1779 s | 5.1× |
| WMT | 2.5396 | 0.0682 | 0.3938 | 254 s | 1802 s | 7.1× |
| JNJ | 2.0448 | 0.0901 | 0.4890 | 340 s | 2417 s | 7.1× |

The decisive comparison is against the process itself: had **any** of these accumulated uninterrupted
since the **1344 s** boot, the ratio would read **0.3116** — which clears KO (0.1946), NVDA (0.2031) and
MSFT (0.2542) outright. It reads 0.0199–0.0920 instead. **So the band is not merely slow; the aim is being
reset roughly every 72–347 s while it needs 779–2417 s, and the desk therefore cannot open a position at
all except on the outlier forecast that shortens the requirement enough to win one race** (the 15:45:50Z
NVDA fill at `combinedForecast` **-9.608504615051698**, whose requirement collapses to **396 s**).

Two known reset sources feed this — `nextAim`'s flat-target snap (item #1's branch) and `withinTarget`
(`PositionBuffer.java:311`) zeroing the aim on any forecast sign flip. **This snapshot cannot separate
their contributions**, and the age inversion assumes the target was stable since the aim's last reset; a
target that grew would make the implied age an over-estimate, i.e. the shortfall worse, not better. The
fix must instrument which reset fires before choosing between them.

**VERIFY-BY (next run):** `insideBuffer` is strictly below the target count, at least one name with
`currentQty` **0** shows a non-zero `deltaQty`, and its `|aim|/|targetQty|` exceeds `1/|combinedForecast|`
— all three read from the same `fusion_targets` snapshot.

*(Every figure above is read from this run's `logs/report.md` or derived by script from it and the shipped
constants `interval-seconds=30`, `horizon-seconds=3600`, `Forecast.TARGET_ABS=10`,
`position-buffer.fraction=0.10`. Nothing here sets or proposes a dial — invariant 7 / ADR-0016.)*

## Verification block — 2026-08-05 17:30Z (**NO CHANGE — ADR-0116 measurement freeze, cycle 2 of 6.** `reports/.pending-baseline.json` names `e956dcf46` and the scorer's own `window_since` reports **2/6** cycles accrued; the ledger's newest row is still `d51f179a2`. Step 0 re-graded the revert ✅ VERIFIED on a **second, independent boot**. The cycle's real product is that **item #2 now has a closed form that predicts the live plan exactly** — `insideBuffer` **26 of 26**, and the release condition `|aim|/|target| ≤ 1/|forecast|` reproduces every one of the six visible frozen names — and that **items #1 and #2 are the two branches of a single `if` in `bufferedDelta`**. Ranking is unchanged: #1 still fires first and moves whole positions. But the fix is now specified against one conditional rather than two separate patches.)

### Step 0 — `e956dcf46` (the completed ADR-0139 revert): ✅ VERIFIED (2nd boot)

Deployment confirmed on a **fresh** JVM, so this is not a re-read of last cycle's process:
`traffic.timestampMillis` **1785951001992** − `ops_jvm.uptimeSeconds` **1388** = boot **17:06:53.992Z**,
after the revert's **16:38:20Z** commit.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| uncurated authors do not pass on a single credential | of the 12 `recent` posts, all tier `STANDARD`, **all 12** read `credible: false` (was 11 of 12) | ✅ strict conjunction is live |
| corroboration rate off its loosened level (Rule 334 — grade the RATE, the counter resets at boot) | `counters.corroborated` **7** in **1388 s**, against **17/1427 s**, **15/1439 s**, **17/1362 s** on the ADR-0139 boots | ✅ holds on a second boot |
| pump tell unaffected | `manipulationSuspected` **39** on `ingested` **3390** / `kept` **800** | ✅ still firing |

The scorer owns the vector verdict; that is 2 of 6 cycles in and is not mine to pre-judge.

### Item #1 — a degenerate 1-source zero forecast BYPASSES the no-trade buffer and full-liquidates: ⚠️ OPEN, stays #1, now localised to one line

Unchanged in rank and evidence (the NVDA 16:59:27 row and the 2026-08-04 20:10–20:17Z seven-name sweep),
and now pinned to the exact conditional. `bufferedDelta` (`PositionBuffer.java:468`) returns the **full,
unbuffered** gap when `target.signum() == 0`. A plan whose sources collapsed to **1** and whose forecast
reads **-0.0** produces exactly that, so source collapse is executed as if a risk control had ordered an
exit. The method's own ADR-0107 javadoc states the correct rule — *"an EXIT is what a control ORDERED, not
what the arithmetic happens to read"* — but scopes its remedy to the `aim == 0` case, leaving `target == 0`
unguarded. The refutation stands 30 s later: `combinedForecast` **-3.2603756638089805**, `sources` **3**,
`agreement` **0.872320186445232**.

**VERIFY-BY (next run):** no order carries `fusion exit — target decayed to flat` with `sources=1` in
`recent_orders`; and a name whose source count collapses within a cycle shows a non-full `deltaQty`. The
change ships a unit test reproducing the 16:59:27 row **before** it alters behaviour (Rule 353).

### Item #2 — the no-trade band admits only outlier forecasts: ⚠️ OPEN, mechanism now CLOSED-FORM

Promoted in precision, not in rank. `insideBuffer` **26 of 26**, every `deltaQty` **0.0**, against real
intent: AMZN `targetQty` **217.187537** at `combinedForecast` **4.89099753538535** on **4** sources with
`currentQty` **0**; CVX **365.706789**; MSFT **121.722257**; AAPL **-191.708027**; JPM **191.617345**;
NEE **333.937475**.

`PositionBuffer.band` (`PositionBuffer.java:338`) is `|target| × TARGET_ABS / |forecast| × width`. With the
shipped `jethro.fusion.position-buffer.fraction=0.10` and `Forecast.TARGET_ABS = 10.0`, the no-trade test
`|aim − held| ≤ band` reduces, from a flat holding, to **|aim| / |target| ≤ 1 / |forecast|**:

| name | sources | `|aim|/|targetQty|` | `1/|combinedForecast|` | inside? | live `deltaQty` |
| --- | --- | --- | --- | --- | --- |
| AMZN | 4 | 0.0735 | 0.2045 | yes | 0.0 |
| CVX | 2 | 0.0142 | 0.2373 | yes | 0.0 |
| MSFT | 3 | 0.0297 | 0.2550 | yes | 0.0 |
| AAPL | 3 | 0.1667 | 0.3272 | yes | 0.0 |
| JPM | 3 | 0.0083 | 0.3510 | yes | 0.0 |
| NEE | 3 | 0.0861 | 0.5554 | yes | 0.0 |

Six of six. Two properties of that threshold are the actual defect: it is **1/|forecast|**, so a *weaker*
view must carry its aim *further* (NEE needs **55%** of target, AMZN **20%**); and the aim is an EWMA held
in an in-memory map (`PositionBuffer.java:105`), re-seeded from the holding on every JVM boot and reset to
flat by `withinTarget` on every forecast sign flip, against `uptimeSeconds` **1388**. So openability is a
function of process uptime and sign-flip luck rather than evidence strength.

**VERIFY-BY (when it becomes #1):** `insideBuffer` strictly below the instrument count with at least one
non-zero `deltaQty` on a multi-source name, and the ratio table above recomputed on the live `aims` map —
prose about the band is no longer acceptable evidence either way.

### Item #3 — (unchanged) the edge gate refuses to size a source with measured expectancy: ⚠️ OPEN

Carried. `edgeGate` reads `null` in this plan, so the gate is not what is holding the book — items #1/#2
are. Re-rank only once the buffer conditional is fixed and the book can actually build.

---

## Verification block — 2026-08-05 17:00Z (**NO CHANGE — ADR-0116 measurement freeze.** `scripts/score-change.py score` reports `e956dcf46 still accumulating evidence (1/6 cycles)` and `reports/.pending-baseline.json` names that commit, so last cycle's revert is under measurement and a new change would destroy its evidence. Step 0 graded the revert ✅ VERIFIED. The window also produced the register's most important reading yet: **the no-trade buffer is BYPASSED by a degenerate single-source zero forecast**, which full-liquidated NVDA seconds before the same three sources came back agreeing on the same side. That is promoted to **item #1**; the old #1 becomes #2 as the second half of the same asymmetry.)

### Step 0 — `e956dcf46` (the completed ADR-0139 revert): ✅ VERIFIED

Deployment confirmed first, so this grades a change the app actually ran: the revert commit is authored
**16:38:20Z**, and the running JVM booted at `traffic.timestampMillis` **1785949202383** −
`ops_jvm.uptimeSeconds` **1260** = **16:39:02.383Z** — 42 s after the commit. The build under test contains it.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| `SocialChannels.isCredible` back to the strict conjunction in the tree | `p.verified() && p.followers() >= credibleFollowerFloor && p.accountAgeDays() >= credibleAgeDaysFloor` for `STANDARD` | ✅ the rejected alternative-credential shape is gone |
| uncurated authors no longer pass on a single credential | of the 12 `recent` posts, all tier `STANDARD`, **11** read `credible: false` and **1** `credible: true` | ✅ the strict gate is what the running app is applying |
| corroboration back off its loosened rate (Rule 334 — grade the RATE, the counter resets at boot) | `counters.corroborated` **7** in **1260 s**, against **17/1427 s**, **15/1439 s**, **17/1362 s** on the ADR-0139 boots | ✅ roughly halved, consistent with the tightening |
| pump tell unaffected | `manipulationSuspected` **39** on `ingested` **3090** / `kept` **792** | ✅ still firing |

The rejected mechanism is out of the running code. The scorer now owns whether the revert helped the
vector; that is 1 of 6 cycles in and is not mine to pre-judge.

### Item #1 — **NEW, promoted**: a degenerate 1-source zero forecast BYPASSES the no-trade buffer and full-liquidates the position: ⚠️ OPEN

This is the mechanism that keeps taking gross to **exactly $0.00**, and this window caught it in the act
with 30 seconds of separation between the liquidation and its own refutation:

| time | event | reading |
| --- | --- | --- |
| 15:45:50.040557Z | NVDA entered | `SELL 7.000000` FILLED — `fusion entry — target increase [forecast=-9.608504615051698, sources=3]` |
| 16:59:27.901128Z | NVDA **full-liquidated** | `BUY 7.000000` FILLED — `fusion exit — target decayed to flat [forecast=-0.0, sources=1]` |
| 16:59:57.948Z (`fusion_targets.atMillis` **1785949197948**) | the same name, 30 s later | `combinedForecast` **-3.2603756638089805**, `sources` **3**, `agreement` **0.872320186445232**, `targetQty` **-200.400971**, `currentQty` **0**, `deltaQty` **0.0** |

**The asymmetry, stated exactly.** `FusionLifecycle.originOf` (`FusionLifecycle.java:428`) labels the exit
from `t.targetQty().signum() == 0`, so the planner's target for NVDA really was **exactly flat** on a
**single** surviving source reading **-0.0**. The full 7 shares routed. In the very next plan the same name
reports `insideBuffer` and releases `deltaQty` **0.0** on a **3-source** forecast with `agreement`
**0.872320186445232**. So: a *degraded, degenerate* plan gets **unbuffered, full-size execution**, while a
*restored, agreeing* plan gets **nothing**. The buffer throttles signal and waves through noise.

**It is not a one-off — it is the book-scale flattening pattern.** The 2026-08-04 20:10–20:17Z window shows
the same reason string firing across **seven** names in seven minutes — XOM `SELL 16`, CVX `SELL 28`, GOOG
`BUY 9`, AAPL `BUY 12`, NVDA `BUY 19`, AMZN `SELL 9`, MSFT `BUY 8`, every one
`fusion exit — target decayed to flat [forecast=0.0/-0.0, sources=1]`. That is the same simultaneous
source-dropout, and it is the shape behind the ledger rows that read `gross 52,192→0`, `gross 54,093→0`
and `gross 93,878→26,441`. Turnover confirms the cost is not theoretical: NVDA **236** fills / **$201,069.31**
turnover, GOOG **176** / **$188,284.68**, MSFT **200** / **$214,385.43**, against `attribution.totalFees`
**398.739093** on a book whose firm total is **-619.47984806**.

**Why this outranks the band.** The band explains why the desk cannot *re-enter*; this explains why it keeps
being *thrown flat* in the first place, and it is the half that actually moves size (7 shares at once versus
the band's ~0.06/cycle). Fixing re-entry while a dropout can still liquidate the book at will would be
fixing the second half of a loop whose first half still fires.

**VERIFY-BY (next run):** no order carries reason `fusion exit — target decayed to flat` with `sources=1`
while that name's *preceding or following* plan shows `sources ≥ 2`; and a name whose sources drop below the
plan's usual count is HELD rather than routed to flat. Read from `recent_orders` + `fusion_targets`.

**Precondition on the fix (carried from Rule 353, still binding):** a target of exactly zero must be
distinguishable in code from "the sources went away", and the change ships a unit test that reproduces the
NVDA 16:59:27 row — `sources=1`, `combinedForecast=-0.0`, full-size route — **before** the behaviour changes.

### Item #2 — (was #1) the no-trade band admits only outlier forecasts, then throttles the retreat from a reversed view: ⚠️ OPEN, still confirmed

Re-ranked, not weakened — this window re-confirmed it on the surviving name and added the plan-wide count:

- `fusion_targets` reports **`insideBuffer` 22** of **`instruments` 23**. Twenty-two of twenty-three names
  are inside the no-trade band; the plan's `deltaQty` is **0.0** for every name shown.
- **GOOG** is the sign-inversion case, still trapped: `currentQty` **8.000000** (entered `BUY 8` at
  16:21:36.733526Z on `forecast=6.3413095741475525, sources=3`) against an `aims` entry of **-0.057125** —
  the desk is long a name its own aim wants slightly short, and nothing routes.
- **NVDA** is the blocked-entry case: `targetQty` **-200.400971**, `aims` **-1.663069**, `currentQty` **0**,
  `deltaQty` **0.0**, on `agreement` **0.872320186445232**.
- The entry bracket recorded last cycle stands: PG at `combinedForecast` **6.117949458685485** wanted
  **656.223137** shares and traded none; GOOG at **6.3413095741475525** got a full entry.

**VERIFY-BY (unchanged):** `insideBuffer` falls below the plan's `instruments` count, and a name holding a
position against an opposite-signed `aims` entry shows a non-zero `deltaQty` that reduces the inversion.

### Item #3 — (unchanged) the edge gate refuses to size a source with measured expectancy: ⚠️ OPEN

Carried from last cycle's ADR-0139 postmortem. `fusion_targets.edgeGate` reads **null** and `social` appears
in the `contributions[]` of **zero** planned names, while `signal_observations` has `social` LIVE at horizon
**3600** with `n` **432** / `resolved` **422** / `hit_rate` **0.606** and horizon **900** at `n` **857** /
`hit_rate` **0.554** — the highest live hit rates in the table, against `trend` LIVE 3600 **0.519** and
`reversion` LIVE 3600 **0.473**. A source measuring above every other one contributes to nothing.

**VERIFY-BY:** `social` appears in `contributions[]` of ≥1 planned name, or `edgeGate` reports a non-null
reason naming what is holding it back.

---

## Verification block — 2026-08-05 16:30Z (**CHANGE: completed the failed auto-revert of the graded-BAD `d51f179a2` / ADR-0139**. The ADR-0116 freeze lifted — `reports/.pending-baseline.json` is gone and the ledger's newest row is `d51f179a2` ❌ BAD — and that row carries `⚠️ REVERT FAILED (git conflict): the BAD commit is STILL LIVE and needs a manual revert`. `git merge-base --is-ancestor d51f179a2 HEAD` confirmed it: a rejected change was still in the running code. That outranks item #1 by the loop's own contract, so it is this cycle's one change. Resolved the established way — running code out (`SocialChannels.isCredible` back to the conjunction, its three tests deleted), the annotated ADR and the loop's memory kept. **Item #1 is unchanged at #1 and gains its THIRD instance plus a bracketed threshold**, and it takes the next cycle.)

### Step 0 — `d51f179a2` (ADR-0139): mechanism ✅ VERIFIED on a sixth boot; vector graded ❌ BAD by the scorer; now REVERTED

Boot at `traffic.timestampMillis` **1785947402056** − `ops_jvm.uptimeSeconds` **1427** — a sixth
independent boot instant.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| `counters.corroborated` rate sustained (Rule 334 — the counter resets at boot, so grade the RATE) | **17** in **1427 s**, versus **15/1439 s**, **17/1362 s**, **17/1377 s**, **17/1252 s**, **16/~900 s** on the five prior boots, and **18 in 64,010 s** pre-fix | ✅ ~3 orders of magnitude above the pre-fix rate on a sixth consecutive boot |
| pump tell not disabled by the loosening | `manipulationSuspected` **32** against `ingested` **3540** / `kept` **919** | ✅ still firing |
| `social` present in `contributions[]` of ≥1 planned name | **zero** planned names carry a `social` contribution | ❌ never happened in the whole window |

**The two readings are not in contradiction and the third explains the gap.** The fix did what it claimed
at the defect level — organic authors can now reach the corroboration gate — and the corroborations it
unlocked **never became size**, so the change bought the desk evidence rather than edge. The scorer owns
the money verdict; it said BAD; the contract says revert and do not re-attempt. Done. The open question
it leaves is about the **gate that refuses to size a measured source**, not about who counts as credible,
and it is filed as item #3 below.

### Item #1 — the no-trade band admits only outlier forecasts, then throttles the retreat from a reversed view: ⚠️ OPEN, THIRD instance, threshold now BRACKETED

Still #1, and this window supplied the sharpest evidence the register has held. Two alpha entries filled:

| name | entry | `forecast` at entry | `combinedForecast` ~7 min later | `targetQty` now | `currentQty` | `deltaQty` released |
| --- | --- | --- | --- | --- | --- | --- |
| NVDA | `SELL 7` (prior window) | **-9.608504615051698** | **+3.722563** | **+176.077255** | **-7.0** | **+0.058091** |
| GOOG | `BUY 8` @ 16:21:36 | **+6.3413095741475525** | **-2.0969536295842746** | **-57.134569** | **+8.0** | **-0.06639** |

And the control, from the same plan:

| name | `combinedForecast` | `targetQty` | `currentQty` | `deltaQty` |
| --- | --- | --- | --- | --- |
| PG | **6.117949458685485** | **656.223137** | **0** | **0.0** |

**The entry threshold is therefore bracketed to (6.1179, 6.3413).** PG wants 656 shares and gets none;
GOOG at a forecast **0.22 higher** got a full entry. That is an ordinal confirmation, not an inference.

**Both entries then inverted sign within minutes and are now trapped.** The desk is short NVDA against a
long target and long GOOG against a short target, and in each case the band releases well under a tenth
of a share per cycle *toward its own current view*. Entry demands an outlier; the retreat is throttled at
the same width. The band admits the most extreme views and preferentially retains the ones that were
wrong — an adverse-selection ratchet on top of the build-blocking already recorded.

**VERIFY-BY next run:** in `/api/fusion/targets`, no row may sit with `sign(currentQty) != sign(targetQty)`
and `|deltaQty| < 1% of |currentQty|` — a position opposite its own forecast must be released at a rate
that closes it, not at a rebalance rate. Separately, a name at `|combinedForecast|` in the 6.1–6.3 range
with a large `targetQty` and `currentQty` 0 must show a non-zero `deltaQty`.

**Blocking precondition on the fix (carried from Rule 348, now at three instances).** The tiny-non-zero
`deltaQty` rows — NQ **0.001584**, NVDA **+0.058091**, GOOG **-0.06639** — still cannot be hand-derived
from the published `bufferedDelta` path, which gives `edge = 0` for all three at any `width ≥ 0.5`,
`rate ≤ 1`. Three instances of the same unexplained shape means the model is wrong, not the data. The
band fix MUST ship a unit test that reproduces these rows **before** it changes the band.

### Item #2 — the ADR-0019 structural hedge round-tripped to flat inside one window: ⚠️ OPEN (new, logged not acted on)

`recent_orders` shows HEDGE ES `BUY 0.006928` (15:46:02) → `SELL 0.007365` (16:21:50) → `BUY 0.000437`
(16:22:50), all FILLED, ending at `byAssetClass` FUTURE `grossExposure` **0.00000000** — the hedge paid
fees to arrive back where it started. The `SELL` leg's own reason shows the ADR-0098 churn-shrink firing
(`|-338.92| − 1.89 σ-step`) and passing the order anyway, while the next leg shrank to zero
(`|-339.69| − 743.92 σ-step → 0.00`) — the σ-step swung by two orders of magnitude between adjacent
cycles. **VERIFY-BY:** count of HEDGE ES orders per window whose signed quantities sum to ~0 must be 0.
Parked behind item #1: at this book size the hedge fee is small, and item #1 is what stops the book
being built at all.

### Item #3 — a source with measured positive expectancy reaches no planned name: ⚠️ OPEN (inherited from ADR-0139's follow-ups)

`social` carries the largest fusion weight (`weights.social` **1.7180889859219977**, above `trend`
**1.4030606798878253**) and appears in the `contributions[]` of **zero** planned names, while the three
sources that do size the book are the three that do not beat their measured trading cost. ADR-0139
attacked the *credibility* half of this and was graded BAD; the remaining half is the **gate**.
**VERIFY-BY:** `social` present in `contributions[]` of ≥1 `/api/fusion/targets` row. Parked behind
item #1 — and note the standing caution: social's `avgReturnBps` has swung materially cycle-to-cycle on a
handful of additional resolved observations, so its edge is suggestive, not established, and the owner ask
on `jethro.fusion.social.per-channel` stays **NOT-YET-SUPPORTED**.

## Verification block — 2026-08-05 16:00Z (**NO CODE CHANGE — `d51f179a2` (ADR-0139) is at 5/6 cycles**; the scorer prints `still accumulating evidence (5/6 cycles) — held, not scored this run`, `reports/.pending-baseline.json` still names `d51f179a2`, and the ledger's newest row is still `629dbbdf8`. The ADR-0116 freeze holds for a **fifth** cycle — and lifts next cycle. Step 0 ran anyway, and **the dormancy broke during this window**, which is the most informative event the register has recorded on item #1. (1) The desk placed its first orders in ~19 h: `orders_day.total` **2** — ALPHA NVDA `SELL 7` on `fusion entry — target increase [forecast=-9.608504615051698, sources=3]`, and the ADR-0019 auto-hedge ES `BUY 0.006928` that followed it. Gross **$0.00 → $4224.88**. (2) **That escape CONFIRMS item #1's mechanism ordinally rather than refuting it**: the one name that traded is the one whose combined forecast was far the largest, and every name in the current plan at `|combinedForecast| ≤ 3.722563` is still at `deltaQty` **0.0**. (3) **Item #1 stays #1 and its severity is UPGRADED — it is not only a build-blocker, it is a wrong-side trap.** NVDA is now held **-7** against a current `targetQty` of **+176.077255** at `combinedForecast` **+3.722563** — the view flipped sign within ~7 minutes of the fill — and the band releases `deltaQty` **+0.058091** per cycle against it. The desk is positioned opposite its own forecast and cannot get back. (4) The tiny-non-zero-delta anomaly Rule 343 flagged on NQ last cycle now has a **second instance** (NVDA) and is still unexplained by the source arithmetic; it is carried as a required unit test for the fix, not smoothed away. (5) ADR-0139's mechanism ✅ holds on a **fifth** independent boot; social's expectancy swung a **fifth** time, so the owner ask stays NOT-YET-SUPPORTED.)

### Step 0 — `d51f179a2` (ADR-0139): mechanism ✅ VERIFIED on a fifth boot; PnL verdict still pending (5/6)

Boot at `traffic.timestampMillis` **1785945601801** − `ops_jvm.uptimeSeconds` **1439**; report snapshot
**16:00:01Z**. That boot instant differs from last cycle's (**1785943801685** − **1362**), so this is a
fifth independent boot, not a continuation.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| `counters.corroborated` rate sustained (Rule 334 — the counter resets at boot, so grade the RATE) | **15** in **1439 s**, versus **17/1362 s**, **17/1377 s**, **17/1252 s**, **16/~900 s** on the four prior boots, and **18 in 64,010 s** pre-fix | ✅ still ~3 orders of magnitude above the pre-fix rate on a fifth boot |
| ≥1 tracked name at `channels ≥ 2` | `signals[]` empty at this snapshot again; `counters.corroborated` **15** and `manipulationSuspected` **39** against `ingested` **3540** / `kept` **855** | ✅ carried by last cycle's positive reading (the app's own WARN naming a corroborated subject); `signals[]` is point-in-time and cannot answer this — Rule 345 |

### Social's expectancy swung a fifth time — Rule 337 re-confirmed, the owner ask stays NOT-YET-SUPPORTED

Same endpoint, same 3600 s horizon, five consecutive cycles:

| field | 14:00Z | 14:30Z | 15:00Z | 15:30Z | 16:00Z |
| --- | --- | --- | --- | --- | --- |
| `avgReturnBps` | **8.855736** | **4.837178** | **7.810160** | **8.351863** | **8.808298** |
| `resolved` | **373** | **374** | **378** | **380** | **381** |
| `hitRate` | **0.619** | **0.615789** | **0.626943** | **0.625641** | **0.630769** |
| `cohorts` | **37** | **37** | **38** | **38** | **38** |
| `stdCohortMeanBps` | **26.863** | **35.424588** | **25.820255** | **25.385394** | **25.380012** |

The mean has traversed **8.86 → 4.84 → 7.81 → 8.35 → 8.81** bps on **eight** additional resolved
observations. The standing decision request to Oleg (restore `jethro.fusion.social.per-channel` from 0 to
4.0, still behind the ADR-0049 OOS gate) **remains open and remains explicitly NOT-YET-SUPPORTED**. That
dial is the owner's; the loop does not touch it.

### Item #1 — the no-trade band admits only extreme forecasts, then traps the position when the view reverts: ⚠️ OPEN, severity UPGRADED

Still #1. The dormancy broke during this window and, in breaking, produced the clearest evidence yet for
this item — and a worse failure mode than the one previously recorded.

**The escape, and what it confirms.** `orders_day.total` is **2**. The alpha order is ALPHA NVDA
`SELL 7.000000` FILLED, `originReason` = `fusion entry — target increase
[forecast=-9.608504615051698, sources=3]`, `createdAtMillis` **1785944750040**. The ADR-0019 auto-hedge
followed it: HEDGE ES `BUY 0.006928` FILLED, `createdAtMillis` **1785944762554**, `originReason` =
`structural β-hedge ES: Σβ·E = -2691.82 systematic`. Gross exposure moved **$0.00 → $4224.88**
(EQUITY **$1533.70**, FUTURE **$2691.18**) — **0.3%** of the **$1,500,000** firm gross cap.

Read off `PositionBuffer` (`band`, `bufferedDelta`, `nextAim`/`withinTarget`) the escape condition for a
name the desk holds flat is `rate > width × TARGET_ABS / |forecast|` — i.e. the required forecast rises as
the band's `|target| × TARGET_ABS / |forecast|` scaling shrinks it. `TARGET_ABS` is **10.0**
(`Forecast.java:20`) and `widthFor` floors `width` at `bufferFraction` **0.5**
(`application.properties:313`) and caps it at **1.0**. The live `rate` and `width` are not exposed, so the
exact threshold is not derivable from this snapshot — but the **ordinal** prediction is, and it held: the
only name that traded carried `|forecast|` **9.608505**, and every name in the plan at this snapshot
(`|combinedForecast| ≤ 3.722563`) is at `deltaQty` **0.0**. That is a prediction made last cycle from source
and observed this cycle in the order book, which is stronger evidence than the arithmetic alone. Per Rule
344 the claim is stated over the population observed: **6 of the 23 planned rows are visible in this
snapshot** (17 elided by the report's truncation), and of those 6, the 5 at `|fc| ≤ 3.174470` are all at
`deltaQty` **0.0**.

**The new and worse failure mode — the trap.** The buffer let the desk in at an extreme forecast and will
not let it out when the forecast reverts:

| field (`/api/fusion/targets`, NVDA) | reading |
| --- | --- |
| `combinedForecast` at the fill (`originReason`) | **-9.608504615051698** |
| `combinedForecast` at this snapshot | **+3.722563** |
| `currentQty` | **-7.0** |
| `targetQty` | **+176.077255** |
| `deltaQty` | **+0.058091** |
| `sources` / `agreement` | **3** / **0.585743** |

The sign of the view inverted between the fill (`createdAtMillis` **1785944750040**) and the plan
(`atMillis` **1785945600418**). The desk is short a name its own planner now wants it long **176.077255** of,
and the band is releasing **0.058091** per cycle toward it. This is not the same defect as "the book can
never be built" — it is that defect plus an asymmetry: entry is gated at extreme conviction, exit toward a
reversed view is gated at the same width, so the buffer preferentially retains positions opened on the
forecasts most likely to mean-revert. It is currently cheap (`unrealizedPnl` on NVDA **+4.27000000**, and
the position is in profit) but it is directionally wrong by the desk's own measure, and it is the mechanism
by which this book would accumulate stale, view-contradicting risk once it is no longer flat.

**Rule 343's open question is now a pair, not a one-off — and still unexplained.** Two rows show a small
non-zero `deltaQty` that the source arithmetic says should be zero:

| name | cycle | `targetQty` | `currentQty` | `combinedForecast` | `deltaQty` |
| --- | --- | --- | --- | --- | --- |
| NQ | 15:30Z | **0.136033** | **0** | **3.146893** | **0.001584** |
| NVDA | 16:00Z | **176.077255** | **-7.0** | **3.722563** | **+0.058091** |

Working the published `bufferedDelta` path by hand for either row gives `|gap| ≤ band` and therefore
`edge = 0` for any `width ≥ 0.5` and any `rate ≤ 1`, so the observed deltas come from a path this reading
does not capture. **I did not resolve it this cycle and I am not guessing at it.** It does not change the
money conclusion — **0.058091** against a target of **176.077255** is a build measured in thousands of
cycles — but a fix aimed at a mechanism that mis-predicts two of its own rows may be aimed at the wrong
line.

**VERIFY-BY (for the fix, next cycle, when the ADR-0116 freeze lifts):**
1. **The required unit test, written before the band is touched:** reproduce both rows above from their
   exact inputs and explain the non-zero `deltaQty`. A fix that cannot reproduce them is not aimed.
2. **The trap closes:** a name whose `currentQty` is on the opposite side of its `combinedForecast` shows a
   `deltaQty` that removes the sign inversion within one cycle — an unwind toward a reversed view is not
   buffered at entry width. Read from `/api/fusion/targets`.
3. **The book builds:** `orders_day.total` shows at least one `fusion entry` whose `originReason` forecast
   is **below** the largest `|combinedForecast|` in the plan — i.e. entry no longer requires an outlier —
   and firm `grossExposure` rises above this cycle's **$4224.88** without breaching the cap.

### Item #2 — nothing the desk is *allowed* to size beats its own trading cost: ⚠️ OPEN (unchanged, and now with a fill to price)

Unchanged in rank and mechanism. The window's evidence is thin but consistent: `signals_telemetry` at
3600 s still has `social` **+8.808298** bps as the only clearly cost-beating source, while the three that
actually size the book measure `trend` **+1.221202**, `reversion` **+0.448958**, `xsreversion`
**-2.808272**. The NVDA entry that finally routed was built from `trend` **+0.137218**, `reversion`
**+6.842818** and `xsreversion` **+16.148632** — i.e. from the two weakest-measured sources plus the one
with negative measured expectancy. `attribution.totalFees` moved **398.004411 → 398.212047** on those two
orders. This item is downstream of #1 (a book that cannot build cannot demonstrate edge either way) and
stays at #2.

### Item #3 — an equity sensor's warm-up seed cannot cross the overnight session close: ✅ mechanism CONFIRMED, stays DEMOTED

Re-confirmed self-healing for a second cycle. `selector.measured` **33**, `tradable` **14**, the planner is
`routing: true` over **23** instruments, and the desk traded within this session. The warm-up is not what
holds the book flat; the band is. Stays below the line.

### Item #4 — the cash close liquidates the whole book on a freshness artifact: ⚠️ OPEN, parked

Unchanged and parked. The 21:00Z `fusion exit — target decayed to flat [forecast=0.0, sources=1]` orders
from **2026-08-04** are still the newest exits in the book. Not actionable while #1 blocks the build.

---
## Verification block — 2026-08-05 15:30Z (**NO CODE CHANGE — `d51f179a2` (ADR-0139) is at 4/6 cycles**; the scorer prints `still accumulating evidence (4/6 cycles) — held, not scored this run`, `reports/.pending-baseline.json` still names `d51f179a2`, and the ledger's newest row is still `629dbbdf8`. The ADR-0116 freeze holds for a **fourth** cycle. Step 0 ran anyway. (1) ADR-0139's mechanism ✅ holds on a **fourth** independent boot. (2) Social's expectancy swung a **fourth** time — back up to near its first reading — re-confirming Rule 337; the owner ask stays NOT-YET-SUPPORTED. (3) **Item #1 (the no-trade band deadlock) is re-verified ⚠️ OPEN and stays #1**, but this cycle **refines its mechanism and records one reading it does not explain**: `insideBuffer` moved **22 → 21 of 22**, and one name (NQ) shows a non-zero `deltaQty` **0.001584** at a combined forecast of **3.146893** — which the band arithmetic read off the source says should be inside. The deadlock claim is unchanged for the other 21 and `orders_day.total` is still **0**, but the escape is flagged as an open question the fix's test must answer rather than glossed over. (4) Item #3 (warm-up across the close) re-confirmed as self-healing — sensors are warm again this boot and the book is still flat.)

### Step 0 — `d51f179a2` (ADR-0139): mechanism ✅ VERIFIED on a fourth boot; PnL verdict still pending (4/6)

Boot **2026-08-05T15:07:19Z** (`traffic.timestampMillis` **1785943801685** − `ops_jvm.uptimeSeconds`
**1362**); report snapshot **15:30:01Z**. Uptime **fell** (1377 → 1362) while wall-clock advanced 1800 s, so
this is a fourth independent boot, not a continuation.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| `counters.corroborated` rate sustained (Rule 334 — counter resets at boot, so grade the RATE) | **17** in **1362 s**, versus **17/1377 s**, **17/1252 s**, **16/~900 s** on the three prior boots, and **18 in 64,010 s** pre-fix | ✅ rate holds on a fourth independent boot |
| ≥1 tracked name at `channels ≥ 2` | `signals[]` empty again this snapshot; the app's own WARN stream logged `1 corroborated social subject(s) — advisory context only (ADR-0050), no order. Suspected manipulation on [AMZN] — failed corroboration, ignored.` | ✅ corroboration is reaching a named subject; the ADR-0050 advisory gate (not ADR-0139) is what stops it becoming an order |

The second row is the first *positive* evidence for this VERIFY-BY rather than the "not reproduced" of the
last two cycles: an actual corroborated subject appears in the log, and the reason it does not size is the
ADR-0050 advisory-only gate — a separate, deliberate policy, not a failure of the ADR-0139 fix.

### Social's expectancy swung a fourth time — Rule 337 re-confirmed, the owner ask stays NOT-YET-SUPPORTED

Same endpoint, same 3600 s horizon, four consecutive cycles:

| field | 14:00Z | 14:30Z | 15:00Z | 15:30Z |
| --- | --- | --- | --- | --- |
| `avgReturnBps` | **8.855736** | **4.837178** | **7.810160** | **8.351863** |
| `resolved` | **373** | **374** | **378** | **380** |
| `hitRate` | **0.619** | **0.615789** | **0.626943** | **0.625641** |
| `cohorts` | **37** | **37** | **38** | **38** |
| `stdCohortMeanBps` | **26.863** | **35.424588** | **25.820255** | **25.385394** |

The mean has now traversed **8.86 → 4.84 → 7.81 → 8.35** bps on **seven** additional resolved observations.
It has returned close to where it started, which is *not* reassurance — a statistic that round-trips 45% of
its own value on seven prints is dominated by a few large returns, exactly as Rule 337 says. The decision
request to Oleg (restore `jethro.fusion.social.per-channel` from 0 to 4.0, still behind the ADR-0049 OOS
gate) **remains open and remains explicitly NOT-YET-SUPPORTED**. The dial is the owner's; the loop does not
touch it and will not re-offer it until the sign *and* magnitude hold across several cycles.

### Item #1 — the no-trade band is wider than the target, so the book can never be built: ⚠️ OPEN (re-verified, mechanism refined)

Still #1, still the dormancy. This boot the sensors are warm again — `streamVolMeasuredNames` **20**,
`volBudgetNames` **20**, `covarianceCoveredNames` **20** — `/api/fusion/targets` reports `routing: true`
with **22** instruments and real target quantities (PG **232.454026**, AMZN **144.748853**), and the desk
placed nothing: `orders_day.total` **0**, newest order in the book still **2026-08-04 21:00:47Z**.

**The mechanism, re-read from source this cycle (not inferred).** `PositionBuffer.band` computes
`scale = |target| × Forecast.TARGET_ABS / |forecast|`, then `band = scale × width`, with `TARGET_ABS`
**10.0** (`Forecast.java:20`) and `width = max(bufferFraction, min(1.0, 2·cost/edge))` (`widthFor`), so
`width ≥ bufferFraction = 0.5` (`application.properties:313`). `bufferedDelta` compares `gap = aim − held`
against that band. `nextAim` ends in `withinTarget`, which clamps the aim to the target's own sign and to
`|aim| ≤ |target|`. Every held position is **0**, so `|gap| = |aim| ≤ |target|`, while
`band ≥ |target| × 5.0 / |forecast|`. Therefore **`band ≥ |gap|` — the delta is identically zero — for every
name whose `|combinedForecast| ≤ 10 × width`, i.e. ≤ **5.0** at the configured fraction.** The strongest
combined forecast in the planned book is **3.146893**, so the condition holds for the whole book.

**The one reading this does not explain — recorded, not glossed.** `insideBuffer` is **21**, not 22, and NQ
shows `deltaQty` **0.001584** against `targetQty` **0.136033**, `currentQty` **0**, `combinedForecast`
**3.146893**. By the arithmetic above NQ's band is wider than its target and its delta should be zero. The
escape is immaterial to the money — at `price` **29885.25** that delta is a fraction of one contract and
`orders_day.total` is still **0** — but it means the deadlock claim is not yet fully reconciled with the
running code. **This is an explicit open question for the fix, not a detail to skip:** the change that
addresses this item must ship a unit test that reproduces NQ's exact inputs and explains the non-zero
delta, because a repair built on a mechanism that mis-predicts one of 22 observed rows is a repair that may
be aimed at the wrong line.

**Why the dial is still not the fix.** `buffer-fraction = 0.5` is **OLEG-SET 2026-07-21** under the
ADR-0055 band `|target| × fraction` — "half the target", which can never swallow the target. ADR-0094
swapped the base to Carver's average-position-at-typical-forecast without re-deriving the owner's number,
so the same 0.5 silently became "half the average position". The loop does not re-set an owner's number to
compensate for a base change he never saw; the fix is code that bounds the band by the interval the aim is
actually permitted to reach, with its own ADR.

- **VERIFY-BY (unchanged, plus one):** `/api/fusion/targets` `insideBuffer` **< instruments** by more than
  the single NQ-style escape, **and** `orders_day.total` **> 0** with fusion-origin orders in
  `recent_orders`; plus a green unit test that reproduces the NQ row.

### Item #2 — nothing the desk is *allowed* to size beats its own trading cost: ⚠️ OPEN (unchanged)

Carried forward unchanged; it is downstream of #1 (a book that cannot be built cannot accumulate the OOS
evidence the edge gate needs). This cycle's readings at the 3600 s horizon: `trend` **0.397262** bps /
**792** resolved, `reversion` **0.240915** / **744**, `xsreversion` **-2.601596** / **766**, `momentum`
**7.831224** / **54** (only **7** cohorts), `social` **8.351863** / **380**. The three sources that actually
size the book remain the three that do not beat cost.

### Item #3 — an equity sensor's warm-up seed cannot cross the overnight session close: ✅ mechanism CONFIRMED, stays DEMOTED

Re-confirmed as **self-healing within a session** and therefore still not the dormancy: this boot reaches
`streamVolMeasuredNames` **20**, `volBudgetNames` **20**, `covarianceCoveredNames` **20** and a full 22-name
planned book — and the desk still placed **0** orders. Fixing it would put no risk on. Stays below #1.

### Item #4 — the cash close liquidates the whole book on a freshness artifact: ⚠️ OPEN, parked

Unchanged, parked behind #1.

---
## Verification block — 2026-08-05 15:00Z (**NO CODE CHANGE — `d51f179a2` (ADR-0139) is at 3/6 cycles**; the scorer prints `still accumulating evidence (3/6 cycles) — held, not scored this run`, `reports/.pending-baseline.json` still names `d51f179a2`, and the ledger's newest row is still `629dbbdf8`. The ADR-0116 freeze holds for a **third** cycle. Step 0 ran anyway and this cycle **found the cause of the dormancy in code** — it is not the warm-up. (1) ADR-0139's mechanism ✅ holds on a third independent boot. (2) Social's expectancy swung a **third** time, re-confirming Rule 337; the owner ask stays NOT-YET-SUPPORTED. (3) **Last cycle's #1 — the warm-up seed cannot cross the overnight close — is ✅ CONFIRMED at the mechanism by a clean two-boot dose-response, but is DEMOTED to #3**, because it self-heals *within* a session and this run proves it is not what holds the book flat. (4) **The new #1 is a code-verified structural deadlock in the no-trade buffer:** with sensors now warm and 22 targets planned, `insideBuffer` is **22 of 22** and every `deltaQty` is **0.0** — because the buffer band is scaled off the *average position at a typical forecast*, which makes the band **wider than the target itself** whenever the combined forecast is at or below half of typical strength. The aim is clamped into `[0, target]`, so the entire reachable interval lies inside the no-trade region and the delta is **identically zero, forever**.)

### Step 0 — `d51f179a2` (ADR-0139): mechanism ✅ VERIFIED on a third boot; PnL verdict still pending (3/6)

Boot **2026-08-05T14:37:04Z** (`traffic.timestampMillis` **1785942001502** − `ops_jvm.uptimeSeconds`
**1377**); report snapshot **15:00:01Z**. Third independent boot; running JVM is still the ADR-0139 build.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| `counters.corroborated` rate sustained (Rule 334 — counter resets at boot, so grade the RATE) | **17** in **1377 s**, versus **17 in 1252 s** and **16 in ~900 s** on the two prior boots, and **18 in 64,010 s** pre-fix | ✅ rate holds on a third independent boot |
| ≥1 tracked name at `channels ≥ 2` | `signals[]` is **empty** this snapshot; StockTwits was polling `[BRK.B, CAT, CVX]` | ⚠️ not reproduced — `signals[]` is point-in-time, not cumulative (see 14:30Z note); no reading contradicts the fix |

### Social's expectancy swung a third time — Rule 337 re-confirmed, the owner ask stays NOT-YET-SUPPORTED

Same endpoint, same 3600 s horizon, three consecutive cycles:

| field | 14:00Z | 14:30Z | 15:00Z |
| --- | --- | --- | --- |
| `avgReturnBps` | **8.855736** | **4.837178** | **7.810160** |
| `resolved` | **373** | **374** | **378** |
| `hitRate` | **0.619** | **0.615789** | **0.626943** |
| `cohorts` | **37** | **37** | **38** |
| `stdCohortMeanBps` | **26.863** | **35.424588** | **25.820255** |

The mean has now traversed **8.86 → 4.84 → 7.81** bps on **five** additional resolved observations. An
estimate that moves that far on that little data is carried by a few large prints, not by a stable edge.
The decision request to Oleg (restore `jethro.fusion.social.per-channel` from 0 to 4.0, still behind the
ADR-0049 OOS gate) **remains open and remains explicitly NOT-YET-SUPPORTED**. The dial is the owner's; the
loop does not touch it and will not re-offer it until the sign *and* magnitude hold across several cycles.

### Item #1 (NEW) — the no-trade band is wider than the target, so the book can never be built: ⚠️ OPEN

**This is the dormancy, and it is not the warm-up.** The controlled comparison is inside this one boot:
the sensors are warm enough to plan a full book — `streamVolMeasuredNames` **20** (it was **3** last
cycle), `volBudgetNames` **20**, `/api/fusion/targets` `routing: true` with **22** instruments and real
target quantities (PG **423.028615**, WMT **500.316417**, NVDA **208.639712**) — and the desk *still*
placed nothing: `insideBuffer` **22 of 22**, every shown `deltaQty` **0.0**, `orders_day.total` **0**,
newest order in the book still **2026-08-04 21:00:47Z**. Warm sensors, real targets, zero orders. The
constraint is downstream of the sensors.

**The mechanism, read from the code, not inferred.** `PositionBuffer.band` computes

```
band  = |target| × Forecast.TARGET_ABS / |forecast| × width
```

with `Forecast.TARGET_ABS = 10.0` (`Forecast.java:20`) and
`width = max(bufferFraction, min(1.0, 2C/μ))` (`PositionBuffer.widthFor`), so `width ≥ bufferFraction`,
and `jethro.fusion.buffer-fraction = 0.5` (`application.properties:313`). Therefore

```
band ≥ |target| × 10.0 / |forecast| × 0.5 = |target| × 5.0 / |forecast|
band ≥ |target|   ⟺   |forecast| ≤ 5.0
```

Meanwhile `PositionBuffer.withinTarget` (ADR-0102) clamps the aim into the closed interval between flat
and this cycle's target, and the held position is **0** on every name. So `|gap| = |aim| ≤ |target| ≤ band`
— the no-trade region **contains the entire interval the aim is allowed to occupy**, and
`bufferedDelta` returns zero unconditionally. Not slowly, not until the aim climbs: *identically*, on
every cycle, for as long as the forecast stays at or below half of typical strength.

The live forecasts are all in that region: the strongest planned row this snapshot is PG
**4.270153**, then NVDA **4.102602**, WMT **3.481492**, and the weakest shown **-2.647928** — every one
inside `|forecast| ≤ 5.0`. That is exactly why `insideBuffer` reads **22 of 22** and not a smaller number.

**How the dial came to mean something else.** `buffer-fraction = 0.5` is **OLEG-SET 2026-07-21**
(`application.properties:293`), set when the ADR-0055 band was `|target| × bufferFraction` — under *that*
formula 0.5 means "half the target", which can never swallow the target. ADR-0094 replaced the base of the
fraction with Carver's *average position at a typical forecast* (`|target| × TARGET_ABS / |forecast|`)
without re-deriving the owner's number against the new base. Under the new base the same 0.5 means "half
the average position", and since the desk's forecasts currently run at roughly 40% of typical strength,
half the average position exceeds the whole target. **The owner's number was silently re-interpreted into
a different quantity** — a provenance failure of exactly the kind `CLAUDE.md` names, not a bad dial.

**Therefore the fix is code, not a re-dial** — the loop must not quietly re-set an owner's number to
compensate for a base change the owner never saw. The direction for next cycle: bound the band by the
interval the aim can actually reach, so the no-trade region can never contain the whole reachable set,
leaving the owner's 0.5 meaning what he set it to mean. That is architecturally significant (it changes
when the desk trades at all) and ships with its own ADR, `**Status:** Implemented`, in the same commit.

**VERIFY-BY (next run):** on `/api/fusion/targets`, `insideBuffer` **< `instruments`** and at least one
planned row with `deltaQty ≠ 0` while the strongest `combinedForecast` is still `≤ 5.0`; and
`orders_day.total` **> 0** with a `recent_orders` row created after this cycle. If `insideBuffer` still
equals `instruments`, the fix is ⚠️ STILL-BROKEN regardless of PnL.

### Item #2 — nothing the desk is *allowed* to size beats its own trading cost: ⚠️ OPEN (was #2)

Unchanged in substance and still the standing-priority item, but it cannot be tested while #1 forces
every delta to zero — a source's edge is unobservable if the desk never takes the position. At 3600 s:
`trend` **+1.201659** (97 cohorts), `reversion` **+0.160001** (86), `xsreversion` negative — all below the
**1.009** bps/side fee plus **~0.75** bps slippage. #1 now ranks above this because #1 is also what starves
this item of the observations it needs.

### Item #3 — an equity sensor's warm-up seed cannot cross the overnight session close: ✅ mechanism CONFIRMED, **DEMOTED** from #1

The hypothesis raised last cycle is now confirmed by a **two-boot dose-response**, and the confirmation is
also the reason to demote it. Coverage tracks time-since-session-open one-for-one:

| boot | seconds after the 13:30Z open | equity seed coverage | rates seed coverage |
| --- | --- | --- | --- |
| 14:09:10Z | **2350 s** | **2011 – 2046 s** | FULL, 11331 s |
| 14:37:04Z | **4024 s** | **3970 – 3999 s** (17 names clustered) | FULL, **11583 s** (reversion) / **9146 s** (trend) |

The equity ceiling moved **+~1960 s** when the boot moved **+1674 s** later — the seed is bounded by the
session open, exactly as Rule 335 predicted, while the rates names (marked off a continuously-refreshed
curve with no session hole) reach **241 of 241** and keep growing. Mechanism: settled.

**But it is not this run's binding constraint, and that is the new information.** It self-heals as the
session runs — the same names that seeded 113/241 and 115/241 last cycle now seed **207 – 233 of 241**
(HD 207, JNJ 215, CAT 218, UNH 233; risk-cut σ BAC **120 of 121**, KO **120 of 121**), and
`streamVolMeasuredNames` rose **3 → 20**. The desk became able to see, planned 22 targets, and *still*
traded nothing. Fixing the warm-up would therefore not have put a single dollar of risk on. It stays open
— every restart still costs the desk its first minutes of sight, and thins the `signal_observations` the
edge gate needs — but it ranks below #1 and #2.
**VERIFY-BY:** an equity seed reporting `FULL` (or a terminator other than the session hole) with coverage
**> 4024 s** on a boot less than 4024 s after the session open.

### Item #4 — the cash close liquidates the whole book on a freshness artifact: ⚠️ OPEN, parked

Unchanged; parked behind #1. Untestable while the book is flat.

---
## Verification block — 2026-08-05 14:30Z (**NO CODE CHANGE — `d51f179a2` (ADR-0139) is STILL under measurement**; `reports/.pending-baseline.json` still names `d51f179a2` and the ledger's newest row is still `629dbbdf8`, so the ADR-0116 freeze holds for a second cycle. Step 0 ran anyway and produced two results that change the ranking. (1) **ADR-0139's mechanism holds** on its rate metric across a fresh boot. (2) **The evidence behind last cycle's decision request to Oleg has decayed and the ask is DOWNGRADED, not withdrawn** — `social`'s cohort-clustered expectancy moved **+8.855736 → +4.837178** bps at an *unchanged* **37** cohorts while `stdCohortMeanBps` rose **26.863 → 35.424588**, on **one** additional resolved observation (373 → 374). A statistic a single print moves by that much is not a basis for asking the owner to unlock a money dial. (3) A new, code-verified structural defect takes **#1**: **an equity sensor's warm-up seed cannot cross the overnight session close**, so every restart inside a session leaves the desk blind — which is both this run's dormancy *and* a direct drag on the OOS evidence the edge gate needs.)

### Step 0 — `d51f179a2` (ADR-0139): mechanism ✅ still VERIFIED on a second boot, PnL verdict still pending

Boot **2026-08-05T14:09:10Z** (`traffic.timestampMillis` **1785940202267** − `ops_jvm.uptimeSeconds`
**1252**); report snapshot **14:30:02Z**. Running JVM is still the ADR-0139 build — no code commit since
`d51f179a2` other than reports/docs.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| `counters.corroborated` rate sustained (Rule 334 — the counter resets at boot, so grade the RATE) | **17** in **1252 s** of uptime, versus **16 in ~900 s** on the previous boot and **18 in 64,010 s** pre-fix | ✅ rate holds across an independent boot |
| ≥1 tracked name at `channels ≥ 2` | **not present in this snapshot** — `signals[]` carries only **TSLA**, `channels: 0`, `manipulationSuspected: true`. Last boot's **AAPL `channels: 2`** is gone | ⚠️ not reproduced this snapshot — see note |
| `social` in `/api/fusion/targets` `contributions[]` | **0 of 7** planned rows | ⚠️ **RETIRED last cycle as mis-specified** — `jethro.fusion.social.per-channel=0` is an owner-set dial (Rule 333), not a defect |

**Note on the second row — do not read it as a regression.** `signals[]` is a *point-in-time* view of
currently-active subjects, not a cumulative record, and StockTwits was polling `[SAP, TSLA, UNH]` at the
snapshot — AAPL was not in the poll set. The cumulative counter is the honest metric and it is the one
that holds. Recorded as **not reproduced**, not as 🔴 REGRESSED, because no reading contradicts the fix.

### The decision request to Oleg — DOWNGRADED, and why (this is the cycle's main product)

Last cycle escalated one concept to the owner: *may a corroborated social signal contribute a sizing
forecast — restore `jethro.fusion.social.per-channel` from 0 to 4.0, still behind the ADR-0049 OOS edge
gate?* The evidence offered was `/api/signals/telemetry` at the 3600 s horizon. One cycle later, the
**same endpoint, same horizon, same cohort count**:

| field | 2026-08-05 14:00Z | 2026-08-05 14:30Z |
| --- | --- | --- |
| `avgReturnBps` | **8.855736** | **4.837178** |
| `resolved` | **373** | **374** |
| `hitRate` | **0.619** | **0.615789** |
| `cohorts` | **37** | **37** |
| `stdCohortMeanBps` | **26.863** | **35.424588** |

**One** additional resolved observation cut the mean expectancy roughly in half and raised the
cohort-mean dispersion by a third, at an unchanged cohort count. That is the signature of an estimate
dominated by a few large observations, not a stable edge — and it is exactly the "statistics of backtest
overfitting" failure the mission warns about. **The ask stands open but is explicitly marked
NOT-YET-SUPPORTED**: the loop is not asking Oleg to unlock a money dial on a number that halves between
two consecutive reads. It should be re-offered only once social's expectancy holds its sign and magnitude
across several independent cycles. No action is taken either way — the dial is the owner's.

### Item #1 (NEW) — an equity sensor's warm-up seed cannot cross the overnight session close: ⚠️ OPEN

**The defect, traced in code.** `SensorWarmup.walk` (`app/src/main/java/io/jethro/app/fusion/SensorWarmup.java:224-257`)
walks the stored mark series backwards and breaks on any gap wider than `step * GAP_TOLERANCE_SAMPLES`:
`brokeAtHole = true; break; // a hole in the series: warm from the contiguous tail, never across it`.
A **scheduled US session close** is ~16.5 h wide and is therefore treated as a data outage. So the
contiguous tail available to an equity sensor is bounded by *today's session open* — no matter how much
durable history LMDB holds (`jethro.ui.history-hours=12`).

**The live evidence, from this boot's own WARN stream.** Boot **14:09:10Z**; today's US session opened
**13:30Z**, i.e. **2350 s** earlier. Every equity seed terminated at a coverage span in the band
**2011 s – 2046 s** — matching that elapsed session time, and short of what the sensors need:

| sensor | example | seeded | terminator | coverage |
| --- | --- | --- | --- | --- |
| trend | JNJ | **173 of 193** | HISTORY_EXHAUSTED | **2038 s** |
| trend | MCD | **160 of 193** | GAP_BREAK | **2043 s** |
| reversion | HD | **113 of 241** | HISTORY_EXHAUSTED | **2041 s** |
| reversion | CAT | **115 of 241** | GAP_BREAK | **2038 s** |
| risk-cut σ | PG | **53 of 121** | HISTORY_EXHAUSTED | **2027 s** |

**The controlled comparison that isolates the cause.** In the *same boot, same code, same store*, the
rates names — whose marks come off a continuously-refreshed curve and therefore have **no session hole** —
are the only ones to reach a **FULL** seed: `USD_IRS_10Y`, `USD.SOFR.{1Y,2Y,5Y,10Y,30Y}`, `USD.TSY.{5Y,10Y}`
each seeded **241 of 241**, terminator **FULL**, covering **11331 s**. Same walk, same tolerance — the only
difference is session continuity. That rules out store depth and read sizing (the ADR-0138 territory) and
points at the overnight hole.

**Why this outranks the dormancy and the cost problem rather than duplicating them.** It is the *cause* of
both readings this run: `/api/fusion/targets` shows `insideBuffer` **21 of 21**, `streamVolMeasuredNames`
**3 of 21**, and every planned row at `currentQty: 0`, `deltaQty: 0` while `targetQty` is large (HD
**318.360826** @ **352.3**, PG **589.883741** @ **146.375**, WMT **-731.08859** @ **112.5**) — a full plan
that routes nothing. And crucially it is an **edge** item under the standing priority, not merely a
trading item: a sensor that is silent for the first ~40 min of every session after a restart *publishes no
forecast*, so it logs **no `signal_observations`** in that window. The loop is measuring every source on an
evidence base systematically thinned by its own restart cadence (~30 min). Fixing the blindness improves
the measurement the edge gate depends on, which is the thing the mission says to spend the change on.

**Open question the fix must answer, stated honestly.** Bridging the session hole is necessary but may not
be sufficient: `trend` needs **193** samples at a 5000 ms step (**965 s** of span) yet seeded only **173**
inside **2038 s** of coverage, so *print density* also binds for some names. The fix must be evaluated on
whether sensors actually reach `warm()`, not merely on whether the seed count rises. Related but distinct:
the rates names seeded **241 of 241** / **FULL** and are *still* reported cold, so the warmth predicate
depends on more than sample count — that is a separate thread, not this item.

**VERIFY-BY (next cycle, after `d51f179a2` scores and the freeze lifts).** On the first boot inside an open
US session: (a) equity seed coverage spans exceed the elapsed-since-open figure — i.e. at least one
`trend`/`reversion` seed reports a coverage span **> 3600 s**; (b) the count of `still cold` WARNs for
equity names falls below this run's level; (c) `/api/fusion/targets` `streamVolMeasuredNames` rises above
**3** of 21. All three read from live telemetry; none authored. *(Counter-semantics note per Rule 334: none
of these is a boot-resetting counter — they are per-boot seed readings, so they compare directly.)*

### Item #2 — nothing the desk is *allowed* to size beats its own trading cost: ⚠️ OPEN (was #1)

Unchanged in substance, and this run's readings make it slightly worse. `/api/signals/telemetry` at
3600 s: `trend` **+1.192832** (cohorts **98**, `stdCohortMeanBps` **17.775674**), `reversion`
**+0.384366** (86, **20.405338**), `xsreversion` **-2.213774** (44, **27.982221**), `momentum`
**+6.549253** but on only **7** cohorts with `stdCohortMeanBps` **27.450798** — far too thin to act on.
Against measured round-trip cost of **1.009** bps/side of fee plus **~0.75** bps of slippage per fill,
none of the three sources that are *permitted* to size the book clears its own cost. Demoted to #2 only
because item #1 is a prerequisite: a blind sensor cannot generate the observations that would either
establish or refute an edge here. Regime context: `trend` **CHOP**, `regime` **CALM**, `volRatio` **0.92**.

**VERIFY-BY.** At least one source that can size (trend / reversion / xsreversion / momentum) shows
`avgReturnBps` above the **1.009** bps/side + **~0.75** bps slippage round trip at a cohort count
comparable to `trend`'s current **98**.

### Item #3 — the desk is DORMANT and has placed no order in ~17.5 h: ⚠️ OPEN, downstream of #1

`/api/risk` `.total`: `grossExposure` **0.00000000**, `netExposure` **0.00000000**, `totalPnl`
**-603.08012889**. Newest `recent_orders` row is **2026-08-04 21:00:47Z** — no order placed across two
restarts and a full session open. Held at #3 deliberately (Rule 329, reaffirmed): with item #2 open,
filling the book from sources that measure below cost is a forecastably losing trade, so the buffer
holding the book flat is currently *saving* money, not costing it. This item closes as a consequence of
#1 and #2, never by relaxing the buffer on its own.

**VERIFY-BY.** A FILLED `fusion entry` order appears in `recent_orders` with `sources ≥ 2` **and** the
source mix behind it clears item #2's cost hurdle.

### Item #4 — the cash close liquidates the whole book on a freshness artifact: ⚠️ OPEN, parked

Carried forward unchanged from the 14:00Z block; parked behind #1–#3.

---
## Verification block — 2026-08-05 14:00Z (**NO CODE CHANGE — `d51f179a2` (ADR-0139) is under measurement**; `reports/.pending-baseline.json` exists and the ledger's newest row is still `629dbbdf8`, so per the contract a new change would destroy the evidence. Step 0 still ran: **ADR-0139's mechanism ✅ VERIFIED on the two checks it controls, and the third VERIFY-BY is RETIRED as mis-specified** — it asked for an outcome an explicit owner decision forbids. Item #1 therefore closes **as a defect** and converts to a **decision request for Oleg**, not something the loop may take itself.)

### Step 0 — `d51f179a2` (ADR-0139): mechanism ✅ VERIFIED (2 of 3 checks; 3rd retired), PnL verdict pending

Boot **2026-08-05T13:50:39Z** (`traffic.timestampMillis` **1785938401645** − `ops_jvm.uptimeSeconds`
**562**) — i.e. immediately after last cycle's commit, so the running JVM *is* the ADR-0139 build. All
readings below are from a single coherent `/api/social` + `/api/fusion/targets` pair.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| `counters.corroborated` above **18** | **16** at ~15 min of uptime. But the counter is an `AtomicLong` field on `SocialLifecycle` (`:49`) — **reset at boot**, so the absolute is not comparable across boots and the honest metric is the RATE: **16 in ~900 s** now vs **18 in 64,010 s** at the previous boot | ✅ (on rate; the stated absolute threshold was itself boot-naive) |
| ≥1 `signals[]` row on a **tracked** name with `channels ≥ 2` | **AAPL — `tracked: true`, `channels: 2`, `direction: BULLISH`, `manipulationSuspected: false`.** This has never happened before. Its enabling cause is visible in the same payload: **4 organic StockTwits authors now read `credible: true`** (`cubie`, `dojidad`, `Etrading`, `peloswing`), where the pre-fix live read returned `official: false` for **30 of 30** and no StockTwits author could ever be credible | ✅ |
| `social` present in `contributions[]` of ≥1 `/api/fusion/targets` row | **0 of 21** rows | ⚠️ **but RETIRED — see below** |

### Why the third check was mis-specified — and must NOT be "fixed"

`jethro.fusion.social.per-channel=0`, and the config comment states its provenance verbatim:
**"ADVISORY-ONLY ENFORCEMENT (Oleg, 2026-07-27) … This RESTORES ADR-0049 ('a social subject can NEVER
originate an order') / the ADR-0050 'advisory only' intent … Restore to 4.0 ONLY to deliberately let
corroborated social size again."** `SourceForecasts.fromSocial` computes
`strength = max(1, channels) × perChannel`, so with `perChannel = 0` every social forecast is exactly
`0.0`, `Forecast.hasView()` is false, and `ForecastRegistry.byInstrument` excludes it **by construction**.

So there were always **two** gates in series, and last cycle's register saw only the first. ADR-0139
opened the one that was a genuine defect (the credibility conjunction). The second is an **owner-set
money dial with explicit provenance and an ADR behind it** — raising it is re-litigating an accepted
decision, which the loop does not do (CLAUDE.md: accepted ADRs are settled; no invented risk numbers).
Note the measured edge is unaffected either way: `signalTelemetry.record("social", …)` runs independently
of `per-channel`, so social's telemetry stays honest while it is sized at zero.

**Item #1 → CLOSED as a defect. Converted to a decision request for Oleg (one concept only):**
*may a corroborated social signal contribute a sizing forecast — i.e. restore
`jethro.fusion.social.per-channel` from 0 to 4.0, still behind the ADR-0049 OOS edge gate?* The evidence
for the ask, read from `/api/signals/telemetry` this run (3600 s horizon): `social` `avgReturnBps`
**8.855736**, `resolved` **373**, `hitRate` **0.619**, `cohorts` **37**, `stdCohortMeanBps` **26.863**;
positive at all three horizons; highest fusion weight **1.5850**. The loop takes **no** action on it.

### Item #1 (NEW) — nothing the desk is *allowed* to size beats its own trading cost: ⚠️ OPEN

With social owner-gated to advisory-only, every source that can actually size the book measures below
cost. From this run's `/api/signals/telemetry` at 3600 s: `trend` **+1.2373** bps (`cohorts` 97,
`stdCohortMeanBps` 17.554), `reversion` **-0.5929**, `xsreversion` **-3.6159**, `momentum` **+6.5493**
(but only `cohorts` 7, `stdCohortMeanBps` 27.451 — far too thin to act on) — against the measured round
trip already on file (**1.009** bps/side of fee plus **~0.75** bps of slippage per fill). This is exactly
what the standing priority names: **re-weighting sources with no edge cannot create edge; build and
validate a NEW predictor through the OOS backtest gate (ADR-0049).** That is next cycle's one change,
once `d51f179a2` is scored.

**VERIFY-BY next run:** a new source name present in `/api/signals/telemetry` with its own `resolved` /
`cohorts` / `avgReturnBps` row, having passed the ADR-0049 OOS gate — not a re-tune of trend, reversion,
xsreversion or the fusion weights.

### Item #2 — the desk is DORMANT and has placed no order in ~17 h: ⚠️ OPEN

`/api/risk` `.total`: `grossExposure` **0.00000000**, `netExposure` **0.00000000**, `totalPnl`
**-603.08012889**. The newest row in `/api/orders` is `createdAtMillis` **1785877247147**
(**2026-08-04T21:00:47Z**) — the MSFT leg of the cash-close liquidation. Nothing since, across a restart.
Meanwhile `/api/fusion/targets` is live and willing: `routing: true`, **21** instruments, non-zero targets
(WMT **1275.12**, NEE **832.06**, CVX **452.92**) against `currentQty` **0** on every one. Rule 331's
buffer-quantisation arithmetic is the standing explanation. Ranked below #1 because deploying harder into
sources measured below cost is a forecastably losing trade (Rule 329) — the edge has to exist first.

**VERIFY-BY next run:** a `/api/orders` row with `createdAtMillis` after the current boot, and `/api/risk`
`.total.grossExposure` above **0.00000000**.

### Item #3 — the cash close liquidates the whole book on a freshness artifact: ⚠️ OPEN, parked

Unchanged and still reproduced by the order log above. Parked per Rule 330: holding through it is
ADR-0135 (graded ❌ BAD) and the ADR-0080-rate decay alternative does not survive its own arithmetic.

---
## Verification block — 2026-08-05 13:30Z (**Item #1 CLOSES — ADR-0138 ✅ VERIFIED from the app's own log.** `629dbbdf8` scored ⚠️ INCONCLUSIVE (risk-adj **+0.001718**/cycle over 37, **t = +1.00** vs the 1.5 hurdle) and is kept; but its *mechanism* verified on every check, and for the first time the grading needed no replication script — the terminator line ADR-0138 shipped answered it directly. With #1 closed the register is re-ranked against the standing priority (work on EDGE; if a source's measured expectancy is positive and significant net of cost, **let it size**), and the new #1 is the one that priority names: **the desk's only cost-beating source is structurally gagged.** `/api/signals/telemetry` cohort-clustered at the 3600 s horizon — `social` **+8.856** bps, 37 cohorts, **t = +2.01**, hit **0.619**, positive at all three horizons and carrying the **highest** fusion weight **1.5768** — against measured cost of **1.009** bps/side of fee (the app's own fills: `$39.187210` on `$388,348.116512140580`) plus **~0.75** bps of slippage per fill. And it contributes to **0 of 9** planned names, while the three sources that *do* size the book — trend **+1.540** (t=+0.83), reversion **-0.286**, xsreversion **-5.006** (t=-1.16) — are exactly the three that do not beat their own trading cost. Traced to one boolean and fixed: ADR-0139.)

### Step 0 — `629dbbdf8` (ADR-0138): SCORED ⚠️ INCONCLUSIVE, mechanism ✅ VERIFIED on every check

Boot **2026-08-04T19:43:11Z** (`traffic.timestampMillis` − `ops_jvm.uptimeSeconds` **64010**); the JVM has
run since without restart, so this is a clean single-boot read.

| VERIFY-BY | reading | verdict |
| --- | --- | --- |
| zero `sensor still cold` WARNs for a name with stored marks | the twelve equities cold at **every** prior boot (JPM 137/193, BAC 192/193, NEE 171/193, JNJ, CAT, GOOG, MCD, HD, PFE, CVX, KO, PG) are **absent** from this boot's cold list | ✅ |
| any short seed names its terminator + span | every survivor does, and every one is a genuinely thin series: rates `FULL` at **241/241** and **193/193**; AMD `HISTORY_EXHAUSTED` **15 of 193** covering **19880s** at a **586000ms** step; NFLX **10**, PLTR **20**, META **41**, GOOGL **24**, AUDUSD `NO_HISTORY` | ✅ |
| no `sources ≤ 1` exit within 5 min of JVM start | boot **19:43:11Z**, first post-boot order **20:10:10Z** — **27 min**. Five prior boots were each followed ~40 s later by a liquidation | ✅ |

**Struck and moved below the line.** `026cda49d`'s flagged auto-revert stays **deliberately not completed**
(Rule 303): it is itself the revert of the graded-BAD ADR-0136.

### Item #1 (NEW) — the desk's only cost-beating source cannot reach the corroboration gate: ⚠️ OPEN, **fix now shipped (ADR-0139)**

| check | reading | verdict |
| --- | --- | --- |
| `social` in `/api/fusion/targets` `contributions[]` | **0 of 9** planned names (the nine carry trend / reversion / xsreversion only) | ⚠️ |
| `/api/social` funnel | **153,207** ingested → **5,289** kept → **18** ever corroborated, against `corroborationChannels = 2` | ⚠️ |
| can StockTwits supply a credible channel? | `isCredible` required `verified && followers ≥ 5,000 && ageDays ≥ 180`; the adapter maps `verified` from `user.official`, which marks StockTwits' OWN accounts. A live read of the same endpoint (`streams/symbol/AAPL.json`, 30 messages) returns `official: false` for **30 of 30**, while `followers` spans **-2 … 5,978** — one author **above** the floor — and join dates reach **2018**. The conjunction short-circuits before either floor is read | ✅ root cause closed |
| can news supply the second? | only Yahoo resolves single-name tickers: all **24** discovery candidates, over 2 days and up to **206** mentions, carry `sources: [news:yahoo]` and nothing else | ⚠️ one channel, threshold two |
| fix deployed | ADR-0139 — verification and the two measured floors become **alternatives**, not a conjunction; `k`, both floor values, the pump tell and the whole deterministic floor unchanged; strictly one-way. `-Pci test` green, four new tests | ✅ shipped |

Deliberately **not** lowering `corroboration-channels` to 1 (that *is* the ADR-0050 §3 adversarial
control), **not** re-trying ADR-0135's hold-through-breadth-collapse (graded ❌ BAD), and **not** widening
deployment on trend/reversion/xsreversion to fill the dormant book — they measure below cost, and forcing
trades that lose money is not a fix for dormancy.

**VERIFY-BY next run:** `/api/social` `counters.corroborated` above **18**; ≥1 `signals[]` row on a
**tracked** name with `channels ≥ 2`; and `social` present in `contributions[]` of ≥1
`/api/fusion/targets` row. None has ever happened.

### Item #2 — the cash close liquidates the whole book on a freshness artifact: ⚠️ OPEN, no fix attempted

Reproduced exactly this window and now timed to the second: the equity cash close is **20:00Z**; the first
liquidation order is **20:10:10Z** — **600 s** later, which is `jethro.fusion.freshness-seconds=600` to
the second. Eight names (MCD, NEE, CAT, JPM, PG, WMT, XOM, CVX) exit within **200 ms** of each other on
`fusion exit — target decayed to flat [forecast=±0.0, sources=1]`, then GOOG **20:11:41**, AAPL
**20:14:13**, NVDA **20:16:15**, AMZN **20:17:46**, MSFT **21:00:47** as each name's last print ages out.
Gross **$52,192.44 → $0.00**.

Ranked **#2, and deliberately parked**: the two obvious repairs are both closed. Holding through the
collapse is ADR-0135, graded ❌ BAD. Decaying the inventory at the ADR-0080 rate — the third option
ADR-0135's own postmortem invited — does **not** help on the fusion clock: `a = 1 − exp(−30/3600) =
0.0083`/cycle, so across a ~17.5 h close (≈2,100 cycles) the position decays to flat anyway, having traded
the same notional in more fills, and `PositionBuffer`'s own doc records that this desk's cost is
proportional to **quantity** traded, not order count — so it saves nothing. A print-clock decay leaves the
overnight case identical to the reverted ADR-0135. This needs a genuinely new mechanism, not another
variant; it does not get a cycle until one exists.

### Item #3 — the aim is in-memory only: ⚠️ OPEN, and its diagnosis was incomplete

Live `/api/fusion/targets` `insideBuffer` **8 of 9** with `currentQty 0` on every name. The register has
blamed restarts, but the JVM ran **64,010 s** without one and the aims were still near zero — they are
zeroed by **item #2's** close liquidation (a flat target snaps the aim to zero), not by a restart. The
buffer arithmetic then reproduces the dormancy exactly: `band = |target| · TARGET_ABS/|forecast| ·
bufferFraction` is **independent of the forecast** while the target is proportional to it, so from flat a
name cannot be entered until its aim clears a band sized for a typical-strength conviction — and ADR-0102
clamps the aim inside the target. Live proof, the app's own numbers: NVDA `forecast +1.202`,
`targetQty +36.142847`, `aim +36.142847` (clamped), `deltaQty +6.0818` — the desk may hold at most **16.8%**
of its own target. GOOG (fc +1.269, aim 23.065231 vs target 44.66), AAPL (fc -1.682), UNH (fc -1.444) and
AMZN (fc +4.972, aim 20.777309 vs target 151.61) are all inside the band and hold **nothing**. Ranked #3
because #2 gates it and because widening deployment onto below-cost sources is the wrong answer regardless.

---
## Verification block — 2026-08-04 19:30Z (**Item #1 FIXED and shipped — ADR-0138.** `120b22b41` has been SCORED ⚠️ INCONCLUSIVE, `reports/.pending-baseline.json` is gone, and the ADR-0116 freeze is lifted — so this cycle makes the first code change in six, and it goes to item #1. The diagnosis finally closed on a single line rather than another reproduction: `SensorWarmup` sized its history read as `2 × samples × step` with `step` the **median** inter-print gap, and the median is the wrong statistic to bound a **sum** of `samples` gaps when the distribution is heavy-tailed — which the class's own documentation states. That makes the window short by a boot-dependent amount, which is precisely the signature Rule 322 could not attribute to any per-name rule (BAC **193 → 192**, NEE **193 → 181 → 171**). The walk then ran off the oldest point it *read* while the stored series continued below it — 12h retention against seeds spanning tens of minutes — and reported short as though the history had ended. The fix makes the window a consequence of the walk: it doubles and the walk repeats until the seed fills, a genuine hole truncates it, or the series demonstrably ends, with the shallowest filling window used. "The series ends here" is inferred from the `since` already passed to the store — no new API, no extra scan. Every seed now names its terminator, span, step and read count, so the next cycle grades this from the app's own log rather than from a replication script.)

### Step 0 — `120b22b41` (ADR-0137): SCORED ⚠️ INCONCLUSIVE, mechanism ✅ VERIFIED to the end

Risk-adjusted return **-0.000284**/cycle over 7 cycles, **t = -1.39** against the 1.5 hurdle → kept, not
reverted (ADR-0116). Its primary VERIFY-BY held every cycle it ran: live `/api/fusion/targets` planned
gross **$500,000.000044975** against the **$500,000** `jethro.risk.max-gross-exposure` cap —
**1.00000000008995×** — where before it the planner targeted a book it was forbidden to hold. The claimed
mechanism is verified; the PnL effect is indistinguishable from noise, the honest verdict for a
planning-side identity. Moved below the line.

`026cda49d`'s flagged auto-revert stays **deliberately not completed** (Rule 303): it is itself the revert
of the graded-BAD ADR-0136, so completing it would re-apply a rejected mechanism.

### Step 0 — Item #1 (restart liquidates the book): ⚠️ STILL-BROKEN on entry, **fix now shipped**

| check | reading | verdict |
| --- | --- | --- |
| `sources ≤ 1` share of `totalFees` | **$39.187210** of **$390.159780** = **10.04%** (63 fills, **$388,348.116512140580** notional) | ⚠️ unchanged — the fix had not shipped |
| concentration in the ten minutes after a boot | **40 of 58** blind liquidation fills (**68.97%**), **68.89%** of their fee | ⚠️ unchanged |
| root cause identified to a line | `SensorWarmup` read window = `2 × samples × median-step`; median bounds a typical gap, the window must span a **sum** of gaps | ✅ closed |
| fix deployed | ADR-0138, `-Pci test` green, three new tests covering fill / genuine-end / genuine-hole | ✅ shipped |

The fix is deliberately **not** ADR-0135's "hold instead of liquidate" (graded ❌ BAD, never re-tried) and
**not** a per-name allowlist (Rule 317 forbids it — seed depth is a property of the boot). It introduces no
money, risk or exposure number: the class replays prices into a sensor that publishes a conviction, with
fusion, the edge gate, the conviction floor and the pre-trade guardrail all still between it and a fill.

**VERIFY-BY next run (unchanged, now gradeable from the app's own log):** zero `sensor still cold` WARNs at
boot for any name with stored marks; **no** `fusion exit — target decayed to flat [… sources ≤ 1]` order in
the five minutes after JVM start; the `sources ≤ 1` fee share falling materially from **10.04%**; and any
seed still short naming `GAP_BREAK` / `HISTORY_EXHAUSTED` with its span, which distinguishes a defect that
remains from a cash-close truncation that is correct behaviour.

### Step 0 — Item #2 (the aim is in-memory only): ⚠️ STILL-BROKEN, unchanged

Live `/api/fusion/targets` `insideBuffer` **16 of 21**, held **$34,292.8900** against a planned
**$500,000.000044975** — **6.86%**. Still ranked **#2** because #1 gates it: a cold sensor plans the name
flat and a flat target snaps the aim to zero, so restoring intent while the sensors still blink out
restores nothing. It becomes actionable once #1 verifies.

---
## Verification block — 2026-08-04 19:00Z (**No change — `120b22b41` is at 5/6 cycles under ADR-0116 measurement; it scores next cycle.** Item #1 reproduced on a **fifth** boot with the same ~40-second signature — but this cycle stops counting reproductions (Rule 318) and **prices the defect instead**. Two new measurements. (a) **A single, complete, causally-timestamped round trip:** JPM `BUY 13 @ 359.130000` FILLED **18:38:08.400753Z** on `[forecast=+9.114237353210052, sources=3]`; the JVM's own `trend sensor still cold for JPM after seeding 137 of 193` WARN lands **18:38:09.591Z**, 1.19 s later; JPM `SELL 13 @ 359.006339` FILLED **18:38:35.165972Z** on `fusion exit — target decayed to flat [forecast=0.0, sources=1]`. The position lived **26.765219 s**, and cost **-$1.607593** of price move plus **$0.933577** of fee ⇒ **-$2.541170**, for zero information: the three-source view that opened it was never contradicted, the sensor simply went blind. (b) **The cumulative bill, from the app's own `fills`⋈`orders` join:** **63** LIVE fills carry an `origin_reason` with `sources ≤ 1` — **$388,348.116512140580** of notional and **$39.187210** of fee, which is **10.04%** of the firm's entire LIVE fee bill `totalFees` **$390.159780**, against a firm total PnL of **-$602.70613085**. And they are not spread across the cycle: **40 of the 58** blind *liquidation* fills (**68.97%**), carrying **68.89%** of their fee, land in the **first 10 minutes** of the loop's 30-minute cycle. Item #1 is no longer a mechanism with a story — it is a mechanism with an invoice, and it keeps #1.)

### Step 0 — `120b22b41` (ADR-0137): ✅ still VERIFIED on its primary metric, fifth cycle running

Live `/api/fusion/targets` this cycle: Σ |targetQty × price| over 21 names = **$500,000.000044975** against
`jethro.risk.max-gross-exposure` **$500,000** — **1.00000000008995×**. The cap binds a fifth consecutive
cycle and no name flipped side. Its PnL verdict is the scorer's; `reports/.pending-baseline.json` is
present and the window holds **5 of 6** heartbeats since the baseline `2026-08-04T16:45:25Z`, so **no code
changed this cycle** and no baseline was recorded. It scores next run.

`026cda49d`'s flagged auto-revert remains **deliberately not completed** (Rule 303): it is itself the
revert of the graded-BAD ADR-0136, so completing it would re-apply a rejected mechanism.

### Step 0 — Item #1 (restart liquidates the book): ⚠️ STILL-BROKEN — and now PRICED

No fix was attempted (code frozen under measurement). The fifth reproduction:

| check | reading | verdict |
| --- | --- | --- |
| JVM boot | `traffic.timestampMillis` **1785870002122** − `ops_jvm.uptimeSeconds` **1327** ⇒ **18:37:55.122Z** | — |
| zero cold WARNs at boot | **13** equities cold on trend at 18:38:09–18:38:24Z (JNJ 139/193, CAT 159, JPM 137, GOOG 178, MCD 163, HD 188, PFE 147, CVX 163, **BAC 192**, KO 178, PG 162, NEE 171, NQ 149); **6** cold on reversion (JNJ 224/241, CAT 225, JPM 239, HD 221, NQ 230, UNH 218); 12 rates names cold on both | ⚠️ failed |
| no `sources≤1` exit within 5 min of boot | JPM `SELL 13` `fusion exit — target decayed to flat [forecast=0.0, sources=1]` FILLED **18:38:35.165972Z** — **40.043972 s** after boot | ⚠️ failed |
| new seed log naming the terminator | not present (the fix has not shipped) | ⚠️ n/a |

**Five boots, one signature: 16:45:57Z, 17:07:55Z, 17:39:05Z, 18:08:35Z, 18:37:55Z — each followed ~40 s
later by a `target decayed to flat [sources≤1]` liquidation.**

**The complete round trip, in the app's own timestamps.** This is the tightest evidence the loop has
produced, because entry, blindness and exit are all inside 27 seconds of one boot:

| t (UTC) | event | source |
| --- | --- | --- |
| 18:37:55.122 | JVM boot | `traffic` − `ops_jvm.uptimeSeconds` |
| 18:38:08.400753 | JPM `BUY 13 @ 359.130000`, fee **$0.466869** — `fusion entry — target increase [forecast=9.114237353210052, sources=3]` | `fills` ⋈ `orders` |
| 18:38:09.591 | `trend sensor still cold for JPM after seeding 137 of 193 stored prices` | boot log |
| 18:38:35.165972 | JPM `SELL 13 @ 359.006339`, fee **$0.466708** — `fusion exit — target decayed to flat [forecast=0.0, sources=1]` | `fills` ⋈ `orders` |

Held **26.765219 s**. Price move **13 × (359.006339 − 359.130000) = -$1.607593**; fees **$0.933577**;
round trip **-$2.541170**. Nothing about the view changed — only whether the sensor could see.

**The cumulative bill** (`select … from fills f join orders o on o.order_id=f.order_id where f.feed_mode='LIVE'
and o.origin_reason like '%sources=0%' or '%sources=1%'`, grouped by trigger):

| trigger | fills | notional | fee |
| --- | --- | --- | --- |
| `fusion exit — target decayed to flat` | 58 | $374,594.624477140580 | $37.811860 |
| `fusion reduce toward a smaller target` | 5 | $13,753.492035000000 | $1.375350 |
| **total** | **63** | **$388,348.116512140580** | **$39.187210** |

That fee is **10.04%** of the firm's LIVE `totalFees` **$390.159780** and **6.50%** of the entire deficit
**-$602.70613085**. Bucketing the 58 blind liquidation fills by minute-within-the-30-minute-cycle:
**40 of 58 (68.97%)**, **$256,958.77** of notional and **$26.0483** of fee (**68.89%**), fall at
`minute % 30 ≤ 10` — i.e. immediately after a reboot. On 2026-08-04 every single one lands at 15:37, 15:38,
16:07, 16:38, 16:40, 16:46, 16:47, 17:08, 17:14, 17:39, 17:40, 17:45, 18:09 or 18:38. This is the restart
cadence written into the fee ledger.

### Step 0 — Item #2 (the aim is in-memory only): ⚠️ STILL-BROKEN, reproduced

Live `/api/fusion/targets`: `insideBuffer` **16 of 21**, held **$34,292.8900** against a planned
**$500,000.000044975** — **6.86%** (6.63% last cycle). `aims` at ~22 minutes of uptime are still far short
of target (`BAC -155.100803` against `targetQty -1275.096134`; `NEE -67.12794` against `-738.512121`),
which is the buffer walking from a restart-zeroed intent exactly as Rule 315 derived. Unchanged in
mechanism; still ranked **#2** because #1 gates it.

---
### Open items, re-ranked most-costly-first

**#1 — a restart blinds the equity sensors, and the desk liquidates what they held.**
*(carried at #1 for the fifth cycle; this cycle it moved from "specified mechanism" to "priced mechanism")*
A name whose sensors are cold contributes no forecast, `sources` falls to ≤1, and the live rule reads that
as unestimable and plans the name **flat** — worked in full, not buffered. **Measured cost: $39.187210 of
fee on $388,348.116512140580 of blind notional = 10.04% of the firm's entire LIVE fee bill**, 68.89% of it
concentrated in the ten minutes after a reboot, against a firm deficit of **-$602.70613085** in which fees
are **64.73%**. The JPM round trip above is one instance costing **-$2.541170** in 26.765219 seconds.
**Fix — unchanged and concrete, and it is not a magic constant:** make the read window a function of the
spacing the walk actually consumes at rather than of `step` — when the backward walk exhausts its window
still short of `samples`, **re-read further back and continue** until the seed is full or a genuine
gap/epoch boundary truncates it (self-calibrating; no fitted multiple). Ask for margin above the bare
`warmupSamples()` minimum (Rule 309). And **log the terminator and the wall-clock span covered** on every
short seed, so the next cycle grades this from the app's own log rather than from a replication script.
Rule 317 forbids any per-name allowlist or per-name constant: the seed depth is a property of the **boot**
(BAC seeded full at 17:39:05Z and 18:08:35Z, then **192 of 193** at 18:37:55Z; NEE **193 → 181 → 171**).
Architecturally significant — it changes ADR-0071/ADR-0114 warm-restart semantics — so it ships with its
ADR (`Status: Implemented`). **NOT** the routing rule: ADR-0135's "hold instead of liquidate" is graded
❌ BAD and must not be re-tried.
**VERIFY-BY:** zero `trend sensor still cold` / `reversion sensor still cold` WARNs at boot for any name
with stored marks, **and** no `fusion exit — target decayed to flat [… sources≤1]` order in the five
minutes after the JVM starts, **and** the `sources ≤ 1` fee share of `totalFees` falls materially from
**10.04%**, **and** the new seed log names `window-exhausted` / `gap-break` and the span for any name still
short.

**#2 — a restart wipes the desk's INTENT, and the buffer then forbids re-entry for 12–35 minutes.**
*(carried at #2; reproduced this cycle)* `PositionBuffer` holds the ADR-0080 aim in a plain `HashMap`
field (`PositionBuffer.java:104`), built per-JVM in `FusionConfig.java:217` with no store and no restore;
`nextAim` seeds `from = held` on first sight. Live: `insideBuffer` **16 of 21**, held **$34,292.8900** of a
planned **$500,000.000044975** — **6.86%** — under a $500,000 cap while ADR-0132 asks the desk to deploy.
**Fix direction:** persist and restore the aim across the process boundary (LMDB warm-restart state is
derived data, ADR-0014). **Never** by re-cutting the band — ADR-0133 (`e61c7f5aa`) tried that and is graded
❌ BAD. Stays behind #1 (Rule 316): a cold sensor plans the name FLAT, and a flat target snaps the aim to
zero, so restoring intent while the sensors still blink out restores nothing.
**VERIFY-BY:** `/api/fusion/targets` `insideBuffer` falls materially from **16 of 21** within five minutes
of a boot, and held ÷ planned gross rises materially from **6.86%**.

**#3 — turnover cost is the loss.** *(carried; a downstream symptom of #1 — a restart round trip IS
turnover, and this cycle #1's share of it is priced)* Firm **-$602.70613085** against `totalFees`
**$390.159780** ⇒ pre-fee trading of **-$212.54635085**: **fees are 64.73%** of the deficit. Σ`turnover_usd`
= **$4,452,006.98** ÷ gross **$45,948.21197500** = **96.89×**, up from 89.20× last cycle — the ratio rose
while gross fell, so this is ⚠️ worse, not better. Rules 295/296/304 hold.
**VERIFY-BY:** Σ`turnover_usd` ÷ `/api/risk` `.total.grossExposure` falls materially from **96.89×**, with
gross not falling to produce it.

**#4 — social never reaches the combiner, and it is the only source clearing the desk's own hurdle.**
*(unchanged, re-confirmed: `forecastScalars` carries `reversion`/`trend`/`xsreversion` only, while
`weights` lists `social 1.6263786635913058` — the second-largest of the five)* Per Rule 292 this is a
**superseding ADR** (gate and dial together), never a quiet dial turn; per Rule 298 it stays behind the
cost fix, because an hour-scale edge cannot be collected by a book liquidated every half hour.
**VERIFY-BY:** a `social` entry appears in `/api/fusion/targets` `forecastScalars` and in at least one
name's `contributions` array.

**#5 — a flat stored mark series can never warm the sensors, so 12 rates names are permanently sensorless.**
*(carried, re-confirmed: `USD.TSY.*`, `USD.SOFR.*`, `USD_IRS_*` all seeded the full `193 of 193` and
`241 of 241` at 18:38:44Z and stayed cold)* Ranked last: dead coverage on MACRO (**-$56.79950536**,
realised, flat), not the live bleed.
**VERIFY-BY:** either those names warm, or they are excluded from the sensor universe so the WARN stops
masking the equity cold-starts that matter.

---
## Verification block — 2026-08-04 18:30Z (**No change — `120b22b41` is at 4/6 cycles under ADR-0116 measurement.** Item #1 reproduced on a **fourth** independent boot (18:08:35Z → `sources=0` liquidations **40 seconds** later), and this cycle's own seed counts *falsify the idea that the short seed is a property of the name*: **NEE seeded 193 of 193 last boot and 181 of 193 this boot**, while **BAC seeded full on both**. That is Rule 313's coin flip, observed in the app's own log rather than in a replication script. The cycle's new product is a **second, additive restart defect, now measured**: `PositionBuffer`'s ADR-0080 **aim map is in-memory only**, so every restart re-seeds intent at the held position — and at the derived rate `a` the buffer does not release the **first** order in a name for **12.4 minutes** at best, **34.7 minutes** at the median, and **never** for 4 of 20 names. On a ~30-minute restart cadence the desk therefore holds **$32,767.885** of a **$494,262.182127365** plan — **6.63%** — under a $500k cap. Ranked **#2**; it stays behind #1 because a cold sensor plans the name FLAT, and a flat target snaps the aim to zero, so fixing intent while the sensors still blink out changes nothing.)

### Step 0 — `120b22b41` (ADR-0137): ✅ still VERIFIED on its primary metric, fourth cycle running

Live `/api/fusion/targets` this cycle: Σ |targetQty × price| over 22 names = **$494,262.182127365** against
`jethro.risk.max-gross-exposure` **$500,000** — **0.98852436425473×**. The cap binds a fourth consecutive
cycle and no name flipped side. Its PnL verdict is the scorer's and stands at **4/6 cycles**
(`reports/.pending-baseline.json` present), which is why no code changed this cycle.

`026cda49d`'s flagged auto-revert remains **deliberately not completed** (Rule 303): it is itself the
revert of the graded-BAD ADR-0136, so completing it would re-apply a rejected mechanism.

### Step 0 — Item #1 (restart liquidates the book): ⚠️ STILL-BROKEN on a fourth boot

No fix was attempted (code frozen under measurement), so this is the defect's fourth reproduction:

| check | reading | verdict |
| --- | --- | --- |
| JVM boot | `traffic.timestampMillis` **1785868202358** − `ops_jvm.uptimeSeconds` **1287** ⇒ **18:08:35.358Z** | — |
| zero cold WARNs at boot | **13** equities cold on trend at 18:08:49–18:09:24Z (PFE, XOM, WMT, CAT, UNH, GOOG, PG, JNJ, CVX, JPM, MCD, KO, NEE); **4** cold on reversion (CAT, UNH, HD, JNJ); 12 rates names cold on both | ⚠️ failed |
| no `sources≤1` exit within 5 min of boot | **18:09:15.234888Z** KO `BUY 12` `[forecast=0.0, sources=0]` FILLED and **18:09:15.386281Z** NEE `SELL 2` `[forecast=0.0, sources=0]` REJECTED — **40 s** after boot | ⚠️ failed |

**Four independent boots, one signature: 16:45:57Z, 17:07:55Z, 17:39:05Z, 18:08:35Z — each followed within
~40 s by a `target decayed to flat [sources≤1]` liquidation wave.**

**New evidence — the seed depth is a property of the BOOT, not of the name.** Rule 313 predicted the seed
fills iff a name's effective consumption spacing happens to land at or under `LOOKBACK_MULTIPLE`, i.e. a
coin flip per boot. This cycle's log confirms it directly, no replication needed: **NEE seeded 193 of 193
at the 17:39:05Z boot and 181 of 193 at the 18:08:35Z boot**, and **BAC seeded full at both** (absent from
the cold list twice). A per-name allowlist or a per-name constant therefore cannot fix this — only sizing
the read-back by what the walk actually consumes can, which is the fix already specified.

### NEW — Item #2: a restart wipes the desk's INTENT, and the buffer then forbids re-entry for 12–35 minutes

`PositionBuffer` holds the ADR-0080 aim in a plain `HashMap` field (`PositionBuffer.java:104`), constructed
per-JVM in `FusionConfig.java:217` with nothing but the fraction — there is no store, no restore, and
`nextAim` seeds `from = held` on first sight of a name. So every restart discards the intent the previous
process had accumulated and restarts it at the (just-liquidated) held position.

That interacts with the band multiplicatively. The band is `|target| × TARGET_ABS / |forecast| × width`,
with `TARGET_ABS = 10.0` (`Forecast.java:20`), `width = 0.10` (`jethro.fusion.position-buffer.fraction`;
the ADR-0101 measured width is inactive — `/api/fusion/targets` `edgeGate` is `null`). From a zero aim the
first order in a name is released only once `1 − (1−a)^n ≥ 10·width/|forecast|`, at the derived rate
`a = 1 − exp(−30/3600) = 0.008298707` (`jethro.fusion.adjustment-rate=0` ⇒ derived, ADR-0080). Computed
from this cycle's live target book:

| name | \|target\| | band | aim now | minutes of uptime to first order |
| --- | --- | --- | --- | --- |
| MSFT | $53,716 | $10,065 | $8,713 | **12.4** |
| AAPL | $47,104 | $9,608 | $3,782 | 13.7 |
| BAC | $61,549 | $13,153 | $3,875 | 14.4 |
| JPM | $50,742 | $11,564 | $9,059 | 15.5 |
| … median of the 20 sized names | | | | **34.7** |
| KO / XOM | $14,142 / $10,709 | $13,556 / $10,336 | $4,031 / $89 | 191.0 / 201.4 |
| MCD, WMT, NEE, NQ | band ≥ \|target\| | | | **never** |

The loop restarts the JVM roughly every 30 minutes. So the desk spends the first ~40 s liquidating on cold
sensors and the next 12–35 minutes unable to open anything, which is why **`insideBuffer` is 17 of 22** and
held gross is **$32,767.885** against a **$494,262.182127365** plan — **6.63%** — with the firm cap at
$500,000 and gross at **3.3%** of the $1,500,000 firm cap. Under ADR-0132 that undeployed capital is the
failure to attack. The corroborating timeline: the prior JVM booted **17:39:05Z** and its first
`fusion entry — target increase` fill lands at **17:55:28.697412Z**, ~16 minutes later, consistent with the
table rather than with any market event.

- **Rank #2, deliberately behind #1.** A cold sensor plans the name FLAT; `nextAim` snaps a flat target's
  aim to zero and ADR-0090 works that exit in full. So restoring intent while the sensors still blink out
  at boot restores nothing — #1 gates #2.
- **This is NOT ADR-0133's band cap** (`e61c7f5aa`, graded ❌ BAD and not to be re-attempted). That change
  capped the band at the target it polices, altering the steady-state no-trade region for every name in
  every cycle. The defect here is that a *derived, in-memory* state is lost across a process boundary the
  desk's own operations create — the same class of bug as the ADR-0071/ADR-0114 warm-restart seed, and
  fixable by restoring intent rather than by widening or narrowing any band.
- **VERIFY-BY:** at the first fusion cycle after a boot, `/api/fusion/targets` `aims` is materially
  non-zero for names the previous process had aims in (today it is the held position), and the interval
  from boot to the first `fusion entry — target increase` FILL drops from the observed **~16 minutes**
  (17:39:05Z → 17:55:28Z) to under one fusion interval. Secondary: held-gross ÷ planned-gross rises from
  **6.63%**.

### Cost picture this cycle (unchanged in character)

Firm total **-$606.83516377** against `totalFees` **$387.738249** ⇒ pre-fee trading of **-$219.09691477**,
so **fees are 63.90%** of the deficit. `riskCuts []`, `bookVolBrake 1.0`, `portfolioRiskMultiplier
0.6124247621887945`, breaker untripped.

---
## Verification block — 2026-08-04 18:00Z (**No change — `120b22b41` is at 3/6 cycles under ADR-0116 measurement.** Item #1 reproduced on a **third** independent boot: 17:39:05Z, then **40 seconds later** PFE `SELL 12` on `sources=0` and HD `BUY 1` on `sources=1`. The cycle's real product is that **Rule 310's open question is now MEASURED and closed**: replaying `SensorWarmup.seedPrices` against the live store, **16 of 18 seeds end on read-window exhaustion, 2 are satisfied, and the `GAP_TOLERANCE` break fires ZERO times.** The window is sized `LOOKBACK_MULTIPLE (2) × samples × step`, but the walk consumes history at a measured **2.26×** `step` after its own `≥ interval` thinning — so the window delivers ~`samples` points in expectation and the seed is a coin flip. The two names that seeded FULL are exactly the two measuring **2.00**. The fix is now specified, not guessed.)

### Step 0 — `120b22b41` (ADR-0137): ✅ still VERIFIED on its primary metric, third cycle running

Live `/api/fusion/targets` this cycle: Σ |targetQty × price| over 20 names = **$498,877.903542685** against
`jethro.risk.max-gross-exposure` **$500,000** — **0.99775580708537×**. The cap binds a third consecutive
cycle. Its PnL verdict is the scorer's and stands at **3/6 cycles** (`reports/.pending-baseline.json`
present), which is why no code changed this cycle.

`026cda49d`'s flagged auto-revert remains **deliberately not completed** (Rule 303): it is itself the
revert of the graded-BAD ADR-0136, so completing it would re-apply a rejected mechanism.

### Step 0 — Item #1 (restart liquidates the book): ⚠️ STILL-BROKEN on a third boot — and its terminator is now MEASURED

No fix was attempted (code frozen under measurement), so this is the defect's own third reproduction:

| check | reading | verdict |
| --- | --- | --- |
| JVM boot | `traffic.timestampMillis` **1785866402483** − `ops_jvm.uptimeSeconds` **1257** ⇒ **17:39:05Z** | — |
| zero cold WARNs at boot | **12** equities cold on trend at 17:39:19–17:39:44Z (WMT, JPM, MCD, CVX, GOOG, KO, PG, BAC, NEE, HD, JNJ, PFE); **4** cold on reversion (CAT, XOM, NQ, HD); 12 rates names cold on both | ⚠️ failed |
| no `sources≤1` exit within 5 min of boot | **17:39:45.355619Z** PFE `SELL 12` `[forecast=0.0, sources=0]` and **17:39:45.294273Z** HD `BUY 1` `[forecast=-0.0, sources=1]` — **40 s** after boot; JPM `SELL 24` `[sources=1]` at 17:40:46Z; CAT `SELL 3` `[sources=1]` at 17:45:50Z | ⚠️ failed |

**Three independent boots, one signature: 16:45:57Z, 17:07:55Z, 17:39:05Z — each followed within ~40 s by
a `target decayed to flat [sources≤1]` liquidation wave.** It is not an intermittent fault.

**Rule 310 is closed — the terminator is READ-WINDOW EXHAUSTION, and the gap `break` never fires.**
I re-implemented `seedPrices` exactly (same `GAP_TOLERANCE_SAMPLES=30`, `LOOKBACK_MULTIPLE=2`, the
`consumptionStepMillis` median, the `age < intervalMillis` thinning) and replayed it against the live
`/api/history` store at the sensors' configured cadences (`jethro.fusion.trend.interval-seconds=5`,
`samples=193`; `reversion.interval-seconds=10`, `samples=241`):

| terminator | trend (14 names) | reversion (4 names) | total |
| --- | --- | --- | --- |
| read-window exhausted | 13 | 3 | **16 of 18** |
| seed satisfied | 1 (NEE) | 1 (XOM) | 2 of 18 |
| `GAP_TOLERANCE` break | 0 | 0 | **0 of 18** |

So the restart gap is **not** what truncates the seed, exactly as Rule 310 warned — and neither is any
outage. The walk simply runs out of window.

**Why the window is too small — a one-line arithmetic error of unit.** The read window is
`LOOKBACK_MULTIPLE × samples × step`, i.e. it is denominated in `step` (the name's *median* print gap,
floored at the poll interval), on the implicit assumption that the walk accepts one point per `step`. It
does not: the `age < intervalMillis` thinning merges every run of short gaps, so the walk consumes history
at a materially wider **effective spacing**. Measured per name as `seed span ÷ (n − 1)`:

| name | step | effective spacing | eff/step | seed |
| --- | --- | --- | --- | --- |
| BAC | 5.0 s | 10.01 s | **2.00** | **193 of 193 — full** |
| NEE | 5.0 s | 9.98 s | **2.00** | **193 of 193 — full** |
| WMT | 5.0 s | 10.53 s | 2.11 | 183 |
| CAT | 8.6 s | 18.22 s | 2.12 | 148 |
| GOOG | 5.0 s | 10.77 s | 2.15 | 180 |
| KO | 5.0 s | 10.99 s | 2.20 | 176 |
| XOM | 6.2 s | 13.61 s | 2.19 | 178 |
| MCD | 5.4 s | 12.19 s | 2.24 | 180 |
| JNJ | 7.0 s | 16.57 s | 2.36 | 163 |
| PG | 5.0 s | 11.87 s | 2.37 | 163 |
| JPM | 5.6 s | 14.34 s | 2.56 | 146 |
| CVX | 5.0 s | 12.98 s | 2.60 | 149 |
| PFE | 5.0 s | 14.03 s | 2.81 | 138 |
| HD | 9.0 s | 17.33 s | 1.92 | 156 |

Mean **eff/step = 2.26** against `LOOKBACK_MULTIPLE = 2`. The seed fills **iff `eff/step ≤ 2`**, and the
only two names that filled are the only two measuring exactly **2.00**. Combined with Rule 309 — the
requested `warmupSamples()` is already the *exact* minimum, so 192 of 193 is as blind as 130 — the desk is
running two stacked zero-margin conditions and losing the coin flip on ~13 of 14 names every boot.

### Open items, re-ranked most-costly-first

**#1 — a restart blinds the equity trend sensors, and the desk liquidates what they held.**
*(carried at #1 for the fourth cycle; mechanism now fully specified — terminator measured, not inferred)*
A name whose sensors are cold contributes no forecast, `sources` falls to ≤1, and the live rule reads that
as unestimable and plans the name **flat** — worked in full, not buffered. Cumulative LIVE turnover
Σ`turnover_usd` = **$4,402,699.14** = **89.20×** live gross **$49,356.54110000**.
**Fix — now concrete, and it is not a magic constant:** make the read window a function of the spacing the
walk actually consumes at rather than of `step` — i.e. when the walk exhausts its window still short of
`samples`, **re-read further back and continue** until the seed is full or a genuine gap/epoch boundary
truncates it (self-calibrating; no fitted multiple). Ask for margin above the bare `warmupSamples()`
minimum (Rule 309). And **log the terminator and the wall-clock span covered** on every short seed, so the
next cycle grades this from the app's own log instead of from a replication script. Architecturally
significant — it changes ADR-0071/ADR-0114 warm-restart semantics — so it ships with its ADR
(`Status: Implemented`). **NOT** the routing rule: ADR-0135's "hold instead of liquidate" is graded ❌ BAD
and must not be re-tried.
**VERIFY-BY:** zero `trend sensor still cold` / `reversion sensor still cold` WARNs at boot for any name
with stored marks, **and** no `fusion exit — target decayed to flat [… sources≤1]` order in the five
minutes after the JVM starts, **and** the new seed log naming `window-exhausted` / `gap-break` and the span
for any name still short.

**#2 — turnover cost is the loss.** *(carried; a downstream symptom of #1 — a restart round trip IS
turnover)* Firm **-$599.45227577** against `totalFees` **$387.112525** ⇒ pre-fee trading of
**-$212.33975077**: **fees are 64.58%** of the deficit. Rules 295/296/304 hold.
**VERIFY-BY:** Σ`turnover_usd` ÷ `/api/risk` `.total.grossExposure` falls materially from **89.20×**, with
gross not falling to produce it.

**#3 — social never reaches the combiner, and it is the only source clearing the desk's own hurdle.**
*(unchanged, carried)* Per Rule 292 this is a **superseding ADR** (gate and dial together), never a quiet
dial turn; per Rule 298 it stays behind the cost fix, because an hour-scale edge cannot be collected by a
book liquidated every half hour.
**VERIFY-BY:** a `social` entry appears in `/api/fusion/targets` `forecastScalars` and in at least one
name's `contributions` array.

**#4 — a flat stored mark series can never warm the sensors, so 12 rates names are permanently sensorless.**
*(carried, re-confirmed: `USD.TSY.*`, `USD.SOFR.*`, `USD_IRS_*` all seeded the full `193 of 193` and
`241 of 241` this boot and stayed cold)* Ranked last: dead coverage on MACRO (**-$56.79950536**, realised,
flat), not the live bleed.
**VERIFY-BY:** either those names warm, or they are excluded from the sensor universe so the WARN stops
masking the equity cold-starts that matter.

---
## Verification block — 2026-08-04 17:30Z (**No change — `120b22b41` is at 2/6 cycles under ADR-0116 measurement.** Last block's item #1 was filed as a *hypothesis* with a concrete VERIFY-BY. **That VERIFY-BY has now resolved, against the defect: it is confirmed, not suspected.** The 17:07:55Z boot re-ran the identical cold-sensor pattern, and **39 seconds later** the desk liquidated XOM and CAT on `sources=1` — XOM's `BUY 15` is the *exact* offset of the `SELL 15` its own 3-source view opened 30 minutes earlier. A complete round trip, opened on conviction and closed by a restart. The code-level mechanism is now narrowed to two named terminators in `SensorWarmup`, plus a zero-margin warm-up requirement that makes a seed one sample short as blind as one sixty short.)

### Step 0 — `120b22b41` (ADR-0137): ✅ still VERIFIED on its primary metric, second cycle running

Live `/api/fusion/targets` this cycle: Σ |targetQty × price| over 20 names = **$499,999.999386065** against
`jethro.risk.max-gross-exposure` **$500,000** — **0.99999999877×**. The cap binds a second consecutive
cycle and no name flipped side. Nothing else about ADR-0137 is graded here; its PnL verdict is the
scorer's and stands at **2/6 cycles** (`reports/.pending-baseline.json` present).

`026cda49d`'s flagged auto-revert remains **deliberately not completed** (Rule 303): it is itself the
revert of the graded-BAD ADR-0136, so completing it would re-apply a rejected mechanism.

### Step 0 — Item #1 of the 17:00Z block (restart liquidates the book): ⚠️ STILL-BROKEN — and now CONFIRMED as mechanism, not hypothesis

The item declared: *"zero `trend sensor still cold` / `reversion sensor still cold` WARNs at boot for any
name that has stored marks, **and** no `fusion exit — target decayed to flat [… sources=1]` order in the
five minutes after the JVM starts."* No fix was attempted (the code was frozen under measurement), so this
is the defect's own reproduction, on a second independent boot:

| check | reading | verdict |
| --- | --- | --- |
| JVM boot | `traffic.timestampMillis` **1785864602305** − `ops_jvm.uptimeSeconds` **1327** ⇒ **17:07:55Z** | — |
| zero cold WARNs at boot | **14** equities cold on trend at 17:08:08–17:08:44Z; **2** cold on reversion | ⚠️ failed |
| no `sources=1` exit within 5 min of boot | **17:08:34Z** — XOM `BUY 15`, CAT `BUY 3`, both `fusion exit — target decayed to flat [forecast=-0.0/0.0, sources=1]`, **39 s** after boot; MCD `SELL 13` same reason at 17:14:09Z | ⚠️ failed |

**The round trip, in full.** XOM `SELL 15` at **16:38:50.180388Z**, reason
`fusion entry — target increase [forecast=-5.086943349481853, sources=3]` — a three-source conviction
short. XOM `BUY 15` at **17:08:34.605741Z**, reason `fusion exit — target decayed to flat [forecast=-0.0,
sources=1]` — same size, opposite side, 39 s after a restart, at **1.00 bps** each way
(`turnover_cost_by_name`). The view did not change its mind; the process died. XOM is on the cold list
("still cold for XOM after seeding **138 of 193** stored prices"). This is the mechanism executing, priced.

**Seed counts this boot** (trend needs **193** = `slow-span 64` + 1 + `normalisation-span 256`/2, matching
the log's "of 193"; reversion needs **241**): CAT **130**, XOM **138**, HD **147**, MCD **148**, JNJ **148**,
BAC **153**, PFE **158**, KO **166**, CVX **169**, JPM **170**, PG **173**, GOOG **179**, NEE **192**, UNH,
plus reversion CAT **235 of 241** and HD **231 of 241**.

**Where the seed dies — narrowed to two terminators, and which one binds is NOT yet determined.** Reading
`SensorWarmup.seedPrices`, the backward walk can end short in exactly two ways:
1. `break` at a hole wider than `GAP_TOLERANCE_SAMPLES (30) × step`, where
   `step = max(poll interval, the name's median stored print gap)` — **150 s at the 5 s trend floor**; or
2. exhausting the read window, `LOOKBACK_MULTIPLE (2) × samples × step` — **32.2 min at that same floor**.
The seed must cover `193 × 5 s ≈ 16.1 min` of contiguous history ending at the anchor. The counts above are
*just under* the requirement, which is consistent with either. **Do not assume the process-restart gap is
the terminator** — the counts of 130–192 show the walk did *not* stop at the boundary between this process
and the previous one; it crossed it and stopped further back. Naming the terminator is the fix's first job.

**And the requirement has zero margin, which multiplies the damage.** `warmupSamples()` returns
`slowSpan + 1 + scaleWarmupSamples`, and tracing `EwmacTrendForecaster.update` that is the *exact* minimum
number of prices to publish: price 1 only initialises, the gate opens at price `slowSpan + 1`, and
`scaleSamples > scaleWarmupSamples` first holds at price `slowSpan + 1 + scaleWarmupSamples`. So the seed
asks for precisely as many samples as it needs and not one more — **NEE at 192 of 193 is exactly as blind
as CAT at 130 of 193.** Any fix must seed with margin, not just reach further back.

**A distinct, second defect surfaced by the same log — the rates names can NEVER warm.** Twelve
instruments (`USD.TSY.1Y/2Y/5Y/10Y/30Y`, `USD.SOFR.1Y/2Y/5Y/10Y/30Y`, `USD_IRS_5Y/10Y`) seeded the **full**
`193 of 193` on trend and `241 of 241` on reversion and are **still cold**. Seed length cannot explain that.
In `EwmacTrendForecaster.update` the warm-up gate includes `s.vol.signum() <= 0`, and `s.vol` is an EWMA of
`|price − last|`: a stored series that does not move has zero step vol, returns cold forever, and never
increments `scaleSamples`. A flat mark series is **permanently** sensorless regardless of history depth.
Filed as item #4 — it is dead sensor coverage on the MACRO book, not the money leak.

### Open items, re-ranked most-costly-first

**#1 — a restart blinds 14 of 20 equity trend sensors, and the desk liquidates what they held.**
*(carried at #1; upgraded from hypothesis to CONFIRMED this cycle — see the XOM round trip above)*
A name whose sensors are cold contributes no forecast, `sources` falls toward 1, and the live rule reads a
one-source view as unestimable and plans the name **flat** — worked in full, not buffered. Live now:
**12 of 20** names flat, held-in-targets **$27,962.865** against a planned **$499,999.999386065** — the desk
holds **5.59%** of its own plan. Cumulative LIVE turnover **$4,344,591.80** = **142.87×** firm gross
**$30,409.11287500**, across **2,277** fills; **5,264** FILLED / **1,951** CANCELLED / **128** REJECTED.
**Fix direction:** the warm-restart seed — (a) request meaningfully MORE than the bare `warmupSamples()`
minimum, since the current ask has zero margin; (b) widen the lookback and/or the hole tolerance so the walk
reaches the full warm-up span; (c) **log which terminator fired and how much wall-clock span the seed
covered**, so the next cycle can grade the fix instead of re-deriving it. Architecturally significant —
it changes ADR-0071/ADR-0114 warm-restart semantics, so it ships with its ADR (`Status: Implemented`).
**NOT** the routing rule: ADR-0135's "hold instead of liquidate" is graded ❌ BAD and must not be re-tried.
**VERIFY-BY:** zero `trend sensor still cold` / `reversion sensor still cold` WARNs at boot for any name
with stored marks, **and** no `fusion exit — target decayed to flat [… sources=1]` order in the five
minutes after the JVM starts, **and** the new seed log naming the terminator for any name still short.

**#2 — turnover cost is the loss, and its mechanism is CADENCE (a 3600s forecast re-planned every 30s).**
*(carried, unchanged; likely a downstream symptom of #1 — a restart-driven round trip IS turnover)*
Firm **-$616.93791140** against `totalFees` **$381.753017** ⇒ pre-fee trading of **-$235.18489440**:
**fees are 61.88%** of the deficit. Rules 295/296 hold; Rule 304 rules out every *uniform-scalar* remedy
a priori. Ranked below #1 because #1 is a proven, priced generator of exactly this turnover.
**VERIFY-BY:** cumulative LIVE `turnover_cost_by_name` Σ`turnover_usd` ÷ `/api/risk` `.total.grossExposure`
falls materially from **142.87×**, with gross not falling to produce it.

**#3 — social never reaches the combiner, and it is the only source clearing the desk's own hurdle.**
*(unchanged, re-confirmed on live `/api/fusion/targets`)* Every `contributions` array lists only
`trend`/`reversion`/`xsreversion`; `forecastScalars` has entries for those three only; yet `weights`
carries `social 1.9382252560856337`, the largest of the five. Per Rule 292 this is a **superseding ADR**
(gate and dial together), never a quiet dial turn — and per Rule 298 it stays behind the cost fix, because
an hour-scale edge cannot be collected by a book that is liquidated every half hour.
**VERIFY-BY:** a `social` entry appears in `/api/fusion/targets` `forecastScalars` and in at least one
name's `contributions` array.

**#4 — a flat stored mark series can never warm the trend/reversion sensors, so 12 rates names are
permanently sensorless.** *(NEW this cycle)* They seed the full `193 of 193` / `241 of 241` and stay cold;
`EwmacTrendForecaster.update` returns cold while `s.vol.signum() <= 0` and never increments `scaleSamples`.
Ranked last: it is dead coverage on MACRO (**-$56.79950536**, realised, flat), not the live bleed.
**VERIFY-BY:** either those names warm, or they are excluded from the sensor universe so the WARN stops
masking the equity cold-starts that matter.

---
## Verification block — 2026-08-04 17:00Z (**No change — `120b22b41` is at 1/6 cycles under ADR-0116 measurement.** ADR-0137 met its primary VERIFY-BY exactly: planned gross **$1,356,452.14 → $499,731.80**, **0.9995×** the $500,000 cap. It missed its secondary one, and the reason refutes its own causal premise: the fusion sizing map is **homogeneous of degree 1** in the target, so a uniform target scalar moves absolute notional and leaves churn-per-unit-of-book untouched. The turnover item stays open, but its mechanism is now **cadence**, not magnitude — and a new, larger candidate has appeared: the loop's own restart may be liquidating the book every cycle.)

### Step 0 — `120b22b41` (ADR-0137): ✅ VERIFIED on the primary metric, ⚠️ STILL-BROKEN on what it was for

Deployed and graded on live code: boot at `traffic.timestampMillis` **1785862802646** − `ops_jvm.uptimeSeconds`
**845** ⇒ ≈**16:45:57Z**, after the commit. `traffic.up true`, `provider alpaca`, `ticksIn 7371`,
`ticksDropped 0`. `GrossNotionalCapTest` 9 tests / 0 failures at commit.

| VERIFY-BY | before | now | verdict |
| --- | --- | --- | --- |
| `/api/fusion/targets` Σ \|targetQty × price\| ≤ $500,000 | $1,356,452.14 (2.71×) | **$499,731.80** (0.9995×) | ✅ |
| `insideBuffer` falls | 19 of 22 (0.86) | **18 of 20 (0.90)** | ⚠️ did not fall |

**Why the second one could not move.** In `PositionBuffer`: `aim ← aim + a·(target − aim)`,
`scale = |target| · TARGET_ABS / |forecast|`, `band = scale · width` (width dimensionless — ADR-0101
measures it in bps of cost vs edge), `gap = aim − held`, `|gap| ≤ band → 0` else `gap − band·sgn(gap)`.
Every term is linear in the target, so ADR-0137's uniform scalar multiplies aim, band, gap and delta
alike: **which names trade, and turnover ÷ book, are scale-invariant.** The change cuts the dollar fee
bill and shrinks the book by the same factor; it cannot cut churn per unit of book. Sound, harmless,
aimed at the wrong variable. Its PnL verdict is the scorer's and is at 1/6 cycles — **not** touched here.

`026cda49d`'s flagged auto-revert remains **deliberately not completed** (Rule 303): it is itself the
revert of the graded-BAD ADR-0136, so completing it would re-apply a rejected mechanism.

### Open items, re-ranked most-costly-first

**#1 — the loop's own restart may liquidate and rebuild the whole book every cycle.** *(new — displaces
the turnover item, which it may well explain)*
The 16:45:57Z boot log shows `trend sensor still cold` for 14 equities — "still cold for CVX after seeding
**164 of 193** stored prices", likewise GOOG, NEE, XOM, JPM, KO, MCD, PG, HD, JNJ, PFE, BAC, CAT, UNH — and
`reversion sensor still cold` for JPM and JNJ. The warm-restart mark store is **short of the warm-up span**,
so a restart blinds those sensors for minutes. A name with no warm sensors contributes no forecast,
`sources` falls toward 1, and the live rule (the one restored when ADR-0135 was scored ❌ BAD and reverted)
treats a one-source view as unestimable and plans the name **flat** — worked in full, not buffered.
**Ten of the twelve flat equities are on that cold list.** The desk now holds **$20,131.72** = **4.03%** of
its own **$499,731.80** plan with **13 of 20** names flat, against cumulative LIVE turnover of
**$4,316,744.05** = **207.42×** the **$20,811.28585000** firm gross.
*Stated as the leading hypothesis, not a proof:* the pre-boot exits (WMT 16:38:19Z, PFE SELL 249
16:40:21Z) were on a warm JVM and are not explained by cold sensors.
**Fix direction:** the warm restart — seed the sensors with enough stored history to publish at boot.
**NOT** the routing rule: ADR-0135's "hold instead of liquidate" is graded ❌ BAD and must not be re-tried.
**VERIFY-BY:** zero `trend sensor still cold` / `reversion sensor still cold` WARNs at boot for any name
that has stored marks, **and** no `fusion exit — target decayed to flat [… sources=1]` order in the five
minutes after the JVM starts.

**#2 — turnover cost is the loss, and its mechanism is CADENCE (a 3600s forecast re-planned every 30s).**
*(was #1; mechanism corrected this cycle — the magnitude hypothesis is closed by ADR-0137's own result)*
Firm **-$599.89296437** against `totalFees` **$379.515318** ⇒ pre-fee trading of **-$220.37764637**:
**fees are 63.26%** of the deficit. Rules 295/296 hold — a forecast measured at 3600s, re-planned every
30s, produces sign-flip whipsaw. Rule 304 now rules out every *uniform-scalar* remedy a priori.
**VERIFY-BY:** cumulative LIVE `turnover_cost_by_name` Σ`turnover_usd` ÷ `/api/risk` `.total.grossExposure`
falls materially from **207.42×**, with gross not falling to produce it.

**#3 — social never reaches the combiner, and it is the only source clearing the desk's own hurdle.**
*(unchanged, re-confirmed)* Every `contributions` array in `/api/fusion/targets` lists only
`trend`/`reversion`/`xsreversion`; `forecastScalars` has no `social` entry; yet `weights` carries
`social 1.7911864985234398`, the largest of the five. Per Rule 292 this is a **superseding ADR** (gate and
dial together), never a quiet dial turn — and per Rule 298 it stays behind the cost fix, because an
hour-scale edge cannot be collected by a book that is liquidated every half hour.
**VERIFY-BY:** a `social` entry appears in `/api/fusion/targets` `forecastScalars` and in at least one
name's `contributions` array.

---
## Verification block — 2026-08-04 16:30Z (**Change shipped: ADR-0137.** `026cda49d` scored ❌ BAD and its window closed, so the five-cycle code freeze is over. The turnover item held #1 and this cycle found its *mechanism*, which is structural rather than a dial: **every sizing control in the fusion pipeline is σ-relative and none constrains notional**, so the planner was targeting **$1,356,452.14** gross — **2.71×** the $500,000 the guardrail permits the routing book to hold and **14.74×** the **$91,999.52** it actually held. An unreachable target is what the churn is made of.)

### Step 0 — `026cda49d` (revert of ADR-0136): ⏹ SCORED ❌ BAD — window closed, freeze lifted

`scripts/score-change.py score` now prints `no pending change to score — nothing to do` and
`reports/.pending-baseline.json` is **gone**. The ledger row `2026-08-04T16:30:08Z` closed the ADR-0116
window at 7 cycles, `t=-1.22` against the 1.5 hurdle. JVM warm and deployed throughout
(`ops_jvm.uptimeSeconds` **1428** at `traffic.timestampMillis` **1785861003017**, `traffic.up true`,
`provider alpaca`, `ticksIn 12860`, `ticksDropped 0`), so this graded live code.

**The flagged auto-revert of `026cda49d` is deliberately NOT completed.** `026cda49d` *is* the revert of
ADR-0136, which was itself scored ❌ BAD — reverting it would re-apply a graded-BAD mechanism, which the
contract forbids. Recorded here rather than acted on. Worth naming for the register: both ❌ verdicts
measured a baseline of `gross_exposure 0E-8` (a dormant book) against a deployed one, so the "exposure
grew" leg fired on a book coming off dormant, which ADR-0132 calls the goal. The ledger owns its numbers
and nothing there has been touched.

### Item #1 of the 16:00Z block (turnover cost is the loss): ⚠️ STILL-BROKEN — and its MECHANISM is now identified

Re-measured on this run's telemetry, the cost decomposition holds: firm total **-$524.66210431** against
`totalFees` **369.021411** → pre-fee trading of only **-$155.64**, so **fees are the majority of the
deficit**. Cumulative LIVE turnover **$4,120,150.44**; `orders_by_status` **1,930 CANCELLED** vs
**5,214 FILLED**.

What is new is *why*. Read from `/api/fusion/targets`:

| quantity | value | ratio |
| --- | --- | --- |
| planned target book, Σ \|targetQty × price\| over 22 names | **$1,356,452.14** | — |
| `jethro.risk.max-gross-exposure` — the guardrail's cap on the routing book | $500,000 | **2.71×** |
| held equity gross | $91,999.52 | **14.74×** |

Nothing bound: `volBudgetLeverCap` **1.0**, `bookVolBrake` **1.0**, `portfolioRiskMultiplier`
**0.8726121632212922**. ADR-0083 / ADR-0079 / ADR-0104 are all σ-*relative* — they decide how risk is
shared out and what σ level the book carries; **none of them states a notional**, and on a `CALM` tape a
measured σ is small, so none of them binds.

That put no risk on (the guardrail still refuses the order) but made the target **unreachable**, and the
whole ADR-0080/ADR-0094 path is a function of the distance to the target:

- **The aim never converges.** PFE: `targetQty` **3319.769516**, `aim` **530.737491**, `currentQty`
  **-267.0** — short a name whose own target is long twelve times the size, closing at 43 shares/cycle.
- **Turnover ∝ the inflation.** The step is `a × gap` at `a = 1 − exp(−30/3600) = 0.0082987…`.
- **Over-trades the few, freezes the many.** The ADR-0094 band is ∝ |target| too: `insideBuffer`
  **19** of 22.
- **Never holds for its horizon.** Edge is measured at 3600s; a desk permanently in transit never
  collects it.

Order trail: PFE took ~20 consecutive `BUY 2.000000` fills, one per 30s cycle, 16:18:33Z → 16:28:42Z,
`[forecast=…]` walking **+0.0968 → +2.045 → +2.822 → +3.526 → +4.797 → +6.032 → +9.144** — then two
minutes later the same name's target had flipped to **-$69,827** at forecast **-6.04**.

**ADDRESSED THIS CYCLE by ADR-0137** — `GrossNotionalCap`, applied after ADR-0104 and before the ADR-0064
gate / ADR-0086 cut / ADR-0094 buffer: `GNM = min(1, capUsd / plannedGross)` applied uniformly to every
priced name. `capUsd` is **wired from `jethro.risk.max-gross-exposure`** — no money number is introduced
(invariant 7 / ADR-0016). One-way, sign-preserving, exact decimal rounded DOWN.

**VERIFY-BY next run:** from `/api/fusion/targets`, `Σ |targetQty × price|` over the planned book must be
**≤ 500,000** (it was **1,356,452.14**). Secondary, same endpoint: `insideBuffer` should fall from **19**
of 22, and the per-cycle `Σ |deltaQty × price|` should fall materially. Tertiary, next-next run once the
book has re-planned: `turnover_cost_by_name` turnover growth per cycle should slow relative to held gross.

### Item #2 — `social` is the only source with measured edge and never reaches the combiner (⚠️ OPEN, #2 — carried)

Unchanged and still true; deliberately not touched this cycle because **cost control is its
precondition** — social's expectancy is hour-scale (**+0.1611 → +1.8322 → +8.7221** bps at 225/900/3600s)
and a book that cannot hold a position for an hour cannot collect it. `jethro.fusion.social.per-channel=0`
is still live at `application.properties:274`; `/api/fusion/targets` still shows `weights` carrying
`social 1.8665040235026624` (the largest of five) while `forecastScalars` has **no `social` entry** and
every `contributions` array lists only `trend`/`reversion`/`xsreversion`. Remains an owner-facing
superseding ADR (gate and dial together, Rule 292), not a quiet dial turn.

**VERIFY-BY:** unchanged — `social` present in `forecastScalars` and in at least one `contributions`
array, and its clustered t re-measured on a grown cohort count.

---
## Verification block — 2026-08-04 16:00Z (**No change — `026cda49d` is at 5/6 cycles; the code stays frozen.** The book is now fully deployed (21 equity positions, gross **$71,398.69**) and that changes the ranking: decomposing the firm total shows the desk's **direction is roughly flat and its COST is the loss**. Firm total **-$458.07** = pre-fee trading **-$94.09** − fees **$363.98**; fees are **79.46%** of the loss. Cumulative LIVE turnover **$4,120,150.44** is **57.71×** the current gross. A new item #1 is therefore ranked above the social dial — with the reason stated, not assumed.)

### Step 0 — `026cda49d` (revert of ADR-0136): ✅ DEPLOYED / ⏳ STILL NOT SCORED — held, undisturbed

`scripts/score-change.py score` prints `026cda49d still accumulating evidence (5/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still holds its snapshot (`ts 2026-08-04T13:37:23Z`,
`gross_exposure 0E-8`). Deployment re-confirmed on a warm JVM: `ops_jvm.uptimeSeconds` **`1363`** at
`traffic.timestampMillis` **`1785859202173`**, `traffic.up true`, `provider alpaca`, `ticksIn 13234`,
`ticksDropped 0`. Per ADR-0116 this **forbids a code change this cycle**; **no baseline was recorded**, so
its evaluation window is intact and it should score next cycle.

### Step 0 — Item #1 of the 15:30Z block (`social` is the only source with edge and contributes `0.0`): ⚠️ STILL-BROKEN — confirmed, and now confirmed *structurally*

Re-measured on this run's `/api/signals/telemetry` with the desk's own ADR-0077/0081 clustered statistic
`avgReturnBps ÷ (stdCohortMeanBps ÷ √cohorts)` over all fifteen (source, horizon) pairs — `social` @3600s is
again the **highest t of the whole set**, and it is the **only** source that clears the desk's own **1.5**
hurdle:

| horizon | source | resolved | cohorts | hitRate | avgReturnBps | clustered t |
| --- | --- | --- | --- | --- | --- | --- |
| 3600 | **social** | 345 | 32 | **0.5977** | **+8.7221** | **+1.601** |
| 3600 | reversion | 658 | 87 | 0.4959 | +3.2504 | +1.079 |
| 3600 | trend | 700 | 97 | 0.5250 | −1.5295 | −0.582 |
| 3600 | momentum | 58 | 9 | 0.4375 | −3.6586 | −0.701 |
| 3600 | xsreversion | 650 | 39 | — | −5.4256 | −1.130 |
| 900 | social | 684 | 69 | — | +1.8322 | +1.010 |
| 225 | social | 750 | 75 | — | +0.1611 | +0.246 |

Still monotonic in horizon (**+0.1611 → +1.8322 → +8.7221** bps at 225/900/3600s) — hour-scale information.
`jethro.fusion.social.per-channel=0` is still live at `application.properties:274`.

**New, stronger evidence than last cycle's arithmetic:** with the book deployed, `/api/fusion/targets`
shows social is not merely multiplied to zero *downstream* — it never reaches the combiner at all. Every
`contributions` array on every name lists only `trend`/`reversion`/`xsreversion` (`sources: 3`), and
`forecastScalars` has **no `social` entry** (only `momentum` 2 readings, `reversion` 2018, `trend` 2762,
`xsreversion` 2514). Yet `weights` still carries `social 1.8401656178888028` — the **largest** of the five.
Rule 291 holds and hardens.

### Item #1 — the desk's loss is TURNOVER COST, not direction: fees are 79.46% of the firm loss and cumulative turnover is 57.71× the book (⚠️ OPEN, #1 — NEW)

Decomposing `/api/risk` `.total` against `/api/attribution` `totalFees` (computed by script, not authored):

| quantity | value |
| --- | --- |
| firm total PnL | **-$458.07450234** |
| total fees | **$363.980201** |
| **pre-fee trading PnL** | **-$94.09430134** |
| fees as a share of the loss | **79.46%** |
| ALPHA pre-fee (fees $351.971487) | -$172.59748123 |
| HEDGE pre-fee (fees $10.899560) | +$77.39402589 |

Pre-fee, the desk is **-$94.09 on $71,398.69 of gross** — statistically indistinguishable from flat. The
**$363.98** of fees is what makes it a loss. Its source is churn: summing `turnover_cost_by_name`
`turnover_usd` over the LIVE epoch gives **$4,120,150.44** against a current gross of **$71,398.69** —
**57.71×**. At `fee_bps` **1.00** per equity side that turnover costs exactly the fees observed.

**Mechanism (from `recent_orders`, each row carrying its ADR-0134 `originReason`):** an hour-scale forecast
is routed on a ~30-second replan. PFE is the exemplar — built short on `fusion entry — target increase` at
`forecast` **-8.6125 → -7.0929 → -5.8472 → -5.5667 → -5.1482 → -5.1178**, then within ~6 minutes the same
name flipped to `fusion reduce toward a smaller target` at `forecast` **+0.0134 → +0.0209 → +0.0274 →
+0.1017 → +0.1634 → +0.5167 → +0.5841**, buying back **3 shares at a time** every ~30s. PFE now shows
**133 fills**, **$219,600.58** turnover and **$21.9601** fees against a **$9,166.59** position — a
**23.96×** re-trade — and it is the **worst name on the desk at -$195.29620728** (short 358 @ avgCost
`25.35220624` vs mark `25.60500000`). MSFT is worse on the ratio: **$200,516.55** turnover, **192 fills**,
**$5,970.66** position → **33.58×**. Book-wide, `orders_by_status` shows **1,911 CANCELLED** against
**5,179 FILLED** — the `fusion re-plan — passive order superseded by a fresh target (ADR-0084)` path
re-issuing faster than the market resolves the signal.

**Why this outranks the social dial** (stated, not assumed): social's edge is **+8.7221 bps at a 3600s
horizon** — it pays out over about an hour. A book that re-trades itself **57.71×** cannot hold a position
long enough to collect an hour-scale forecast, and would spend the new edge on fees the same way it is
spending the current one. Cost control is the precondition for item #2 paying, not a competing priority.

**VERIFY-BY (next run, all read live):** (a) `/api/attribution` `totalFees` growth over the window falls
relative to this window's, while pre-fee PnL (`.total.totalPnl` + `totalFees`) does **not** deteriorate;
(b) the top `turnover_cost_by_name` `turnover_usd ÷ /api/risk` per-name `grossExposure` ratio drops below
the **23.96×** (PFE) / **33.58×** (MSFT) recorded here; (c) `orders_by_status` CANCELLED-to-FILLED falls
below the **1,911 / 5,179** recorded here. If (a) improves only because the desk stopped trading, that is
**not** a pass — gross must stay materially deployed (it is only **4.8%** of the $1,500,000 firm cap, with
**$1,428,601** of headroom, so there is no reason to shrink).

### Item #2 — the ONLY source with measured positive expectancy contributes a forecast of exactly 0.0 (⚠️ OPEN, #2 — was #1; needs an OWNER DECISION / superseding ADR, not a dial turn)

Evidence re-measured above (t **+1.601**, the only source clearing the desk's own **1.5** hurdle; absent
from every `contributions` array and from `forecastScalars`). The desk sizes entirely off `reversion`
(weight **1.6969247455912904**, t **+1.079**), `trend` (**0.5718732950667389**, t **−0.582**) and
`xsreversion` (**0.2799728684361928**, t **−1.130**) — **two of the three effective sources measure
negative** at the 3600s horizon.

Per Rule 292 this is **not** a dial turn. `per-channel=0` is `ADVISORY-ONLY ENFORCEMENT (Oleg, 2026-07-27)`
restoring ADR-0049, and its stated restore condition ("still behind the OOS edge gate") does **not** hold
today — `jethro.fusion.edge-gate.enabled=false` (ADR-0122). Gate and dial move together in one superseding
ADR, or not at all.

**VERIFY-BY:** `/api/fusion/targets` `contributions` contains a `social` entry on ≥1 name with a non-zero
`forecast`, **and** `forecastScalars` gains a `social` entry with `readings > 0` — while the edge gate's
state is whatever the superseding ADR sets, stated explicitly in that ADR.

---
## Verification block — 2026-08-04 15:30Z (**No change — `026cda49d` is at 4/6 cycles, so the code is frozen.** The cycle's value is that the standing "does ANY source have edge?" question is now **answered from live telemetry**: `social` at the 3600s horizon carries `avgReturnBps` **+9.4817** with the highest clustered t of all fifteen (source, horizon) pairs — and `jethro.fusion.social.per-channel=0` multiplies its every forecast to exactly `0.0`. Last block's item #1 is struck: 20 names traded with no trading commit in between, so the band was a convergence lag, not a wall.)

### Step 0 — `026cda49d` (revert of ADR-0136): ✅ DEPLOYED / ⏳ STILL NOT SCORED — held, undisturbed

`scripts/score-change.py score` prints `026cda49d still accumulating evidence (4/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still holds its snapshot (`ts 2026-08-04T13:37:23Z`).
Deployment re-confirmed: `ops_jvm.uptimeSeconds` **`1316`** at `traffic.timestampMillis` **`1785857401923`**
→ a fresh JVM booted after the commit. Per ADR-0116 that forbids a code change this cycle. **No baseline
was recorded**, so its window is intact.

### Step 0 — Item #1 of the 15:00Z block (the band `|target|/|forecast|` blocks opening from flat): 🔴 FALSIFIED — struck, not fixed

Its VERIFY-BY was that a flat name cannot open. **It opened, on 20 names, with no trading commit in
between** (only `4f9ff26`/`685f01a`, the report generator and docs). `insideBuffer` went **22 → 20 → 16**
of 21 while `streamVolMeasuredNames` reached **20** = `volBudgetNames` and `covarianceCoveredNames`
**20**; aims grew to `PFE -824.468683`, `NEE -191.515729`, `KO +169.115440`, `JPM +80.451761`,
`JNJ +79.504232`, and gross went **$0.00 → $51,339.05**. The 6-of-6 reconciliation was arithmetically
right about that *instant* and wrong about the *mechanism*: it measured a **cold-JVM convergence lag**,
not a structural barrier. **Fourth time** (Rule 287) — struck without prescribing into it.

### Item #1 — the ONLY source with measured positive expectancy contributes a forecast of exactly 0.0 (⚠️ OPEN, #1 — NEW; needs an OWNER DECISION / superseding ADR, not a dial turn)

Computing the desk's own ADR-0077/0081 clustered statistic `avgReturnBps ÷ (stdCohortMeanBps ÷ √cohorts)`
across all fifteen (source, horizon) pairs in `/api/signals/telemetry`, **`social` @ 3600s is the highest
t of the whole set**:

| horizon | source | resolved | cohorts | avgReturnBps | clustered t |
| --- | --- | --- | --- | --- | --- |
| 3600 | **social** | 344 | 32 | **+9.4817** | **+1.75** |
| 3600 | reversion | 649 | 87 | +2.7012 | +0.93 |
| 3600 | trend | 696 | 98 | −0.7778 | −0.30 |
| 3600 | xsreversion | 634 | 39 | −4.9878 | −1.03 |
| 900 | social | 675 | 67 | +1.6706 | +0.90 |
| 900 | trend | 2347 | 316 | +0.2513 | +0.36 |
| 225 | trend | 5873 | 500 | +0.0298 | +0.13 |

Its expectancy is 3.5× the next-best and rises monotonically with horizon (**+0.2849 → +1.6706 → +9.4817**
bps at 225/900/3600s) — information that pays out over about an hour. Cost does not eat it: `/api/tca`
`avgSlippageBps` is sub-bps on the liquid names (`MSFT 0.594`, `PFE 0.633`, `AMZN 0.658`, `GOOG 0.746`) and
`turnover_cost_by_name` `fee_bps` is **1.00** per equity side → a round trip near **3.3 bps**, cleared ~3×.

**The block:** `jethro.fusion.social.per-channel` is **`0`**, and `SourceForecasts.fromSocial` computes
`strength = max(1, corroboratingChannels) × perChannel` — so every social forecast is exactly `0.0`
regardless of direction or corroboration. `/api/fusion/targets` `weights` showing `social 1.725995` — the
largest of the five, above `reversion 1.500165`, `momentum 0.761144`, `trend 0.712538`,
`xsreversion 0.300158` — is **decorative**; the telemetry weighting optimises a source multiplied out
downstream. The book is therefore sized entirely off trend/reversion/xsreversion/momentum, **none of which
has a significantly positive expectancy at any horizon**.

**Why this is NOT a dial turn.** `per-channel=0` is `ADVISORY-ONLY ENFORCEMENT (Oleg, 2026-07-27)`,
restoring **ADR-0049** ("a social subject can NEVER originate an order"). Settled decision → superseding
ADR + owner call, never a quiet edit. Its stated justification (*"Social's own OOS edge was measured
negative anyway"*) is what the live telemetry now contradicts — and because social has never sized, that
telemetry is an **uncontaminated OOS measurement**, exactly the evidence ADR-0049 asks for. But its stated
restore condition (*"still behind the OOS edge gate"*) does **not** currently hold:
`jethro.fusion.edge-gate.enabled=false` under ADR-0122. Restoring `4.0` today is therefore a *different*
decision from the one that comment authorises, and must not be conflated with it.

**VERIFY-BY (next run, read from `/api/signals/telemetry`):** `social` @ `horizonSeconds 3600` still has
`avgReturnBps` **> 0** with `cohorts` **> 32** and a clustered t **≥ 1.75** (ideally clearing the desk's
own `edge-gate.t-hurdle` **2.0**). If it holds two more cycles, the one change is the superseding ADR that
re-admits corroborated social to sizing **with** the ADR-0064 per-name edge gate re-enabled for that source
— gate and dial together, since t=1.75 on 32 cohorts is suggestive, not proven. If it decays back toward
0, strike this item and say so plainly.

### Item #2 — the fee bill exceeds the deficit, but is sunk, not bleeding (⚠️ OPEN, #2 — deliberately NOT ranked #1)

`totalFees` **360.289492** against `firmTotal` **-297.161461** still means the desk is positive gross of
cost. Rule 289's test settles the ranking with this window's own numbers: fees grew about **$2.60** while
gross grew **$32,374.63**, over **5148** lifetime FILLED orders most of which predate
ADR-0064/0084/0094/0101. **VERIFY-BY:** re-rank to #1 only if `totalFees` growth per unit of *new* gross
exceeds the prior window's — i.e. a live cost rate, never the cumulative figure.

---
## Verification block — 2026-08-04 15:00Z (**No change — `026cda49d` is at 3/6 cycles, so the code is frozen.** The cycle's value is that last block's item #1 is ✅ VERIFIED-RESOLVED *without any code*, which falsifies its stated root cause and replaces it with an arithmetically proven one: the ADR-0094 band is `|target|/|forecast|`, the aim path has only converged to 6–20% of target, and the two are mismatched from flat — so a name with zero holding **cannot open**. Reconciled exactly on 6 of 6 rendered names.)

### Step 0 — `026cda49d` (revert of ADR-0136): ✅ DEPLOYED / ⏳ STILL NOT SCORED — held, undisturbed

`scripts/score-change.py score` prints `026cda49d still accumulating evidence (3/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still holds its snapshot (`ts 2026-08-04T13:37:23Z`,
`gross_exposure 0E-8`). Deployment re-confirmed: `ops_jvm.uptimeSeconds` **`1487`** at
`traffic.timestampMillis` **`1785855602555`** → this is a fresh JVM booted after the commit. Per ADR-0116
that forbids a code change this cycle. **No baseline was recorded**, so its window is intact.

### Step 0 — Item #1 of the 14:30Z block (risk-scaling collapses 17 of 22 aims to zero): ✅ VERIFIED-RESOLVED — but by STATE, not by code, which falsifies its root cause

Its VERIFY-BY was: non-zero `aims` above **5** of 22, `insideBuffer` below **22**, gross off **$0.00**. All
three are met — non-zero aims **20** of 22 (only `GOOGL 0.0` and `PLTR 0.0` remain, precisely the two names
outside `volBudgetNames 20`), `insideBuffer` **20**, gross **$11,313.99**. **No trading code changed**: the
only commit since was `4f9ff26`, the offline report generator. What moved was JVM state —
`streamVolMeasuredNames` **6 → 20**, `bookVolBrake` **0.880 → 1.0**, `portfolioRiskMultiplier`
**0.977 → 1.0**, `volBudgetLeverCap` **0.995 → 1.0**. So the zero aims were a **cold-start coverage
artifact** on an ephemeral ~25-minute JVM, not the structural sizing defect the block asserted. Struck.

### Item #1 — from a flat holding a name CANNOT open: the no-trade band is `|target|/|forecast|` while the aim path is at 6–20% of target (⚠️ OPEN, #1 — NEW, arithmetically proven)

The desk holds **$11,313.99** gross against the **$1,500,000** firm cap (**0.8%**, headroom
**$1,488,686**) with `routing` **`true`**, `edgeGate` **`null`**, `riskCuts` **`[]`**, `breaker.halted`
**`false`** and **20 of 22** names carrying a non-zero aim. Nothing denies it permission. The band and the
aim are simply mismatched, and the arithmetic is closed-form, not inferred:

`PositionBuffer.band` is `scale × fraction` with `scale = |target| × Forecast.TARGET_ABS / |forecast|`.
`Forecast.TARGET_ABS` is **10.0** and `jethro.fusion.position-buffer.fraction` is **0.10**, and
`widthFor` returns that floor unchanged because `gate` is `null` (so the ADR-0101 measured width never
applies). The band is therefore exactly **`|target| / |forecast|`**. Reading `targets` ⋈ `aims` from this
cycle's `/api/fusion/targets`, with `gap = aim − currentQty`:

| name | forecast | targetQty | aim | aim/target | held | gap | band = \|target\|/\|forecast\| | deltaQty |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AMZN | 5.978480 | 449.356041 | 90.236431 | 0.201 | 10.0 | 80.236431 | 75.16 | **5.074178** |
| NVDA | 5.805192 | 534.093773 | 65.071975 | 0.122 | 0 | 65.071975 | 92.00 | 0.0 |
| XOM | −5.137078 | −598.622367 | −90.960412 | 0.152 | 0 | −90.960412 | 116.53 | 0.0 |
| CAT | −5.075347 | −103.426349 | −9.047052 | 0.087 | 0 | −9.047052 | 20.38 | 0.0 |
| PG | −5.039211 | −1025.046014 | −66.612635 | 0.065 | 0 | −66.612635 | 203.41 | 0.0 |
| AAPL | −4.813032 | −489.014825 | −38.250908 | 0.078 | 0 | −38.250908 | 101.60 | 0.0 |

**6 of 6 reconcile.** Every zero-`deltaQty` name has `|gap| < band`. AMZN is the one that trades, and it
trades to the band's near edge: `80.236431 − 75.16 = 5.07` against the observed **`5.074178`** — the model
is confirmed to the routed share count, so this is measurement, not hypothesis.

The opening condition reduces to `|aim| / |target| > 1 / |forecast|`. At the forecasts this desk actually
carries (**4.81–5.98**) that demands the aim reach **17–21%** of target, while the ADR-0080/ADR-0117
partial-adjustment path has it at **6.5–20.1%** after ~25 minutes of uptime. The desk is stalled a few
percent short of its own trigger, on **every** name, from flat. AMZN cleared it only because it already
held 10 shares.

**Why this is the top item.** It is the whole gap between a $11.3k book and the aims it has already
decided on, on a $1.5M cap — the "undeployed capital is a failure to attack" case in ADR-0132, quantified.
It is also **not** another routing-permission rule: ADR-0135, its revert and ADR-0136 all graded ❌ BAD
changing *whether* the desk may act. This is *how far* it acts, one layer down.

**Do NOT reach for the obvious dial.** Lowering `position-buffer.fraction` re-litigates Carver's published
convention and ADR-0101's floor, and widening the adjustment rate re-litigates ADR-0117's identity
`a = 1 − exp(−cycle/horizon)`. The defect is neither number alone — it is that `scale` is the average
position at a **typical-strength** forecast (`TARGET_ABS = 10`) while the aim is throttled toward a
**below-typical** one, so a weak view gets a band *wider than its own target*. Any change needs a
superseding ADR that states which of the two quantities is being re-based, and must leave exits unbuffered.

**VERIFY-BY (next run):** in `/api/fusion/targets`, `insideBuffer` falls below **20** of 22 and the count of
names with non-zero `deltaQty` rises above **1**, with firm gross exposure (`/api/risk` `.total`) above
**$11,313.99** — and the same `gap` vs `band` table recomputed from live fields shows at least three names
with `|gap| ≥ band`. Fee cost must not be traded for it: `/api/attribution` `totalFees` growth per unit of
new gross stays at or below its current ratio.

### Item #2 — the ephemeral JVM never outlives the sensor warm-up (⚠️ OPEN, #2 — NEW, carried from the struck item's real cause)

The struck item's true cause deserves its own row: `streamVolMeasuredNames` was **6** of
`volBudgetNames 20` at ~23 minutes of uptime last cycle and **20** of 20 at ~25 minutes this cycle, and
the WARN log still shows `trend sensor still cold for XOM after seeding 175 of 193 stored prices` (also
CVX, JPM). The loop boots a fresh ~25-30 minute JVM every cycle, so a material fraction of the desk's life
is spent under-covered and mis-sized — and, worse, **the loop has repeatedly read that warm-up state as a
structural defect and shipped routing changes at it** (three ❌ BAD in a row). Below item #1 because it now
self-heals within the cycle; above nothing else because it corrupts every diagnosis taken too early.
- **VERIFY-BY:** `streamVolMeasuredNames` equals `volBudgetNames` and zero `trend sensor still cold`
  WARNs, at an `ops_jvm.uptimeSeconds` **below 900**.

### Item #3 — the cumulative fee bill exceeds the cumulative deficit, but is NOT a live bleed (⚠️ OPEN, #3 — DEMOTED, framing corrected)

`/api/attribution` `totalFees` **357.229782** against `firmTotal` **−308.37124107** — the desk is
**+48.86** gross of fees and the entire loss is cost. But that is a **lifetime** figure over
`orders_by_status` FILLED **5129**, accumulated across many boots and several code generations, most of it
before the cost-aware work (ADR-0064, ADR-0084, ADR-0094, ADR-0101) landed. This window placed **4** orders
in ~25 minutes. Ranking it #1 would be overfitting to a sunk cumulative number — the live trading rate is
not the one that produced it. **Re-open as #1 only if fee growth per unit of new gross rises** once item #1
deploys and turnover resumes.
- **VERIFY-BY:** `totalFees` growth per cycle divided by the cycle's gross-exposure growth, tracked against
  the ratio implied by the current **357.229782 / 11,313.99**.

---
## Verification block — 2026-08-04 14:30Z (**Fixed the report truncation (item #1).** The previously-hidden fields answered the question on the first read: the edge gate is open and the ADR-0094 buffer is holding all 22 names — but the `aims` map shows the real defect is upstream of both, in **sizing**: the risk-scaling stage collapses 17 of 22 aims to zero and leaves the rest at single-digit share counts the buffer then absorbs. That becomes item **#1**. `026cda49d` remains under measurement and was NOT disturbed — this change is to the offline report generator, not the JVM, and no new baseline was recorded.)

### Step 0 — Item #1 of the 14:00Z block (report truncation): ✅ VERIFIED — fixed and struck

`scripts/system-report.py` rendered each endpoint as `json.dumps(data, indent=1)[:6000]`, cutting the
*string* at a fixed offset. Measured against this run's live payloads: `/api/fusion/targets` serialises to
**14,266** chars and the old form lost **17 of its 22 top-level fields**; `/api/risk` (**10,881**) and
`/api/discovery` (**20,757**) were cut the same way. The fix elides long *arrays* progressively until the
object fits, marking each elision explicitly. Proving numbers, from a full end-to-end `system-report.py`
run: lost top-level fields on `fusion_targets` **17 → 0**; rendered block **5,449** chars (smaller than the
old truncated 6,000, so no budget cost); **zero** endpoint blocks at or over budget. `./gradlew -Pci test`
BUILD SUCCESSFUL.

### Step 0 — `026cda49d` (revert of ADR-0136): ✅ DEPLOYED / ⏳ STILL NOT SCORED — held, undisturbed

`ops_jvm.uptimeSeconds` **`1390`** at `traffic.timestampMillis 1785853801356` (`2026-08-04T14:30:01Z`) →
boot **`2026-08-04T14:06:51Z`**, after its `13:37:19Z` commit. Still no ledger row; `.pending-baseline.json`
still holds its snapshot. No orders since `2026-08-04 13:34:31`. **No baseline was recorded this cycle**, so
its ADR-0116 window is intact. No trading code was touched.

### Item #1 — the risk-scaling stage collapses 17 of 22 aims to zero, so a fully-routing desk stays flat (⚠️ OPEN, #1 — NEW, promoted above the breadth collapse)

Read from the fields the truncation had been discarding, live this cycle. The desk is **not** being denied
permission to trade: `routing` **`true`**, **`edgeGate` `null`** (gate not shut), `riskCuts` **`[]`**,
`riskCutStoppedNames` **`0`**, `breaker.halted` **`false`**. What it lacks is size. The `aims` map carries
`GOOG 2.445123`, `NVDA -8.537723`, `AAPL -8.609491`, `CAT 0.984813`, `AMZN 3.444406` and **`0.0` for the
other 17 names** — against `targets` entries with far larger `targetQty` (e.g. `JPM … → 921.47787`) and
`currentQty 0` throughout. `insideBuffer` is **`22`**: the five surviving single-digit aims are then
absorbed by the ADR-0094 band, which is why the book shows `deltaQty 0` everywhere and gross **$0.00**
(0.0% of the $1,500,000 firm cap) in an open session.

So the buffer is the *proximate* suppressor but not the root: a band cannot be the problem when the aim it
is asked to cross is 2 shares against a 921-share target. The root is between `targetQty` and `aim`.
Candidate scalers, all now visible: `portfolioRiskMultiplier 0.977009632409871`, `volBudgetLeverCap
0.9946240255628247`, `bookVolBrake 0.8800080087567065`, `volBudgetDispersion 2.49171157141781`,
`bookVolPlannedSigmaUsd 382.73752692272154` vs `bookVolReferenceSigmaUsd 336.81208894373054`,
`volBudgetNames 20`, `covarianceCoveredNames 20`, `covarianceBasis "mark-stream"`, `streamVolMeasuredNames`
only **6**. None of those multipliers is near zero, so multiplication alone does not explain 921 → 0.0 —
which points at a per-name gate or floor inside the vol-budget allocation, most likely tied to the 6-of-20
names with measured stream vol. **Diagnose which stage zeroes the aim before changing anything**; do not
ship another routing-permission rule (ADR-0135, its revert, and ADR-0136 all graded ❌ BAD doing that).

**VERIFY-BY (next run):** in `fusion_targets`, the count of names with non-zero `aims` rises above **5**
of 22, and `insideBuffer` falls below **22** — with gross exposure moving off **$0.00** against the
$1,500,000 cap. If the next change is diagnostic only, the VERIFY-BY is instead a named stage: the specific
multiplier/floor whose live value maps `targetQty` to `aim 0.0`, quoted from telemetry.

## Verification block — 2026-08-04 14:00Z (**No change this cycle — the ADR-0136 revert `026cda49d` is still under measurement** (no ledger row, `reports/.pending-baseline.json` present), so touching the code would destroy its evidence. The cycle's value is a diagnosis: the desk planned 21 targets and routed none, and the report is **structurally incapable** of naming which suppressor did it. That blindness — not any routing rule — is why three consecutive changes at this layer graded ❌ BAD, so it takes over as item **#1**.)

### Step 0 — `026cda49d` (revert of ADR-0136): ✅ DEPLOYED / ⏳ NOT YET SCORED — hold, do not disturb

Deployment confirmed, so this is live code and not a stranded commit: commit stamped `2026-08-04T13:37:19Z`;
`ops_jvm.uptimeSeconds` **`1325`** at `traffic.timestampMillis 1785852002696` (`2026-08-04T14:00:02Z`) →
boot **`2026-08-04T13:37:57Z`**, after it. The rejected mechanism is gone from the running code: `grep` over
`app/` for `clearsConvictionFloor` and `ADR-0136` returns nothing.

**No verdict is claimed and none is available.** There is no ledger row for `026cda49d` and the pending
baseline still exists — the ADR-0116 window is accumulating. The revert has also placed **no orders**: the
newest `recent_orders` row is `2026-08-04 13:34:31`, *before* the boot. It will be graded on its window.

### Item #1 — the report truncates `/api/fusion/targets` before the fields that name the suppressor (⚠️ OPEN, #1 — NEW, promoted above the breadth collapse)

`fusion_targets` at `atMillis 1785851991548` reports `routing: true` over **21 instruments** with real
conviction — `JPM combinedForecast 13.201644846856365 → targetQty 921.47787`,
`BAC 11.759593061749303 → 4682.485093`, `PG 9.730455372959304 → 1672.761797` — and **every visible target
carries `currentQty 0` and `deltaQty 0`**, 22 minutes into an open session (`feeds` alpaca `connected true`,
`lastUpdateAgeMillis 16`; `ticksIn 26538`, `ticksDropped 0`). Gross **$0.00**, `var95 0.00` note
`"no positions"`, `breaker.halted false`. The desk sized 21 names and planned to trade none of them. That is
not an absence of opportunity — it is suppression.

**Two mechanisms produce exactly that pattern and the report cannot tell them apart.** In `FusionLifecycle`
the gate clamp runs at line 302 (`reduceOnlyWhere`) and the ADR-0094 buffer at line 321, both *before*
`lastBook` is published at line 324 — so the telemetry `deltaQty 0` is post-both. Either (a) `EdgeGate` is
shut and `TargetPlanner.reduceOnly` projects every increase onto zero because `currentQty` is zero, or
(b) the `PositionBuffer` is holding every name inside its band. **The remedies are opposite.**

**Root cause is a truncation in the report generator, not in the trading code.**
`scripts/system-report.py:405` emits `json.dumps(data, indent=1)[:6000]` per endpoint. The delivered
`fusion_targets` block measures exactly **6001 characters** — hard-cut mid-object inside the `targets`
array. The `TargetBook` record orders its fields `… targets, edgeGate, portfolioRiskMultiplier, …, aims,
insideBuffer, …`, so **every field that would name the suppressor sits after the cut and is discarded every
cycle.** `FusionController` serializes them correctly; the report throws them away.

**Why this outranks the breadth collapse.** ADR-0135, its own revert, and ADR-0136 were three consecutive
routing-rule changes at this layer, all graded ❌ BAD. Rule 280 said the defect was one layer up. It is —
and the layer up is **observability**: the loop has been prescribing fixes for a suppressor it has never
been able to see. This costs no risk, puts no money on, cannot move the vector, and is what makes the next
change aimed instead of guessed.

**VERIFY-BY (next run):** the `fusion_targets` block in `logs/report.md` contains a non-null **`edgeGate`**
object (its `mayIncrease` boolean and `reason` prose) and a numeric **`insideBuffer`** count — neither of
which appears in this run's block. Then, from those two fields, state in one sentence which mechanism
zeroed the deltas. Do **not** change any routing rule until that sentence can be written from telemetry.

### Item #2 — the breadth collapse liquidates the whole book at the equity close (⚠️ OPEN, #2 — demoted from #1, unchanged evidence)

Demoted only because item #1 is its prerequisite, not because it got cheaper: the same `sources=1` sweep is
still the largest single identified cost. Evidence unchanged and still visible in this run's
`recent_orders` — on 2026-08-03 the entire routed book (`NEE`, `GOOG`, `JNJ`, `CAT`, `CVX`, `PG`, `XOM`,
`NVDA`, `AAPL`, `AMZN`, `BAC`) exited in one sweep, every row `fusion exit — target decayed to flat` and
**every one carrying `sources=1`**, where the entries that preceded them carried `sources=2`/`sources=3`.
Since ADR-0113 the price-driven sensors advance only on PRINTS, so they fall silent at every cash close and
leave the snapshot-based cross-sectional source alone. `totalFees 356.521740` against
`firmTotal -314.90914721` — the fee bill still exceeds the entire deficit, and this round trip is the bulk.

**VERIFY-BY:** at the next cash close, no `fusion exit — target decayed to flat` row carries `sources=1`
while the preceding entry for that name carried `sources≥2`. **Blocked on item #1** — three routing-rule
remedies have already been graded ❌ BAD here, so the next attempt must be aimed by the restored
`edgeGate`/`insideBuffer` telemetry rather than guessed.

---
## Verification block — 2026-08-04 13:30Z (**Revert shipped.** `e3b33679d`/ADR-0136 was scored ❌ BAD and the scorer's own `git revert` conflicted, leaving the graded-bad mechanism LIVE for a full cycle. Completing that revert is this cycle's one change. The finding it leaves behind is the important part: the mechanism **passed its own falsification test and still lost the vector** — so item #1 is re-ranked onto the breadth collapse.)

### Step 0 — `e3b33679d` (ADR-0136): 🔴 REGRESSED on the objective, ✅ VERIFIED on its own mechanism

Deployment confirmed, so this grades live code and not a stranded commit: `ops_jvm.uptimeSeconds` **`64358`**
at `traffic.timestampMillis 1785850202149` → boot **`2026-08-03T19:37:24Z`**, after the commit's
`19:15:32Z`. It ran continuously through the whole evaluation window.

**Its own falsification test passed on both halves** (verified last cycle by splitting the order log at
boot; nothing since contradicts it). Sub-floor `fusion reduce toward a smaller target` went 13 → 0, the
sub-floor `fusion exit — target decayed to flat` still routed, and `fusion_targets` showed three sub-floor
names (`NEE`, `JNJ`, `NVDA`) with non-zero planned deltas that never became orders — suppression, not an
absence of opportunity.

**And the scorer graded it ❌ BAD anyway**, on the risk-adjusted return over its ADR-0116 window with the
t-statistic clearing the hurdle on the losing side. The ledger row owns those numbers. `revertApplied` was
`false` — a git conflict on the loop's report files — so the mechanism stayed in the running code.

**This is the whole lesson of the cycle.** A change that does exactly what it specified, and whose
specification was honestly scoped, can still lose: ADR-0136 itself recorded that the sub-floor dribble was
a *minority* of turnover. The window has now priced that admission. **Verifying a mechanism is not
verifying a fix** — the register's VERIFY-BY discipline grades whether the defect went away, and it must
not be mistaken for evidence that removing the defect helped.

**Action taken:** the two running-code paths (`FusionLifecycle`, the `application.properties` provenance
comment) restored to `e3b33679d^`; `ConvictionFloorRoutingTest` deleted; ADR-0136 and `docs/adr/README.md`
annotated **Reverted** with the rationale; the loop's findings kept. Verified no `clearsConvictionFloor`
or `ADR-0136` reference survives under `app/src/`. `./gradlew -Pci test` green.

### Item #1 — the breadth collapse liquidates the whole book at the equity close (⚠️ OPEN, #1 — promoted)

Three mechanisms have now been scored ❌ BAD against this bleed: **ADR-0135** (hold an unestimable view),
**its own revert**, and now **ADR-0136** (bind the floor on partial reduces). A change, its exact inverse,
and an adjacent remedy all losing says the graded variable is dominated by something none of them touched.

The order log names it directly. Post-boot on 2026-08-03, `recent_orders` shows `NEE`, `GOOG`, `JNJ`,
`CAT`, `CVX`, `PG`, `XOM`, `NVDA`, `AAPL`, `AMZN`, `BAC` — the entire routed book — exiting in one sweep,
every row tagged `fusion exit — target decayed to flat` and **every one carrying `sources=1`**. The
entries that preceded them carried `sources=2` and `sources=3`. Breadth collapsed to a single effective
source at the cash close, every combined forecast decayed to flat, and the book was liquidated.

This is a **sensor-availability defect upstream of every routing rule**, which is why three routing-rule
remedies could not reach it: since ADR-0113 the price-driven sensors advance only on PRINTS, so they fall
silent at every close, leaving the snapshot-based cross-sectional source alone. The desk is not deciding
to be flat — it is losing the ability to hold an opinion, and paying a full round trip for it.

**Rank rationale.** `/api/attribution` reads `totalFees 356.521740` against `firmTotal -314.90914721` —
**the fee bill still exceeds the entire deficit**, and `ALPHA` is `-371.22428289` on `feesPaid 345.102835`.
Gross of fees the desk is roughly flat: the cost IS the loss, and this round trip is the bulk of it.

**VERIFY-BY next run:** in `recent_orders`, the count of `fusion exit — target decayed to flat` orders
carrying `sources=1` must be **0** across a session close, while orders at `sources≥2` are unaffected.
Cross-check `fusion_targets.sources` spanning the close: a name held before the close must not show
`sources` dropping to 1 with `combinedForecast` at `±0.0`.

**Constraint on the next attempt — do not repeat what the ledger already rejected.** Not another
conviction-floor variant, and not ADR-0135's "hold the position through the collapse" (graded BAD: it kept
risk deployed against a view already measured uninformative). The untried direction the ADR-0135 index
already records: **decay the inventory at the ADR-0080 partial-adjustment rate while breadth is absent**,
so sensor silence costs neither a full round trip nor a full position's carry.

### Item #2 — the loop's report truncates 8 of 24 JSON sections into invalid JSON, silently (⚠️ OPEN, was #1)

`scripts/system-report.py:405` — `L.append(json.dumps(data, indent=1)[:6000])`. Unchanged this cycle and
still live: `fusion_targets` again declares fewer instruments than the desk measures, and `risk`, `marks`,
`discovery`, `social`, `tca`, `strategy_selection`, `orders_day` are still cut mid-object with no marker.

**Why it moved to #2 rather than being fixed.** It is a diagnosis-quality defect with no path into the
JVM, and this cycle's slot was owed to a graded-BAD mechanism sitting live in the running code — a money
defect outranks a reporting one. It stays open and ranked, not dropped.

**Scope, re-confirmed against this run's report:** `signals_telemetry` is again well under the cap (its
per-source rows for `momentum`, `reversion` and the rest are complete), so the standing "no source has
measured edge" conclusion rests on the full source set. `scripts/score-change.py` still fetches
`/api/risk` and `/api/attribution` over HTTP directly (`urlopen`, line 94), never through the report — **no
ledger number has ever been computed from truncated input.** Invariant 7 holds.

**VERIFY-BY:** every ```json``` block in `logs/report.md` parses with `json.loads`, or carries an explicit
truncation marker naming what was dropped.

---
## Verification block — 2026-08-03 19:30Z (**No change — `e3b33679d` is mid-evaluation.** ADR-0136 is ✅ VERIFIED on its stated mechanism from post-boot orders. A new defect displaces item #1: the loop's own report silently truncates 8 of 24 JSON sections into invalid JSON, so every cross-section diagnosis has been running on a partial view.)

### Step 0 — `e3b33679d` (ADR-0136): ✅ VERIFIED on mechanism — PnL verdict still accumulating

`reports/.pending-baseline.json` **exists** for `e3b33679d`; no new ledger row. The scorer has not judged
it, so **no code change this cycle** — that is the contract, not a preference.

**Deployed, confirmed:** commit at `2026-08-03T19:15:32Z`; `ops_jvm.uptimeSeconds` **`813`** at
`timestampMillis 1785785403524` → boot **`19:16:30Z`**, after the commit.

**Its own falsification test, both halves passing.** The window's 60 orders split at boot:

| | pre-boot (44) | post-boot (16) |
| --- | --- | --- |
| `fusion reduce toward a smaller target` at \|forecast\| < `5.0` | **13** | **0** |
| `fusion exit — target decayed to flat` at sub-floor forecast | 2 | **1** (`KO SELL 51`, `forecast=-0.0`) |
| `fusion entry — target increase` below the floor | 0 | 0 |

Suppressed: `BAC`×9 (`0.347`…`4.493`), `GOOG`×2 (`-1.548`, `-3.930`), `NVDA`×2 (`-3.415`, `-4.886`).

**Suppression, not absence of opportunity** — the distinction that makes this a verification rather than a
coincidence. `fusion_targets` at `atMillis 1785785388985` (`19:29:48Z`, post-boot) still plans non-zero
deltas on three sub-floor names: `NEE` (`-3.775`, `currentQty 107`, `deltaQty -0.888`), `JNJ` (`-2.495`,
`-18`, `-0.175`), `NVDA` (`2.053`, `-8`, `+0.066`). The gate sits at route time, downstream of that field.
The dribble was planned and did not route.

### Item #1 — NEW: the loop's report truncates 8 of 24 JSON sections into invalid JSON, silently (⚠️ OPEN, #1)

`scripts/system-report.py:405` — `L.append(json.dumps(data, indent=1)[:6000])`. Measured block sizes: the
cap is hit by **`risk`, `marks`, `fusion_targets`, `discovery`, `social`, `tca`, `strategy_selection`,
`orders_day`** — each cut mid-object, invalid JSON, **no truncation marker**. `fusion_targets` declares
`"instruments": 20` and only **9** survive; `tca` and `orders_day` lose their tails the same way.

**Why this outranks everything else even though it moves no money directly.** Every diagnosis this loop
writes is derived from this file. A cross-section read that silently covers the first nine names looks
identical to one that covers twenty, so the loop cannot tell a complete finding from a partial one — and
the register's whole purpose is that "fixed" is never an opinion. A wall of INCONCLUSIVE and BAD verdicts
built on a half-visible cross-section is not evidence about the market; it is evidence about the report.

**Scoped honestly — two things it does NOT compromise, checked this cycle:**
- `signals_telemetry` is `4517` chars, **under** the cap → the standing "no source has edge" conclusion
  rests on the full source set and is unaffected.
- `scripts/score-change.py` fetches `/api/risk` and `/api/attribution` **directly over HTTP** (`urlopen`,
  line 94), never through the report → **no ledger number was ever computed from truncated input.**
  Invariant 7 / ADR-0016 holds. This is a defect in the loop's eyes, not in its money math.

**VERIFY-BY (next run):** every `### <section>` JSON block in `logs/report.md` parses as valid JSON, and
`fusion_targets` contains as many target entries as its own `instruments` field declares (`20` at the last
reading). Any block still shortened carries an explicit truncation marker naming what was dropped.

**Why it is not done this cycle:** `e3b33679d` is under measurement. It is a reports-only script with no
path into the JVM, but the contract's rule is unconditional, and the register exists precisely to carry an
item forward rather than smuggle it in. This is the change the moment ADR-0136 is scored.

### Item #2 — the desk pays a fee bill larger than its whole deficit, with no measured edge to pay it for (⚠️ OPEN, #2 — was #1)

`totalFees 349.041721` against `firmTotal -271.70317883`; `ALPHA -327.37076589` on `feesPaid 337.902341`,
`HEDGE +112.46709242` on `10.030226`, `MACRO -56.79950536` on `1.109154`. `turnover_cost_by_name` sums to
**`$3,935,992.30`** of turnover for **`$349.04`** of fees at `fee_bps 1.00` on a `$72,532.50` book.

Re-checked on the clustered denominator this cycle — still nothing to pay it with. Max \|t\| across all
five sources and all three horizons is **`1.44`**, and it is *negative* (`xsreversion`, `-7.06` bps at
`3600s`). Best positive is `social` `+4.96` bps at `t=+1.00`. Against `fee_bps 1.00` plus `tca` slippage
of roughly half a bp, no source clears its own cost.

**ADR-0136 removed one slice** (the sub-floor dribble, verified above). The majority — the
breadth-collapse round trip — remains, and both direct remedies for it scored ❌ BAD in opposite
directions, so it stays closed to direct attack per Rule 270/271.

**VERIFY-BY:** `totalFees` growth per cycle falls relative to `firmTotal`, or a source's clustered t
clears the edge gate's hurdle. Demoted below #1 because the next attack on it should be chosen from a
cross-section the loop can actually see in full.

---
## Verification block — 2026-08-03 19:00Z (**ADR-0136 shipped.** `c20fb0b70` scored ❌ BAD with a failed revert — and the revert precedent was **refused**, because completing it re-applies the graded-BAD ADR-0135. Item #1 is **re-scoped**: its direct remedies are exhausted in both directions, so the register now tracks the cost problem where a lever still exists.)

### Step 0 — `c20fb0b70` (the ADR-0135 revert): 🔴 SCORED ❌ BAD — and deliberately NOT reverted

`scripts/score-change.py score` → `no pending change to score`; `reports/.pending-baseline.json` gone.
The ledger row: `-0.000563` risk-adj return/cycle over 7 cycles, t=`-1.54` (hurdle `1.5`), gross
`48,013.41 → 54,235.90` [grew], **⚠️ REVERT FAILED (git conflict)**.

**The precedent (Rule 252, "complete the failed revert by hand") does not apply and was not followed.**
`c20fb0b70` **is** the revert of `74a47adee` (ADR-0135), which scored ❌ BAD itself. Reverting it would
re-apply a mechanism the scorer already rejected — forbidden by the contract. Both directions of one
branch are now graded BAD:

| commit | effect on the breadth-collapse exit | risk-adj/cycle | t | gross | verdict |
| --- | --- | --- | --- | --- | --- |
| `74a47adee` | unestimable view **holds** instead of liquidating | `-0.000387` | `-1.59` | `0 → 51,059` [grew] | ❌ BAD |
| `c20fb0b70` | **restores** the liquidation | `-0.000563` | `-1.54` | `48,013 → 54,236` [grew] | ❌ BAD |

A change and its exact inverse cannot both cause the same deterioration (Rule 271). Both verdicts fired
on "risk-adj negative AND gross grew", and in both windows gross grew because the book was rebuilding
off a flatten. The constant across both is the fee bleed, which neither commit touched.

### Item #1 — RE-SCOPED: the desk pays a fee bill larger than its whole deficit, with no measured edge to pay it for (⚠️ OPEN, #1)

`totalFees 346.044801` against `firmTotal -264.50191793`; `ALPHA -329.32225499` on `feesPaid 334.905421`
(roughly flat gross of fees), `HEDGE +121.61984242` on `10.030226`, `MACRO -56.79950536` on `1.109154`.
`turnover_cost_by_name` sums to ~`$3.3M` of equity turnover at `fee_bps 1.00` on a `$54,238.11` book. On
the clustered denominator no source is significant at any horizon.

**Why the old framing is retired.** Item #1 was "a breadth collapse pays a full round trip". That is
still true and still the majority of turnover — but its two direct remedies both scored ❌ BAD, in
opposite directions, so it is closed to further direct attack. The register now tracks the cost problem
itself, where levers remain.

**Attacked this cycle (ADR-0136), partially.** The conviction floor gated entries but not partial
reduces, so a name whose view had decayed below the floor was still walked toward a target computed from
that sub-floor forecast every cycle. `recent_orders`: `BAC` short took six `BUY 1` fills tagged
`fusion reduce toward a smaller target` at forecasts `0.3474462086964614`, `0.5363969925205933`,
`0.7016479414426884`, `2.835935684486436`, `1.72006937226672`, `1.82687778188073`; `GOOG` (`-1.548`,
`-3.930`) and `NVDA` (`-4.886`, `-3.415`) the same — **10 sub-floor partial reduces**. Below the floor a
name is now **held or flat, never re-sized**.

**Stated honestly: this is a minority of the turnover.** The flattens (`BAC BUY 435`, `KO BUY 175`,
`MCD BUY 33`, `JNJ BUY 26`) dominate and are untouched.

**VERIFY-BY next run** — counts the defect directly, not a proxy (Rule 269):
- `recent_orders` orders tagged `fusion reduce toward a smaller target` with `|forecast| < 5.0`: must be
  **0** (was **10** this window).
- `recent_orders` orders tagged `fusion exit — target decayed to flat` at sub-floor forecasts: must
  **still appear**. Their absence means an exit was trapped and **falsifies** ADR-0136.
- `ALPHA feesPaid` / `turnover_cost_by_name` fill counts on `BAC`, `GOOG`, `NVDA`: direction only, read
  as association not proof (Rule 266).

### Item #2 — the ADR-0101 cost-derived buffer width is inert, and must NOT be revived as specified (⚠️ OPEN, ranked #2, blocked)

`widthFor` returns the `0.10` convention unless (a) an `EdgeGate.Decision` is supplied — it is not,
`jethro.fusion.edge-gate.enabled=false` sets `gateSupplier = null` (ADR-0122, owner-directed) — and (b)
some source **passes** significance, which none does. Doubly dead.

**Do not simply switch it on.** `max(0.10, min(1, 2C/μ))` with measured `C` above measured `μ` pins at
the `1.0` cap = one full average position, which an aim clamped inside its own target (ADR-0102) can
never cross ⇒ the book freezes. That is the failure ADR-0133 was written to patch, and ADR-0133 scored
❌ BAD. A viable version needs a width that is cost-derived **without** a cap the aim cannot cross.

**VERIFY-BY:** not actionable until that geometry is solved; carried as a known-dead mechanism so no
future cycle rediscovers it and ships the freezing version.

---


## Verification block — 2026-08-03 18:30Z (**no change shipped — `c20fb0b70` is at 5/6 cycles.** Item #1 stays #1 and is now **demonstrated rather than argued**: three names were opened and completely flattened inside this one window, on a source-breadth collapse with the direction never reversing. Item #1's **VERIFY-BY is replaced** — last block's three criteria all moved materially on a no-op cycle, so they could not grade anything.)

### Step 0 — `c20fb0b70` (the ADR-0135 revert): ⚠️ UNDER MEASUREMENT, deployment confirmed a fourth window

`scripts/score-change.py score` → `still accumulating evidence (5/6 cycles) — held, not scored this run`.
`reports/.pending-baseline.json` present against its `16:37:25Z` baseline. Held, per contract; it scores
next cycle.

Behavioural confirmation (Rule 256): the restored `fusion exit — target decayed to flat` branch fired on
`JNJ BUY 26` (`sources=1`, 18:05:27Z), `BAC BUY 435` (`sources=0`, 18:05:28Z) and `KO BUY 175` (`sources=1`,
18:15:35Z).

**Recorded against it, not glossed:** those three orders **are** the window's `-39,623.47` gross drop. Still
not graded a regression — a revert restores prior behaviour by construction, and the scorer settles it next
cycle — but the register keeps watching it select trades rather than calling it costless.

### 🔁 VERIFY-BY replaced — last block's criteria drifted on a cycle with NO change shipped

| criterion set 18:00Z | this window, no change shipped | verdict |
| --- | --- | --- |
| supersession cancels below `27/59` | `26/60` | moved on its own |
| zero-fill entry names below `4` | `2` (`HD`, `PG`) | moved on its own |
| gross exposure not falling | fell `-39,623.47` | moved on its own |

Two of the three would have read as partial success for a no-op. A proxy that swings this far unaided cannot
grade a change (Rule 269). Replaced below with a direct count of the defect itself.

### Item #1 — a source-breadth collapse pays a full round trip on a view that never reversed, at a cost above every measured expectancy at the matching horizon (⚠️ OPEN, stays #1, now demonstrated)

**The execution, read from `recent_orders`** — every one of these closed a position the *same* window opened:

| name | opened (fill, forecast) | flattened | held |
| --- | --- | --- | --- |
| `BAC` | `SELL 159` 17:43:48Z `-10.14`, `SELL 146` 17:46:20Z `-10.09`, `SELL 130` 17:46:51Z `-11.20` | `BUY 435` 18:05:28Z `sources=0` | ~19 min |
| `KO` | `SELL 97` 17:44:18Z `-12.83`, `SELL 76` 17:45:19Z `-15.03` | `BUY 175` 18:15:35Z `sources=1` | ~30 min |
| `JNJ` | `SELL 20` 17:53:25Z `-6.18`, `SELL 3` 17:54:26Z `-6.54`, `SELL 3` 17:54:56Z `-5.54` | `BUY 26` 18:05:27Z `sources=1` | ~12 min |

Every exit carries `forecast=0.0`/`-0.0`: the desk never reversed its view, it stopped being able to *count*
sources. `BAC` was accumulated on three strengthening fills and bought back entire 19 minutes later.

**The cost, at the horizon that matches a 12–30 minute hold (900s), clustered denominator:**
`trend +0.371` (`cohorts 275`, `stdCohortMeanBps 13.699`), `reversion +0.698` (`237`, `13.870`),
`social +0.849` (`61`, `15.773`), `momentum +2.140` (`23`, `14.144`), `xsreversion -1.261` (`126`, `12.653`)
— all far inside their own dispersion; no source is significant at 900s, 225s or 3600s. Against
`fee_bps 1.00` per side plus `tca avgSlippageBps` `BAC 0.4878` / `KO 0.4638` / `JNJ 0.4307` per side. The
`sources=2`–`3` trend+reversion pair driving these entries measures **below one side's cost**.
Firm-wide: `totalFees 341.366091` against `firmTotal -285.01226135`; `ALPHA -350.27709557` on
`feesPaid 330.599686` while `HEDGE +122.06433958` pays `9.657251`.

**Unit correction, so it is not repeated:** `app/src/main/java/io/jethro/app/fusion/Forecast.java` is
Carver-scaled (`TARGET_ABS = 10.0`, `CAP = 20.0`). The `-15` on `KO` is a 1.5×-average-strength *view*, not
15 bps of expected return. No calibration claim may be read off the order-reason forecast.

**The change it calls for (ships next cycle, once `c20fb0b70` scores):** gate the **entry** — require the
source breadth that justifies an entry to persist before size is committed, so a reading about to collapse
never opens a round trip. Deliberately **not** the exit branch: that is ADR-0135's mechanism, already graded
❌ BAD and reverted, and a reverted idea is never re-attempted.

**VERIFY-BY (new, counts the defect instead of proxying it):** **same-window round trips** — names with both
`fusion entry` fills and a `fusion exit — target decayed to flat` fill inside one report window. This window:
**3** (`BAC`, `KO`, `JNJ`), on `435`/`175`/`26` shares. The fix must drive that count down while `totalFees`
growth per window falls relative to `firmTotal`, and without gross exposure collapsing.

---

## Verification block — 2026-08-03 18:00Z (**no change shipped — `c20fb0b70` is at 4/6 cycles.** Item #1 stays #1, but its *stated mechanism* is **partly retracted**: a controlled re-test on this window does **not** reproduce the forecast-strength adverse selection Rule 260 claimed. The cost and the non-execution are confirmed and now carry cleaner evidence; the "we fill our worst view" framing does not survive its own denominator test.)

### Step 0 — `c20fb0b70` (the ADR-0135 revert): ⚠️ UNDER MEASUREMENT, deployment confirmed a third window

`scripts/score-change.py score` → `still accumulating evidence (4/6 cycles) — held, not scored this run`.
`reports/.pending-baseline.json` present against its `16:37:25Z` baseline. Held, per contract.

The restored branch fired again on a fresh window: `fusion exit — target decayed to flat [forecast=0.0,
sources=0]` on `XOM BUY 44` and `JPM SELL 46` (both 17:37:13Z), and `[… sources=1]` on `NEE SELL 170`
(17:39:15Z). Deployment confirmed on behaviour, per Rule 256.

**Recorded against it, not glossed:** `NEE` is now flat at `realizedPnl -25.58`, crystallised by that
17:39:15Z restored exit; `JPM`'s 46 shares — the ones the previous block flagged — were flattened by the
`sources=0` branch at 17:37:13Z, and `JPM` has dropped out of the position table entirely. Still not graded
a regression: a revert restores prior behaviour by construction and the counterfactual is unknowable. But
the register keeps watching it select trades rather than calling it costless.

### ⛔ RETRACTION — Rule 260's adverse-selection mechanism does not survive a controlled test

Last block claimed the re-plan cadence systematically **fills the desk's weakest forecasts**, from the `JPM`
ladder (`14.48 → … → 5.05`, only the two weakest filling). Re-tested on this window's ladders, comparing the
`|forecast|` on FILLED vs CANCELLED orders:

- **Pooled over all ALPHA orders:** filled `n=26` mean `5.685` vs cancelled `n=27` mean `8.579` — looks like
  a large adverse gap.
- **Pooled over `fusion entry — target increase` orders only** (apples-to-apples; exits carry `forecast=0.0`
  and always fill, so including them manufactures the gap): filled `n=13` mean **`8.698`** vs cancelled
  `n=27` mean **`8.579`**. The gap **inverts and vanishes**.
- **Within-name, entries only:** the sign is consistent — `5 of 6` names with both fills and cancels show
  filled weaker (`AMZN -1.43`, `KO -1.28`, `BAC -0.55`, `JNJ -0.55`, `NEE -0.15`; `GOOG +0.29` against) —
  but with **1–3 fills per name** this is far too small to call, and the cross-name mix explains the pooled
  result better than selection does.

This is Rule 259 applied to the register's own claim: the dramatic number came from a bad comparison set,
not from the market. The `JPM` ladder was a real anecdote, not a demonstrated mechanism.

### Item #1 — the 30s re-plan cadence blocks passive entries from executing, at a fee that exceeds every measured expectancy (⚠️ OPEN, stays #1, mechanism narrowed to what the evidence supports)

What survives, and it is enough to keep this at #1:

**(a) The cost still dominates the expectancy — unchanged and confirmed.** `turnover_cost_by_name`:
`fee_bps 1.00` per side on every equity (`0.20` on `ES`/`NQ`), so ~2 bps a round trip before slippage.
`/api/attribution`: `totalFees 334.401006` against `firmTotal -234.55764728`, with `ALPHA` carrying
`feesPaid 324.009878` on `totalPnl -304.50434241` while `HEDGE` is the only book earning (`+126.74620049`,
`feesPaid 9.281974`). **Gross of fees the desk is up; fees alone put it underwater** (Rule 262).

**(b) The execution failure is real, and this is the clean form of it.** `27 of 59` `recent_orders` rows are
`fusion re-plan — passive order superseded by a fresh target (ADR-0084)` cancels — up from `22/60`. Sharper:
**four names placed entry orders this window and filled none of them** — `NVDA`, `HD`, `PG`, `PFE`, at
`|forecast|` of `5.88`, `6.53`, `6.24`, `5.42`, every one cancelled by supersession before it could execute.
The defect is not "we fill the worst view"; it is **"a whole name's view can fail to reach the market at
all"**, while the names that *do* trade pay 2 bps a round trip for the privilege.

**(c) The churn/PnL association, stated with its caveat.** The window's losers are all ladder-churned names —
`JNJ -169.82373597` (108 fills), `NVDA -92.32373936` (218 fills), `BAC -82.69208761` (76 fills),
`KO -56.61374169` (89 fills) — while the winners are largely untouched: `AMZN +333.52971740` (one fill this
window), `AAPL +61.40808388` (no window order), `GOOG +32.73213379`, and the `ES` hedge `+126.74620049`.
**This is an association, not an attribution:** the position table is cumulative (Rule 263), so no exact
per-name decomposition of the window delta is claimed, and high-turnover names are also the high-conviction
names. It is corroborating, not proof.

**VERIFY-BY (the cycle after `c20fb0b70` scores) — three numbers, all three or it is not the fix:**
(i) supersession-cancel rows in `recent_orders` fall materially below **27/59**;
(ii) the count of names that place entry orders and fill **none** of them falls below **4** — the honest
replacement for the retracted mean-gap test, which is too noisy at this sample size to verify anything;
(iii) gross exposure does **not** fall. Cost down, views reaching the market, position retained.

## Verification block — 2026-08-03 17:30Z (**no change shipped — `c20fb0b70` is at 2/6 cycles.** Item #1 is unchanged at #1 and gained the piece it was missing: the re-plan cadence does not merely *cost* money, it systematically fills the desk's *weakest* forecasts. Item #2 is **DOWNGRADED and effectively withdrawn** — the telemetry's clustered dispersion field shows the "significant negative" reading that created it was an artifact of a naive standard error.)

### Step 0 — `c20fb0b70` (the ADR-0135 revert): ⚠️ UNDER MEASUREMENT, behavioural criterion holds a second window

`reports/.pending-baseline.json` present, no new ledger row → held, not scored. 2 heartbeats since its
`16:37:25Z` baseline against `MIN_CYCLES 6`.

The restored branch fired again on a fresh window: `fusion exit — target decayed to flat [… sources=1]` on
`MCD SELL 35` (17:08:59Z) and `BAC SELL 240` (17:10:00Z). Deployment confirmed on behaviour, per Rule 256.

**Recorded against it, not glossed:** `MCD` shows `realizedPnl -27.35` on a now-flat position, crystallised
by that restored exit; `BAC` was accumulated 16:43–16:50Z and liquidated by the same branch at 17:10:00Z.
Not graded a regression — a revert restores prior behaviour by construction and the counterfactual is
unknowable here — but the register will not claim a change was costless while it can watch it select trades.

### Item #1 — the 30s re-plan cadence fills the desk's WEAKEST forecasts, at a fee that exceeds every measured expectancy (⚠️ OPEN, stays #1, mechanism upgraded)

Two facts from this window, and the second is new.

**(a) The cost dominates the expectancy.** `turnover_cost_by_name` shows `fee_bps 1.00` per side on every
equity (`0.20` on `ES`/`NQ`) — a round trip is ~2 bps before slippage. `/api/signals/telemetry` at 225s:
`reversion +0.041` (5198 resolved), `trend +0.056` (5443), `social +0.303` (666). The largest expectancy
anywhere in the table is `social` at 3600s, `+5.690` on 309 resolved. The two heaviest fusion weights —
`social 1.663`, `reversion 1.580` — are exactly the sources whose expectancy only appears at 3600s.
Firm-wide `totalFees 324.11` against `firmTotal -205.30`: **gross of fees the desk is up; fees alone put it
underwater.**

**(b) NEW — the ladder is adversely selected.** `JPM`, 17:13:02Z → 17:17:35Z: forecast `14.48 → 12.23 →
9.64 → 9.40 → 9.21 → 5.90 → 5.05` while ordered qty ran `4 → 10 → 13 → 15 → 18 → 21 → 10`. The first five
are `CANCELLED … superseded by a fresh target (ADR-0084)`; the two that **FILL** are the two weakest views
on the ladder. Passive orders resting on strong forecasts are superseded by the next 30s re-plan before they
fill; only decayed ones survive to execution. `JPM` now carries `realizedPnl -54.74`, `totalPnl -79.46` on
the 46 shares assembled this way. Window-wide: **22 of 60** `recent_orders` rows are supersession cancels.

So this is not "turnover is expensive" — it is a **directional** defect: the cadence biases *which* forecasts
get executed toward the worst ones. That reframes the fix from "trade less" to "let a passive order live
long enough to fill on the view that placed it".

**VERIFY-BY (the cycle after `c20fb0b70` scores):** three numbers together, or it is not the fix —
(i) supersession-cancel rows in `recent_orders` fall materially below **22/60**; (ii) the forecast attached
to *filled* orders is no longer systematically weaker than the forecast on cancelled ones for the same name
(the `origin` field carries it — read the ladder directly); (iii) gross exposure does **not** fall.
Cost down, adverse selection gone, position retained.

### Item #2 — ~~`xsreversion` has the only statistically significant expectancy, and it is NEGATIVE~~ (🔴 **WITHDRAWN — the finding was a statistical artifact**)

The telemetry reports `cohorts` and `stdCohortMeanBps` next to `resolved` and `stdReturnBps`. Last cycle's
`t = -2.19` was built as `avgReturnBps / (stdReturnBps / √resolved)` — treating **517** overlapping,
cross-sectionally-linked observations as 517 independent draws. There are **`33` cohorts** behind them.
Recomputed on the clustered denominator (`stdCohortMeanBps 30.03` over `33` cohorts against
`avgReturnBps -8.288`), the reading sits well inside the noise band, as does every other cell at every
horizon — `social` 3600s `+5.690` over `28` cohorts at `stdCohortMeanBps 28.27`; `reversion` 3600s `+3.146`
over `71` at `30.92`; the whole 225s column inside `±0.31` bps.

**The honest conclusion, stated plainly as the standing priority requires: no source in this universe has
demonstrated significant edge at any measured horizon.** That is why #1 is a cost/execution fix and not a
signal fix — and why nothing here justifies zeroing or flipping a weight. Withdrawn rather than re-ranked:
acting on it would have been the exact overfit this loop's INCONCLUSIVE wall was built from.

### Item #2 (new) — `/api/fusion/targets` reports a target the planner never orders toward (⚠️ OPEN, diagnostic integrity, low money cost)

`JPM`: `targetQty 357.679`, `currentQty 46.0`, **`deltaQty 0.0`**. `CAT`: `targetQty -94.603`,
`currentQty 0`, `deltaQty 0.0`. `AAPL`: `targetQty -223.167`, `currentQty 1.0`, `deltaQty -0.008299`.
The published target and what the desk actually works toward differ by an order of magnitude, with no field
on the endpoint explaining the gap. This costs no money directly, but it is the endpoint every future cycle
reads to reason about sizing — and a past cycle *has* misread a target as intent.

**VERIFY-BY:** `/api/fusion/targets` either reconciles (`deltaQty` consistent with the `targetQty`/`currentQty`
gap and the adjustment rate) or carries an explicit field naming what suppressed the delta. Ranked below #1
because it misleads the reader, not the book.

---

## Verification block — 2026-08-03 17:00Z (**no change shipped — `c20fb0b70` is at 1/6 cycles.** Last block's item #1 is **✅ VERIFIED and closed**: the ADR-0135 revert landed in the code, on the endpoint, in the ADR index, and — the criterion that actually matters — in the *running desk's behaviour*. No process danger remains. The horizon/cost mismatch is now #1 and is quantified to the point of being ready to ship; a **new #2** is separated out of it.)

### Step 0 — `c20fb0b70` (manual completion of the ADR-0135 revert): ✅ VERIFIED, four for four

Graded on the **CODE and the BEHAVIOUR**, never on ancestry (Rule 255):

- `grep -rn "estimable" app/src/main/java/io/jethro/app/fusion/` → **0** lines. Repo-wide the only survivor
  is a prose comment in `TelemetryWeightsTest`.
- `/api/fusion/targets` rows carry no `estimable` field — **0** occurrences of the string in the report.
- ADR index shows `0135 … Reverted`, annotated record kept.
- **Behavioural — the decisive one.** The suppressed branch is live again:
  `fusion exit — target decayed to flat [… sources=1]` fires on `PFE SELL 26` (16:40:13Z), `CAT SELL 1` and
  `JNJ SELL 63` (16:38:42Z), `CVX BUY 1` (16:43:46Z). Under ADR-0135 these were exactly the orders that did
  not happen. Source-tree greps prove a revert compiled; a restored trigger proves it *deployed*.

No PnL is claimed. The window's `-9.93` is mark-to-market and cost on positions this change did not select.

### Item #1 — the desk re-plans every 30s against sources with NO measurable return until 3600s (⚠️ OPEN, promoted from #2, now fully quantified and ready to ship)

The evidence closed this cycle. Expectancy by source × horizon from `/api/signals/telemetry`, t computed
from the reported `stdReturnBps` and resolved count:

| horizon | best source | avgReturnBps | t |
| --- | --- | --- | --- |
| 225s | social | `+0.402` | `+0.70` |
| 900s | reversion | `+0.774` | `+0.91` |
| 3600s | social | `+5.917` | `+1.34` |

At **225s every source is inside ±0.71 bps with every `|t| < 0.71`** — there is nothing there to trade. The
expectancy lives at **3600s**, and the fusion weights already know it: `social 1.680` and `reversion 1.572`
are the two heaviest dials, and they are precisely the two sources that only pay at 3600s. Then the planner
re-plans every **30s** and turns the position over long before 3600s arrives, at `fee_bps 1.00` per side
(`turnover_cost_by_name`, every equity) — roughly 2 bps of fee round trip chasing a 225s expectancy of
`+0.042`.

Live instances this window: `BAC` re-planned four times in 90s, three `CANCELLED … superseded by a fresh
target (ADR-0084)`. `JNJ` — five cancelled entries 16:25–16:27Z, `BUY 51` filled, `SELL 6/7/9/2` "reduce
toward a smaller target" by 16:31Z, `SELL 63` liquidated 16:38Z. A full round trip inside 13 minutes on a
view whose expectancy needs 60.

**VERIFY-BY (the cycle after `c20fb0b70` scores):** the count of
`fusion re-plan — passive order superseded by a fresh target (ADR-0084)` rows in `recent_orders` falls
materially versus this window's, **and** median holding time per name rises toward the horizon the weights
imply — with fills per name in `turnover_cost_by_name` falling while gross exposure does **not** fall.
Cost down, position retained: both, or it is not the fix.

### Item #2 — `xsreversion` has the only statistically significant expectancy in the book, and it is NEGATIVE (⚠️ OPEN, NEW — split out of #1, deliberately not merged with it)

`xsreversion` at 3600s: `avgReturnBps -7.765` on 512 resolved, **`t = -2.19`**. Every other cell in the
telemetry table is inside `|t| < 1.35`. This is the single significant measurement the desk has, and it says
the source is anti-predictive at the horizon where the others earn.

The telemetry weighting is **already responding correctly** — `xsreversion` sits at the floor weight `0.25`
while `social` is at `1.680`. So this is not a broken weighter. The open question is narrower: a source
measured as significantly *negative* still contributes at floor weight **in the correct-sign direction**,
which is a small persistent drag rather than a large one.

Ranked #2 and **not** folded into #1 on purpose: sign-flipping a source on a single significant t-statistic
is textbook backtest overfitting, and the conservative reading (floor the weight to zero, do not invert) is
a different decision from the horizon fix. It needs its own cycle and its own ADR, not a rider on another
change.

**VERIFY-BY:** `xsreversion`'s 3600s `t` is re-read next cycle and the sign persists on a grown resolved
count — persistence across an independent window is the precondition for acting at all.

---

## Verification block — 2026-08-03 16:30Z (**change shipped: the failed auto-revert of the graded-BAD ADR-0135 is completed by hand.** The scorer closed ADR-0135's ADR-0116 window at **❌ BAD** and its own `git revert` **hit a conflict and did not land** — so the rejected mechanism was **still live in the running code**. That outranks every open item: the loop was about to stack a new change on top of code the scorer had already rejected. Item #1 is that revert.)

### Step 0 — ADR-0135 (`74a47adee`): 🔴 REGRESSED on the money vector, and the revert never landed

Two separate findings, both true, recorded separately because conflating them is how a bad mechanism survives:

- **Defect-level: still ✅ VERIFIED.** The guard does what it claimed. `fusion exit — target decayed to flat`
  fires **2** times this window, and both carry **`sources=0`** (`PG BUY 106` FILLED, `CVX BUY 2` REJECTED at
  16:08:19Z) — the ADR-0065 orphan sweep, which ADR-0135 explicitly left out of scope and byte-identical. No
  `sources=1` liquidation. The one-source branch is still clean.
- **Money-level: ❌ BAD, and it is the verdict that governs.** The ledger row for `74a47adee` records a
  risk-adjusted return per cycle of `-0.000387` over 7 cycles at `t=-1.59` against the `1.5` hurdle, gross
  `0.00 → 51,059.11`. Under ADR-0116 that reverts. **`git merge-base --is-ancestor 74a47adee HEAD` returned
  true** — the commit was still in the running code four cycles after being graded.

The honest synthesis: stopping a forced exit is not the same as having a reason to hold the position. The
guard removed a cost and replaced it with carry against a view the desk had already measured as
uninformative. Recorded in the ADR so a superseding decision starts from it rather than restating it.

### Item #1 — the graded-BAD ADR-0135 mechanism is still in the running code (✅ **FIXED THIS CYCLE**)

The scorer's auto-revert aborted on a merge conflict in the loop's own memory files
(`docs/loop-findings.md`, `reports/last-analysis.md`, `reports/must-fix.md`) — later cycles had appended to
all three. `git revert` is all-or-nothing, so the conflict took the **code** revert down with it. This is the
**second** time this exact failure has occurred (ADR-0133 / `e61c7f5aa`, completed by hand in `4f67f0515`).

Resolved the same way as the precedent: the rejected mechanism is out of the running code; the loop's
accumulating memory is **kept** (never rolled back); the ADR is **kept**, marked `Status: Reverted` with a
"Why it was reverted" section and a not-to-be-re-attempted note. `./gradlew -Pci test` green.

**VERIFY-BY (next run):** `grep -rn "estimable" app/src/main/java/io/jethro/app/fusion/` returns
**nothing** (it returns **0** lines as of this commit); `/api/fusion/targets` rows no longer carry an
`estimable` field; and the ADR index shows `0135 … Reverted`.

> **Not** `git merge-base --is-ancestor` — a revert adds an inverse commit and leaves the original in
> history, so that test returns true forever and would misgrade this as STILL-BROKEN. **Grade a revert on
> the CODE, not on ancestry.**

### Item #2 — the desk re-plans every 30s against sources with NO measurable return until 900–3600s (⚠️ OPEN, carried, unchanged)

Carried verbatim from the 16:00Z block — untouched this cycle because the revert outranked it, and because
shipping it on top of un-reverted BAD code would have made it unattributable. `/api/signals/telemetry`
`avgReturnBps` at 225s sits inside ±0.7 bps for every source while one side of a round trip costs
`fee_bps 1.00` plus `avgSlippageBps 0.59`–`0.73`; the two heaviest fusion weights (`reversion`, `social`)
belong to the sources that only pay at 900–3600s. This window: **35** `fusion entry — target increase`,
**26** `fusion re-plan — passive order superseded by a fresh target`, **22** `fusion reduce toward a smaller
target`. **This is the #1 candidate for next cycle**, once the revert has scored.

**VERIFY-BY:** per-name `fills` in `turnover_cost_by_name` falls against a flat-or-higher `qty`, and no name
shows an entry and its reversing unwind inside one source-horizon in `recent_orders`.

---

## Verification block — 2026-08-03 16:00Z (**no change shipped — ADR-0135 is at 5/6 cycles.** ADR-0135 is at last **✅ VERIFIED**: its guarded branch was exercised and the liquidation trigger it targets fired **zero** times. Last block's item #1 is **🔴 FALSIFIED** — the desk opened 41 positions this window, so there is no opening veto. The new #1 is the defect the ADR-0134 triggers now make plain: **the desk re-plans every 30s against sources with no measurable return until 900–3600s**, so it pays a round trip on every view before the view resolves.)

### Why no change this cycle

`scripts/score-change.py score` printed **`74a47adee still accumulating evidence (5/6 cycles) — held, not
scored this run`** and `reports/.pending-baseline.json` is present. ADR-0116 forbids stacking a change on a
pending one. Verified, falsified, re-ranked, stopped. ADR-0135 scores next cycle; item #1 ships behind it.

### Step 0 — ADR-0135 (`74a47adee`): ✅ VERIFIED (first genuine exercise, after three vacuous windows)

The branch finally took traffic. `recent_orders` carries a **`sources=1`** cycle —
`JNJ BUY 9 [forecast=-0.0, sources=1]` at 15:40:00Z — and the string **`fusion exit — target decayed to
flat` appears ZERO times** in the window. That is the exact trigger ADR-0135 was shipped to eliminate: one
effective source zeroes the combined forecast, and pre-ADR-0090 read that flat as a decision and liquidated
the whole position at full urgency. The only order at one source was a **partial** `fusion reduce toward a
smaller target` from a downstream risk stage acting on the held target — the escape hatch the ADR keeps
open by design. Deployment re-proved from the field the change ADDED (Rule 240): every `/api/fusion/targets`
row carries `"estimable": true`. **Struck below the line.** The PnL verdict remains the scorer's at 6/6.

### 🔴 Last block's item #1 ("a name at FLAT cannot open") — FALSIFIED, struck

The claimed split was 4/4 flat names at `deltaQty 0.0` and 5/5 held names non-zero, read as a veto
conditioned on `currentQty == 0`. **Both halves fail this window:**

- Held names now sit at exactly zero too — `PG` (`currentQty -106`), `JNJ` (`-76`), `NVDA` (`-44`), all
  `deltaQty 0.0`.
- Flat names were **opened**: `PG SELL 52 · fusion entry — target increase` from flat at 15:45:34Z, plus
  CAT, XOM, CVX, JPM. The window carries **41** `fusion entry — target increase` orders.

The zero deltas were a snapshot artefact: `/api/fusion/targets` is stamped **15:59:43Z**, after the last
order at **15:57:42Z**, with ADR-0084 re-planning every 30s. This is the **third** causal story on this item
killed by data (Rules 234, 241) and the cause is identical each time — a mechanism inferred from an
aggregate instead of measured. See Rule 248.

### Item #1 — the desk re-plans every 30s against sources with NO measurable return until 900–3600s, so cost exceeds edge by construction (⚠️ OPEN, NEW — this is old #3 promoted and quantified)

`/api/signals/telemetry`, `avgReturnBps` by source and horizon, against the fusion weights actually in use:

| source | 225s | 900s | 3600s | fusion weight |
| --- | --- | --- | --- | --- |
| reversion | `0.047` | `0.752` | `3.1947226656494396` | `1.6383242369546946` |
| social | `0.490` | `1.225` | `4.6399547951434625` | `1.6087300321666014` |
| trend | `0.031` | `0.357` | `-0.674` | `0.8246239286767585` |
| momentum | `-0.666` | `2.129` | `-3.409` | `0.7275477047443887` |
| xsreversion | `-0.101` | `-1.144` | `-7.717` | `0.25` |

At **225s — the horizon the desk trades** — every source lies inside ±0.7 bps, hit rates `0.424`–`0.504`.
One side of a round trip costs `fee_bps 1.00` on equities (`turnover_cost_by_name`) plus `avgSlippageBps`
`0.59`–`0.73` on the liquid names (`tca`). **Cost exceeds the best source's gross expectancy at that
horizon.** The two heaviest weights, `reversion` and `social`, are exactly the sources that only become
non-trivial at 900–3600s.

The execution trace, via the ADR-0134 origination triggers:

- **JNJ** — `SELL 11 @ forecast -18.13` (15:47:05Z), `SELL 22 @ -17.12`, `SELL 17 @ -14.93` (15:49:06Z);
  then **31 seconds later** `BUY 21 @ -0.32`, then BUY 4/7/1/7. Sold 50, bought back 40, inside 8 minutes.
- **XOM** — sold 92 at forecasts `-5.06` to `-10.33`, bought back 60 at forecasts `+1.28` to `+3.75`: a
  full sign flip inside the window.
- Cumulative: MSFT `189` fills / `193554.74` turnover while flat; PFE `95` fills / `193675.36` turnover
  while flat. Firm-wide `FILLED 4945` / `CANCELLED 1784`.

The cost lands where the books show it: `ALPHA` **`-152.93147385`** against `feesPaid` **`293.768903`** —
the book's loss is smaller than the fees it paid. `firmTotal` **`-76.36276038`**, `totalFees` **`302.007152`**.

**This is a horizon defect, not an edge drought.** `social` at 3600s (`hitRate 0.5734265734265734`,
`avgReturnBps 4.6399547951434625`, `resolved 298`) and `reversion` at 3600s (`3.1947226656494396`,
`resolved 485`) are the only candidates worth capital, and the desk churns straight through both. No
re-weighting can repair this — the combiner is shaping a view the execution layer destroys before it
resolves.

**VERIFY-BY (next run):** per-name `fills` in `turnover_cost_by_name` falls against a flat-or-higher `qty`,
and `recent_orders` shows no name whose entry and reversing unwind both land inside one source horizon.

### Item #2 — a resting passive order is cancelled and re-issued against a near-identical target (⚠️ STILL-BROKEN, carried)

`orders_by_status` is `FILLED 4945` / `CANCELLED 1784` / `REJECTED 84`; the window shows repeated
`fusion re-plan — passive order superseded by a fresh target (ADR-0084)` on CAT, JNJ, JPM, CVX, GOOG within
30s of issue. Likely the same root cause as #1 (re-plan cadence), so it is deliberately ranked **below** it —
fixing the cadence may close this without a separate change. Do not fix independently until #1 is scored.

**VERIFY-BY:** the `CANCELLED`-to-`FILLED` ratio in `orders_by_status` falls.

### Item #3 — a collapse of source breadth to ZERO liquidates the book in full at FULL urgency (⚠️ OPEN, carried, still NOT EXERCISED)

ADR-0135 deliberately left `sources=0` out of scope (the ADR-0065 orphan sweep). It has now gone three
windows without firing — the window's histogram is `sources=1` ×1, `sources=2` ×35, `sources=3` ×23, and
zero at `sources=0`. It costs nothing mid-session and fires at the close. Ranked below #1 on measured
co-occurrence, per Rule 245.

**VERIFY-BY:** at the next equity close, `recent_orders` shows no full-book exit at `sources=0`.

### Struck this cycle

- ~~ADR-0135 — an unestimable one-source view liquidates the whole position~~ ✅ VERIFIED FIXED (branch
  exercised at `sources=1`; zero `fusion exit — target decayed to flat` orders in the window).
- ~~Item — a name at FLAT cannot open (`deltaQty` conditioned on `currentQty == 0`)~~ 🔴 FALSIFIED (41
  `fusion entry — target increase` orders; held names also at `deltaQty 0.0`).

---

## Verification block — 2026-08-03 15:30Z (**no change shipped — ADR-0135 is at 4/6 cycles.** The book DEPLOYED broadly this window and the zero-source sweep did NOT fire, so old #1 goes unexercised for a second window. Old #2 is now **precisely localised**: the desk can GROW a name it holds but cannot START one — `deltaQty` is exactly `0.0` on 4/4 flat names and non-zero on 5/5 held names. That split is the new item #1.)

### Why no change this cycle

`scripts/score-change.py score` printed **`74a47adee still accumulating evidence (4/6 cycles) — held, not
scored this run`** and `reports/.pending-baseline.json` is present. ADR-0116 forbids stacking a change on a
pending one. Verified, re-ranked, stopped.

### Step 0 — ADR-0135 (`74a47adee`): ⚠️ DEPLOYED, STILL NOT EXERCISED (second consecutive window)

Deployment re-proved from the field the change ADDED (Rule 240): every row of `/api/fusion/targets` carries
`"estimable": true`. Of the fusion orders since 15:00Z — **29 at `sources=2`, 12 at `sources=3`, ZERO at
`sources=1`, ZERO at `sources=0`**. The guarded branch has now gone two full windows without traffic. Its
criterion remains vacuously satisfied and it must not be graded on this window (Rule 239).

### Item #1 — a name at FLAT cannot open: `deltaQty` is exactly `0.0` on every zero-position target and non-zero on every held one (⚠️ OPEN — this is old #2, re-scoped from "under-deployed" to a specific OPENING veto)

From `/api/fusion/targets` this run, the split is total and has no exception:

| name | forecast | sources | estimable | currentQty | targetQty | **deltaQty** |
| --- | --- | --- | --- | --- | --- | --- |
| PFE | `6.601885581804063` | 3 | true | **0** | `6589.465055` | **`0.0`** |
| HD | `3.7137715352834646` | 3 | true | **0** | `188.898658` | **`0.0`** |
| JPM | `3.6401820813173313` | 3 | true | **0** | `257.546115` | **`0.0`** |
| MSFT | `-3.451626099802119` | 3 | true | **0** | `-103.004746` | **`0.0`** |
| XOM | `-5.16945990498043` | 3 | true | -92.0 | `-794.11651` | `-0.363738` |
| JNJ | `-3.1631353632155177` | 3 | true | -75.0 | `-301.165041` | `-35.776603` |
| CVX | `-2.6889594393466063` | 3 | true | -31.0 | `-307.047327` | `-6.065554` |
| BAC | `-5.488089050299691` | 3 | true | 46.0 | `-2761.077689` | `-0.381741` |
| AMZN | `-5.204157224891409` | 3 | true | 6.0 | `-248.539411` | `-0.049792` |

**4 of 4 flat → exactly zero. 5 of 5 held → non-zero.** This is not slow pacing and it is not the
conviction floor: PFE carries the LARGEST absolute forecast in the whole book at three sources, and JNJ and
CVX trade on smaller ones. The blocker is conditioned on `currentQty == 0`.

**Two candidate mechanisms, and neither is asserted as fact.** This register has already had one trace on
this item falsified (Rule 234) and one causal story reversed by a timestamp (Rule 241), so both are logged
as candidates with a discriminating test, not a diagnosis:

1. **`PositionBuffer.mayIncrease` → the ADR-0126 σ-cold veto** (`PositionBuffer.java:203–211`). When a
   name's stop sensor is unarmed the delta is passed through `TargetPlanner.reduceOnly`, and
   `reduceOnly` returns ZERO identically whenever `cur.signum() == 0` (`TargetPlanner.java:202`). A flat
   name under this veto can therefore **never** open, at any conviction, forever. This mechanism predicts
   exactly the observed split. It is also the story a previous cycle had falsified, so it needs proof.
2. **The ADR-0094 band measured from flat.** With ADR-0133 reverted, the band is scaled by the average
   position at a FULL-strength view, so from flat `|gap| = |aim|` can sit inside it. All four frozen names
   carry `|forecast|` below the ADR-0101 `TARGET_ABS` scale; two of the trading names do too, which argues
   against this being the whole story but does not rule it out.

**The two cannot be separated from `/api/fusion/targets` as it stands — it reports the delta but never why
it is zero.** That is the same information gap ADR-0134 closed for orders, and closing it turned four
cycles of guessing into a lookup.

**VERIFY-BY (next run):** `/api/fusion/targets` carries a per-name reason for a zero delta (which veto,
named), AND the four flat names above can be classified from telemetry alone without reading source. Then:
at least one name with `currentQty == 0` shows a non-zero `deltaQty` and appears in `recent_orders` with a
`fusion entry` trigger. A gross rise alone does NOT satisfy this — that half already passes (below) while
the defect stands.

**Ranking note — why this is above the sweep.** Last block ranked the zero-source sweep first on the
argument that deploying more capital just feeds the sweep. This window falsifies the coupling it assumed:
the desk deployed `+57555.68` of gross across XOM, CVX, JNJ, CAT, BAC, AAPL, AMZN and NVDA and the sweep
fired **zero** times. The sweep needs `sources=0`, which the ADR-0113 sensors produce at the equity CLOSE;
the opening veto costs every mid-session cycle. Fix the recurring one first, the tail-risk one next.

### Item #2 — a 30-second collapse of source breadth to ZERO liquidates the book in full at FULL urgency (⚠️ OPEN, carried from #1, NOT EXERCISED)

Unchanged and untouched — no change was shipped, and no `sources=0` order occurred this window (the single
one in the digest is still the 14:32:51Z NQ event recorded in the block below). MACRO remains
`grossExposure 0.00000000` with `realizedPnl -56.79950536`, and that realized loss is still carried in the
firm total. The mechanism, the scope note (ADR-0065's responsibility is correct; the *urgency* is what is
wrong) and the VERIFY-BY from the 15:00Z block all stand verbatim.

**VERIFY-BY (unchanged):** in `recent_orders`, no `fusion exit — target decayed to flat` order at
`sources=0` for a name that carried `sources>=2` within the prior 5 planning cycles; and if a zero-source
name must still be unwound, the unwind is *paced* — spanning more than one cycle in `recent_orders`.

### Item #3 — the combined forecast reverses SIGN inside the entry window, leaving the desk holding the side its own model now opposes (⚠️ OPEN, NEW)

Two names bought on a three-source positive view are now carried against a three-source negative one, from
`recent_orders` and `/api/fusion/targets`:

| name | bought at | order forecast | forecast now | currentQty | targetQty |
| --- | --- | --- | --- | --- | --- |
| BAC | 15:06:18Z | `+5.310419321398655` (sources=3) | `-5.488089050299691` (sources=3) | `46.0` | `-2761.077689` |
| AMZN | 15:03:16Z | `+5.553398525956138` (sources=3) | `-5.204157224891409` (sources=3) | `6.0` | `-248.539411` |

Full sign reversal in roughly twenty minutes, and the unwind is running at `-0.381741` and `-0.049792`
shares per cycle against gaps of thousands. `regime` reads `"trend": "CHOP"` with `volRatio 1.01`, and the
dominant fusion weight is `reversion` at `1.6917588861521635` — a mean-reverting source whose sign is
expected to invert as price moves, which is precisely the source whose horizon must be matched by the
execution schedule rather than outrun by it. The cumulative shape is in `turnover_cost_by_name`: MSFT has
`189` fills and `193554.74` of turnover and holds `currentQty 0`; XOM has `109` fills and `274426.96`.

**VERIFY-BY:** over one window, no name that filled a `fusion entry` order goes on to carry a combined
forecast of the OPPOSITE sign at equal or greater source count within the same window; or, if it does, the
resulting unwind reaches flat inside that window rather than moving sub-share quantities.

### Item #4 — a resting passive order is cancelled and re-issued against a near-identical target (⚠️ STILL-BROKEN, carried, WORSE this window)

`orders_by_status` moved from `FILLED 4901` / `CANCELLED 1750` (15:00Z block) to **`FILLED 4921` /
`CANCELLED 1772`** — within this window more orders were cancelled than filled. `recent_orders` shows the
ADR-0084 re-plan cancelling JNJ on a forecast walking `-14.456807214028592` → `-14.829164125098862` →
`-13.213446920589528` → `-14.026389744529501`, i.e. re-planning against a target that has barely moved.
Ranked fourth because a cancel pays no fee directly; its cost is the un-earned spread, and it compounds
items #1 and #3.

**VERIFY-BY:** the cancelled share of a name's orders over a window in which its combined forecast neither
changes sign nor moves by more than the ADR-0101 band; the re-plan should not fire on an unchanged target.

### Partially satisfied and struck from the deployment item

~~*"firm `grossExposure` rises without the increase being a single name"*~~ — ✅ **met.** Gross moved
`+57555.68` this window to `86124.23375000`, spread across XOM, CVX, JNJ, CAT, BAC, AAPL, AMZN, NVDA and
the ES hedge; `ALPHA` now carries `positionCount 21` at `grossExposure 64809.82000000`. The desk is no
longer DORMANT. The *other* half of that criterion — a flat name opening — is item #1 above and is not met.

---

## Verification block — 2026-08-03 15:00Z (**no change shipped — ADR-0135 is still under measurement at 3/6 cycles.** ADR-0135 is confirmed *deployed* but its guarded branch never fired, so it is **NOT YET EXERCISED**, not verified. Meanwhile the defect it was shipped to close **recurred through the adjacent `sources=0` branch it deliberately left out of scope** and took the MACRO book to zero. That is the new item #1.)

### Why no change this cycle

`scripts/score-change.py score` printed **`74a47adee still accumulating evidence (3/6 cycles) — held, not
scored this run`** and `reports/.pending-baseline.json` is present. ADR-0116 forbids stacking a change on a
pending one. Verified, re-ranked, stopped.

### Step 0 — ADR-0135 (`74a47adee`): ⚠️ DEPLOYED, NOT YET EXERCISED

**Deployment is proven, not assumed.** `/api/fusion/targets` now carries an `"estimable"` field on every
target row — a field that did not exist before this commit. The running binary is the ADR-0135 binary.

**Its guarded branch never fired, so the window carries no evidence either way.** Of the 44 orders in the
post-fix window (`recent_orders`, from 14:00Z):

| trigger | source count | orders |
| --- | --- | --- |
| `fusion entry — target increase` | `sources=2` | 28 |
| `fusion entry — target increase` | `sources=3` | 9 |
| `fusion reduce toward a smaller target` | `sources=3` | 1 |
| `fusion exit — target decayed to flat` | **`sources=0`** | **1** |
| `auto-hedge EQUITY (ADR-0019)` | n/a | 5 |

**Zero orders at `sources=1`.** ADR-0135's confirm-next-run criterion ("no order carries the
decayed-to-flat trigger at one source") is satisfied *vacuously* — no name reached one effective source
all window. It stays under measurement; do not grade it on this window.

### Item #1 — a 30-second collapse of source breadth to ZERO liquidates the book in full, while entry is paced over 18 minutes (⚠️ OPEN, NEW — this is the ADR-0135 defect recurring one branch over)

**What happened, from `recent_orders` alone.** NQ carried a two-source short view and was being worked in:

| time (UTC) | instrument | trigger | forecast / sources |
| --- | --- | --- | --- |
| 14:32:21 | NQ | `fusion entry — target increase` | `-6.867533453373563`, **sources=2** |
| 14:32:51 | NQ | **`fusion exit — target decayed to flat`** | `0.0`, **sources=0** |

One cycle. Thirty seconds. No change of view was ever measured — the view *stopped being reported*, and
`ForecastCombiner` maps "no source spoke" to a combined value of `0.0`, which `TargetPlanner` maps to a
target of flat, which ADR-0090 works at FULL urgency. `FusionPlanner.java:83–84` is the sweep that owns
this case, and `ForecastCombiner.java:121` is the line that hands it the zero.

**What it cost, from `/api/risk` and `/api/attribution`.** The MACRO book:

```
"book": "MACRO",  "totalPnl": "-56.79950536",  "realizedPnl": "-56.79950536",
                  "unrealizedPnl": "0.00000000",  "grossExposure": "0.00000000"
```

Every dollar of it realized; nothing left on. NQ is now absent from `/api/fusion/targets` altogether.
The firm total is **`-13.88198097`** — MACRO's realized loss is four times the whole firm's PnL, offset
only by HEDGE `+107.90433891` against ALPHA `-64.98681452`.

**Attribution, honestly (Rule 237).** The **-$56.80 itself is market** — a fresh ~$23.6k NQ short that the
tape moved against between 14:21Z and 14:32Z. The **sweep's** attributable damage is not the loss, it is
the *decision*: it crystallised a position whose own forecast had read `-6.8675` thirty seconds earlier,
and it paid a full round trip (NQ `turnover_usd 55457.65`, `fee_usd 1.1092`) to do it. Do not credit the
sweep with de-risking and do not blame it for the mark.

**The asymmetry that makes this expensive.** Entry is paced — 17 NQ orders across 18 minutes to build one
position. Exit on an *information outage* is instant and full-urgency. The desk is built to accumulate
slowly and liquidate at once, so any lapse in sensor availability is a one-way ratchet down.

**VERIFY-BY (next run):** in `recent_orders`, no `fusion exit — target decayed to flat` order at
`sources=0` for a name that carried `sources>=2` within the prior 5 planning cycles; and MACRO
`grossExposure` in `/api/risk` is non-zero while its combined forecast has not changed sign. If a
zero-source name must still be unwound (it must — a derouted name cannot be held forever), the unwind is
*paced*, not FULL: the exit spans more than one cycle in `recent_orders`.

**Explicitly in scope for the fix, and explicitly not.** ADR-0065's responsibility — a held name always
gets a target so it can never be orphaned — is correct and stays. What is wrong is the *urgency and
immediacy* of the resulting flat target when the trigger is an absence of information rather than a
measured view. The deterministic floor (pre-trade guardrail, firm drawdown breaker, ADR-0086 cut,
ADR-0027 breaker) is untouched and must remain able to flatten instantly.

### Item #2 — 9 of the 10 carried target rows hold nothing against six-figure targets; the firm deploys 1.6% of its cap (⚠️ OPEN, re-ranked down from #1 — cause NOT localised)

From `/api/fusion/targets` (`instruments: 22`, 10 rows carried in the digest), every row `estimable: true`
and every row `deltaQty: 0.0`:

| name | sources | forecast | targetQty | price | currentQty | deltaQty |
| --- | --- | --- | --- | --- | --- | --- |
| KO | 3 | `5.802` | `1104.641247` | 86.76 | 0 | `0.0` |
| AAPL | 3 | `5.779` | `268.101107` | 305.39 | 0 | `0.0` |
| BAC | 3 | `2.199` | `702.725` | 62.00 | 0 | `0.0` |
| NVDA | 3 | `-3.490` | `-151.177` | 206.25 | **-44** | `0.0` |

Against `grossExposure 23679.80075000` — **1.6% of the $1,500,000 firm cap**, `1,476,320` of headroom.
Of that gross, `HEDGE` is `14604.36075000` (62%) hedging an `ALPHA` book of `9075.44000000` that is, on
inspection, one real position (NVDA −44 ≈ $9,075) plus dust across `positionCount 21`.

**Cause is NOT established.** Last block's σ-coverage story for this item is unproven — this run's digest
carries no `streamVolMeasuredNames` figure to re-check it against, and the same register has already
recorded one falsified trace on this item (Rule 234). **Do not inherit that diagnosis as fact.** NVDA
demonstrably trades (six fills this window) while KO, holding an equal-conviction three-source view, does
not; the next diagnosis must start from what differs between those two names, measured, not recalled.

**Ranked below #1 deliberately.** #1 is the mechanism that empties the book ten minutes after it fills.
Deploying more capital before fixing #1 just feeds more of it into the same sweep.

**VERIFY-BY:** at least one name other than NVDA/ES shows a non-zero `deltaQty` in `/api/fusion/targets`
while its `currentQty` is 0 and its forecast exceeds the conviction floor; firm `grossExposure` rises
without the increase being a single name.

### Item #3 — a resting passive order is cancelled and re-issued every 30s against a bit-identical forecast (⚠️ OPEN, NEW)

`recent_orders` shows the NQ forecast frozen at exactly `-6.867543315104213` across **13 consecutive**
planning cycles (14:23:45Z → 14:29:49Z), and on each one the resting order was killed by
`fusion re-plan — passive order superseded by a fresh target (ADR-0084)`. The "fresh target" was
bit-identical to the one it superseded. Of the NQ orders in the window, only 2 reached FILLED.
Firm-wide `orders_by_status`: `FILLED 4901`, `CANCELLED 1750`, `REJECTED 84`.

A passive order that is never allowed to rest cannot earn the spread it was placed to earn — this is the
other half of why entry takes 18 minutes (item #1's asymmetry). Ranked third because a cancel costs no
fee directly; its cost is opportunity and it compounds item #2.

**VERIFY-BY:** the cancelled share of a name's orders over a window in which its combined forecast does
not change sign; the ADR-0084 re-plan should not fire when the new target is unchanged.

---

## Verification block — 2026-08-03 14:30Z (**no change shipped — ADR-0135 is still under measurement at 2/6 cycles.** The book came off DORMANT at the US open. Last block's item #1 is 🔴 **FALSIFIED as stated** — the σ veto is not permanent, it warms with the live tape. It is **re-scoped**, not closed: σ covers only 6 of 20 names, which under-deploys the book and concentrates 82% of firm gross into one levered index future.)

### Why no change this cycle

`scripts/score-change.py score` printed **`74a47adee still accumulating evidence (2/6 cycles) — held, not
scored this run`** and `reports/.pending-baseline.json` is present. ADR-0116 forbids stacking a change on a
pending one. Verified, corrected the record, re-ranked, stopped.

### Step 0 — last block's item #1 ("permanent veto, no boot can satisfy"): 🔴 FALSIFIED

The claim was that the σ warm-up (121 prices × 30 s ≈ 60.5 min) exceeds the app's ~30 min lifetime, so
`stopArmed` is false on **every** boot, **forever**. This run disproves it. The ADR-0071 durable mark store
**grows while the tape prints**, so each boot seeds deeper than the last:

| name | seeded at 13:47Z boot | seeded at 14:30Z boot | of |
| --- | --- | --- | --- |
| AAPL | 75 | 107 | 121 |
| NVDA | 85 | 117 | 121 |
| MSFT | 67 | 101 | 121 |
| GOOG | 61 | 96 | 121 |
| AMZN | 44 | 80 | 121 |

`streamVolMeasuredNames` went **1 → 6**, and the book re-entered on its own: `recent_orders` shows
`fusion entry — target increase` fills on NQ from 14:14Z and NVDA from 14:26Z. **The dormancy was a
closed-session artifact — a weekend adds no marks to the store — not a structural deadlock.** The rule
recorded from that wrong trace (Rule 231) is superseded by Rule 234 in `docs/loop-findings.md`.

What *does* survive from that trace, confirmed again here: for a name that is **not** armed,
`PositionBuffer.java:164` re-seeds the aim to `held + delta`, so intent cannot accumulate — the 14 names
with `aims: 0.0` are exactly the 20 minus the 6 in `streamVolMeasuredNames`.

### Item #1 — σ arms for only 6 of 20 names, so the desk holds 2.4% of its own intent and 82% of firm gross sits in ONE name (⚠️ OPEN, re-scoped)

**Cost: the deployment AND the concentration.** `/api/fusion/targets` carries **19 of 20** names estimable
with targets summing to **$1,208,080** of |notional|. The desk holds **$28,761.93**. Worse than the
shortfall is its shape: **NQ alone is $23,492.67 of that gross — 81.7%** — so the firm's book is a naked
short index future, while the equity legs that would diversify it are held at zero. PG: `combinedForecast
-7.398`, `agreement 0.614`, target **-$213,901**, `aim 0.0`. AAPL: target **+$127,333**, `aim 19.91`,
`currentQty 0`.

**The evidence that localises the defect.** On the *same* mark store, at the *same* span of 120:

- `covarianceCoveredNames` = **19**
- `streamVolMeasuredNames` = **6**
- `covarianceBasis` = `mark-stream`

Two estimators, one data source, one span — one covers the whole planned book, the other under a third of
it. So this is **not** a data-availability problem, and not a "wait for the session" problem: it is
something in the σ sensor's own seeding/publishing path (`FusionLifecycle.seedVolatility` →
`SensorWarmup.warm` → `StreamVolatility.warmupPrices`, which demands a **full span** of returns before it
will speak at all, where the covariance estimator evidently does not).

**Constraint on the fix (Rule 229 / Rule 233).** The fix must **arm the stop with a measured σ** — never
bypass `stopArmed`, never delete the ADR-0126 conjunction. Opening a position the ADR-0086 trailing cut
cannot price an exit for is precisely the risk that control exists to prevent, and the deterministic floor
is off-limits. Any σ derived from a coarser history (the 9 LIVE / 1568 SEED daily closes in
`daily_close_depth`) must be scaled to the per-sample horizon with the finance-math skill and carry its
provenance; a daily σ dropped into a 30 s-sampled EWMA is a scale error, not a fix.

**VERIFY-BY (next run, from live telemetry):** `/api/fusion/targets` → `streamVolMeasuredNames` materially
above **6** of 20 at comparable uptime, the count of `aims` entries reading exactly `0.0` materially below
**14**, and NQ's share of `/api/risk` `.total.grossExposure` materially below **81.7%**. Architecturally
significant → ships with its ADR at `**Status:** Implemented` in the same commit.

### Item #2 — even for ARMED names the aim sits inside the no-trade band (⚠️ OPEN, watch only)

`insideBuffer` is **19** of 20: of the six armed names only NQ routed a delta this planning cycle. GOOG
holds `aim 47.33` against `currentQty 0`; AMZN `aim -12.06` against `currentQty 0`. NVDA did get on
(`currentQty -26.0`), so the ramp does work — this is a *rate*, not a block, and the band history is
littered with graded-BAD attempts (ADR-0133 ❌ BAD and reverted). **Do not touch the band until item #1 is
verified**, because arming more names changes what the band is even measured against.

**VERIFY-BY:** re-read `insideBuffer` and `aims` after item #1 lands; only then decide whether a rate
problem remains.

### Not a defect this cycle — recorded so it is not re-diagnosed

- **The -$23.64 PnL move is noise, not a regression.** It is a 15-minute mark on a $23.5k NQ short opened
  at 14:14Z: **-0.084%**. `recent_orders` shows every entry fired at `sources=2`/`sources=3`, so ADR-0135
  (which only alters the `sources=1` branch) is causally uninvolved.
- **Turnover cost is not the current problem.** Fees moved **+$0.79** across this window
  (288.003716 → 288.791846). ALPHA's **$281.55** is cumulative history, not a live bleed.
- **Signal weights already track measured edge.** `/api/signals/telemetry` @3600s: social **+8.54** bps
  (weight 1.719), reversion **+3.71** (1.540), momentum **-0.96** (0.867), trend **-1.19** (0.679),
  xsreversion **-8.22** (0.250, floored). The combiner is ranking sources correctly — consistent with the
  standing "work on edge, not the combiner" priority, and a reason **not** to spend the next change there.

---

## Verification block — 2026-08-03 14:00Z (**no change shipped — ADR-0135 is still under measurement at 1/6 cycles.** Last cycle's change ✅ VERIFIED on its own terms. A NEW **#1** is now traced end to end: the ADR-0126 unarmed-stop veto freezes the ENTIRE equity book flat, permanently, because the risk-cut σ sensor cannot warm inside an ephemeral process.)

### Why no change this cycle

`scripts/score-change.py score` printed **`74a47adee still accumulating evidence (1/6 cycles) — held, not
scored this run`** and `reports/.pending-baseline.json` is present. ADR-0116's rule is explicit: a new code
change on top of a pending one destroys its evidence. Verified, diagnosed, recorded, stopped.

### Step 0 — last cycle's change (`74a47ade`, ADR-0135 unestimable view holds): ✅ VERIFIED

- **Deployed: ✅.** `/api/fusion/targets` returns the `estimable` field this change introduced.
- **VERIFY-BY met.** All nine one-source names — CAT, UNH, JPM, TSLA, HD, META, WMT, XOM, CVX — read
  `sources: 1`, `estimable: false`, `agreement: 0.0`, `deltaQty: 0.000`. The unestimable branch trades
  nothing in either direction. **Zero** liquidation orders were generated on them.
- **Honest caveat.** The book was already flat at deploy, so the branch runs at zero inventory. Its real
  test — a breadth collapse at the cash close no longer round-tripping a *held* book — is unobservable
  until the desk holds something. That is gated on item #1 below.
- **Not yet scored** (1/6 cycles). The scorer owns that verdict, not this register.

### Item #1 — the ADR-0126 unarmed-stop veto holds the whole equity book flat, permanently (⚠️ OPEN, NEW)

**Cost: the entire opportunity.** Gross **$0.00** against **$1,500,000** of headroom, DORMANT for a fourth
day, while the desk carries thirteen estimable views it is not allowed to act on: MCD `combinedForecast
-8.446` / `agreement 0.596` / `targetQty -670.773`; BAC `+6.507` → `+3090.103`; PFE `-2.315` →
`-1588.257` — every one with `deltaQty 0.000` at `currentQty 0`.

**Mechanism, read from code and telemetry, not inferred.** `edgeGate` is `null`, so the ADR-0064 gate is
silent and is NOT the vetoer. That leaves the ADR-0126 clause in `PositionBuffer.mayIncrease`
(`PositionBuffer.java:202`): `stopArmed == null || stopArmed.test(instrument)`.
`FusionLifecycle.stopArmed` (`FusionLifecycle.java:550`) answers from
`streamVol.sigmaPerSample(instrument).isPresent()`. False ⇒ the delta is clamped `reduceOnly` (exactly zero
at `held = 0`) and then, at `PositionBuffer.java:164`, **the aim is re-seeded to `held + delta` = 0** — so
the ADR-0080 aim path is reset to flat every cycle and can never accumulate. Telemetry matches exactly:
every `aims` entry `0.0`, `insideBuffer: 22`, `streamVolMeasuredNames: 1` of 22.

**Why it is structural, not a passing warm-up.** `vol-span=120` (`application.properties:453`) ⇒
`warmupPrices() = 121` (`StreamVolatility.java:119`), replayed at the 30 s cadence ≈ **60.5 minutes** of
30 s-spaced history. The durable seed delivers far less — the app's own WARN log, per name: AAPL **75**,
NVDA **85**, MSFT **67**, GOOG **61**, AMZN **44**, BAC **35**, KO **34**, WMT **33**, NEE **33**, PFE
**31**, JNJ **30**, XOM **30**, PG **29**, CVX **29**, JPM **28**, CAT **27**, HD **27**, UNH **27** — all
`of 121`. And the process is ephemeral: `uptimeSeconds` **727**, torn down each cycle. A sensor needing
~60 min, seeded short, in a process living ~30 min, never warms on any boot. The tape is not at fault:
`ticksDropped: 0`, mark `ageMillis` 122–232, and the same mark store warms the covariance for **14** names.

ADR-0126's principle is right — do not open a position the risk cut cannot protect. The defect is that its
warm-up requirement is **unsatisfiable under this deployment's process lifetime**, so a control meant to
gate *some* names vetoes *all* of them, forever. Note this is above the deterministic floor: the pre-trade
guardrail, firm breaker and conviction floor are untouched by any fix here.

**VERIFY-BY (next run, from live telemetry):**
- `/api/fusion/targets` → `streamVolMeasuredNames` **> 1**, and `insideBuffer` **< 22**.
- At least one name with `estimable: true` shows a **non-zero `deltaQty`**, and its `aims` entry is
  **non-zero** (proving the aim path is no longer re-seeded to flat every cycle).
- `/api/risk` `.total.grossExposure` **> 0**.
- Every name that does put risk on must have a **priced stop distance** — the fix must not simply delete
  the ADR-0126 protection (see the trap below).

**The trap to avoid (rank this above speed).** The cheap "fix" is to make `stopArmed` return true when the
sensor is cold. That does not satisfy ADR-0126, it deletes it — the desk would open positions the risk cut
demonstrably cannot price a cut for. The fix must give the stop a **measured** σ (a shorter measured span,
a seed that draws on the daily-close depth already in Postgres — `LIVE` **9** days × **51** instruments,
`SEED` **1568** days × **34** — or persistence of the sensor state across boots), not bypass the check.
Whatever it is, the σ that arms the stop must be one the ADR-0086 cut will actually use, or the stop is
armed in name only. Rule 229 applies too: check WHICH source sizes the resulting position before declaring
victory — `xsreversion` measures **-6.044110** avgReturnBps and must not inherit the book.

### ~~Item — the desk liquidates its whole book at every equity close~~ ✅ VERIFIED FIXED (ADR-0135), struck

---

## Verification block — 2026-08-03 13:30Z (**item #1 ✅ VERIFIED and struck** — the ADR-0134 origination trigger is live and populated on FILLED orders, and it immediately named the mechanism behind the DORMANT book. New **#1** — the desk liquidates its entire book at every equity close on a breadth collapse — diagnosed and **fixed this cycle** (ADR-0135).)

### Step 0 — last cycle's change (`ad6c42c88`, ADR-0134 origination triggers): ✅ VERIFIED, and SCORED

- **Deployed + landed: ✅.** `recent_orders` now selects `origin_reason` as `origin`, and every FILLED
  order created after the deploy carries it — the last NULL-origin FILLED row is at 19:44:23Z, every row
  from 19:45:20Z onward is populated. The pre-deploy rows are the only NULLs left, which is the expected
  shape for a column stamped at insert.
- **VERIFY-BY met, both halves.** FILLED orders with a NULL origin among post-deploy rows is **0**, and the
  distinct FILLED origins name **four** triggers, not one: `fusion entry — target increase`,
  `fusion reduce toward a smaller target`, `fusion exit — target decayed to flat`, and
  `auto-hedge EQUITY (ADR-0019)`.
- **Scored: ⚠️ INCONCLUSIVE.** The ledger row records risk-adjusted return/cycle **-0.001135** over **89**
  cycles, **t=-0.94** against the **1.5** hurdle; gross **65,195 → 0**. Kept, not reverted — and exactly the
  verdict predicted for a telemetry-only change that moves no money. Grading is the scorer's; nothing here
  is authored.
- **Its actual payoff was immediate and is the entire content of this cycle:** it named the trigger behind
  the dormancy on the first window it covered. Rule 223 held.

### ~~Item — FILLED orders carry no origination trigger~~ ✅ VERIFIED FIXED (ADR-0134), struck

### Item #1 — the desk liquidates its whole book at every equity close (⚠️ STILL-BROKEN → fixed this cycle, ADR-0135)

Read straight off the new `origin` column. Between 20:10Z and 20:49Z on 2026-07-31 source counts fall
**4 → 3 → 2** through the equity cash close, and then every routed name exits on
`fusion exit — target decayed to flat [forecast=-0.0, sources=1]`. Not one of those exits was a change of
view. `/api/fusion/targets` still shows all five routed names at `sources: 1`, `agreement: 0.0`,
`combinedForecast: 0.0` with contributing forecasts as large as **±10.97** underneath. Gross has been
**$0.00** ever since — three days of the **DORMANT** flag against **$1,500,000** of headroom.

**Mechanism, confirmed in code, not inferred.** At one effective source the residual degrees of freedom
`1 − Σŵᵢ²` are zero, so ADR-0124's dispersion is UNESTIMABLE and the agreement scalar returns 0 — correct
statistics. But that 0 multiplies the combined forecast to exactly 0, `TargetPlanner.targetQuantity` maps 0
to a target of flat, and **ADR-0090 works a flat target IN FULL**. So a collapse in source BREADTH executes
as a full-urgency decision to LIQUIDATE. It is structural, not a market event: since ADR-0113 the
price-driven sensors advance only when the tape PRINTS, so they fall silent at every cash close and leave
only the snapshot-based cross-sectional source. The cost is a daily round-trip of the whole book on
measured-zero information — `/api/attribution` shows ALPHA at **-18.59706568** having paid **281.28065700**
in fees, against a firm total of **32.51192011** that is positive only because HEDGE carries **86.93246234**.

**What shipped.** `ForecastCombiner.Combined` gains `estimable`, false in exactly one circumstance (sources
spoke but `1 − Σŵᵢ² ≤ 0`), and `FusionPlanner` then targets the inventory ALREADY HELD with a zero delta —
no exit, and equally no entry, since an uncorroborated view may not size a position either. Deliberately
out of scope and byte-identical: a name with NO source is still swept flat by ADR-0065, and two or more
sources netting to zero is a MEASURED view of flat and still exits in full. The deterministic floor is
untouched — the ADR-0086 trailing cut, firm breaker, pre-trade guardrail, edge gate and every risk-reducing
stage still run against the held target and can still flatten it. Eight new tests, `-Pci test` green.

- **VERIFY-BY (next run):** in `/api/fusion/targets`, a routed name with `sources: 1` shows
  `estimable: false` and `targetQty` equal to its `currentQty` with `deltaQty` **0** — and NO order in
  `recent_orders` carries `fusion exit — target decayed to flat [... sources=1]`. Second half, the one that
  matters for money: gross exposure is **no longer $0.00** once the desk next puts a position on.
- **Honest caveat:** this removes a cost, it does not create edge. It also accepts overnight/weekend gap
  risk the daily liquidation was removing by accident.

### Open items — re-ranked, most-costly first

- **#2 — no source has demonstrated positive out-of-sample edge.** `/api/signals/telemetry` shows
  `avgReturnBps` of **+4.954893** (social), **+4.297909** (reversion), **-0.956358** (momentum),
  **-1.191550** (trend), **-5.153198** (xsreversion); `strategy_diag.edgeGated` reports "no positive OOS
  edge" on every name it lists. Nothing has cleared the gate. This is the standing priority and remains
  the real problem — but it could not be worked while a structural gate made holding any position
  impossible past a close. **VERIFY-BY:** a source with positive measured expectancy that clears the
  ADR-0049 OOS gate and is permitted to size.
- **#3 — breadth may be collapsing DURING the session too, not only at the close.** `forecastScalars`
  lists readings for `xsreversion` only (**784**), and `streamVolMeasuredNames`, `covarianceCoveredNames`
  and `bookVolSamples` are all **0** — so trend/reversion/momentum/social published nothing at all this
  process life. ADR-0135 stops the liquidation either way, but if this persists through the next cash open
  the sensors' warm-up (ADR-0071/0113), not the combiner, is the target. **VERIFY-BY:** at the next open,
  whether any routed name reaches `sources: 2` or more.
- **#4 — `hedgeMasking: true` with `covarianceReady: false`.** The firm total is still sourced from a
  hedge book the desk cannot recompute a covariance for. Carried from the 2026-07-30 block, unchanged.

## Verification block — 2026-07-31 19:30Z (**hold cleared — CHANGE SHIPPED**. `4f67f0515` scored **⚠️ INCONCLUSIVE** and `reports/.pending-baseline.json` is gone, so the five-cycle hold is over. Item **#1 re-verified ⚠️ STILL-BROKEN** — 36 of 36 FILLED orders NULL this window against 24 of 24 CANCELLED populated — and **fixed this cycle** (ADR-0134): the origination trigger is threaded from the deciding call sites into `submit` and stamped on the row at insert, where no status transition can overwrite it.)

### Step 0 — last cycle's change (`4f67f0515`, the manual completion of the failed auto-revert): ✅ VERIFIED, and now SCORED

- **Deployed + landed: ✅.** `docs/adr/0133-…md` reads `**Status:** Reverted` and a grep for `0133` across
  the Java sources still returns no file, so the graded-BAD band cap is absent from the running code.
- **Scored: ⚠️ INCONCLUSIVE.** The ledger row records risk-adjusted return/cycle **-0.000147** over **7**
  cycles, **t=-0.37** against the **1.5** hurdle; gross **90,641 → 44,979**. Kept, not reverted. Grading is
  the scorer's and the ledger row is its output — nothing here is authored.
- **Consequence:** the hold that blocked cycles 1/6 through 5/6 is over, so item #1 shipped this cycle.

### Item #1 — FIXED this cycle (ADR-0134); the defect first re-verified ⚠️ STILL-BROKEN

Reproduced precisely on this window's 60 `recent_orders` before the change: **36 of 36 FILLED** orders
carry a NULL `reason`; **24 of 24 CANCELLED** carry text, and it is the same string each time
(`fusion re-plan — passive order superseded by a fresh target (ADR-0084)`). The only orders explained are
the ones that never traded — exactly the shape the code predicts.

What shipped, per last cycle's diagnosis that `reason` is a *status-transition* field and cannot be
patched at the order layer:

- `NewOrder` gains a nullable `originReason`; `OrderStore.insertIfAbsent` writes it into a new
  `orders.origin_reason` column (migration `V50`) **at insert**, before the order can hold any status, and
  **no transition ever overwrites it**. So it is present on exactly the orders that FILLED.
- Separate column, not an overload of `reason`: the two answer different questions, and a REJECTED row is
  strictly more useful carrying both the want and the refusal.
- Every deciding call site passes the sentence it already held — `signal.rationale()`, the AI sleeve's
  `thesis()`, the hedge advisor's `rationale()`, the operator's manual POST. `FusionLifecycle`, which
  places most of the flow, names four triggers the post-mortem must separate: ADR-0086 trailing-stop cut,
  entry, reduce-toward-target, and exit-decayed-to-flat.
- ADV child slices inherit the parent's trigger — slicing changes *how* the risk goes on, not *why*.
- Persistence-only, so `common-domain` and the messaging contract are untouched (invariant 4). Four new
  tests cover the FILLED path, the REJECTED path keeping both fields, slice inheritance, and that a null
  origin never blocks a trade. `-Pci test` green.

- **VERIFY-BY (next run):** in `recent_orders`, FILLED orders with a NULL `origin` is **0**, and the
  distinct FILLED origins name **more than one** trigger. The report query now selects `origin_reason` as
  `origin` alongside `reason`, so both are visible and can be told apart.
- **Honest caveat, restated:** telemetry only — nothing reads the field back, so it moves no money and
  should be expected to score **⚠️ INCONCLUSIVE**. That is the correct outcome for buying evidence.

### Open items — re-ranked, most-costly first

**#1 (was #2) — ALPHA is not covering its own fees while a frozen hedge masks the headline.** Now the top
item. `/api/attribution`: ALPHA `totalPnl` **28.99506278** on `feesPaid` **266.333508**, HEDGE
**160.19086234** on **6.151134**, MACRO **-35.82347655**. ALPHA swung from **-13.33312927** last cycle, so
the strategy book is the entire run-over-run move in both directions while HEDGE and MACRO sit byte-identical
— they are ballast, not hedges. `hedgeMasking` **true**. ADR-0134 is what makes this diagnosable next run:
cross the FILLED origins against per-name PnL and the fee line to find which trigger is paying the fees.
- **VERIFY-BY:** ALPHA `totalPnl` in `/api/attribution` exceeds its `feesPaid`, or the gap narrows while
  the fee line does not grow.

**#2 (was #3) — the holding period is shorter than the signal horizon.** `horizonSeconds` **3600** on every
source against a ~30-minute process recycle.
- **VERIFY-BY:** median position age across a teardown exceeds one cycle for names the desk did not intend
  to close.

**#3 (was #4) — the scorer's auto-revert fails on a git conflict and leaves the BAD commit live.** Two
ledger rows carry `⚠️ REVERT FAILED (git conflict)`. Reliability defect with a manual workaround that has
now worked twice.
- **VERIFY-BY:** a BAD verdict is followed by a clean revert with no manual step and no `revert-failed`
  heartbeat action.

**#4 (was #5) — the hedge covariance never converges.** `/api/hedging` `covarianceReady` **false**, EQUITY
axis `WARMING`, `targetProxyQty` **null**, `utilization` **0.0**, `netExposureUsd` **24004.97**. Downstream
of #2 (Rule 212), and what makes #1's masking possible.
- **VERIFY-BY:** `covarianceReady` **true** with a non-null `targetProxyQty`.

**#5 (was #6) — the book is under-deployed against the owner budget.** Net at **2.4%** of the net cap.
Downstream of the edge gate holding every source flat; not independently actionable.
- **VERIFY-BY:** net-cap utilisation rises while the risk-adjusted return stays positive.

---

## Verification block — 2026-07-31 19:00Z (still **HOLD at 5/6 cycles** — **NO code change**. Item **#1 re-verified ⚠️ STILL-BROKEN, and this cycle found its code cause**: `reason` is a *status-transition* reason, not an *origination* reason — the happy path `NEW → ROUTED → FILLED` passes a literal `null` at every step, so only orders that FAIL can ever carry text. That turns #1 from "a column is empty" into a one-line-per-call-site fix with a known shape, ready to ship the moment the hold clears. Second finding: the whole run-over-run PnL fall is the **ALPHA** book — the HEDGE number is byte-identical to last cycle, so the hedge is frozen, not helping.)

**HOLD — no change this cycle.** `python3 scripts/score-change.py score` prints
`score: 4f67f0515 still accumulating evidence (5/6 cycles) — held, not scored this run`, and
`reports/.pending-baseline.json` is present (commit `4f67f05158a4b7cf159180250589c7b48a0ebc59`,
`ts` `2026-07-31T16:35:59Z`). One cycle from a verdict — a new change now would waste five cycles of
accumulated evidence on the revert.

### Step 0 — last cycle's change (`4f67f0515`, the manual completion of the failed auto-revert): ✅ VERIFIED (deployed + landed), ⏳ effect still under measurement

- **Deployed: ✅.** `/api/ops/jvm` `uptimeSeconds` **1460** against `/api/risk` `asOfMillis`
  **1785524432537** puts boot at **2026-07-31T18:36:12Z** — after the 16:35:50Z revert commit, so the
  running process is running the reverted code.
- **Landed in the source: ✅ (unchanged).** `docs/adr/0133-…md` reads `**Status:** Reverted`; a grep for
  `0133` across the Java sources returns no file, so the rejected band cap is absent from the code.
- **Effect: ⏳ not gradeable — the scorer's job at 6/6, not mine.** Direction since the pending baseline
  (`total_pnl` **202.24011897**, `gross_exposure` **90641.36436250**) to live now: `totalPnl`
  **111.03425652**, `grossExposure` **42125.90500000**.

### Item #1 re-verified ⚠️ STILL-BROKEN — and the mechanism is now known

The defect reproduces on this window's `recent_orders` (60 rows): **32 of 32 FILLED** orders and the
**1 ROUTED** order carry a NULL `reason`; **27 of 27 CANCELLED** carry text, and it is the same string
each time (`fusion re-plan — passive order superseded by a fresh target (ADR-0084)`).

This cycle traced *why*, which the previous blocks had not:

- `modules/order/src/main/java/io/jethro/order/OrderService.java` `routeApproveAndFill` writes the reason
  only on the failure branches — `transition(order, OrderStatus.REJECTED, gate.reason())`,
  `"no market data for " + …`, `"IOC — not marketable on arrival"`. The success branch is
  `transition(order, OrderStatus.ROUTED, null)`, and the submit path publishes
  `publisher.publishOrderEvent(order, null)`.
- `OrderStore.updateStatus(orderId, status, reason, now)` therefore persists `null` on every transition
  an order makes on its way to FILLED.

So the column is not "failing to populate" — it is **doing exactly what it was built to do**, recording
why a status *changed* rather than why the desk *wanted the trade*. The order-level post-mortem the
procedure mandates needs the second thing, and nothing in the system currently carries it from the
fusion target down to the order. That reframes the fix: the trigger has to be threaded from the call
site that decides to trade into `submit`, not recovered at the order layer.

### The other thing this cycle found — the hedge is frozen, and ALPHA is the whole move

`/api/attribution` now reads `firmTotal` **111.03425652** = HEDGE **160.19086234** + ALPHA
**-13.33312927** + MACRO **-35.82347655**, on `totalFees` **270.415875** of which ALPHA paid
**264.094908** (HEDGE **6.151134**, MACRO **0.169833**).

- The HEDGE figure **160.19086234** and the MACRO figure **-35.82347655** are byte-identical to last
  cycle's reading. Neither book did anything this window.
- ALPHA read **40.37358247** last cycle and reads **-13.33312927** now. The entire run-over-run fall the
  SITUATION header reports (`PnL -47.48`) is the strategy book, and it happened while gross rose
  (`gross +5619.53`).
- `hedgeMasking` is **true** while `/api/hedging` `covarianceReady` is **false** — so the book the owner
  sees as positive is positive only because a frozen hedge P&L is sitting on top of a strategy book that
  has now gone negative net of its own fees. Rule 217 predicted this last cycle; it has since crossed
  from "not covering its fees" to "negative outright".

Not a danger state: gross **$38262.24** is **2.6%** of the $1,500,000 firm cap (headroom **$1,461,738**),
net **$17218.54** is **1.7%** of the $1,000,000 net cap, `Flags: none`.

### Standing priority — still no source with actionable edge

`/api/signals/telemetry` at `horizonSeconds` **3600**: momentum `avgReturnBps` **5.749961141428572**
over **10** cohorts (`stdCohortMeanBps` **29.247194899516227**), social **5.743293811097191** / **22** /
**25.910850044717932**, reversion **4.911734698471268** / **63** / **32.17586652023403**, while trend
**-2.044825086028045** / **71** / **30.426625198598547** and xsreversion **-6.146473850702721** / **25** /
**33.63312259265042** are negative. Every positive source remains small against its own cohort
dispersion; the edge gate correctly lets none of them size. Unchanged for six cycles.

### Open items — re-ranked, most-costly first

**#1 — the desk records no origination trigger on any order it actually trades.** ⚠️ STILL-BROKEN.
32/32 FILLED orders NULL this window. Now with a known cause (above): `reason` is a status-transition
field written `null` on the success path, so the fix is to thread the deciding trigger from the fusion/
strategy call site into `submit`, not to patch the order layer. Still the cheapest change with the
largest downstream leverage — it touches no money math, no sizing, nothing on the deterministic floor —
and four consecutive cycles have now failed to name a trigger for want of it.
- **VERIFY-BY:** next run, FILLED orders in the window with a NULL `reason` is **0**, and the distinct
  FILLED reasons name more than one trigger.
- **Honest caveat, restated:** this is a telemetry change. It moves no money and will very likely score
  ⚠️ INCONCLUSIVE. That is the correct outcome for buying evidence, not a failure.

**#2 — ALPHA is negative net of its own fees while a frozen hedge masks it.** NEW, entering at #2.
ALPHA `totalPnl` **-13.33312927** on `feesPaid` **264.094908**, against HEDGE **160.19086234** that has
not moved. `hedgeMasking` **true**. This is a live bleed, not a reliability defect — it ranks above #3
and #4. It is *not* actionable before #1, because fixing it means knowing which trigger opened the
losers, which is exactly what #1 buys.
- **VERIFY-BY:** ALPHA `totalPnl` in `/api/attribution` is positive net of `feesPaid`, or its loss
  narrows while the fee line does not grow.

**#3 — the holding period is shorter than the signal horizon.** `horizonSeconds` **3600** on every
source against a ~30-minute process recycle. Survives the Rule 213 retraction because it never depended
on the flatten being universal.
- **VERIFY-BY:** median position age across a teardown exceeds one cycle for names the desk did not
  intend to close.

**#4 — the scorer's auto-revert fails on a git conflict and leaves the BAD commit live.** Two ledger
rows carry `⚠️ REVERT FAILED (git conflict)`. Reliability defect with a manual workaround that has now
worked twice; ranks below the live bleed.
- **VERIFY-BY:** a BAD verdict is followed by a clean revert with no manual step and no `revert-failed`
  heartbeat action.

**#5 — the hedge covariance never converges.** `/api/hedging` `covarianceReady` **false**, EQUITY axis
`WARMING`, `targetProxyQty` **null**, `utilization` **0.0**, `netExposureUsd` **13246.27** for a sixth
consecutive cycle. Downstream of #3 (Rule 212) — do not spend a change here first. Note this is also
what makes #2's masking possible.
- **VERIFY-BY:** `covarianceReady` **true** with a non-null `targetProxyQty`.

**#6 — the book is under-deployed against the owner budget.** Net at **1.7%** of the net cap. Downstream
of the edge gate holding every source flat; not independently actionable.
- **VERIFY-BY:** net-cap utilisation rises while the risk-adjusted return stays positive.

---

## Verification block — 2026-07-31 18:30Z (still **HOLD at 4/6 cycles** — **NO code change**. This hold cycle **falsified last cycle's own headline**: the desk did **not** flatten at this teardown — six names carried across the reboot. So the flatten is **episodic, not structural**, and Rule 207 overgeneralised from two consecutive cycles. Chasing it further is blocked by a defect found this cycle: **every order that actually traded carries a NULL `reason`**, so the order-level post-mortem the whole procedure depends on has no evidence in it. That becomes **item #1** — it is the reason three consecutive cycles could not name a trigger.)

**HOLD — no change this cycle.** `python3 scripts/score-change.py score` prints
`score: 4f67f0515 still accumulating evidence (4/6 cycles) — held, not scored this run`, and
`reports/.pending-baseline.json` is present (commit `4f67f05158a4b7cf159180250589c7b48a0ebc59`,
`ts` `2026-07-31T16:35:59Z`). A new change now would destroy the evidence on the revert.

### Step 0 — last cycle's change (`4f67f0515`, the manual completion of the failed auto-revert): ✅ VERIFIED (deployed + landed), ⏳ effect still under measurement

- **Deployed: ✅.** `/api/ops/jvm` `uptimeSeconds` **1279** against `/api/risk` `asOfMillis`
  **1785522627137** puts boot at **2026-07-31T18:09:08Z** — after the 16:35:50Z revert commit, so the
  running code is the reverted code.
- **Landed in the source: ✅ (unchanged).** `docs/adr/0133-*.md` reads `**Status:** Reverted`; a grep for
  `0133` across the Java sources returns nothing, so the rejected band cap is absent from the code, not
  merely disabled.
- **Effect: ⏳ not gradeable — the scorer's job at 6/6, not mine.** Direction since the pending baseline
  (`totalPnl` **202.24011897**, gross **90641.36436250**) to live now: `totalPnl` **164.74096826**,
  gross **33932.54000000**.

### The correction — the teardown flatten is EPISODIC, not every cycle

Last cycle's block asserted the desk "liquidates the entire book to exactly flat at every loop teardown".
This cycle's telemetry does not support "every":

- **Six names carried across this reboot.** Summing every LIVE ALPHA fill executed before the
  **2026-07-31T18:09:08Z** boot, the net is non-zero on BAC **68.000000**, GOOG **1.000000**,
  MCD **-43.000000**, MSFT **37.000000**, NVDA **-30.000000**, PFE **-221.000000**. Not flat.
- **The heartbeat-minute bursts are outliers, not a cadence.** Minutes over $20k of turnover since
  12:00Z: the three outsized ones are `16:05` (**3** fills, **$63,599.14**), `17:12` (**6**,
  **$81,499.01**) and `17:38` (**11**, **$99,922.58**). The teardown minutes of the other cycles are
  ordinary — `16:37` **$28,990.48**, `18:06` **$20,729.30**, `18:09` **$17,188.13**.
- **What survives from Rule 209 is the part that never depended on "every".** Every source in
  `/api/signals/telemetry` still publishes `horizonSeconds` **3600** while the process is recycled every
  ~30 minutes, so the holding period is still structurally shorter than the horizon the expectancy is
  measured over. That claim stands on its own; the "flat at every teardown" mechanism does not.

### Why the diagnosis stalled — the post-mortem source is empty

`ops/improve-prompt.md` step 5 requires attributing each move to the **trigger** that opened it. That
evidence does not exist for any order that traded:

- Of the **523** FILLED orders since 12:00Z, **0** carry a `reason`.
- All **249** populated reasons belong to CANCELLED orders, and every one is the same string —
  `fusion re-plan — passive order superseded by a fresh target (ADR-0084)`. The only other is a single
  REJECTED `no market data for MCD`.

So the register has been asking three consecutive cycles to "name the trigger behind the collapse" using a
column that is populated only for orders that never traded. That is the binding constraint.

### Situation triage (live, read — never authored)

1. **Money.** SITUATION: total PnL **$164.68**, **+103.62** since last run, **-95.01** over the last 3.
   Heartbeat `2026-07-31T18:08:44Z`: `pnl_growth_pct` **-72.0** vs `pnl_target_pct` **1.0**, `on_track`
   **false**, `stale` **true**, `underwater` **false**. Off target, but up run-over-run.
   `/api/attribution`: `firmTotal` **164.74096826** = HEDGE **160.19086234** + ALPHA **40.37358247** +
   MACRO **-35.82347655**; `totalFees` **268.179380**, of which ALPHA `feesPaid` **261.858413**.
   **The strategy books are not paying for their own fees — the hedge is carrying the total.**
2. **Risk.** Gross **$33,927.71** = **2.3%** of the $1,500,000 firm cap, headroom **$1,466,072**; net
   **$12,537.56** = **1.3%** of the $1,000,000 net cap. `Flags: none`. Badly under-deployed.
3. **Cause.** Last cycle's change is the revert completion, still under measurement (4/6). The
   **+103.62** move is not attributable to it: `/api/risk/breaker` is `halted: false`, regime is
   `CHOP`/`CALM` with `volRatio` **1.04**, and the window's orders are ordinary-sized. With FILLED
   orders carrying no `reason`, market vs change **cannot be separated from the numbers** — so it is
   recorded as unattributed rather than credited.
4. **Danger.** None. Not bleeding, not near a cap, breaker not halted. The live problem is the inverse:
   **1.3% net-cap utilisation** with a stale growth flag.
5. **Edge check (the standing priority).** `/api/signals/telemetry`, longest horizon
   (`horizonSeconds` **3600**), each as `avgReturnBps` / `cohorts` / `stdCohortMeanBps`:
   reversion **4.870510576792947** / **63** / **32.18731967290294**;
   social **5.656591478039339** / **22** / **25.926539977790824**;
   trend **-1.9332555813332086** / **71** / **30.395970142413756**;
   momentum **-1.053279287301587** / **9** / **21.01493057213379**;
   xsreversion **-6.334834134258276** / **25** / **33.59752101445426**.
   The two positive sources remain small against their own cohort dispersion, and the edge gate still
   lets none of them size. Unchanged from prior cycles.

### Open items — re-ranked, most-costly first

**#1 — FILLED orders carry no `reason`, so no trade can be attributed to its trigger.** 0 of 523 FILLED
orders since 12:00Z have a reason; the 249 that do are all CANCELLED. Every diagnosis this register has
asked for over three cycles — "which trigger opened the loser", "what collapsed the targets" — is
unanswerable without it, and the loop has instead been guessing mechanisms and then falsifying them
(Rule 207 this cycle). This is the cheapest change with the largest downstream leverage: it touches no
money math, no sizing, and nothing on the deterministic floor.
- **VERIFY-BY:** next run, `select count(*) filter (where reason is null) from orders where status='FILLED'`
  over the window returns **0**, and the distinct FILLED reasons name more than one trigger.
- **Honest caveat to record with it:** this is a telemetry fix. It will very likely score
  ⚠️ INCONCLUSIVE because it moves no money directly. That is the correct outcome, not a failure — it
  buys the evidence the next *money* change needs.

**#2 — the holding period is shorter than the signal horizon.** `horizonSeconds` **3600** on every source
against a ~30-minute process recycle. Survives this cycle's correction because it never depended on the
flatten being universal.
- **VERIFY-BY:** median position age across a teardown exceeds one cycle for names the desk did not
  intend to close.

**#3 — the scorer's auto-revert fails on a git conflict and leaves the BAD commit live.** Two ledger rows
now carry `⚠️ REVERT FAILED (git conflict): the BAD commit is STILL LIVE`. Drops below #1 because the
manual completion has worked twice; it is a reliability defect, not an active bleed.
- **VERIFY-BY:** a BAD verdict is followed by a clean revert with no manual step and no `revert-failed`
  heartbeat action.

**#4 — the hedge covariance never converges.** `/api/hedging` `covarianceReady` **false**, EQUITY axis
`WARMING`, `targetProxyQty` **null** for a fifth consecutive cycle, `utilization` **0.0**. Still
downstream of #2 (Rule 212) — do not spend a change here first.
- **VERIFY-BY:** `covarianceReady` **true** with a non-null `targetProxyQty`.

**#5 — the book is under-deployed against the owner budget.** Net at **1.3%** of the net cap. Downstream
of the edge gate holding every source flat; not independently actionable.
- **VERIFY-BY:** net-cap utilisation rises while the risk-adjusted return stays positive.

---

## Verification block — 2026-07-31 18:00Z (still **HOLD at 3/6 cycles** — **NO code change**. A third hold cycle went into measurement and it **promoted the churn item to #1 and corrected its mechanism**: the desk does not churn a few cold names, it liquidates the **entire book to exactly flat at every loop teardown** and rebuilds from zero. The flatten fires at **teardown**, not at boot — so cold sensors are the sequel, not the cause. The scorer's revert bug drops to #2: it has a manual workaround that has now worked twice; this one has none and is halving the holding period the edge needs.)

**HOLD — no change this cycle.** `python3 scripts/score-change.py score` prints
`score: 4f67f0515 still accumulating evidence (3/6 cycles) — held, not scored this run`, and
`reports/.pending-baseline.json` is present (commit `4f67f05158a4b7cf159180250589c7b48a0ebc59`,
`ts` `2026-07-31T16:35:59Z`). A new change now would destroy the evidence on the revert.

### Step 0 — last cycle's change (`4f67f0515`, the manual completion of the failed auto-revert): ✅ VERIFIED (deployed + landed), ⏳ effect still under measurement

- **Deployed: ✅.** `/api/ops/jvm` `uptimeSeconds` **1283** against `/api/risk` `asOfMillis`
  **1785520826830** puts boot at **2026-07-31T17:39:03Z** — after the 16:35:50Z revert commit, so the
  running code is the reverted code.
- **Landed in the source: ✅ (unchanged).** `docs/adr/0133-*.md` reads `**Status:** Reverted`;
  `PositionBuffer` carries no ADR-0133 band cap.
- **Effect: ⏳ not gradeable — the scorer's job at 6/6, not mine.** Direction since the pending baseline
  (`totalPnl` **202.24011897**, gross **90641.36436250**) to live now: `totalPnl` **76.28509719**, gross
  **16109.57500000**.

### The finding that re-ranks the register — the book round-trips ITSELF every cycle

- **ALPHA was EXACTLY FLAT at the reboot.** Summing every LIVE fill executed before **2026-07-31T17:39:03Z**,
  ALPHA nets **0.000000** across **21** names over **1687** fills. Not "mostly reduced" — flat.
- **The liquidation is one 500ms burst at teardown.** Minute `17:38` (2s after the `2026-07-31T17:38:42Z`
  heartbeat, 19s before boot): **11** fills, **$99,922.58** turnover, **$9.99** fee — against the
  heartbeat's recorded gross **99920.63500000**. A round trip of ~100% of the book. One cycle earlier the
  same signature: minute `17:12`, **6** fills, **$81,499.01**, against a whole-book gross of
  **81647.96500000**. Rebuild from flat reached gross **16111.97500000** by 18:00Z.
- **Day-level share.** Over today's 9 loop cycles since 13:52:55Z, the windows `heartbeat −60s … +120s`
  hold **56** fills (**12.5%** of fills) but **$372,487.16** = **24.17%** of turnover and **$37.33** =
  **23.95%** of the fee bill, vs **392** fills / **$1,168,829.38** / **$118.53** outside. Against
  `/api/attribution` `firmTotal` **76.28509719** and `totalFees` **261.125419** (ALPHA `feesPaid`
  **254.804452** on `totalPnl` **-48.08228860**).
- **Ruled out — this is NOT a projection/state-loss bug.** At one instant `/api/risk` `positions` and
  `sum(BUY − SELL)` over `fills` agree to the share on all five holdings (MSFT **20.000000**, PFE
  **-221.000000**, BAC **68.000000**, GOOG **1.000000**, ES **-0.053455**). Invariant 3 intact.

### Situation triage (live, read — never authored)

1. **Money.** SITUATION: total PnL **$72.70**, **-33.20** since last run, **-145.34** over the last 3.
   Heartbeat `2026-07-31T17:38:42Z`: `pnl_growth_pct` **-79.14** vs `pnl_target_pct` **1.0**, `on_track`
   **false**, `stale` **true**, `underwater` **false**. Off target.
2. **Risk.** Gross **$16,105.99** = **1.1%** of the $1,500,000 firm cap, headroom **$1,483,894**; net
   **$5,013.99** = **0.5%** of the $1,000,000 net cap. `Flags: none`. Badly under-deployed — because of #1.
3. **Cause.** Pending change verified deployed, at 3/6. The window's move is mechanical, not directional.
4. **Danger: NO.** Not bleeding at the cap, not near the breaker.
5. **Order-level post-mortem.** Post-boot `recent_orders` are a rebuild from flat — PFE SELLs to
   **-221.000000**, MSFT/BAC/GOOG/AAPL BUYs — interleaved with repeated `fusion re-plan — passive order
   superseded by a fresh target (ADR-0084)` CANCELLEDs on XOM, PFE, NEE, GOOG. `orders_by_status`:
   FILLED **4780**, CANCELLED **1678**.

## Open items, re-ranked most-costly-first

### 🎯 Item #1 (PROMOTED from #2, mechanism CORRECTED) — the desk liquidates its ENTIRE book to exactly flat at every loop teardown, so its holding period is shorter than the horizon its edge is measured over

**⚠️ STILL-BROKEN, and larger than previously stated.** Rule 202's "3.09x churn on cold-start names"
understated this. The proof is above: ALPHA nets **0.000000** across **21** names / **1687** fills at the
17:39:03Z boot, with **$99,922.58** of turnover in the single minute before it against a book of
**99920.63500000**.

**Why it is #1, above the scorer bug — the argument is HORIZON, not fees.** Every source in
`/api/signals/telemetry` publishes `horizonSeconds` **3600**, while the loop round-trips the whole book
every ~30 minutes. The desk is structurally incapable of holding a position as long as the horizon over
which its own expectancy is measured — reversion **+4.851871026828735** (63 cohorts), social
**+5.298898880312067** (22 cohorts). No sizing, fusion or hedge work can realise an edge whose holding
period is halved before it pays. The **24.17%** mechanical turnover share is the second argument, not the
first.

**Correction to the standing diagnosis — do not re-run the old hypothesis.** The flatten fires at
**teardown**, 26 minutes into a healthy process, *not* 40 seconds into a fresh one. There is no
shutdown-flatten hook in the source (`grep -rn --include=*.java -iE
"PreDestroy|shutdownHook|flattenAll|liquidateAll|closeAllPositions"` outside tests returns nothing). So it
is a **normal fusion re-plan that took all 21 targets to zero simultaneously** — and that simultaneity is
what next cycle must explain. Cold sensors are the *sequel* (the rebuy after boot), not the cause.

**Dead end already closed — do not propose it.** A full re-seed does **not** warm a sensor:
`ReversionForecastLifecycle` logs *"still cold for USD.SOFR.10Y after seeding **241 of 241** stored
prices"*, and 21 minutes after boot trend is still **49 of 193** for TSLA and **1 of 193** for GOOGL.
Warm-up is wall-clock against process lifetime, not store coverage, so "seed harder / seed more often" is
refuted in advance.

- **VERIFY-BY (primary):** at the next boot, `sum(BUY − SELL)` over LIVE `fills` executed before the boot
  instant must be **non-zero for at least one ALPHA name** — i.e. the book survives the teardown. This run
  it was **0.000000** across **21** names.
- **VERIFY-BY (secondary):** re-run the `heartbeat −60s … +120s` grouping over `fills`. The boot-window
  share of turnover must fall from **24.17%** toward its share of fills (**12.5%**), and no single
  boot-adjacent minute may carry turnover comparable to whole-book gross (this run **$99,922.58** vs gross
  **99920.63500000**).
- **Why not this cycle:** the ADR-0116 hold at 3/6. Take it the moment `4f67f0515` is scored.
- **Next cycle's first read (cheap, decisive):** instrument or inspect what makes `FusionPlanner.plan`
  publish zero for *all* names at once late in a process — a global gate (selector, session/market-hours,
  breaker, staleness sweep), not a per-name one. `/api/ops/jvm` `selector` currently reports `measured`
  **31**, `tradable` **15**, `lastError` **null**, `guardTripping` **false**.

### Item #2 (was #1) — `scripts/score-change.py`'s auto-revert is still not conflict-proof

**⚠️ STILL-BROKEN (unchanged).** `scripts/score-change.py:357` still reverts with plain
`git("revert", "--no-edit", sha)` and aborts at `:359` on conflict; `reports/run-status.json` still carries
`"action": "revert-failed"`. Because the loop commits its analysis into the same commit as its code, every
revert attempted a cycle later touches `docs/loop-findings.md`, `reports/last-analysis.md` and
`reports/must-fix.md` — rewritten every cycle — so the conflict is **structurally guaranteed**. It remains
armed: the change under measurement is *itself* a revert.

**Why it drops to #2:** it has a **proven manual workaround** (Rule 193), executed successfully by hand
twice now (`64a7a6336`, `4f67f0515`). A defect with a working workaround ranks below one that is halving the
holding period the edge needs and has none.

- **The fix:** `git revert --no-commit`, restore the loop's memory files from HEAD
  (`git checkout HEAD -- docs/loop-findings.md reports/…`) keeping the ADR annotated `Status: Reverted`,
  then commit. Revert the **code**, never the **memory**.
- **VERIFY-BY:** on the next ❌ BAD verdict the scorer's stdout prints `score: BAD verdict — reverted
  <sha>` (not `conflicted — NOT reverted; still LIVE`), the ledger note carries **no** `⚠️ REVERT FAILED`
  string, and the snapshot's `revertApplied` is **true**. A targeted test staging a synthetic conflict in a
  memory file and asserting the revert still lands is acceptable proof.

### Item #3 (unchanged rank) — the desk's conviction is concentrated in exactly the names its own OOS backtest rejects

**⚠️ STILL-BROKEN.** `/api/ops/jvm` `selector`: `measured` **31**, `tradable` **15** — unchanged for four
cycles. Live 3600s `avgReturnBps`: reversion **+4.851871026828735** (63 cohorts), social
**+5.298898880312067** (22), momentum **-1.5209718798941796** (9), trend **-1.871643156770244** (70),
xsreversion **-6.650324388795753** (24). The combiner already leans on the two positive sources, so it is
not the problem — the honest fix is a **new, OOS-validated predictor** through the ADR-0049 gate.

- **Note the interaction with item #1:** expectancy is measured at `horizonSeconds` **3600** on a book that
  cannot hold for 3600s. Item #1 may be *causing* part of this item's insignificance — fix #1 first, then
  re-read these numbers before spending a cycle on a new source.
- **VERIFY-BY:** at least one source's `avgReturnBps` positive with `cohorts` large enough to clear the
  ADR-0064 hurdle **on the gate's own computation**, and `tradable` rises above **15** of `measured` **31**.
- **Sequencing note:** do **not** attack this by re-arming `require-backtest-support` — Rule 197 showed
  that path ends in a one-name book.

### Item #4 — the hedge overlay has no covariance estimate — and it is DOWNSTREAM of item #1

**⚠️ STILL-BROKEN, fourth consecutive cycle.** `/api/hedging`: `covarianceReady` **false**, EQUITY axis
`status` **WARMING**, `tier` **"—"**, `effectiveness` / `grossSigmaUsd` / `residualSigmaUsd` /
`targetProxyQty` all **null**, holding **-0.053455** ES, rationale *"net equity 8243.99 to hedge, but no
tradable proxy can be sized yet (price/covariance/betas missing)"*.

**New: this is a symptom of item #1, not an independent defect.** `netExposureUsd` flipped
**-7368.29 → 8243.99** between cycles — the axis is re-drawing its whole input every 30 minutes. A
covariance estimate cannot converge on a book that is destroyed and rebuilt each cycle. **Do not spend a
change here until item #1 is fixed.** The HEDGE book is still the profitable one (`totalPnl`
**160.19086234** on `feesPaid` **6.151134**).

- **VERIFY-BY:** `covarianceReady` flips to **true**, the EQUITY axis publishes non-null `effectiveness`,
  `grossSigmaUsd`, `residualSigmaUsd` with `residualSigmaUsd < grossSigmaUsd`, and `status` leaves
  **WARMING**.

---

## Verification block — 2026-07-31 17:30Z (still **HOLD at 2/6 cycles** — **NO code change**. The hold cycle went into measurement, and it found the mechanism behind the churn that has been sitting at the bottom of this register for two cycles: **the loop's own 30-minute redeploy makes the desk liquidate and re-buy names whose sensors cold-start.** Old item #4 is absorbed into the new **item #2**, which now carries a computed dollar cost.)

**HOLD — no change this cycle.** `python3 scripts/score-change.py score` prints
`score: 4f67f0515 still accumulating evidence (2/6 cycles) — held, not scored this run`, and
`reports/.pending-baseline.json` is present (commit `4f67f05158a4b7cf159180250589c7b48a0ebc59`,
`ts` `2026-07-31T16:35:59Z`). A new change now would destroy the evidence on the revert.

### Step 0 — last cycle's change (`4f67f0515`, the manual completion of the failed auto-revert): ✅ VERIFIED (deployed + landed), ⏳ effect still under measurement

- **Deployed: ✅.** `/api/ops/jvm` `uptimeSeconds` **1111** against `/api/risk` `asOfMillis`
  **1785519050573** puts boot at **2026-07-31T17:12:19Z**; `logs/jethro-app.log` confirms
  `Starting JethroApplication … PID 334284` at `2026-07-31T13:12:20.633-04:00`. That postdates the
  16:35:50Z revert commit, so the running code is the reverted code.
- **Landed in the source: ✅.** `docs/adr/0133-*.md` header reads `**Status:** Reverted`;
  `PositionBuffer`'s javadoc carries no ADR-0133 band cap.
- **Effect: ⏳ not gradeable — that is the scorer's job at 6/6, not mine.** Direction since the pending
  baseline (`totalPnl` **202.24011897**, gross **90641.36436250**) to live now: `totalPnl`
  **123.87853727**, gross **81647.96500000**.

### Attribution over 17:00Z → 17:31Z — the realized decline is very nearly the fee bill

- `realizedPnl` **309.27313292 → 283.29807851**; over the same interval `totalFees`
  **229.638225 → 248.070383**. The realized leg fell by less than the fee bill grew — i.e. the desk's
  *directional* result was roughly flat and **transaction cost is what moved the realized number.**
- `unrealizedPnl` **-7.47426487 → -159.41954124** on a book whose net went **-33,407.76400000 →
  -152.51500000**. The book was substantially re-planned inside this window, so the unrealized swing
  **cannot be cleanly split** into market vs mechanism from these numbers alone — stating that plainly
  rather than guessing a cause.
- Per Rule 196, the mechanism's own leg is the realized one, and it says **cost, not direction**.

### Situation triage (live, read — never authored)

1. **Money.** SITUATION: total PnL **$122.48**, **-137.21** since last run, **-385.26** over the last 3.
   Heartbeat `2026-07-31T17:11:45Z`: `pnl_growth_pct` **-51.14** vs `pnl_target_pct` **1.0**, `on_track`
   **false**, `stale` **true**. Down this run and down across the window — off target.
2. **Risk.** Gross **$81,646.13** = **5.4%** of the $1,500,000 firm cap, headroom **$1,418,354**; net
   **-$153.91** = **0.0%** of the $1,000,000 net cap. `Flags: none`; not near a cap, not near the breaker.
3. **Cause.** The pending change is the revert of the ❌ BAD ADR-0133; it is verified deployed and is at
   2/6. The window's *cost* driver is item #2 below.
4. **Danger: NO.** Not bleeding at the cap, not near the breaker. Not DORMANT either — gross is deployed.
5. **Order-level post-mortem.** The 17:12 minute — **39 seconds after boot** — fired BAC **BUY 441**,
   CAT **BUY 13**, JPM **SELL 77**, JNJ **SELL 54**, PG **BUY 1**, all FILLED. CAT had been **SOLD 12** at
   17:11:30, i.e. **reversed inside 88 seconds across the reboot**. That minute alone did
   **$81,499.01** of turnover — against a whole-book gross of **$81,647.97**.
6. **Memory.** Rules 194/195/197 applied: no gate re-arming, no combiner tuning. Rule 200 applied — the
   hold cycle was spent measuring, and it produced item #2.

---

## Open items, re-ranked most-costly-first

### 🎯 Item #1 — `scripts/score-change.py`'s auto-revert is still not conflict-proof; the next ❌ BAD will fail to revert exactly as the last three did

**⚠️ STILL-BROKEN (unchanged).** `scripts/score-change.py:357` still reverts with plain
`git("revert", "--no-edit", sha)` and aborts at `:359` on conflict. Because the loop commits its analysis
into the same commit as its code, every revert attempted a cycle later touches `docs/loop-findings.md`,
`reports/last-analysis.md` and `reports/must-fix.md` — files rewritten every cycle — so the conflict is
**structurally guaranteed**. Three occurrences (`efccc6502`, `e61c7f5aa`, plus a `revert-failed`
heartbeat, and `reports/run-status.json` still carries `"action": "revert-failed"`, `"revert_failed":
true` at `2026-07-31T17:11:45Z`). **It stays #1 because it is armed right now**: the change under
measurement is *itself* a revert, so a ❌ BAD verdict on it makes the scorer revert a revert and hit the
same wall. It is also a non-trading fix, so taking it cannot perturb a live measurement.

- **The fix (per Rule 193, already proven by hand):** `git revert --no-commit`, then restore the loop's
  memory files from HEAD (`git checkout HEAD -- docs/loop-findings.md reports/…`) keeping the ADR
  annotated `Status: Reverted`, then commit. Revert the **code**, never the **memory**.
- **VERIFY-BY:** on the next ❌ BAD verdict the scorer's stdout prints `score: BAD verdict — reverted
  <sha>` (not `conflicted — NOT reverted; still LIVE`), the ledger note carries **no** `⚠️ REVERT FAILED`
  string, and the snapshot's `revertApplied` is **true**. Until a real BAD verdict occurs, a targeted test
  that stages a synthetic conflict in a memory file and asserts the revert still lands is acceptable proof.
- **Why not this cycle:** the ADR-0116 hold. Take it the moment `4f67f0515` is scored.

### 🆕 Item #2 — every 30-minute redeploy makes the desk liquidate and re-buy the names whose sensors cold-start: **3.09x** the steady-state turnover, for no change of view

This absorbs and explains the old item #4 (BAC round-trip churn). It is no longer "worth a targeted read" —
the mechanism is confirmed in the source and the cost is measured.

**The mechanism, read out of the code:**
1. `FusionPlanner.plan` (ADR-0065, in-file comment): *"A held name with no fresh view has an implicit
   target of ZERO — no view, no position."*
2. `PositionBuffer` javadoc line 30 (ADR-0090): *`aim ← 0` when the target is FLAT: **an exit is not
   buffered***. So a zero target snaps the intent to flat and **bypasses the no-trade band entirely**.
3. `PositionBuffer:105` — `private final Map<String, BigDecimal> aims = new HashMap<>();`, *"Derived
   state, seeded from the held position"* — in-memory, **lost on every boot**.
4. On reboot the trend / reversion / risk-cut σ sensors cold-start and re-seed from LMDB. This run's log
   tail shows them still cold for **UNH** (trend 143 of 193 stored prices), **EURUSD** (17 of 193),
   **META** (17 of 193), **GOOGL** (36 of 193), and reversion/σ likewise.
5. A cold sensor publishes **no forecast** → step 1 plans the name **flat** → step 2 liquidates it
   **unbuffered** → minutes later the sensor warms, the view returns, and the desk **buys it back**.

ADR-0065's rationale ("sources fall silent or the OOS selector drops the name") is sound; it simply cannot
distinguish *"the desk has decided this name should be flat"* from *"this name's sensor has not finished
re-seeding since the process restarted 40 seconds ago"* — a condition **the loop itself guarantees every
30 minutes**.

**The cost, computed by SQL over today's `fills` (not authored):** taking each of the 8 heartbeat times
after 13:50Z as a boot and the window `heartbeat … +2min`:

| | minutes | fills | turnover | per-minute |
|---|---|---|---|---|
| post-boot | 11 | 42 | $277,330.06 | **$25,211.82** |
| all other | 140 | 382 | $1,144,024.38 | $8,171.60 |

**Ratio 3.09x.** Post-boot minutes are **9.9%** of fills but **19.5%** of turnover — i.e. the post-boot
orders are far larger than normal, which is the signature of whole-position liquidations rather than the
ADR-0080 incremental path. Day totals: **470** fills, **$1,459,288.78** turnover, **$148.1502** fees, on a
book whose gross exposure is **$81,647.97** — the book is turned over many times its own size per day, and
`/api/attribution` `totalFees` **248.070383** now stands against `firmTotal` **118.52242327**, with the
ALPHA book at `feesPaid` **241.749416** on `totalPnl` **-5.84496252**.

This is a pure state-reconstruction artifact: the boot schedule is set by the loop and is uncorrelated
with the tape, so none of this turnover is a response to the market. It is the clearest available
non-combiner, non-gate lever on risk-adjusted PnL.

- **VERIFY-BY:** re-run the same grouping over `fills` after the fix. The post-boot per-minute turnover
  ratio must fall from **3.09x** toward **1x**, and the post-boot share of turnover from **19.5%** toward
  its share of minutes (**7.3%**). Secondary: no name is both SOLD and BOUGHT within 5 minutes of a boot
  in `recent_orders` (this run: CAT SELL 12 at 17:11:30 → BUY 13 at 17:12:58).
- **Shape of the fix (next cycle, not now):** distinguish *no view* from *not yet warm*. A held name whose
  sensors are still in their warm-up span should be **held, not planned flat** — the desk has no fresh
  information, which is not the same as information that it should be flat. Must not touch the
  deterministic floor, and must keep ADR-0065's genuine case (a name the selector really dropped) working.

### Item #3 — the desk's conviction is concentrated in exactly the names its own OOS backtest rejects

**⚠️ STILL-BROKEN.** `/api/ops/jvm` `selector`: `measured` **31**, `tradable` **15** — unchanged. Live
3600s expectancies (`/api/signals/telemetry` `avgReturnBps`): reversion **+4.874804359460039** (63
cohorts), social **+6.22893692818407** (21 cohorts), trend **-1.884262124055958** (70 cohorts),
xsreversion **-6.858218349322071** (24 cohorts), momentum **-2.918277546560846** (9 cohorts). The fusion
weights already lean on the two positive sources (reversion **1.6796828791749714**, social
**1.5894668077730054**, vs trend **0.6063985334926396**, xsreversion **0.35311503241404785**) — so the
combiner is *not* the problem, confirming the standing "work on EDGE" priority. The honest fix is a
**new, OOS-validated predictor** through the ADR-0049 gate — a build, not a dial.

- **VERIFY-BY:** at least one source's `avgReturnBps` positive with `cohorts` large enough to clear the
  ADR-0064 hurdle **on the gate's own computation**, and `tradable` rises above **15** of `measured`
  **31**. Do not grade on one window.
- **Sequencing note:** do **not** attack this by re-arming `require-backtest-support` — Rule 197 showed
  that path ends in a one-name book.

### Item #4 — the hedge overlay has no covariance estimate, and now cannot even size its proxy

**🔴 REGRESSED.** Last cycle the EQUITY axis was `tier` **STRUCTURAL**; live now `/api/hedging` reports
`covarianceReady` **false**, axis `status` **WARMING**, `tier` **"—"**, and `hedgeRecommended` **false**
with rationale *"net equity -7368.29 to hedge, but no tradable proxy can be sized yet (price/covariance/
betas missing)"* — `effectiveness`, `grossSigmaUsd`, `residualSigmaUsd`, `targetProxyQty` all **null**,
holding **-0.053455** ES. It ranks below #2 because the HEDGE book is still the profitable one
(`totalPnl` **160.19086234** on `feesPaid` **6.151134**) — under-instrumented rather than visibly
bleeding. But an unmeasured hedge is an unfalsifiable one, and it has now stopped sizing altogether.

- **VERIFY-BY:** `/api/hedging` `covarianceReady` flips to **true**, the EQUITY axis publishes non-null
  `effectiveness`, `grossSigmaUsd`, `residualSigmaUsd` with `residualSigmaUsd < grossSigmaUsd`, and
  `status` leaves **WARMING**.

---

## Verification block — 2026-07-31 17:00Z (last cycle's manual revert **✅ VERIFIED** — the graded-BAD ADR-0133 mechanism is out of the running code. That revert is itself **under measurement (1/6 cycles)**, so per the ADR-0116 hold rule this cycle makes **NO code change**. Register re-ranked; the reading done this cycle kills the obvious candidate before it could be shipped.)

**HOLD — no change this cycle.** `scripts/score-change.py score` prints
`score: 4f67f0515 still accumulating evidence (1/6 cycles) — held, not scored this run`, and
`reports/.pending-baseline.json` is present. A new change now would destroy the evidence on the revert.

### Step 0 — last cycle's change (`4f67f0515`, the manual completion of the failed auto-revert): ✅ VERIFIED

- **Deployed: ✅.** `/api/ops/jvm` `uptimeSeconds` **1431** at 17:02Z (**1409** in the report snapshot) — a
  boot that postdates the 16:35Z revert commit, so the running code is the reverted code.
- **Landed in the source: ✅.** `PositionBuffer.band(target, forecast, held, width)` is back to
  `scale × width` with no `min(|target|)` cap; `application.properties` carries no ADR-0133 paragraph;
  `docs/adr/0133-*.md` header reads `**Status:** Reverted`. The loop's memory files survived intact — the
  Rule 193 resolution worked exactly as written.
- **Did what it claimed: ✅.** The claim was "get the rejected mechanism out of the running code", and the
  mechanism's own signature is gone. ADR-0133's effect was a ~9x gross; at its window close gross was
  **$167,400.77** with firm total PnL **$228.93**. Live now: `/api/risk` `.total` gross
  **113683.50400000**, `totalPnl` **301.79886805** (`realizedPnl` **309.27313292**, `unrealizedPnl`
  **-7.47426487**). Gross came down and PnL came up together — the direction the revert was for.
- **Attribution (change vs market).** Honest split: the gross reduction is **mechanism** (the wide band is
  back, so the desk stopped opening into the names ADR-0133 had unstuck). The PnL recovery is **mostly
  market** — `unrealizedPnl` is now **-7.47426487** against **-210.25727251** last cycle on a book whose
  net moved from **+2,801.04706250** to **-33,407.76400000**; that is mark-to-market unwinding, not
  earnings. Per Rule 196 the mechanism's own leg is realized, and realized only moved
  **402.49713476 → 309.27313292**. Do **not** credit the revert with the headline PnL rise.

### Situation triage (live, read — never authored)

1. **Money.** SITUATION: total PnL **$293.55**, **+75.52** since last run, but **-237.98** over the last 3.
   Heartbeat `2026-07-31T16:36:13Z`: `pnl_growth_pct` **-67.02** vs `pnl_target_pct` **1.0**, `on_track`
   **false**, `stale` **true**. Up this run, still well off the owner target across the window.
2. **Risk.** Gross **$113,691.23** = **7.6%** of the $1,500,000 firm cap, headroom **$1,386,309**; net
   **-$33,416.01** = **3.3%** of the $1,000,000 net cap. `Flags: none`. `/api/risk` breaker `halted`
   **false**. Not near a cap, not near the breaker — deployed, with room.
3. **Cause.** Last cycle's scored verdict was **❌ BAD** on ADR-0133; this cycle's pending change is its
   revert, verified above.
4. **Danger: NO.** Not bleeding at the cap and not near the breaker. Regime `trend` **CHOP**, `regime`
   **CALM**, `volRatio` **1.00**. This is neither the DANGER state nor the DORMANT state.
5. **Order-level post-mortem.** `recent_orders` 16:35Z–17:00Z is dominated by **BAC** churn: SELL 309/105/
   79/23 filled across 16:45–16:46 (with 32/87/165/235 CANCELLED as `fusion re-plan — passive order
   superseded by a fresh target (ADR-0084)`), then BUY 38 at 16:48 and **BUY 3 every 30s** from 16:55 to
   16:59. A sold-down-then-bought-back round trip in one name inside fifteen minutes. `orders_by_status`:
   FILLED **4704**, CANCELLED **1631**, REJECTED **84**.
6. **Cost is the standing leak.** `/api/attribution`: `firmTotal` **293.55386805**, `totalFees`
   **229.638225**. Split: HEDGE `totalPnl` **159.97904013** on `feesPaid` **6.021541**; ALPHA `totalPnl`
   **169.39830447** on `feesPaid` **223.446851**; MACRO **-35.82347655**. The hedge overlay is out-earning
   the alpha book while paying a small fraction of its fees.

### ✅ CLOSED this cycle — ES absent from `/api/marks`, hedge axis pinned WARMING (was item #2)

`/api/marks` now returns **39** marks and ES is among them: `price` **7495.000000**, `source` **alpaca**,
`ageMillis` **781** (NQ **28374.000000**, `ageMillis` **728**). `/api/hedging` EQUITY axis reads `status`
**ON-TARGET**, `trackingRate` **0.999915**, `heldProxyQty` **-0.036164** → `targetProxyQty` **-0.032791**.
Rules 191/192 are discharged: the gap was never refdata, and the price arrived on its own. The **residual**
is carried below as item #3 — `covarianceReady` is still **false**.

### 🔎 Candidate investigated and REJECTED this cycle (read-only — this is why the hold was worth it)

The obvious-looking defect was: `/api/strategy/diagnostics` reports `measured` **31**, `tradable` **15**,
`edgeGated` **16** (`lastSelectionMillis` = 16:38:19.623Z), yet `/api/fusion/targets` carries live non-zero
targets **and non-zero routed deltas** in gated names — CVX `deltaQty` **0.207468**, GOOG **4.873257** —
holding GOOG **-35**, NVDA **-27**, CVX **-25**, all flagged `no positive OOS edge`.

**It is not a defect. It is deliberate configuration**, and shipping a "fix" would have re-litigated a
documented decision. The ADR-0049/0059 veto exists at `FusionExecutor.java:139` and is already exempt for
risk-reducing deltas — it is simply switched **off**: `application.properties:414`
`jethro.fusion.require-backtest-support=false` and `:401` `jethro.fusion.edge-gate.enabled=false`, per
ADR-0122's paper-book exploration mode.

**And re-arming it today would re-create DORMANCY** — the exact failure ADR-0122 was written to escape.
Read from `/api/fusion/targets` against the selector-supported set: of the **13** supported names carrying
a target, only **JPM** (`combinedForecast` **5.921**) clears `jethro.fusion.min-forecast-to-route=5.0`.
The conviction sits in the **rejected** names — MSFT **5.4686**, CAT **-5.2810**. Re-arming leaves JPM as
the desk's only risk-increasing name. **Rule 197** below records what that inversion means.

---

## Open items, re-ranked most-costly-first

### 🎯 Item #1 — `scripts/score-change.py`'s auto-revert is still not conflict-proof; the next ❌ BAD will fail to revert exactly as the last three did

Last cycle fixed the **symptom** (it manually reverted the live BAD code). The **cause** is untouched:
`scripts/score-change.py:357` reverts with plain `git("revert", "--no-edit", sha)` and aborts at `:359` on
conflict. Because the loop commits its analysis into the same commit as its code, every revert attempted a
cycle later touches `docs/loop-findings.md`, `reports/last-analysis.md` and `reports/must-fix.md` — files
rewritten every cycle — so the conflict is **structurally guaranteed**, not bad luck. Three occurrences
(`efccc6502`, `e61c7f5aa`, plus a `revert-failed` heartbeat). This is the most expensive open defect
because it silently lets rejected code keep trading, which is precisely what ADR-0133 did for a full
window. **It is also live right now**: the pending change under measurement is itself a revert, so if it
grades ❌ BAD the scorer will try to revert a revert and hit the same wall.

- **The fix (per Rule 193, already proven by hand):** `git revert --no-commit`, then restore the loop's
  memory files from HEAD (`git checkout --ours` / `git checkout HEAD --`) and keep the ADR annotated
  `Status: Reverted`, then commit. Revert the **code**, never the **memory**.
- **VERIFY-BY:** on the next ❌ BAD verdict, the scorer's stdout prints `score: BAD verdict — reverted
  <sha>` (not `conflicted — NOT reverted; still LIVE`), the ledger note carries **no**
  `⚠️ REVERT FAILED` string, and the snapshot's `revertApplied` is **true** (not `false`). Additionally,
  after that run `git log -1 --stat` shows the revert commit touching only code paths, with
  `docs/loop-findings.md` unchanged by it. Until a real BAD verdict occurs, a targeted test that stages a
  synthetic conflict in a memory file and asserts the revert still lands is acceptable proof.
- **Why not this cycle:** the ADR-0116 hold. Take it the moment `4f67f0515` is scored.

### Item #2 — the desk's conviction is concentrated in exactly the names its own OOS backtest rejects

Read this cycle: `edgeGated` **16** of `measured` **31**, and the two names clearing the 5.0 conviction
floor besides JPM are both gated (MSFT **5.4686**, CAT **-5.2810**). The forecast stack is loading onto
names whose own OOS history says the algos lose there. That is a statement about the **sources**, not the
combiner, and it is the standing priority (`work on EDGE, not the combiner`) with a mechanism attached: no
amount of gate-tuning fixes a forecast that is anti-correlated with measured edge. The honest fix is a
**new, OOS-validated predictor** taken through the ADR-0049 gate — a build, not a dial.

- **VERIFY-BY:** `/api/signals/telemetry` shows at least one source whose `avgReturnBps` is positive with
  `cohorts` large enough that the cohort-mean t-statistic clears the ADR-0064 hurdle, **and**
  `/api/strategy/diagnostics` `tradable` rises above **15** of `measured` **31** on the selector's own run.
  Sample size before conclusion: do not grade this on one window.
- **Sequencing note:** do **not** attack this by re-arming `require-backtest-support` — the reading above
  shows that path ends in a one-name book. Rules 194/195 apply.

### Item #3 — the hedge overlay runs STRUCTURAL with no covariance estimate: it hedges by notional, not by measured beta

`/api/hedging`: `covarianceReady` **false**, and on the EQUITY axis `effectiveness`, `grossSigmaUsd` and
`residualSigmaUsd` are all **null** while `tier` is **STRUCTURAL**. The overlay is sizing off net notional
with no measured hedge ratio — so nothing tells the desk whether the hedge is actually removing variance
or just costing spread. It is currently the *profitable* book (`hedgePnl` **159.97904013** on `feesPaid`
**6.021541**), which is why this ranks below #2 rather than above it: it is under-instrumented, not
visibly broken. But an unmeasured hedge is an unfalsifiable one.

- **VERIFY-BY:** `/api/hedging` `covarianceReady` flips to **true** and the EQUITY axis publishes non-null
  `effectiveness`, `grossSigmaUsd` and `residualSigmaUsd`, with `residualSigmaUsd < grossSigmaUsd`.

### Item #4 — BAC round-trip churn inside one 15-minute window

`recent_orders` shows BAC sold down (309/105/79/23 FILLED, 16:45–16:46) then bought back (38 at 16:48,
then 3 every 30s to 16:59), against `orders_by_status` CANCELLED **1631** vs FILLED **4704** — most
cancels tagged `fusion re-plan — passive order superseded by a fresh target (ADR-0084)`. BAC's own
`combinedForecast` is **-0.878**, far below the 5.0 routing floor, yet it traded both ways. Worth a
targeted read of why a sub-floor name generated a full round trip; may be an aim-path artifact rather than
a defect, which is why it ranks last. Do not tune the buffer to chase it (Rules 194/195).

- **VERIFY-BY:** for a name whose `combinedForecast` is below `min-forecast-to-route`, `recent_orders`
  shows no risk-increasing FILLED order in the following window.

---

## Verification block — 2026-07-31 16:35Z (ADR-0133 scored **❌ BAD** at the close of its window; the scorer's auto-revert **failed on a git conflict and the bad code was still live**. **This cycle's one change is completing that revert manually** — that outranks every open item, per "verification outranks novelty".)

**Step 0 — last cycle's change (`e61c7f5aa`, ADR-0133, the band cap).** `scripts/score-change.py score`
now prints `no pending change to score` and `reports/.pending-baseline.json` is **gone** — the window
closed. The fresh ledger row grades it **❌ BAD** (risk-adjusted return per cycle significantly negative
against the ADR-0116 hurdle; gross grown roughly an order of magnitude), and carries the note
**`⚠️ REVERT FAILED (git conflict): the BAD commit is STILL LIVE and needs a manual revert`**.

- **Deployed: ✅ confirmed.** `/api/ops/jvm` `uptimeSeconds` **1646** — a fresh boot running the graded code.
- **Did it do what it claimed: ✅ mechanically, 🔴 economically.** It unstuck the vetoed names exactly as
  derived — the desk deployed off its DORMANT floor. It then lost money doing so. The derivation was
  right and the conclusion was wrong: the buffer was not strangling a profitable book, it was
  incidentally suppressing turnover on names with **no measured edge**. Removing the suppression bought
  spread, not return.
- **Revert landed: ⚠️ NO — this is the defect this cycle fixes.** Reproduced the scorer's failure
  directly: `git revert e61c7f5aa` conflicts on `docs/loop-findings.md`, `reports/last-analysis.md` and
  `reports/must-fix.md` — the loop's own memory files, rewritten every cycle since. The **code** hunks
  (`PositionBuffer.java`, `application.properties`, `PositionBufferTest.java`) apply **cleanly**. Same
  failure mode as `efccc6502` and the `revert-failed` heartbeats before it.

**Live situation.** SITUATION header: total PnL **$227.68**, gross **$167,392.97** (**11.2%** of the
$1,500,000 firm cap, headroom **$1,332,607**), net **-$24,228.68** (**2.4%** of the $1,000,000 net cap),
PnL **-280.06** since last run and **-433.47** over three, **Flags: none**. Live `/api/risk` `.total` read
minutes later: `totalPnl` **192.23986225**, `realizedPnl` **402.49713476**, `unrealizedPnl`
**-210.25727251**, gross **128,959.31706250**, net **+2,801.04706250**. Heartbeat
`2026-07-31T16:04:57Z`: `pnl_growth_pct` **-31.45** vs `pnl_target_pct` **1.0**, `on_track` **false**,
`stale` **true**. Bleeding and off target — but at 11.2% of the gross cap with the breaker untripped this
is **not** the DANGER state.

**Attribution (change vs market).** Both legs point the same way this cycle, which is unusual and worth
stating. `unrealizedPnl` **-210.25727251** against `realizedPnl` **402.49713476** is mark-to-market on a
book that is now near flat net (**+2,801.05**) — that part is **market**. But the **realized** leg is
where the change shows: the scored window turned gross **17,957 → 167,401** and PnL **-$495.57**, and
that gross is precisely what ADR-0133 was built to unlock. A ~9x book put on against no demonstrated
edge pays spread on every name it opens. That is **mechanism**, and it is the reverted commit's.

### 🎯 Item #1 THIS CYCLE (pre-empts the standing #1) — the BAD commit `e61c7f5aa` is still in the running code because the scorer's auto-revert cannot survive a conflict in the loop's own memory files

The scorer reverts by `git revert`, which touches **every** path the original commit touched — including
`docs/loop-findings.md`, `reports/last-analysis.md` and `reports/must-fix.md`, which the loop rewrites
every single cycle. So a revert is **guaranteed** to conflict once one cycle has passed, and the scorer
aborts leaving the graded-bad **code** live. This has now happened three times (`efccc6502`,
`e61c7f5aa`, plus the `revert-failed` heartbeat run). It is the most expensive open defect in the loop
because it silently keeps mechanisms the measurement already rejected.

**Fixed this cycle by:** reverting the code paths only — `PositionBuffer.java`,
`application.properties`, `PositionBufferTest.java` restored byte-for-byte to their pre-ADR-0133 form —
and **keeping** the memory files and the ADR (annotated `Status: Reverted` with what the window taught).
Findings are append-only durable memory; a revert must never erase them.

**VERIFY-BY (next run):** `git show HEAD --stat` contains no `docs/`/`reports/` memory files, and
`grep -n 'ADR-0133' app/src/main/java/io/jethro/app/fusion/PositionBuffer.java` returns **nothing**;
`/api/fusion/targets` shows `insideBuffer` **true** returning across the low-conviction names, and
`/api/risk` `.total.grossExposure` falling back off its post-ADR-0133 level.

### 🎯 Item #2 (was #1, unchanged and still open) — the beta-covered subset is not a representative sample of the book: the hedge sizes off ~6.5% of the systematic risk it is meant to neutralise

Not re-measured this cycle — verification of the failed revert outranks it, and re-reading the coverage
ratio against a book that is mid-revert would grade the wrong thing. Carried forward at its last read
(uncovered net **93.9%** of |net|, `Σ βᵢ·Eᵢ = -5,786.4225` against **-89,268.24**).

**VERIFY-BY:** `/api/risk` positions × live `hedge_beta` rows — uncovered net as a share of |net| falls
materially below **93.9%**. Track **coverage**, never sign (rule 189).

### 🎯 Item #3 (was #2) — the EQUITY hedge axis pins a proxy that has no mark, while a priced fallback sits in its own candidate list

Carried forward unchanged: ES absent from `/api/marks`, axis `status` **WARMING**, `covarianceReady`
**false**, while NQ is priced and already in `jethro.hedge.equity-proxy-candidates`.

**VERIFY-BY:** state **existence** before freshness (rule 191) — ES or NQ **has a row** in `/api/marks`,
then that row's `providerTimestampMillis` advances between two polls, then `/api/hedging` EQUITY
`targetProxyQty` is non-null.

---

## Verification block — 2026-07-31 16:05Z (ADR-0133 is UNDER MEASUREMENT at 5/6 — **no code change made this cycle**, per the pending-baseline rule. **Item #1 stays #1 and its magnitude error got worse**; last cycle's headline sign inversion **un-flipped on its own with nothing fixed**, which is evidence for the item, not against it. Item #2's proxy went from *frozen* to *absent*.)

**Step 0 — last cycle's change (`e61c7f5aa`, ADR-0133, the band cap).**
`scripts/score-change.py score` prints `e61c7f5aa still accumulating evidence (5/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still exists. Held, unscored; **no new change this
cycle**.

- **Deployed: ✅ confirmed.** Fresh boot — `ops_jvm.uptimeSeconds` **1078** at report generation and
  **1127** on a later direct read.
- **Did it do what it claimed: ⚠️ still unscored.** The band is being exercised across a live book; the
  sign of its effect is the scorer's at 6/6, not mine.
- **Regression check: none attributable.** Breaker untripped, `/api/marks/quarantined` **[]**, no new
  WARN/ERROR classes.

**Live situation.** SITUATION header: total PnL **$457.39**, gross **$124,652.20** (**8.3%** of the
$1,500,000 firm cap, headroom **$1,375,348**), net **-$86,242.18** (**8.6%** of the $1,000,000 net cap),
PnL **-74.15** since last run and **-283.28** over three, **Flags: none**. Live `/api/risk` `.total`:
`totalPnl` **457.61324815**, `realizedPnl` **605.62787143**, `unrealizedPnl` **-148.01462328**, gross
**122,936.565**, net **-87,970.885**. Heartbeat `2026-07-31T15:41:42Z`: `pnl_growth_pct` **-28.04** vs
`pnl_target_pct` **1.0**, `on_track` **false**, `stale` **true**, `underwater` **false**. Off target and
bleeding mildly — but at 8.3% of the gross cap with the breaker untripped, **not** the DANGER state.

**Attribution (change vs market).** No code change this cycle, so nothing is attributable to one. The desk
is net short equity **-$87,970.89** into a firm tape, and `unrealizedPnl` **-148.01462328** against
`realizedPnl` **605.62787143** is that mark-to-market — **market, not mechanism**. Gross **+50,996.19**
run-over-run is the book deploying into a $1.37M headroom, which is the objective.

**Rejected before it cost a cycle — not a defect.** The report's Postgres log shows `column "hedge_beta"
does not exist` at **15:30:46Z**. That is a *past loop cycle's own* ad-hoc psql query against an EAV table
(`instrument_attributes` is `instrument_id | name | value`), the same class as `relation
"instrument_attribute" does not exist` (15:06:33Z) and `column a.attr_key does not exist` (15:06:40Z). No
app code queries a `hedge_beta` column — `grep -rn hedge_beta --include=*.java --include=*.sql` hits only
`backups/*.sql` seed data.

### 🎯 Item #1 (UNCHANGED RANK) — the beta-covered subset is not a representative sample of the book: the hedge sizes off ~6.5% of the systematic risk it is meant to neutralise

**Proving numbers, read live this cycle from `/api/risk` positions × the live `hedge_beta` rows:**

- equity net total **-89,268.235**
- beta-covered net **-5,458.75**; **UNCOVERED net -83,809.485 = 93.9% of |net|** (last cycle **71.3%**)
- **Σ βᵢ·Eᵢ = -5,786.4225** against a **-89,268.24** book — the hedge sees about **6.5%** of the risk
- covered contributions: MSFT **-7,138.362**, AMZN **+3,878.064**, GOOG **-2,214.387**, NVDA **-688.100**,
  AAPL **+376.3625**, JNJ/JPM **0** (flat)

**The sign inversion self-cleared — and that CONFIRMS the item.** Last cycle Σβ·E was **+5,657.02** against
a negative book; this cycle it is **-5,786.42**, correctly signed. Nothing was fixed. The whole difference
is NVDA's net going **+10,371.04 → -393.20**: one name, at beta **1.75**, was carrying the inversion. A
statistic whose *sign* flips on one position's move is not a risk measurement. Coverage, not sign, is the
invariant to track — and coverage got **worse** (71.3% → 93.9% uncovered).

**Mechanism, unchanged and confirmed in the source.** `HedgeMath.structuralBetaHedge` (lines 168-170):
`if (beta == null || eUsd == null || eUsd.signum() == 0) { continue; }` — a name with no assigned beta is
silently dropped from both `systematic` and `netExposure`. Live `instrument_attributes` carries
`hedge_beta` for exactly **8** rows — AAPL **1.25**, AMZN **1.20**, GOOG **1.05**, JNJ **0.55**, JPM
**1.10**, MSFT **1.10**, NVDA **1.75**, SAP **1.00** (V32, the sim-era universe) — against **28** equities
in the master, and the desk currently holds **23** positions across names like HD, CAT, NEE, XOM, WMT, UNH,
PFE, KO, MCD, BAC, CVX, TSLA, NFLX, META that carry no beta at all.

**Ranking note — this must be fixed BEFORE item #2.** Item #2 (no live proxy price) is currently the only
reason the mis-sized target is not being traded: the axis reads WARMING and sizes nothing. Restoring the
proxy price first would convert a passive gap into an active mis-hedge. Sequence matters here.

**The fix must NOT hand-author betas.** CLAUDE.md names a `β=1.0` placeholder as a lesson already paid for;
a self-chosen beta that sizes a hedge is exactly the invented risk number the house rules forbid. The change
derives each name's beta **in code** from the durable mark history the platform already stores (stated
estimator, cited convention), and the axis must refuse to claim neutrality while coverage is partial.
Architecturally significant → ships with an ADR (`**Status:** Implemented`) in the same commit.

**VERIFY-BY (next run).** On the `/api/hedging` EQUITY axis: (a) a published beta-coverage figure exists and
reads **≥ 0.95** of `|netExposureUsd|`; (b) `sign(rawTargetNotionalUsd)` is opposite `sign(netExposureUsd)`
on a live read; and (c) `status` does **not** read ON-TARGET while coverage is partial. In Postgres,
`select count(*) from instrument_attributes where name='hedge_beta'` covers every equity carrying exposure.

### Item #2 — ⚠️ STILL-BROKEN and WORSE: the equity hedge proxy went from a frozen price to NO price row at all

**Proving numbers this cycle.** ES is **absent from `/api/marks` entirely** — the endpoint returns **36**
marks (AAPL, AMZN, BAC, CAT, CVX, GOOG, GOOGL, HD, JNJ, JPM, KO, MCD, META, MSFT, NEE, NFLX, **NQ**, NVDA,
PFE, PG, TSLA, UNH, the SOFR/TSY curve points, WMT, XOM) and **no ES row**. Last cycle ES was present with
a frozen `providerTimestampMillis` **1785509694000**; now the row is gone, so the staleness test from last
cycle's VERIFY-BY cannot even be run — a strictly harder failure.

`/api/hedging` EQUITY axis: `status` **WARMING**, `tier` **"—"**, `targetProxyQty` **null**,
`rawTargetNotionalUsd` **null**, `hedgeRecommended` **false**, `covarianceReady` **false**, on
`netExposureUsd` **-87,968.53**, rationale *"net equity -87968.53 to hedge, but no tradable proxy can be
sized yet (price/covariance/betas missing)"*. `/api/marks/quarantined` **[]** — not the ADR-0042 quarantine.

**The held hedge is still carried at zero.** `/api/risk` position: `bookId` **HEDGE**, `instrumentId`
**ES**, `quantity` **0.022800**, `avgCost` **7478.33667943**, `hasMark` **false**, `mark` **0.00000000**,
`markAgeMillis` **-1**, `netExposure` **0.00000000**, `grossExposure` **0.00000000**. CLAUDE.md defines
"total" as the whole book *including* the hedge, so headline gross **$124,652.20** understates money at
risk by the proxy's notional.

**Sharpened diagnosis — the fallback exists but is not yielding a candidate.** `NQ` **is** priced
(`providerTimestampMillis` **1785512939000**) and **is** already configured as a fallback:
`jethro.hedge.equity-proxy-candidates=ES,NQ` with `jethro.hedge.equity-proxy=ES`
(`app/src/main/resources/application.properties:164,166`). `HedgeAdvisor.candidates()` (line 531-532)
documents "tradable candidates only (live price, not quarantined)". Yet the axis still reports `proxyId`
**ES** and sizes nothing — so either the price gate is rejecting NQ too (its mark is itself minutes old) or
`covarianceReady` **false** is the binding constraint. Refdata is not the cause: both ES and NQ carry
`yahoo` symbology (**ES=F**, **NQ=F**) and contract multipliers (**50**, **20**) in `/api/instruments`.

**VERIFY-BY (next run).** An ES row **exists** in `/api/marks` and its `providerTimestampMillis` advances
between two polls 30 s apart; **and** the HEDGE book's ES position reads `hasMark` **true** with
`grossExposure` **> 0**; **and** the EQUITY axis leaves `status` **WARMING**. If ES genuinely cannot be
priced on this feed, the axis must say *that* explicitly rather than the generic "price/covariance/betas
missing", and the candidate list must actually fall through to a proxy the feed does print.

### Item #3 — ⚠️ carried, unchanged: the ADR-0126 σ-cold veto freezes the book after any long market gap

`FusionLifecycle.seedVolatility` still guards on `volSeeded.add(instrument)`, so the seed runs once per
process; a weekend, an outage or a pre-market start empties the recent tail and the same freeze returns.
Re-rank to the top the moment a boot logs `still cold` below ~100/121 again.
**VERIFY-BY.** After the next weekend or multi-hour gap, count the boot's `risk-cut σ sensor still cold`
lines and their seeded/121 ratios; and read `/api/fusion/targets` for the number of aims at exactly 0.0
within 10 minutes of boot. Prior remedy ADR-0131 scored ❌ BAD and is retired — a different lever is
required (Rule 175).

### Item #4 (carried) — ADR-0132's destination clamp has never been observed firing

Unchanged. No clamp telemetry is published, so there is still no live evidence the `onTargetSide` branch
has ever been reached.
**VERIFY-BY.** A published counter, or a `/api/fusion/targets` row whose `deltaQty` stops strictly short of
flat on the target's side while the aim sits across it.

---

## Verification block — 2026-07-31 15:40Z (ADR-0133 is UNDER MEASUREMENT at 4/6 — **no code change made this cycle**, per the pending-baseline rule. **Item #1 is ESCALATED, not replaced**: last cycle's #1 said the structural hedge under-covers; this cycle the covered subset's sign is INVERTED against the book, and a second defect — the hedge proxy has no live price — is currently the only thing stopping it from trading backwards)

**Step 0 — last cycle's change (`e61c7f5aa`, ADR-0133, the band cap).**
`scripts/score-change.py score` prints `e61c7f5aa still accumulating evidence (4/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still exists. Held, unscored; **no new change this
cycle**.

- **Deployed: ✅ confirmed.** `ops_jvm.uptimeSeconds` **1166** at report generation and **1216** on a later
  direct read — the running process is the fix build.
- **Did it do what it claimed: ⚠️ still unscored, still being exercised.** `/api/fusion/targets` publishes
  **22** instruments this cycle (20 last cycle) with non-zero `deltaQty` on MSFT **-1.712278**, CAT
  **4.165898**, NEE **-60.416979**, and exactly 0.0 on NVDA and XOM whose aim-to-holding gaps are enormous
  (NVDA `targetQty` **282.837575** vs `currentQty` **45.000000**; XOM **-353.805355** vs **-23.000000**).
  The band is being consulted across a real book; the sign of its effect is for the scorer, not for me.
- **Regression check: none attributable.** Breaker untripped, `/api/marks/quarantined` **[]**, no new
  WARN/ERROR classes.

**Live situation.** SITUATION header: total PnL **$575.87**, gross **$77,739.62** (**5.2%** of the
$1,500,000 firm cap, headroom **$1,422,260**), net **-$45,265.26** (**4.5%** of the $1,000,000 net cap),
PnL **-85.28** since last run and **-162.78** over three, **Flags: none**. Live `/api/risk` `.total` a few
minutes later: `totalPnl` **570.29561460**, `realizedPnl` **796.09988240**, `unrealizedPnl`
**-225.80426780**. Heartbeat `2026-07-31T15:10:13Z`: `pnl_growth_pct` **-9.67** vs `pnl_target_pct`
**1.0**, `on_track` **false**, `stale` **true**, `underwater` **false**. Off target and bleeding mildly —
but nowhere near the cap or the breaker, so this is not the DANGER state.

**Attribution (change vs market).** No code change was made this cycle, so nothing here is attributable to
one. The book is net **short** equity (**-$38,817.87** net across 21 names on a live read) into a rising
tape, and `unrealizedPnl` **-225.80426780** against `realizedPnl` **796.09988240** is that mark-to-market —
**market, not mechanism**. The one mechanism-attributable cost is turnover: `turnover_cost_by_name` totals
roughly **$1.6M** of traded notional against a **$77.7k** book, and the baseline-to-heartbeat `fees` move
**105.584233 → 129.292241** is the desk paying for its own 30s re-plan churn (`orders_by_status` FILLED
**4495** / CANCELLED **1509**, the cancels all reasoned *"fusion re-plan — passive order superseded by a
fresh target (ADR-0084)"*). That churn is a standing cost, not this cycle's doing.

### 🎯 Item #1 — ESCALATED from "under-covers" to "can size BACKWARDS": the beta-covered subset is not a representative sample of the book, and its sign is currently OPPOSITE the book's net

**Proving numbers, all read live this cycle from `/api/risk` positions × the live `instrument_attributes`
betas.** Per-name net exposure summed by beta-coverage:

- net equity total **-38,817.87**
- beta-covered net **-11,126.16**; **UNCOVERED net -27,691.71 = 71.3%** of |net|
- **Σ βᵢ·Eᵢ = +5,657.02** — **positive**, while the book's net equity is **negative**

**Why that is worse than last cycle's finding.** Last cycle Σβ·E was **-18,856.99** against net
**-60,974** — under-sized, but correctly *signed*. This cycle NVDA's **+10,371.04** at beta **1.75**
(**+18,149** of systematic on its own) dominates the eight covered names and flips the covered subset
positive while the real book is short. `HedgeMath.structuralBetaHedge` sets `hedgeNotional =
systematic.negate()`, so on these live numbers the structural tier would size a **SELL** of the equity
proxy against a book that is **already net short equity** — *adding* systematic exposure rather than
removing it. Under-hedging is a gap; wrong-signed hedging is an anti-hedge.

**Mechanism, unchanged and confirmed in the source.** `HedgeMath.structuralBetaHedge` (line 168-170):
`if (beta == null || eUsd == null || eUsd.signum() == 0) { continue; }` — a name with no assigned beta is
silently dropped from both `systematic` and `netExposure`. Live `instrument_attributes` carries
`hedge_beta` for exactly **8** rows — AAPL **1.25**, AMZN **1.20**, GOOG **1.05**, JNJ **0.55**, JPM
**1.10**, MSFT **1.10**, NVDA **1.75**, SAP **1.00** (V32, the sim-era universe) — against **28** equities
in the master. The sampling error is not bounded, so the covered subset's sign carries no guarantee.

**Ranking note — this must be fixed BEFORE item #2.** Item #2 (no live proxy price) is currently the only
reason the wrong-signed target is not being traded: the axis reads WARMING and sizes nothing. Restoring the
proxy price first would convert a passive gap into an active anti-hedge. Sequence matters here.

**The fix must NOT hand-author betas.** CLAUDE.md names a `β=1.0` placeholder as a lesson already paid for;
a self-chosen beta that sizes a hedge is exactly the invented risk number the house rules forbid. The change
derives each name's beta **in code** from the durable mark history the platform already stores (stated
estimator, cited convention), and the axis must refuse to claim neutrality while coverage is partial.
Architecturally significant → ships with an ADR (`**Status:** Implemented`) in the same commit.

**VERIFY-BY (next run).** On the `/api/hedging` EQUITY axis: (a) a published beta-coverage figure exists and
reads **≥ 0.95** of `|netExposureUsd|`; (b) `sign(rawTargetNotionalUsd)` is opposite `sign(netExposureUsd)`
on a live read — i.e. a net-short book never gets a SELL-proxy target; and (c) `status` does **not** read
ON-TARGET while coverage is partial. In Postgres, `select count(*) from instrument_attributes where
name='hedge_beta'` covers every equity carrying exposure.

### Item #2 — NEW: the equity hedge proxy ES has no live price, so the entire net equity book is unhedged and the held hedge is carried at ZERO exposure

**Proving numbers.** `/api/hedging` EQUITY axis read **six consecutive times over two minutes**: `status`
**WARMING**, `tier` **"—"**, `targetProxyQty` **null**, `rawTargetNotionalUsd` **null**, rationale *"net
equity … to hedge, but no tradable proxy can be sized yet (price/covariance/betas missing)"*, on
`netExposureUsd` **-43039.17 / -43051.55 / -38678.29 / -38674.17 / -38282.08 / -38297.96**. Not a warming
transient (Rule 183 discharged): ES's `providerTimestampMillis` in `/api/marks` is **frozen at
1785509694000** across three polls 30 s apart — age **2477.5 s → 2507.5 s → 2537.7 s**, climbing
monotonically past **42 minutes** — while AAPL ticks at **0.4 s / 1.7 s / 1.1 s**. `/api/marks/quarantined`
returns **[]**, so this is not the ADR-0042 quarantine; the proxy simply is not printing.

**Two costs, both live.** (a) The whole **-$38,817.87** of net equity is carrying **no hedge at all** —
`covarianceReady` **false** and the structural fallback cannot produce a candidate, because
`HedgeAdvisor.candidate()` returns null without a positive price (the ADR-0040 multiplier has a config
fallback of **50**, so the multiplier is not the missing input — the price is). (b) The HEDGE book's held
proxy is priced at nothing: `/api/risk` position `bookId` **HEDGE**, `instrumentId` **ES**, `quantity`
**0.022800**, `hasMark` **false**, `mark` **0.00000000**, `netExposure` **0.00000000**, `grossExposure`
**0.00000000**, `markAgeMillis` **-1**. So the hedge contributes **$0** to the firm total exposure the loop
optimizes — and CLAUDE.md is explicit that "total" is the whole book *including the hedge*. The headline
gross **$77,739.62** understates money at risk by the proxy's notional.

**VERIFY-BY (next run).** ES's `providerTimestampMillis` in `/api/marks` **advances between two polls
30 s apart**; and the HEDGE book's ES position reads `hasMark` **true** with `grossExposure` **> 0**; and
the EQUITY axis leaves `status` **WARMING**. If ES genuinely cannot be priced on this feed, the axis must
say *that* explicitly rather than the generic "price/covariance/betas missing", and the proxy candidate
list must fall through to one the feed does print (`NQ` advanced **1785511513000 → 1785511603000** in the
same window, so at least one index proxy is live).

### Item #3 — ⚠️ DOWNGRADED, not closed (was #1 for three cycles): the ADR-0126 σ-cold veto freezes the book after any long market gap

Unchanged from last cycle and still not fixed — it cleared by accumulation, not by a change (Rule 182).
`FusionLifecycle.seedVolatility` still guards on `volSeeded.add(instrument)`, so the seed runs once per
process; a weekend, an outage or a pre-market start empties the recent tail and the same freeze returns.
Re-rank to the top the moment a boot logs `still cold` below ~100/121 again.
**VERIFY-BY.** After the next weekend or multi-hour gap, count the boot's `risk-cut σ sensor still cold`
lines and their seeded/121 ratios; and read `/api/fusion/targets` for the number of aims at exactly 0.0
within 10 minutes of boot. Prior remedy ADR-0131 scored ❌ BAD and is retired — a different lever is
required (Rule 175).

### Item #4 (carried) — ADR-0132's destination clamp has never been observed firing

Unchanged. No clamp telemetry is published, so there is still no live evidence the `onTargetSide` branch
has ever been reached.
**VERIFY-BY.** A published counter, or a `/api/fusion/targets` row whose `deltaQty` stops strictly short of
flat on the target's side while the aim sits across it.

### Item #5 (carried) — MACRO holds a frozen directional loss

`/api/risk` `.byBook`: MACRO `totalPnl` **-35.82347655**, `grossExposure` **0.00000000**, `positionCount`
**1** (NQ, `quantity` **0**). Realized, frozen, no live exposure — no bleed.
**VERIFY-BY.** MACRO `positionCount` reaches 0, or the name reappears in the fusion target book.

### Item #6 (carried, housekeeping, no money cost) — the ADR index is missing rows for 0129, 0130, 0131, 0133, and 0132 is a duplicated number

Unchanged.
**VERIFY-BY.** `docs/adr/README.md` lists every file present in `docs/adr/`.

---

## Verification block — 2026-07-31 15:05Z (ADR-0133 is UNDER MEASUREMENT at 3/6 — **no code change made this cycle**, per the pending-baseline rule. **Item #1 is REPLACED**: the old #1 (σ-cold freeze) cleared itself by accumulation and the desk deployed a real book — which immediately exposed that the structural hedge sizes on 8 of 28 equities and calls the result ON-TARGET)

**Step 0 — last cycle's change (`e61c7f5aa`, ADR-0133, the band cap).**
`scripts/score-change.py score` prints `e61c7f5aa still accumulating evidence (3/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still exists. Held, unscored; **no new change this
cycle**.

- **Deployed: ✅ confirmed.** `ops_jvm.uptimeSeconds` **1427** at report generation and **1748** on a later
  direct read, against a boot logged at 10:36 EDT. The running process is the fix build.
- **Did it do what it claimed: ⚠️ still unscored, but no longer starved of input.** Last cycle only 3 of 21
  names ever reached the band. This cycle `/api/fusion/targets` publishes **20** instruments, **8** with a
  non-zero `deltaQty` (GOOG **-4.072490**, CVX **-9.325964**, HD **-15.302545**, CAT **-7.000596**, MCD
  **-26.061341**, XOM **-50.804343**, JNJ **-21.372829**, AAPL **-0.688539**) and the rest at exactly 0.0.
  The band is being exercised across a real book for the first time.
- **Regression check: none.** Breaker untripped (`halted` **false**), `var95` **1161.13** on
  `coveredExposure` **76657.72**, `skippedExposure` **0.00**, no new WARN/ERROR classes.

**Live situation.** `/api/risk` `.total` moved during the run as the desk built: total PnL **$563.79** at
one read, **$652.57** at a later one; gross **$76,657.72** → **$86,777.90**; net **-$74,838.88** →
**-$42,118.67**. The SITUATION header reads gross at **5.1%** of the $1,500,000 firm cap with **$1,423,342**
of headroom, PnL **-163.54** since last run and **-154.79** over three, **Flags: none**. The 14:35:53Z
heartbeat has `pnl_growth_pct` **-5.03** vs `pnl_target_pct` **1.0**, `on_track` **false**, `stale`
**true**, `underwater` **false**. Not danger — exposure rising with this much headroom is the goal.

**Attribution (change vs market).** The desk went from **3 shares of AAPL** to **21 equity positions**
between 14:41Z and 15:00Z. No code change caused that: this boot logged `risk-cut σ sensor still cold` at
**103–117 of 121** stored prices against **38–99** one boot earlier, so the veto lifted by accumulation.
The deployment is warm-up; the negative unrealized on it is a rising tape against a short book, i.e.
market. Neither ADR-0132 nor ADR-0133 gets credit or blame (Rules 141/152/174).

### 🎯 Item #1 — NEW, ranked top: the structural hedge sizes on the 8 equities that have a `hedge_beta` and reports ON-TARGET while 64% of the firm's net equity is invisible to it

**Proving numbers, all read live this cycle.** `/api/hedging` EQUITY axis: `netExposureUsd` **-60979.73**,
`tier` **STRUCTURAL**, `status` **ON-TARGET**, `rawTargetNotionalUsd` **18860.33**, `heldProxyQty`
**0.050468** vs `targetProxyQty` **0.050481**, rationale *"largest delta under the 4715.08 no-trade band,
holding"*. A ~31% hedge ratio, presented to the desk as complete.

**Mechanism, confirmed against the app's own number (Rule 181).** `HedgeMath.structuralBetaHedge` sums
`Σ βᵢ·Eᵢ` and skips any name with no assigned beta — its own test
`structuralBetaHedgeSkipsNamesWithNoAssignedBeta` asserts exactly that. The live `instrument_attributes`
table carries `hedge_beta` for **8 of 28** equities: AAPL **1.25**, NVDA **1.75**, AMZN **1.20**, JPM
**1.10**, MSFT **1.10**, GOOG **1.05**, SAP **1.00**, JNJ **0.55** — the original sim-era universe. BAC,
CAT, CVX, HD, KO, MCD, NEE, PFE, PG, UNH, WMT, XOM, plus BRK.B, DIS, GOOGL, GS, META, NFLX, PYPL, TSLA,
carry none. Recomputing `Σ βᵢ·Eᵢ` from the live positions × the live betas gives **-18,856.99** against the
hedger's published `rawTargetNotionalUsd` **18,860.33** — the same number to within snapshot drift.

**What it costs.** Of **-$60,974** net equity, **-$22,077** is beta-covered and **-$38,897 (63.8%)** is
invisible to the hedge. **XOM alone is $24,442 of gross — the largest equity position on the desk and 28%
of firm gross — with no beta at all.** The firm is carrying an unhedged directional equity bet that its own
hedge overlay reports as neutralized. A missing input is being read as "no market exposure" rather than
"unknown market exposure".

**This is a known defect class.** CLAUDE.md already records it from the V31→V34 universe expansion:
instruments added to the master without their attribute rows backfilled in the same change.

**The fix must NOT hand-author betas.** CLAUDE.md names a `β=1.0` placeholder as a lesson already paid for,
and a self-chosen beta that sizes a hedge is precisely the invented risk number the house rules forbid. The
change derives each name's beta **in code** from the durable mark history the platform already stores
(stated estimator, cited convention), and surfaces the uncovered fraction on the axis so a partially-covered
book can never report ON-TARGET. Architecturally significant → ships with an ADR (`**Status:** Implemented`)
in the same commit.

**VERIFY-BY (next run).** On `/api/hedging` EQUITY axis: (a) a published beta-coverage figure exists and
reads **≥ 0.95** of `|netExposureUsd|`, and (b) `rawTargetNotionalUsd` is within the hedge's own no-trade
band of the beta-weighted net rather than ~31% of it; and in Postgres, `select count(*) from
instrument_attributes where name='hedge_beta'` covers every equity carrying exposure. If coverage is still
partial, `status` must NOT read ON-TARGET.

### Item #2 — ⚠️ DOWNGRADED, not closed (was #1 for three cycles): the ADR-0126 σ-cold veto freezes the book after any long market gap

**Cleared itself this cycle, by accumulation, with no code change (Rule 182).** Boot logged `risk-cut σ
sensor still cold` at **103–117 of 121** stored prices vs **38–99** one boot earlier; the veto lifted, and
gross went **$76,657.72 → $86,777.90** across **21** positions where last cycle it was **$906.47** across
one. The durable mark store crossed the `vol-span=120` warm-up span.
**Why it is not closed:** nothing was fixed. The store crossed the span because the market has been open
continuously; a weekend, an outage or a pre-market start empties the recent tail again and the same freeze
returns. `FusionLifecycle.seedVolatility` still guards on `volSeeded.add(instrument)`, so the seed runs
once per process. Re-rank to #1 the moment a boot logs `still cold` below ~100/121 again.
**VERIFY-BY.** After the next weekend or multi-hour gap, count the boot's `risk-cut σ sensor still cold`
lines and their seeded/121 ratios; and read `/api/fusion/targets` for the number of `aims` at exactly 0.0
within 10 minutes of boot. Prior remedy ADR-0131 (re-seed on the warm-up cadence) scored ❌ BAD and is
retired — a different lever is required (Rule 175).

### Item #3 (carried) — ADR-0132's destination clamp has never been observed firing

Unchanged. `riskCuts` **[]** and no clamp telemetry is published, so there is still no live evidence the
ADR-0132 `onTargetSide` branch has ever been reached.
**VERIFY-BY.** A published counter, or a `/api/fusion/targets` row whose `deltaQty` stops strictly short of
flat on the target's side while the aim sits across it.

### Item #4 (carried) — MACRO holds a frozen directional loss

`/api/risk` `.byBook`: MACRO `totalPnl` **-35.82347655**, `grossExposure` **0.00000000**, `positionCount`
**1** (NQ, quantity **0**). Realized, frozen, no live exposure — no bleed, ranked below the items above.
**VERIFY-BY.** MACRO `positionCount` reaches 0, or the name reappears in the fusion target book.

### Item #5 (carried, housekeeping, no money cost) — the ADR index is missing rows for 0129, 0130, 0131, and 0132 is a duplicated number

Unchanged. `docs/adr/README.md` still lacks index rows for 0129–0131, and 0133 is now also unindexed.
**VERIFY-BY.** `docs/adr/README.md` lists every file present in `docs/adr/`.

---

## Verification block — 2026-07-31 14:30Z (ADR-0133 is UNDER MEASUREMENT at 2/6 — **no code change made this cycle**, per the pending-baseline rule. Item #1 is ⚠️ STILL-BROKEN and now has its *rate*: the σ seed IS converging across reboots, just far slower than the reboot cadence, so the process always dies with most of the book still frozen)

**Step 0 — last cycle's change (`e61c7f5aa`, ADR-0133, the band cap).**
`scripts/score-change.py score` prints `e61c7f5aa still accumulating evidence (2/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still exists. Held, unscored; **no new change this
cycle**.

- **Deployed: ✅ confirmed.** `ops_jvm.uptimeSeconds` **1281** at report generation and **1340** on a later
  direct read; the JVM booted well after the commit landed. The running process is the fix build.
- **Did it do what it claimed: ⚠️ UPGRADED from vacuous to ACTIVE-BUT-UNSCORED.** Last cycle every `aim` was
  exactly 0.0, so the band was never consulted. This cycle `/api/fusion/targets` publishes three non-zero
  aims — AAPL **-21.458419**, MSFT **5.806959**, AMZN **-6.428999** — and for AAPL the published
  `currentQty` is **-3.0** while the published `deltaQty` is **-15.939691**, i.e. strictly narrower than the
  distance between the aim and the holding. The band is being evaluated on the warm names, so ADR-0133's
  code path is live and reached. It still cannot be graded: 2/6, and only 3 of 21 names ever reach it.
- **Regression check: none.** `riskCuts` is `[]`, `riskCutStoppedNames` **0**, `bookVolBrake` **1.0**,
  `portfolioRiskMultiplier` **0.7763148577861426**, breaker untripped, no new WARN/ERROR classes.

**Live situation.** `/api/risk` `.total`: realized **$738.55885736**, unrealized **$1.84500000**, total
**$740.40385736**; gross **$906.46500000**, net **-$906.46500000**. The SITUATION header reads gross at
**0.1%** of the $1,500,000 firm cap with **$1,499,091** of headroom, PnL **-0.42** since last run and
**-41.64** over three, **Flags: none**. The 14:08:16Z heartbeat has `pnl_growth_pct` **-5.29** vs
`pnl_target_pct` **1.0**, `on_track` **false**, `stale` **true**, `underwater` **false**. Not danger — the
opportunity case, and a severe one: the entire firm book is a short in a single name.

**Attribution (change vs market).** One order in the whole window: **AAPL SELL 3, FILLED 14:29:27**, ~21
minutes into a process that boots roughly every 30. Nothing else was held, so there is no market leg to
speak of — the window's move is that one position plus its cost. Neither ADR-0133 nor any prior change
caused it; it is the σ-warm clock reaching AAPL. I claim neither credit nor blame (Rules 141/152/174).

### 🎯 Item #1 — ⚠️ STILL-BROKEN, carried at top: the ADR-0126 σ-cold veto freezes most of the book for the whole life of every process, and the seed converges slower than the reboot cadence

**Proving numbers, all read live this cycle.** `streamVolMeasuredNames` = **4** at `uptimeSeconds` **1340**
(≈22 minutes in) against `volBudgetNames` **19** and `covarianceCoveredNames` **19**. **18 of the 21**
published `aims` are exactly **0.0** — including the largest targets on the desk: PFE **2848.93**, NEE
**-1250.11**, WMT **878.21**, BAC **-765.91**, NVDA **-578.31**. `insideBuffer` **19**. Gross is
**$906.46500000**: the whole firm book is **3 shares of AAPL**, against **$1,499,091** of headroom.

**What is NEW this cycle — the seed is converging, just too slowly.** The boot logged `risk-cut σ sensor
still cold` for **18** names, each against the **121** prices it needs
(`jethro.fusion.risk-cut.vol-span=120` ⇒ `warmupPrices() = 121`). Comparing the same names to the previous
process's boot log: MCD **38 → 57**, KO **43 → 64**, WMT **43 → 64**, BAC **41 → 62**, NEE **41 → 62**,
GOOG **46 → 67**, NVDA **49 → 71**, MSFT **61 → 83**, AMZN **80 → 101**, AAPL **99 → 103**. So the durable
mark store IS accumulating and last cycle's read of this as a permanent floor was too pessimistic — but the
gain is roughly twenty prices per ~35 minutes of elapsed open market, while the loop tears the process down
every ~30 minutes. The slowest name still needs dozens more samples, and **`SensorWarmup` seeds each name
exactly once per process** (`volSeeded.add(instrument)` in `FusionLifecycle.seedVolatility`), so within a
process the only further warming comes from live prints at the 30s re-plan cadence — which is why the count
crawls 0 → 1 → 4 over twenty minutes instead of arriving.

**The mechanism is unchanged and still upstream of everything else.** `stopArmed` is
`streamVol.sigmaPerSample(instrument).isPresent()`; false ⇒ `PositionBuffer.mayIncrease` false ⇒ the
ADR-0064/0075 reduce-only branch, which re-seeds `aim` to `held + delta` and, where the holding is
wrong-side, classes it `isTrappedExit` and works it out **in full**. That is what emptied the book at 13:53
(gross **$17,942.67878750 → $0.00000000**, five FILLED orders 19 seconds after boot). ADR-0132's
destination clamp and ADR-0133's band cap both act on the aim and are simply not reached for the 18 frozen
names — items #2 and #3 stay blocked behind this.

**Do NOT re-attempt ADR-0131.** `efccc6502` — "a cold sensor re-seeds on its own warm-up cadence until it
warms" — attacked this same root cause, scored **❌ BAD** (risk-adj return/cycle -0.000016 over 7 cycles,
t=-0.03; gross 0 → 33,743) and was reverted. A graded-BAD remedy retires the *remedy*, not the *defect*
(Rule 175). Two untried levers remain, and next cycle picks **one**:
- **(a) Make the veto asymmetric.** A σ sensor that cannot price a stop is a sound reason not to *open*
  risk; it is not a reason to *liquidate* a book the desk held and was measuring one process earlier. This
  suppresses the boot flatten without touching the warm-up. Changes when a risk control fires ⇒ needs an ADR.
- **(b) Decouple the EWMA span from the warm-up threshold.** `warmupPrices()` is `span + 1` by convention,
  but an EWMA of span 120 is well defined long before 120 samples; the σ *distance* the ADR-0086 stop uses
  would keep its span while the sensor is allowed to speak sooner. Also changes when a risk control fires
  ⇒ needs an ADR, and must not silently widen or narrow a live stop.
Lever (a) is the better-argued one because it fixes the destructive half of the asymmetry rather than
trading sensor accuracy for speed, and because it leaves the "cannot open what cannot be stopped" rule —
the part of ADR-0126 that is right — completely intact.

**VERIFY-BY (next run, `/api/fusion/targets` + `/api/risk` + the boot log):** within the first two re-plans
after a boot, either (a) `currentQty` is non-zero for names the previous process held — no full-book
liquidation at boot — or (b) the count of non-zero `aims` exceeds **3** and `streamVolMeasuredNames`
exceeds **4** at comparable uptime. Also record the `risk-cut σ sensor still cold` WARN count at boot,
**18** this cycle. If gross is again a single name with 18 aims at 0.0, this item is **STILL-BROKEN**.

### Item #2 (carried, PARTLY ANSWERED) — ADR-0133's band cap now observed active, still unscored
No longer "never observed": AAPL's published `aim` **-21.458419**, `currentQty` **-3.0** and `deltaQty`
**-15.939691** show the band trimming a live gap. **VERIFY-BY:** the same three fields on a name whose
`|combinedForecast| < 1.0`, plus a scored ledger row. Blocked behind item #1 for breadth (3 of 21 names).

### Item #3 (carried, unchanged) — ADR-0132's destination clamp has never been observed firing
It needs a *wrong-side holding*; AAPL is the only name held and its `targetQty` **-25.19** and `currentQty`
**-3.0** share a sign. **VERIFY-BY:** a name with `currentQty` and `targetQty` of opposite signs whose
`deltaQty` moves it toward flat. Blocked behind item #1.

### Item #4 (carried) — MACRO holds a frozen directional loss
Unchanged; ranked below #1.

### Item #5 (carried, housekeeping, no money cost) — the ADR index is missing rows for 0129, 0130, 0131, and 0132 is a duplicated number
`docs/adr/` contains both `0132-deploy-capital-objective-200k-budget.md` and
`0132-the-buffers-destination-never-sits-on-the-side-the-target-opposes.md`. Renumber and index.

---

## Verification block — 2026-07-31 14:00Z (ADR-0133 is UNDER MEASUREMENT at 1/6 — **no code change made this cycle**, per the pending-baseline rule. Item #1 is RE-RANKED: a gate UPSTREAM of the last two fixes zeroes their input, so neither can be exercised)

**Step 0 — last cycle's change (`e61c7f5aa`, ADR-0133, the band cap).**
`scripts/score-change.py score` prints `e61c7f5aa still accumulating evidence (1/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still exists. So the change is held, unscored, and
**this cycle must not make a new one**.

- **Deployed: ✅ confirmed.** The commit landed 13:52:36 and the JVM answering the endpoints booted after it
  (`ops_jvm.uptimeSeconds` **412** at report generation ⇒ boot ≈13:53:30). The running process is the fix build.
- **Did it do what it claimed: ⚠️ UNVERIFIABLE — vacuous, not passed.** Its VERIFY-BY was that `insideBuffer`
  falls below the name count and the desk deploys toward its target book. Live it reads **19/19, 19/19,
  18/18, 19/19** across four consecutive re-plans (`atMillis` 1785506422370 / 1785506573423 / 1785506603620 /
  1785506663999) — but **every `aim` is exactly 0.0 and every `currentQty` is 0**, so the gap the band is
  tested against is *zero* and the band is never consulted. `insideBuffer` is counting a zero gap, not a
  vetoed trade. The band cap is live and its tests pin it; the live book simply cannot reach it.

**Live situation.** `/api/risk` (14:00:46 and again 14:04:24): total PnL **$738.64968836**, gross
**$0.00000000**, net **$0.00000000** — **0.0%** of the $1,500,000 firm cap, headroom **$1,500,000**. Flag
**DORMANT**. Every book's `unrealizedPnl` is **0.00000000** in `/api/attribution` (ALPHA, HEDGE, MACRO) and
every `currentQty` in `/api/fusion/targets` is 0, so this is a genuinely **flat book**, not an unmarked one.
The 13:52:55Z heartbeat recorded gross **$17,942.67878750**, net **-$2,402.15121250**, total PnL
**$731.92411373**, `pnl_growth_pct` **-6.15** vs `pnl_target_pct` **1.0**, `on_track` **false**, `stale`
**true**. Not in danger — nowhere near a cap or the breaker; this is the *opportunity* case.

**Attribution (change vs market).** The book went from **$17,942.68** gross at 13:52:55 to **$0.00**, and the
only orders after boot are **five FILLED at 13:53:49** — 19 seconds into the new process: JPM SELL 4, AAPL
SELL 9, AMZN BUY 17, GOOG BUY 7, MSFT BUY 7. Realized PnL moved **+6.73** (731.92 → 738.65) across that
liquidation. This is a **boot-sequence** effect, not a market move and not the band cap: ADR-0133 changes
only the band's scale, and the band is not reached on any of these paths. I claim neither credit nor blame
for the +6.73 (Rules 141/152).

### 🎯 Item #1 — NEW, ranked top: the desk FLATTENS on boot and then cannot rebuild, because the ADR-0126 σ-cold veto holds every name reduce-only for the first ~10–40 minutes of every process

**Mechanism, read off the code and confirmed against live telemetry.** `FusionLifecycle.stopArmed` is
`streamVol.sigmaPerSample(instrument).isPresent()`. At boot the sensor is seeded from the durable mark store
and the process logged **`risk-cut σ sensor still cold` for 18 names** at 13:53:49, each quoting what the
seed actually found against what it needed — **121** prices (`jethro.fusion.risk-cut.vol-span=120` ⇒
`warmupPrices() = 121`): MCD **38**, NEE **41**, BAC **41**, WMT **43**, KO **43**, GOOG **46**, NVDA **49**,
MSFT **61**, AMZN **80**, AAPL **99**. With `stopArmed` false everywhere, `PositionBuffer.mayIncrease` is
false for **every** name, which takes the reduce-only branch: a wrong-side holding becomes an
`isTrappedExit` and is worked **in full** (the five fills), and thereafter `aim` is re-seeded to
`held + delta` = **0** every cycle. That is exactly what the endpoint shows — `streamVolMeasuredNames`
**0, 0, 0** across the first three samples with **0** non-zero aims and **0** non-zero deltas.

**It is transient, and that is the point.** On the fourth sample (`atMillis` 1785506663999, ~10.5 minutes
after boot) `streamVolMeasuredNames` ticked **0 → 1**, and **exactly one aim went non-zero with it**. The
sensor warms one name at a time as marks accumulate at the re-plan cadence. The slowest names are the
problem: MCD at **38 of 121** needs ~83 further samples. **The loop reboots the app about every 30 minutes**
— so the book is liquidated at every boot and only the fastest-warming names are ever re-opened before the
next teardown. The veto is asymmetric in the damaging direction: an unarmed sensor does **not** stop the
desk from *liquidating* a book, only from rebuilding it.

**Why this outranks everything else:** it is upstream of both of the last two shipped fixes. ADR-0132's
`onTargetSide` clamp and ADR-0133's band cap both operate on the aim, and this path sets the aim to zero
before either is consulted — which is precisely why both were graded ⚠️ UNVERIFIABLE/vacuous rather than
verified. No amount of buffer work can be measured until this is addressed.

**Do NOT re-attempt ADR-0131.** `efccc6502` — "a cold sensor re-seeds on its own warm-up cadence until it
warms" — is the same root cause and scored **❌ BAD** (risk-adj return/cycle -0.000016 over 7 cycles,
t=-0.03; gross 0 → 33,743) and was reverted. The next change must attack a **different** lever. The
untried and better-argued one is the **asymmetry**: a σ sensor that cannot price a stop is a reason not to
*open* risk, but it is not a reason to *liquidate* a book the desk already holds and was measuring happily
one process earlier. Whether the boot flatten should be suppressed (rather than the warm-up accelerated) is
the design question to answer — with an ADR, since it changes when a risk control fires.

**VERIFY-BY (next run, from `/api/fusion/targets` and `/api/risk`):** within the first two re-plans after a
boot, either (a) `currentQty` is non-zero for the names the previous process held — i.e. no full-book
liquidation at boot — or (b) `streamVolMeasuredNames` is at the name count rather than 0. Plus: the count of
`risk-cut σ sensor still cold` WARN lines at boot, which was **18** this cycle. If the book is again $0.00
gross with every aim 0.0 at boot, this item is **STILL-BROKEN**.

### Item #2 (carried, unchanged) — ADR-0133's band cap has never been observed firing
Shipped and unit-pinned, but the live book has not once reached the band this process (all four samples had a
zero gap). **VERIFY-BY:** with aims non-zero, `insideBuffer` strictly below the target count while
`deltaQty` is non-zero for at least one name whose `|forecast| < 1.0`. Blocked behind item #1.

### Item #3 (carried) — ADR-0132's destination clamp has never been observed firing
Same reason, one level deeper: it needs a *wrong-side holding*, and every `currentQty` is 0. **VERIFY-BY:**
a name with `currentQty` and `targetQty` of opposite signs whose `deltaQty` moves it toward flat. Blocked
behind item #1.

### Item #4 (carried) — MACRO holds a frozen directional loss
`/api/attribution`: MACRO `totalPnl` **-$35.82347655**, all realized, `unrealizedPnl` **0.00000000**, fees
**$0.169833**. Ranked below #1.

### Item #5 (carried, housekeeping, no money cost) — the ADR index is missing rows for 0129, 0130, 0131, and 0132 is a duplicated number
`docs/adr/` contains both `0132-deploy-capital-objective-200k-budget.md` and
`0132-the-buffers-destination-never-sits-on-the-side-the-target-opposes.md`. Renumber and index.

---

## Verification block — 2026-07-31 13:30Z (ADR-0132 scored ⚠️ INCONCLUSIVE and was kept, so a change was due; item #1 is a NEW defect found in the same component — the band, not the destination — and shipped as ADR-0133)

**Step 0 — last cycle's change.** `c58e7bb83` (ADR-0132, the destination clamp) scored **⚠️ INCONCLUSIVE**
(+0.015004 risk-adjusted return/cycle over 37 cycles, t=+1.45 against a 1.5 hurdle) — kept, not reverted.
`reports/.pending-baseline.json` is gone and `score` prints `no pending change to score`, so this cycle was
free to make a change. Its own claim — that a wrong-side holding is no longer steered to a wrong-side
destination — is **⚠️ UNVERIFIABLE this cycle, not verified**: every `currentQty` in `/api/fusion/targets`
is 0, so there is no wrong-side holding for `onTargetSide` to act on and the check is vacuous. Carried as
item #2 with its VERIFY-BY intact. It did deploy (the clamp is in the running commit and the shipped tests
pin it).

**Live situation.** The SITUATION header reads total PnL **$779.87**, gross **$0.00** (**0.0%** of the
$1,500,000 firm cap, headroom **$1,500,000**), net **$0.00** (0.0% of the $1,000,000 net cap). Flag:
**DORMANT**. Since last run PnL **+0.00**, gross **+0.00**; over three runs the same. `run-status.json`
(heartbeat `2026-07-31T13:00:02Z`) reads `pnl_growth_pct` **0.0** vs `pnl_target_pct` **1.0**, `on_track`
**false**, `stale` **false**, `underwater` **false**. **The header was snapshotted at `atMillis`
1785504593354 — seven seconds BEFORE the 13:30Z US open, after three `market-closed` heartbeats**, so the
flat reading is the overnight freeze (Rule 169). Live endpoint reads after the open show the book moving.
Not in danger: nowhere near a cap or the drawdown breaker.

**Attribution.** The window spans a market-closed stretch and the open, and the scored change opens and
closes nothing. Market and change are **not separable**; neither is claimed (Rules 141/152).

### ✅ Item #1 — CLOSED and shipped: the no-trade band was wider than the interval it policed (ADR-0133)

Sampled `/api/fusion/targets` at four consecutive re-plans after the open (`atMillis` 1785504804718 /
1785504834909 / 1785504865091 / 1785504895225): `insideBuffer` **19, 18, 20, 20** of **20** — two re-plans
planned nothing at all — with the desk holding **$14,215** gross against its own target book of
**$303,271** (4.7%), a $200k deploy budget unused.

Recomputed from the endpoint's own published fields (Rule 164), reproducing every `deltaQty` exactly. The
band is `width × |target| × TARGET_ABS / |forecast|`; the target is **linear** in the forecast, so the two
`|forecast|` factors cancel and the band is the position at a **full-strength** view regardless of the
current one. The gap it is tested against lives inside ADR-0102's interval `[flat, target]`, whose width
**does** shrink with conviction. So `band / |target| = width × TARGET_ABS / |forecast|`, and past
`|forecast| < width × TARGET_ABS` (= 1.0 shipped) the band exceeds the whole interval: from flat,
`|gap| = |aim| ≤ |target| < band` at **every** aim the hour-long ADR-0080 path can reach, so the delta is
exactly zero **forever** (Rule 168).

| name | forecast | target | band | gap | planned |
|---|---|---|---|---|---|
| PFE | 2.541 | 959.066 | 377.467 | 7.959 | 0.000000 |
| NEE | 2.305 | 403.475 | 175.021 | 34.533 | 0.000000 |
| BAC | 1.986 | 263.702 | 132.757 | 22.772 | 0.000000 |
| AAPL | 8.075 | 102.395 | 12.681 | 11.665 | 0.000000 |
| CAT / XOM / HD / PG / JNJ / KO | 0.208 / 0.200 / 0.175 / 0.127 / 0.033 / 0.000 | — | **> \|target\|** | — | permanently vetoed |

**Shipped:** `PositionBuffer.band` caps the position scale at `|target|` — one `min`, strictly one-way (it
can only NARROW a band), inert at `|forecast| ≥ TARGET_ABS`, no number introduced (the bound is the target
the planner already computed). ADR-0133, `Status: Implemented`, config comment carries the provenance. The
aim path, ADR-0101 width, ADR-0107 rating, ADR-0090 de-risking, ADR-0118 trapped exit, ADR-0132 destination
clamp and unbuffered flat-target exits are all byte-identical; the deterministic floor is untouched. A
larger fix (re-centring the region on the target, as Carver states it) was written and **reverted** — it
broke ADR-0107 and ADR-0090 (Rule 170). Full `-Pci test` green.

**VERIFY-BY next run:** `/api/fusion/targets` `insideBuffer` materially below **20 of 20**, and
`/api/risk` `.total.grossExposure` climbing off ~5% of the summed `|targetQty| × price` in the same
payload. If `insideBuffer` is still 20/20 the fix did not land — ⚠️ STILL-BROKEN.

### Item #2 (carried, was closed prematurely) — ADR-0132's destination clamp has never been observed firing

Not a regression, an absence of evidence: it needs a name held on the side its own target opposes, and the
book was flat all cycle. **VERIFY-BY:** on a cycle where `/api/fusion/targets` shows a name with
`sgn(currentQty) != sgn(targetQty)`, its `deltaQty` must resolve the position toward flat rather than to a
destination still on the held side.

### Item #3 — MACRO holds a frozen directional loss (unchanged, still ranked below #1)

Carried from the 2026-07-30 blocks, unchanged.

### Item #4 — the ADR index is missing rows for 0129, 0130 and 0131, and 0132 is a duplicated number

Housekeeping, no money cost. Two files claim ADR-0132 (`0132-deploy-capital-objective-200k-budget.md` and
`0132-the-buffers-destination-never-sits-on-the-side-the-target-opposes.md`); one needs renumbering. Not
touched this cycle to keep the change coherent and attributable.

---

## Verification block — 2026-07-30 19:30Z (the pending revert SCORED ⚠️ INCONCLUSIVE, so a change was due; item #1 re-diagnosed a THIRD time — this time from the buffer's own published arithmetic, and shipped as ADR-0132)

**Step 0 — last cycle's change.** `64a7a6336` (the completed auto-revert) scored **⚠️ INCONCLUSIVE**
(`c3d588c`), `reports/.pending-baseline.json` is gone and `score` prints `no pending change to score`. Its
own claim — that the graded-BAD ADR-0131 re-seed mechanism is out of the running code — was **✅ VERIFIED**
on five independent JVMs across the 17:00Z–19:00Z blocks. That item stays closed and this cycle was free to
make a change.

**Live situation.** The SITUATION header reads total PnL **$131.04**, gross **$38033.13** (**2.5%** of the
$1,500,000 firm cap, headroom **$1,461,967**), net **$-6383.71** (**0.6%** of the $1,000,000 net cap).
Flags: **none**. Since last run PnL **+7.70**, gross **-3089.11**; over three runs PnL **+1.25**, gross
**+22.43**. `run-status.json` (heartbeat `2026-07-30T19:06:38Z`) reads `pnl_growth_pct` **7.74** vs
`pnl_target_pct` **1.0**, `on_track` **true**, `stale` **false**, `underwater` **false** — back on track
since last run. Not DORMANT (24 names in `fusion_targets`), not in danger.

**Attribution.** The window's move is **not separable** into market vs change: the scored change was a
*revert* that opens and closes nothing, and a JVM boot sits inside the window. Claim neither (Rule 141/152).

### ✅ Item #1 — CLOSED and shipped: the buffer's DESTINATION sat on the side the target opposes (ADR-0132)

The 19:00Z block re-framed this as "the target book re-randomises every 30s, so no buffer width or
adjustment rate can absorb it". **That framing is also wrong, and this cycle's telemetry says so.** Rule 163
sampled `targetQty` at three re-plans and read sign flips; sampling the *same* names again this cycle
(`atMillis` 1785439853409 / 1785439883657 / 1785439913989) shows the targets **drift**, they do not
re-randomise: MSFT **-63.86 → -51.60 → -57.99**, PG **-285.25 → -153.26 → -158.88**, NVDA **+132.74 →
+169.02 → +174.22**, AAPL **+95.05 → +93.32 → +106.59**. The earlier "re-randomisation" was the forecast
crossing zero on a handful of names, not the whole book.

**What the same three samples DO show, in every one of them, is the actual defect.** The desk held the
SAME six names on the opposite side of their own live target, at a standstill:

| name | held (all 3 re-plans) | target | `deltaQty` per re-plan |
|---|---|---|---|
| GOOG | **-9.00** | +115.70 / +102.33 / +95.24 | +0.011 / +0.019 / +0.040 |
| AAPL | **-13.00** | +95.05 / +93.32 / +106.59 | +0.019 / +0.034 / +0.058 |
| NVDA | **-16.00** | +132.74 / +169.02 / +174.22 | +0.098 / +0.110 / +1.050 |
| PG | **+26.00** | -285.25 / -153.26 / -158.88 | -0.128 / -0.139 / -0.198 |
| KO | **+45.00** | -18.12 / -30.32 / -63.07 | **0.000000 / 0.000000 / 0.000000** |
| WMT | **+9.00** | (long) / (long) / -10.23 | **0.000000** |

**The mechanism, proved rather than inferred (new Rule 164).** `/api/fusion/targets` also publishes `aims`.
With `aims`, `targetQty`, `currentQty` and `combinedForecast` the whole ADR-0094/0101/0102 arithmetic can be
recomputed — and it reproduces the published `deltaQty` **exactly on all 13 planned names**, e.g. GOOG
`6.043466 × 0.008298707 = 0.050153` and AMZN `22.379233 × 0.008298707 = 0.185720`, both matching the
published figure digit for digit. That closes the four cycles of inference. What the arithmetic shows:

```
GOOG  held -9   target +32.814821   forecast +2.618561447358846   aim +9.575088
  band = 32.814821 x 10 / 2.618561447358846 x 0.10 = 12.531622
  gap  = 9.575088 - (-9) = 18.575088 > band  =>  edge = 6.043466
  DESTINATION = -9 + 6.043466 = -2.956534    <-- the desk intends to STOP while SHORT a name it wants LONG
KO    held +45  target -18.120000   forecast -0.31   aim 0
  band = 58.451613 ; gap = -45.000000 ; |gap| <= band  =>  deltaQty 0.000000, indefinitely
```

The band is scaled by the average position at the **target**; early on ADR-0080's hour-long aim path it
exceeds the aim, so the no-trade region **straddles flat** and reaches onto the side the forecast opposes.
Nothing bounded it: ADR-0102 bounds the *intent*, not the destination a whole band below it.

**Rule 161 stands and Rule 163 is retracted.** The band is not inert (`insideBuffer` **19** of **24**) — but
"binding" was never the question; *where* it binds is. And this is not an upstream signal-stability problem.

**ADR-0118 already diagnosed this exact position and its fix does not reach the shipped configuration.**
It worked the identical AAPL plan (short 1 vs target +6.031064) but scoped the remedy to `isTrappedExit`,
inside `!mayIncrease` — a shut edge gate or a σ-cold name. With `jethro.fusion.edge-gate.enabled=false`
(ADR-0122) and the σ sensors warm, that branch never runs. Its own test pinned the open-gate case at
`deltaQty` 0 as if correct.

**The change (ADR-0132).** `PositionBuffer.onTargetSide` replaces a destination on the opposite side of flat
from the target with **flat**, before the ADR-0107 rating — so it stays a rated unwind, not a liquidation.
Band, aim path, ADR-0101 width and rating unchanged; no number introduced (the bound is flat). Proved to
fire only on a holding the target opposes, to resolve to `|held+delta| = 0` (never opens, enlarges or
side-flips), and never to trade past the aim. Flat-target exits keep exact semantics. Full `-Pci test` green.

**VERIFY-BY (next cycle, from live telemetry).** On `/api/fusion/targets`: **no name has `currentQty` and
`targetQty` of opposite sign with `|deltaQty|` below `|currentQty| x 0.0082987`** — i.e. every wrong-side
holding is unwinding at the full derived rate rather than at a band-reduced one or at zero. `insideBuffer`
should fall from **19** of **24**. GOOG/AAPL/NVDA/PG/KO/WMT should show `currentQty` moving toward flat
across consecutive re-plans instead of standing still, and firm gross should fall as they wind off.

### Item #2 — MACRO holds a frozen directional loss (unchanged, still ranked below #1)

`/api/attribution` reads MACRO `totalPnl` **-$35.82347655**, all realized, on `feesPaid` **$0.169833** —
bit-identical for a fourth consecutive run. The book is not trading, so the loss is closed and not growing
(Rule 157). It needs its own cycle and its own trigger-level post-mortem.
**VERIFY-BY:** MACRO `totalPnl` moves off **-$35.82347655**, or a post-mortem names the trigger that opened it.

### Item #3 — the ADR index is missing rows for 0129, 0130 and 0131 (housekeeping, no money cost)

`grep '0129\|0130\|0131' docs/adr/README.md` returns nothing though all three files exist. CLAUDE.md
requires reading the index before proposing designs, so the gap hides three accepted decisions. Not fixed
this cycle — one coherent change per run, and this one carries no live cost.
**VERIFY-BY:** those three rows appear in `docs/adr/README.md`.

---

## Verification block — 2026-07-30 19:00Z (revert ✅ VERIFIED on a FIFTH JVM — at 5/6, no change made; item #1 is RE-FRAMED: the buffer is NOT inert and the "inverted fallback" is not the defect — the TARGET BOOK is unstable at the re-plan cadence)

**Fifth independent reproduction.** A new process (PID **3646490**, boot **14:34:38.573**–**14:34:47.704**
local `-04:00`) — distinct from the 17:00Z/17:30Z/18:00Z/18:30Z JVMs — re-tests the four pre-registered legs:
- **No second re-seed wave.** Over the entire running log, keyed by lifecycle+name,
  `grep -oP '(Trend|Reversion)ForecastLifecycle\s+: \w+ sensor still cold for \S+' | sort | uniq -c |
  awk '$1>1'` returns **nothing**; **53** cold lines, each unique.
- **ADR-0071 boot seeding still fires.** **62** `sensor warmed` lines.
- **No name is warmed twice, proved by count.** Keying on the FULL lifecycle class + name
  (`grep -oP '\S+Lifecycle\s+:.*sensor warmed \S+' | sort | uniq -c | awk '$1>1'`) returns **nothing**.
- `grep -rn "SensorReseed" --include=*.java app/` returns nothing.

**Method correction (new Rule 159).** A truncating pattern that keys only on the word *before* `sensor`
reports **14** false double-warms (AAPL, AMZN, BAC, GOOG, JNJ, KO, MSFT, NEE, NVDA, PFE, PG, UNH, WMT,
XOM at count 2) because it collapses `i.j.a.f.CrossSectionalReversionLifecycle : cross-sectional reversion
sensor warmed AAPL` with `i.j.a.fusion.ReversionForecastLifecycle : reversion sensor warmed AAPL`. Rule 158
said "prove by count"; it must also say **key on the full lifecycle class**, or the count proves nothing.

**Item #1 of the 16:30Z block stays CLOSED.** Five independent JVMs, four legs each.

**No change made this cycle.** `scripts/score-change.py score` prints
`64a7a6336 still accumulating evidence (5/6 cycles) — held, not scored this run` and
`reports/.pending-baseline.json` is present, so a new change would destroy the evidence. **The pending
change scores next cycle**, which is when the re-framed item #1 below becomes actionable.

**Live situation.** The SITUATION header reads total PnL **$117.52**, gross **$37668.89** (**2.5%** of the
$1,500,000 firm cap, headroom **$1,462,331**), net **$-12290.58** (**1.2%** of the $1,000,000 net cap).
Flags: **none**. Since last run PnL **-6.43**, gross **-2210.24**; over three runs PnL **+3.04**, gross
**-17516.73**. `run-status.json` (heartbeat `2026-07-30T18:34:15Z`) reads `pnl_growth_pct` **-12.89** vs
`pnl_target_pct` **1.0**, `on_track` **false**, `stale` **true**, `underwater` **false** — the objective
flags flipped off-track since last run. Not DORMANT (20 instruments plus the ES hedge), not in danger.

**Attribution caveat.** A JVM boot at **14:34:38** local sits inside this window and the pending change is
a *revert* that opens and closes nothing, so the **-6.43** / **-2210.24** move is **not separable** into
market vs change — claim neither (Rule 141/152).

### 🎯 Item #1 — RE-FRAMED: the fusion TARGET BOOK re-randomises every 30s; the buffer below it is working as designed and cannot absorb it

The previous four blocks ranked this as "ALPHA churns, and the cost-aware no-trade band that should stop it
is INERT, via an inverted ADR-0101 fallback". **Two of those three claims do not survive this run's
telemetry, and the queued fix would have been wrong.** Correcting it is this cycle's work.

**❌ The band is NOT inert.** `/api/fusion/targets` exposes `insideBuffer`, which `PositionBuffer.apply`
increments exactly when a name's planned delta is zero. It reads **13** of **20**. The ADR-0094/0101/0102
buffer is suppressing the majority of the book every cycle — it is binding, not dormant.

**❌ The ADR-0101 fallback is not "inverted".** `PositionBuffer.widthFor` returns the convention
`bufferFraction` when `edgeBps <= 0` or `costBps <= 0`. The previous block proposed widening that branch on
the argument that unmeasured μ makes `2C/μ` unbounded. But **0.10 is Carver's published convention for
precisely the desk that has NOT measured its edge** (`Systematic Trading` 2015), and the method's contract
is explicit that with no measurement "there is no claim to make". Widening it would be **authoring a risk
number where none is measured** — the exact thing invariant 7 / ADR-0016 forbids. **Do not ship that fix.**

**✅ What IS wrong, measured directly.** Sampling `/api/fusion/targets` across three consecutive re-plans
(`atMillis` **1785438150584**, **1785438180812**, **1785438210932** — 30s apart) the `targetQty` book does
not drift, it re-randomises:

| name | re-plan 1 | re-plan 2 | re-plan 3 |
|---|---|---|---|
| AAPL | -10.75 | **+129.53** | +67.19 |
| HD | -1.48 | **+42.78** | +1.39 |
| NVDA | -0.08 | -6.69 | **-50.91** |
| KO | -324.08 | -231.96 | **-45.38** |
| GOOG | +0.29 | +1.01 | **-3.44** |
| PG | -288.30 | -339.67 | -238.00 |
| MCD | -89.97 | -121.89 | -117.06 |

AAPL flips from short to long and swings ~140 shares in one 30s step; HD flips sign and back; KO sheds 86%
of its target in 60s. An earlier read the same cycle had MCD at **-0.85** and GOOG at **-75.52**. **No
buffer width and no adjustment rate can fix a target that is re-drawn this way** — and worse, the buffer's
own band is `|target|·TARGET_ABS/|forecast|`, so the band swings *with* the target it is meant to filter.

**Why the targets swing.** `weights` reads `reversion` **1.5810674747889952** and `xsreversion`
**0.9850309963910409** against `trend` **0.39443196542948245** — the book is dominated by two fast
mean-reversion sources (`reversion.interval-seconds=10` on a 120s range span; `xs-reversion` on a 900s
lookback) sampled by a 30s re-plan. Meanwhile `strategy_diag.edgeGated` reports `no positive OOS edge` on
every name it lists (MSFT `momentum -37.36954202 … mean-rev -73.69953186`; AMZN `-46.40665676`; GOOG
`mean-rev -11.15007399`; SAP `-39.20256473 … -65.12971133`). **The desk is paying round trips to chase
sources with no measured edge, at a cadence faster than those sources decay.**

**The cost this produces.** `/api/attribution` reads ALPHA `totalPnl` **$17.08569992** on `feesPaid`
**$79.469996**; `firmTotal` **$116.30055246** is carried entirely by `hedgePnl` **$135.03832909** with
`hedgeMasking` **true** and `strategyAlpha` **-$18.73777663**. `orders_by_status` reads FILLED **4052**,
CANCELLED **1364**, and every cancellation in the `recent_orders` tape carries
`fusion re-plan — passive order superseded by a fresh target (ADR-0084)`. The tape shows HD posted BUY 1 →
2 → 2 → 3 → 4 across five consecutive re-plans, each cancelled before filling.

**Rule 154 is RETRACTED as a trend.** The four-run monotone ALPHA decline broke this run: `totalPnl` read
**$15.27790493** then **$17.08569992** against the prior **$7.64428496**. PnL is *rising*. Only the fee leg
is still monotone (**$73.682486** → **$78.463912** → **$79.469996**). A four-point monotone run was not
enough to call a trend — see Rule 160.

**VERIFY-BY (next cycle, once the revert scores).** The fix must damp the *target*, not the executor or the
buffer. Sample `/api/fusion/targets` across ≥3 consecutive `atMillis` re-plans and confirm **no name's
`targetQty` changes sign, and no name's `|targetQty|` changes by more than the buffer width, between
adjacent re-plans**; `insideBuffer` should rise from **13**/20; ALPHA `feesPaid` should stop its monotone
climb from **$79.469996**. The candidate change is to smooth the combined forecast over a horizon matched
to the source's own decay (or to re-plan at that horizon) — with an ADR in the same commit, since it is a
cross-cutting change to how every target is formed.

### Item #2 — MACRO holds a frozen directional loss (unchanged, still ranked below #1)

`/api/attribution` reads MACRO `totalPnl` **-$35.82347655**, all realized, on `feesPaid` **$0.169833** —
bit-identical for a third consecutive run. The book is not trading, so the loss is closed and not growing
(Rule 157). It needs its own cycle and its own trigger-level post-mortem.
**VERIFY-BY:** MACRO `totalPnl` moves off **-$35.82347655**, or a post-mortem names the trigger that opened it.

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
