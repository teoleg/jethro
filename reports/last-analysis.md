ADR-0131's re-seed retry finally fired, and the evidence disproves my own last-cycle diagnosis — it works for trend but re-derives from a *sliding* window, so it never converges and actually set six tradable names backwards; the change is at 3/6 evaluation cycles, so I held and made no new change.

*(Every figure below is read from the live endpoints, `logs/report.md`, `logs/jethro-app.log` or
`reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

**No code change this cycle** — `reports/.pending-baseline.json` still exists for `efccc6502` and no new
ledger row has appeared, so the change is still accumulating evidence (3/6). Piling a second change on
top would destroy the measurement.

## Situation

1. **Money.** Live `/api/risk` `.total` reads total PnL **$167.76973099**; a later read the same cycle
   reads **$178.02603045** (realized **$159.63438842**, unrealized **$18.39164203**). The report's
   SITUATION header computes **+$23.60** on the run and **+$40.17** across the last three.
   `run-status` reads `pnl_growth_pct` **13.06** against `pnl_target_pct` **1.0** — `on_track=true`,
   `stale=false`, `underwater=false`. Not bleeding; comfortably ahead of the owner target.
2. **Risk.** Gross exposure **$24383.04512500**, net **$6210.52512500** — **1.5%** of the firm gross cap
   with **$1,477,265** of headroom, and **0.5%** of the net cap. Flags: **none**. Gross rose
   **+$15,755.74** on the run, which is exactly what a book coming off dormant should do with 98.5% of
   its budget unused. That is the goal, not a risk event.
3. **Cause.** Last cycle's change (ADR-0131, `efccc6502`) is unscored at 3/6 cycles. It gets **no credit**
   for the book opening — see the attribution below.
4. **Danger.** None. Nowhere near the exposure cap or the drawdown breaker, and PnL is rising.

## Order-level post-mortem

The desk traded **eight ALPHA names plus the HEDGE book** this window, against two names last cycle:
NVDA (repeated BUYs, +8/+7/+7/+6…), AAPL (repeated SELLs), CAT, HD, PG, MSFT, UNH, JNJ, and HEDGE ES.
A large share of the orders are `CANCELLED` with `reason` = *"fusion re-plan — passive order superseded
by a fresh target (ADR-0084)"* — normal passive re-planning, but the churn is high enough to be worth
watching against `totalFees` **$36.280400** once the sensor work closes.

## Step 0 — verifying ADR-0131, and correcting my own last-cycle root cause

**The retry fired.** Last cycle I wrote a VERIFY-BY that only the mechanism could pass: *a second
`still cold` line for a name that already logged one*. It passed — **52** trend still-cold lines across
**27 distinct names**, so **25 names logged twice**. XOM is the clean trace: **10:36:25.022**
(`170 of 193`) → **10:52:34.778** (`169 of 193`), **16 min 9 s** apart against the predicted
193 × 5 s = **16.08 min**.

**So last cycle's root cause was half wrong.** I claimed the retry cadence outlives the process on three
of four call sites. For trend that is false — 16.1 min fits inside this process (`uptimeSeconds`
**1506**) and it fired. It holds only for the other two: `Reversion` still-cold is **22 lines across 22
distinct names** (zero duplicates), `XsReversion` **0 lines**.

**And the pre-registered discriminator resolved.** I had written: *"if the retries fire and σ stays cold,
the defect is the seed span, not the cadence."* The retries fired; the names stayed cold. Two distinct
defects sit underneath:

- **12 rate/swap names seeded `193 of 193` and are still cold in both waves** (USD.TSY.\*, USD.SOFR.\*,
  USD_IRS_\*). They got the full warm-up span and `warm()` is still false, so the sample count is not the
  binding predicate — their stored series does not move, leaving the scale estimator nothing to absorb.
  Re-seeding them can never work.
- **The retry can move a tradable name backwards.** `warmWhileCold` calls `forecaster.forget(...)` and
  replays whatever the re-read returns; because the seed anchors on the *current* mark's provider
  timestamp and walks newest-first, the window **slides** rather than accumulating. Wave 1 → wave 2:
  **HD 164→140**, **JPM 188→161**, **MCD 171→162**, **JNJ 186→182**, **PFE 178→175**, **XOM 170→169** —
  against **UNH 153→183**, **PG 176→183**, **CAT 150→159**, **CVX 157→162**. For those six, the retry
  discarded ~16 minutes of consumed live prints *and* replayed less history than the boot seed had.

That is a real regression in the sensor-warmup path, so the honest defect-level verdict is 🔴 REGRESSED,
not merely still-broken. I did **not** revert it this cycle: the regression is confined to warm-up
progress on cold sensors, the desk is trading and PnL is up 13.06%, there is no DANGER flag, and a revert
is itself a code change that would corrupt the 3/6 measurement window. It becomes item #1's fix next
cycle, and the fix is now *monotone re-seeding* — not last cycle's "shorten the cadence", which would
only have regressed these names faster.

## Attribution this window (honest split)

**Desk activity, but not ADR-0131's doing, and cause not separable.** The enabling event was the boot
seed: **7 names** warmed their σ sensor at **10:36:40** (NVDA, MSFT, KO, AAPL, GOOG, AMZN, NQ) — the
pre-existing ADR-0071 path, untouched by ADR-0131 — where the previous process had warmed one. That is
what lifted the ADR-0126 σ veto across the desk. ADR-0131's retry ran only *after* that, and every name
it touched stayed cold. **It takes no credit for the PnL.** How much of the move is market drift on
freshly-opened intraday positions versus selection cannot be separated from these numbers, so I claim no
cause. Note also that `/api/attribution` reads `firmTotal` **$167.04598599** = ALPHA **$57.15161336** +
HEDGE **$145.71784918** + MACRO **−$35.82347655**: the hedge book is carrying most of the firm total.

## What remains blocking the rest of the book

`strategy_diag` reads `measured` **29**, `tradable` **16**, `edgeGated` **13 names**, each annotated
`no positive OOS edge` — PFE (momentum **−137.76114263**, mean-rev **−1.56304119**), PG
(**−82.99627912** / **0.00000000**), GOOGL (**−58.18505489** / **−41.67661313**), and ten more. That is
the ADR-0064 gate working as designed. Per the standing priority the answer stays **a new signal with
genuinely measured edge**, never a looser gate.
