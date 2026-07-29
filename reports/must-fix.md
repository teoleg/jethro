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
_(none yet — items move here, struck through, once a later run confirms the fix from live telemetry.)_
