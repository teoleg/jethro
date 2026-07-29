ADR-0124 holds for a second cycle — every uncorroborated name still sizes at zero — and at 2/6 it is still measuring, so no new change; the order churn it was hoped to stop has resumed on AAPL, which puts the blame back on execution where it belongs.

*(Every figure below is read live from `/api/risk`, `/api/fusion/targets`, `/api/attribution`, `/api/var`,
`/api/signals/telemetry`, or the tables in `logs/report.md`. None is authored here — invariant 7 /
ADR-0016. The PnL verdict stays the scorer's.)*

## Step 0 — did last run's change land, and is it still doing what it claimed?

**Deployed:** yes, and proven behaviourally rather than from `git log` (rule 84). The JVM restarted at the
17:07 heartbeat (`uptimeSeconds` **1359** at a 17:30:01Z report), and `/api/fusion/targets` reads
`sources=1 → agreement 0.000` — a value only the ADR-0124 code can produce. No revert commit for
`3e7817e` exists, so the running process is ADR-0124.

**✅ VERIFIED (second consecutive cycle), on the written VERIFY-BY:**

- *"every `sources=1` row reads `agreement 0.000` and `combinedForecast 0.0`"* — live: **TSLA
  `sources=1, agreement 0.000, fc 0.000, targetQty 0`**; **META `sources=1, agreement 0.000, fc 0.000,
  targetQty 0`**; **GOOGL `sources=1, fc 0.000`**. All three carry a non-trivial raw `xsreversion`
  contribution (TSLA **+13.72**, META **+8.06**) that is correctly discarded for want of a second sensor.
- *"the largest conviction in the book belongs to a corroborated name"* — live: **NVDA `sources=3,
  agreement 0.571, combinedForecast +8.206`**. Not one `sources=1` name carries any conviction at all.

**⚠️ The second check FAILS — and last cycle's reading of it was the confounded one.** Last cycle I
recorded "zero `fusion re-plan` cancels post-restart" as *confounded, not evidence* (rule 85). That
caution was right: after the 17:07 restart the churn is **back** — **3 of 17** post-restart orders are
`fusion re-plan` cancels, and AAPL is running the same ramp ORCL ran: SELL **1 FILLED → 1 FILLED →
1 CANCELLED → 2 CANCELLED → 3 ROUTED**, each re-planned 30 s apart at a larger size. So the cancellation
churn is **not** an agreement-scaler artefact; it is independent of ADR-0124 and belongs entirely to
must-fix #1 (one-sided execution). That is a real narrowing of the diagnosis, not a regression of the fix
— the first check, the decisive one, is clean.

The falsifier stays live and is the scorer's to call: *if the ordering corrects but firm realized bps does
not improve over the window, the inversion was cosmetic.* `score-change.py score` prints
**`3e7817e4f still accumulating evidence (2/6 cycles)`**.

## Situation

1. **Money.** Underwater, but up on the window and up over three. Total PnL **−$50.75**, **+$1.74** vs last
   run, **+$37.29** over the last 3. `pnl_growth_pct` **35.01%** against the **+1.0%** target;
   `on_track=true`, `stale=false`, `underwater=true`. Not bleeding.
2. **Risk.** Not the problem, and moving the right way. Gross **$18,414.69 = 1.2%** of the $1.5M firm cap
   (headroom **$1,481,585**); net **−$163.02 = 0.0%** of the $1M net cap. Gross **+$7,742.82** on the
   window — a book coming off dormant with 98.8% of its ceiling unused, which is the goal, not a concern.
   Breaker `halted: false`. VaR95 **$158.64**, ES95 **$210.62** on **$18,414.41** covered with
   `skippedExposure 0.00`. Regime `CHOP` / `CALM`, `volRatio 0.88`.
3. **Cause.** ADR-0124 is at 2/6 and unscored. Its live footprint is confined to the three names it
   silences — TSLA, META, GOOGL — all at `targetQty 0`, none of which traded this window. The window's
   money came from names it did not touch.
4. **Danger.** No. Underwater is the only flag; there is no near-cap bleed and nothing to de-risk.

## Order-level post-mortem, and change-vs-market attribution (honest)

The window traded **42 fills / 17 cancels / 1 routed** across AAPL, GOOG, NVDA, JPM, JNJ, AMZN, MSFT and
the ES hedge. Turnover is concentrated in JNJ (**44** fills, **$58,850**), JPM (**41**, **$28,661**), AAPL
(**37**, **$27,964**), GOOG (**30**, **$27,041**), MSFT (**36**, **$22,487**) — every one of them a
**three-source** name ADR-0124 leaves alone. **So none of the +$1.74 is attributable to my change**; it is
the standing exploration configuration trading against the market.

The **+$7,742.82** gross rise I *can* attribute, and not to ADR-0124 either: it is the **hedge doing its
job**. `/api/risk` shows EQUITY gross **$9,288.86** with net **−$9,288.86** (all nine equity positions
short) against a single ES position of **$9,125.84** long, netting the firm to **−$163.02**. The three ES
BUYs at 17:15/17:18/17:20 built that leg; `/api/hedging` reads `status: ON-TARGET`, `held 0.024588 →
target 0.028131`. Gross rose because a hedged book carries both legs — exactly as designed.

Book split: ALPHA **−$18.57** (realized **+$5.16**, unrealized **−$23.73**, fees **$21.60**), HEDGE
**+$3.64**, MACRO **−$35.82** — and MACRO is realized-only with `grossExposure 0.00`, a frozen historical
NQ loss in this epoch, not an ongoing bleed. Restating rule 87 deliberately: ALPHA's realized turning
**positive** against $21.60 of fees is *not* licence to re-adopt the fee framing rule 74 already killed.

## What I checked on the standing priority (edge), and why it argues for patience

Per the standing priority I asked the first question — does *anything* predict returns here? Cohort-
clustered t on `/api/signals/telemetry` (LIVE, 3600 s), computed from the endpoint's own `avgReturnBps`,
`cohorts` and `stdCohortMeanBps` by the same construction the edge gate uses:

| source | n | cohorts | avgReturnBps | t |
|---|---|---|---|---|
| reversion | 109 | 29 | **+8.783** | **+1.22** |
| trend | 115 | 30 | −8.634 | −1.25 |
| xsreversion | 53 | 6 | −11.524 | −1.05 |
| social | 37 | 9 | −4.370 | −0.39 |
| momentum | 15 | 5 | −13.257 | −1.45 |

**Reversion is the only positive expectancy in the book, and at t = +1.22 it is short of the 1.5 hurdle —
not by much, and on only 29 cohorts.** The fusion weights already reflect this (`reversion` **2.384**, the
largest by a factor of two). This also explains the thing that looks alarming in `/api/fusion/targets` and
is not: NVDA aims **+148.27** against a holding of **−3.0** with `deltaQty 0.0`, JPM **−69.89** against
**0** with `deltaQty 0.0`. That is `PositionBuffer` under a reduce-only edge gate, holding size back
because nothing has cleared the hurdle. The gate is behaving correctly; the desk holds little because it
has measured little. The honest read is that reversion needs **more cohorts**, not more tuning — another
argument for letting the window run rather than perturbing the book.

## Why no code change

`reports/.pending-baseline.json` is present and the scorer prints **2/6 cycles**. Stacking a change on a
measurement in progress destroys the evidence, and this cycle's own finding — that the cancellation churn
is independent of ADR-0124 — is exactly the kind of signal a second change would have muddied. Holding.

Next cycle, once `3e7817e4f` is scored, the target is must-fix **#1**: execution is one-sided by design
(`FusionExecutor.route`, ADR-0084 — entries rest at the mid, exits cross). The AAPL ramp is now the
second-named instance of a mid-resting limit that re-plans larger every 30 s instead of transacting, and
it is the entry side of that scheme failing silently. Changing it needs an ADR superseding ADR-0084, not
a dial.
