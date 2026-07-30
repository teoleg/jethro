ADR-0131 is at 5/6 evaluation cycles so I made no change; its defect reproduced a third time on a third JVM — six sensors moved backwards — but the retry also warmed PFE from 192→193, the second clean strict-improvement success, which is the exact case my queued one-line guard preserves.

*(Every figure below is read from the live endpoints, `logs/report.md`, `logs/jethro-app.log` or
`reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

**No code change this cycle.** `scripts/score-change.py score` prints
`efccc6502 still accumulating evidence (5/6 cycles) — held, not scored this run`, and
`reports/.pending-baseline.json` still holds `efccc650`. One cycle remains. Shipping the fix now would
destroy the measurement on its own predecessor.

## Situation

1. **Money — up, and the recovery is exactly where I said not to chase it.** Live `/api/risk` `.total`
   reads total PnL **$174.01223659** (realized **$180.21540531**, unrealized **−$6.20316872**). The report
   SITUATION header computes **+$25.70** on the run and **+$28.97** across the last three. `run-status`
   last heartbeat reads `pnl_growth_pct` **15.65** vs `pnl_target_pct` **1.0**, `on_track=true`,
   `stale=false`, `underwater=false`. Note what happened to last cycle's scare: unrealized was
   **−$49.23652754** and is now **−$6.20316872**. The NVDA/UNH mark drag I explicitly declined to treat as
   a defect reverted on its own. Not bleeding.
2. **Risk — gross FELL while PnL rose, which is the objective moving the right way.** Gross exposure
   **$32990.96481250**, net **−$8634.83518750** — **2.1%** of the firm gross cap with **$1,467,797** of
   headroom, **0.9%** of the net cap. Flags: **none**. Gross is down **−$18,049.05** on the run against
   PnL up **+$25.70**: more money on less risk. One shape change worth recording: net moved from
   **+$49.80412500** (essentially market-neutral) to **−$8,634.83518750**, so the book is now net *short*.
   At 0.9% of the net cap that is not a danger, but the market-neutrality of last cycle is gone and I am
   noting it so a later cycle does not discover it as a surprise.
3. **Cause.** Last cycle's change (ADR-0131, `efccc650`) is unscored at 5/6. It gets **no credit** for
   either the de-grossing or the PnL — see the attribution below.
4. **Danger.** None. Nowhere near the exposure cap or the drawdown breaker, and PnL is rising.

## Order-level post-mortem

The window is **de-grossing flow**, which is what moved exposure: repeated ALPHA SELLs in PG, KO, BAC,
GOOG, AMZN, XOM, NEE, AAPL, NVDA against BUYs in UNH and HD, plus a steady run of small HEDGE **ES BUYs**
(0.000093 → 0.000149) *covering* the short hedge leg. Equities sold down and the hedge covered together —
that is the **−$18,049.05** gross move. `orders_by_status` reads FILLED **3592**, CANCELLED **1032**,
REJECTED **82**; every CANCELLED row carries the same `reason`, *"fusion re-plan — passive order superseded
by a fresh target (ADR-0084)"*. Churn is concentrated in a few names — NVDA **93** fills on
**$38,748.31** turnover, MSFT **81**, GOOG **69**, AAPL **68**, JPM **61**, JNJ **56** — against
`totalFees` **$47.780648** at **1.00 bps** on equities and **0.20 bps** on ES. Fees are not yet material
against the firm total, but NVDA's fills-per-dollar-of-turnover is the one number I would attack once the
sensor work closes.

## Step 0 — verifying ADR-0131, third independent reproduction

This is a **third distinct JVM** (PID **3523413**, booted **11:36:18**, `uptimeSeconds` **1425**), after
the 10:36 and 11:06:51 processes. The running JVM emits the ADR-0131 WARN text (*"re-seeding every 193
sightings until it does (ADR-0131)"*), so the code under test is live and this is a fair grade.

- **Item #1 → ⚠️ STILL-BROKEN.** Wave 1 (11:36:35–11:43:07) → wave 2 (11:52:45–11:59:16), **16 min**
  apart, the predicted 193 × 5 s cadence. **Six names went backwards:** **HD 171→133**, **PG 181→143**,
  **CAT 174→139**, **UNH 156→141**, **MCD 159→145**, **GOOG 191→180**. Seven advanced (JPM 141→190,
  XOM 153→166, CVX 175→185, JNJ 163→167, GOOGL 0→2, TSLA 9→11, EURUSD 28→29, ES 48→49). Three JVMs,
  three different sets of victims, one defect.
- **And the retry scored its second clean win: PFE.** Wave 1 at 11:36:35 logs `still cold ... 192 of 193`;
  at 11:52:45 the log reads `trend sensor warmed PFE from 193 stored prices (needs 193) — warm`. Only the
  ADR-0131 retry emits that. PG did the same thing last cycle at 190→193. **Both successes are
  strict-improvement cases**, and both regressions that matter most this run — GOOG at **191** and PG at
  **181**, each within a dozen prints of warm — are exactly what the guard would have refused to discard.
  The evidence for the queued fix is now stronger than the evidence against the mechanism.
- **Class A reconfirmed a third time:** the same **12** rate/swap names (USD.TSY.\*, USD.SOFR.\*,
  USD_IRS_\*) seeded **193 of 193** in *both* waves and remain cold. Sample count is not their binding
  predicate; retrying them is pure waste, and the strict-improvement condition retires them with no
  separate rule.
- **Leg 2 of last cycle's VERIFY-BY, re-measured on the unfixed code:** **53** trend still-cold lines
  across **27** distinct names, **26** of them repeating. That is the baseline the fix has to beat.

## Attribution this window (honest split)

**Market and fusion re-planning — not ADR-0131.** Its only attributable effects on this tape are one
warmed sensor (PFE) and six regressed warm-up counters; **none of those is a position**. The gross
reduction came from the fusion re-plan / hedge-cover flow above, and the PnL recovery is marks on
positions the desk already held reverting from last cycle's unrealized swing. How much of that is drift
versus selection cannot be separated from 30 minutes of marks, so I claim no cause.
`/api/attribution` reads `firmTotal` **$174.01223659** = ALPHA **$59.63435276** + HEDGE **$150.20136038**
+ MACRO **−$35.82347655**, `hedgeMasking` now **false** (it was **true** last cycle). ALPHA has climbed
from **$20.35473620** to **$59.63435276** as its unrealized leg recovered to **−$6.18030270**; the hedge
still carries most of the firm total, and MACRO (**NQ**, flat, realized **−$35.82347655**) is still the
one closed loser.

## What remains blocking the rest of the book

`strategy_diag` reads `measured` **29**, `tradable` **16**, `edgeGated` **13 names**, each annotated
`no positive OOS edge` — MSFT (momentum **−37.36954202** over 3 paths, mean-rev **−73.69953186** over 3),
AMZN (**−46.40665676** over 4 paths), GOOG and ten more. The ADR-0064 gate working as designed. Per the
standing priority the answer stays **a new signal with genuinely measured edge**, never a looser gate —
item #2, queued behind the sensor fix, which ships next cycle when the scorer window closes.
