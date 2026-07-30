The revert re-verified on a third, independent JVM — a whole-log duplicate check proves every cold sensor logs exactly one seed line, so the mechanism that scored BAD cannot recur — and since it is only 3/6 cycles into its measurement window I made no change, spending the cycle instead on pinning ALPHA's churn to a specific inverted fallback: the ADR-0101 cost-aware no-trade band widens to `2C/mu` only when expectancy is measured, and with the edge gate reading no positive OOS edge on all 13 names it is never measured, so every name silently falls back to the NARROW 0.10 convention — the churn-permissive branch — when the economics say an unmeasured or non-positive mu implies an unbounded band.

*(Every figure below is read from the live endpoints, `logs/report.md`, `reports/improvement-ledger.md`
or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

## Situation (answered first)

1. **Money.** `/api/risk` `.total` reads total PnL **$130.80462716**. The SITUATION header computes
   **+$16.33** on the run and **+$5.56** across the last three. The book is **not bleeding** this window.
   `run-status.json` still reads `pnl_growth_pct` **−19.1** against `pnl_target_pct` **1.0**, `on_track`
   **false**, `stale` **true**, `underwater` **false** — the growth target is missed, but that heartbeat is
   stamped **17:34:04Z**, before this JVM booted.
2. **Risk.** Gross **$36483.18000000** = **2.4%** of the $1,500,000 firm cap, headroom **$1,463,517**; net
   **$2427.90000000** = **0.2%** of the $1,000,000 net cap. Flags: **none**. 20 equity positions plus the
   ES hedge — not DORMANT, and 97.6% of the gross cap is unused.
3. **Cause.** Last cycle's change is the completed revert of the ❌ BAD ADR-0131 cold-sensor re-seed. It is
   still under measurement: the scorer prints `64a7a6336 still accumulating evidence (3/6 cycles)` and
   `reports/.pending-baseline.json` is present, so per the contract I made **no code change** — piling one
   on top would destroy the evidence.
4. **Danger.** No. Gross *fell* **−$18,702.44** while PnL rose, with no flag set and the cap 97.6% unused.
   Neither the exposure cap nor the drawdown breaker is anywhere near.

## Step 0 verification — ✅ VERIFIED on a third independent JVM

PID **3603471**, boot **13:34:34** local `-04:00` — a different process from the 17:00Z and 17:30Z
verifications, so all four pre-registered legs are re-tested rather than re-read. The binding leg is a
**count**, not a timestamp (Rule 142): across the whole running log, keyed by lifecycle+name, no cold-sensor
line appears more than once — the duplicate check returns nothing — so the second re-seed wave whose
wave-over-wave regressions scored ADR-0131 ❌ BAD has no mechanism to recur. ADR-0071 boot seeding still
fires (**58** of **63** `sensor warmed` lines stamped **13:34:40**–**13:35:15**), and all 11 post-boot
`warmed` lines were checked individually per Rule 143: **7** are ADR-0089 covariance rebuilds and **4** are
genuine first-seeds of late-arriving names (JPM, GOOGL, META, AUDUSD). No re-seed code remains in the tree.

## Diagnosis — what the cycle went into instead of a change

Must-fix **#1** has read "ALPHA re-plan churn" for three runs; this run pins the actual line. `fusion_targets`
shows AAPL at `targetQty` **−153.110674**, `currentQty` **−12.0**, `deltaQty` **−1.379481** — the desk steps
toward its target at the ADR-0080 *derived* rate (`adjustment-rate=0` ⇒ a = 1 − exp(−30/3600) =
**0.0082987…**, a 3600s e-folding time), while the aim itself is **recomputed every 30s** and is dominated by
`reversion` at weight **1.5417176854024859** against `trend` **0.3723889694091483** — a mean-reverting view
whose sign flips far faster than the desk converges. The round-trip signature is unmistakable in gross shares
traded versus shares held: JNJ **299** traded to hold **13**, AAPL **197** to hold **12**, NVDA **263** over
**119** fills, all at **1.00** bps. ALPHA `feesPaid` **$66.073455** now dwarfs its `totalPnl`
**$8.61831343** — the third consecutive deterioration in that ratio (Rule 145), and `firmTotal`
**$130.80462716** is carried entirely by `hedgePnl` **$158.00979028** with `strategyAlpha` at
**−$27.20516312**.

The defence for exactly this already exists and is inert. ADR-0101 widens the no-trade band to
`max(fraction, min(1, 2C/mu))`, with the documented fallback *"Unmeasured … or non-positive cost or edge ⇒
the convention, unchanged."* `strategy_diag.edgeGated` reads **13** names, **every one** `no positive OOS
edge`. So mu is never measured, the widening never applies, and every name falls back to
`position-buffer.fraction=0.10` — the narrow branch. That is economically backwards: mu ≤ 0 does not mean
"use the convention", it means `2C/mu` is **unbounded** — rebalancing is never worth its cost. Correcting the
fallback to a maximal band on the risk-**increasing** leg only (exits keep trading in full per ADR-0080) is
the single change queued for the next scored cycle, shipping with its ADR at `**Status:** Implemented`. Its
stated risk — that widening on every unmeasured name drives the book toward DORMANT — is pre-registered in
the VERIFY-BY, which requires gross exposure to *hold*, not just turnover to fall.

## Honest attribution

A JVM restart lands inside this window and the pending change is a revert that opens and closes nothing. The
**+$16.33** / **−$18,702.44** move is therefore **not separable** into market versus change from these
numbers, and I claim neither (Rule 141). Separating it is exactly what the 6-cycle window exists for.
