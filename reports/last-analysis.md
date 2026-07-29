ADR-0124 is VERIFIED on its exact VERIFY-BY — the uncorroborated name now sizes at zero, not at maximum — and it is at 1/6 of its evaluation window, so no new change this cycle.

*(Every figure below is read live from `/api/risk`, `/api/fusion/targets`, `/api/attribution`, the TCA and
turnover tables in `logs/report.md`, or `reports/run-status.json`. None is authored here — invariant 7 /
ADR-0016. The PnL verdict remains the scorer's.)*

## Step 0 — did last run's change land, and did it do what it claimed?

**Deployed:** yes. The JVM started **12:45:40 local**, 45 s after commit `3e7817e` (12:44:55) — the running
process is ADR-0124, not the code that preceded it.

**✅ VERIFIED**, on the written VERIFY-BY, read live from `/api/fusion/targets`:

- The register required *"every `sources=1` row reads `agreement 0.000` and `combinedForecast 0.0`"*.
  **META: `sources=1`, `agreement 0.000`, `combinedForecast 0.000`, `targetQty 0.000`.** Last cycle the same
  name read `sources=1, agreement 1.000, |forecast| 15.41` and was targeting **−78.3** shares against a
  holding of **0**.
- The register required *"the largest conviction in the book belongs to a corroborated name"*. It does:
  **MSFT, `sources=2`, `agreement 0.958`, `combinedForecast −10.381`**. No `sources=1` name carries a
  `|combinedForecast|` above any multi-source name — the inversion (**15.41** at one source against
  **8.44** at three) is gone.
- Second check — the repeated single-name `fusion re-plan` cancellation runs. **Zero cancellations in
  `recent_orders` after the 16:45:57Z restart**; every post-restart order is FILLED. Honest caveat: this
  half is **confounded**. Pre-restart the same pathology was running on **ORCL** (15 consecutive
  `fusion re-plan` cancels, SELL size ramping 5 → 12 → 18 → … → 94, never filled), and after the restart
  ORCL is no longer in the routed set at all (`selector: measured 19, tradable 9`). Warm-up, not the fix,
  may be doing that work. The **first** check is not confounded and is decisive.

The falsifier stays live: *if the ordering corrects but firm realized bps does not improve over the window,
the inversion was cosmetic.* That is the scorer's call at 6/6, not mine.

## Situation

1. **Money.** Underwater and down on the window, up over three. SITUATION header: total PnL **−$53.69**,
   **−$31.33** vs last run, **+$27.08** over the last 3. Read again live a few minutes later: **−$56.76**.
   `pnl_growth_pct` **68.4%** vs the **+1.0%** target, `on_track=true`, `stale=false`, `underwater=true`.
2. **Risk.** Not the problem. Gross **$9,651.27 = 0.6%** of the $1.5M firm cap (headroom **$1,490,349**);
   net **−$4,361.67 = 0.4%** of the $1M net cap. Breaker `halted: false`. VaR95 **$169.64**, ES95
   **$209.05** on **$9,651.27** covered, `skippedExposure 0.00`. No cap flag, no DANGER flag, and the book
   is trading — neither near the ceiling nor dormant. The only flag is UNDERWATER.
3. **Cause — and it is mostly *not* my change.** ADR-0124's live effect is confined to the two names it
   silences: META and TSLA, both `quantity 0`, contributing no new PnL. Everything that moved money this
   window is a **multi-source** name ADR-0124 did not silence: JPM **−$24.88** realized over **41** fills
   ending flat, JNJ **+$42.64** over **44** fills ending flat, AMZN **−$14.79**, GOOG **−$8.28**. GOOGL
   (**−$40.71**) and NQ (**−$35.82**) are byte-identical to last cycle — frozen historical losses in this
   epoch, not an ongoing bleed. **Attribution:** essentially none of the −$31.33 is attributable to
   ADR-0124; it is the standing exploration configuration trading against the market. The gross fall
   ($28,332.63 → $9,651.27) I will **not** claim either — ADR-0124 does shrink targets book-wide, but the
   restart re-cut the tradable set from ORCL-inclusive to 9 names in the same minute. The two cannot be
   separated from these numbers, so I am attributing neither.
4. **Danger.** No. Bleeding, yes — but at 0.6% of the gross cap with the breaker untripped, this is not the
   near-cap bleed that would override everything. Nothing to de-risk.

## Why no code change

`score-change.py score` prints **`3e7817e4f still accumulating evidence (1/6 cycles) — held, not scored
this run`**, and `reports/.pending-baseline.json` is present. Per the contract, stacking a change on top of
a measurement in progress destroys the evidence. Holding.

## What I am watching, and what is next

The next target is already ranked: **execution is one-sided by design** (`FusionExecutor.route`, ADR-0084 —
entries POST at the mid, exits CROSS). This cycle handed it two more pieces of evidence. First, the ORCL
ramp is the TSLA pathology on a different name — a passive sell that never fills, re-planned every 30 s at
a larger size, 15 times, zero fills: the entry side simply does not transact unless the market comes to it.
Second, `/api/attribution` reads ALPHA `totalPnl` **−$18.66** against `feesPaid` **$21.10** — the strategy
book is positive before commission. I am flagging that carefully rather than acting on it, because rule 74
already disproved the pure-fee framing once (fees were 21% of the realized loss firm-wide); fee and adverse
selection are both execution costs and must be split before either is blamed. Also logged, not chased:
TCA `avgSlippageBps` is **6.67** (TSLA) and **6.00** (GOOGL) against **0.40–0.56** for the Alpaca-WS names —
real, but on 3 and 5 fills respectively, too little turnover to be the target.
