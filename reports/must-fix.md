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
