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
