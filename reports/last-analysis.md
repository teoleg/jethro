The planner was targeting a book 2.71× the gross the guardrail permits and 14.74× what the desk holds — so the target was unreachable, the aim never converged, and the desk paid that inflation in turnover every cycle; capped the planned gross at the guardrail's own cap (ADR-0137).

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`,
`/api/signals/telemetry`, `/api/hedging`, `ops_jvm`, `traffic`, `recent_orders`, `orders_by_status`,
`turnover_cost_by_name`, `application.properties`, or the scorer's own output. None is authored here —
invariant 7 / ADR-0016.)*

## Step 0 — `026cda49d` (the ADR-0136 revert): ⏹ SCORED ❌ BAD — window closed, code now free

`scripts/score-change.py score` prints `no pending change to score — nothing to do` and
`reports/.pending-baseline.json` is **gone**: the ledger row `2026-08-04T16:30:08Z` closed the ADR-0116
window at 7 cycles with `t=-1.22` against the 1.5 hurdle. That releases the freeze the last five cycles
were under, so this cycle makes a change.

**I did NOT complete the auto-revert of `026cda49d`.** The ledger flags its `git revert` as failed, but
`026cda49d` is *itself* the revert of ADR-0136, which was also scored ❌ BAD. Reverting it would
**re-apply a graded-BAD mechanism** — the one thing the contract forbids ("never re-attempting the
reverted idea"). Both verdicts share a scorer artefact worth naming: each measured a baseline of
`gross_exposure 0E-8` (a dormant book) against a deployed one, so "exposure grew" fired on a book coming
off dormant — which ADR-0132 calls the goal, not a fault. The ledger owns its verdicts and I have not
touched them; I am recording why the flagged revert is deliberately not being completed.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **$-524.66210431** (`/api/risk` `.total`, firm headline incl. hedge) — **down
   $30.53** since last run, **down $194.50** over the last 3. `UNDERWATER` is the live flag. By book:
   ALPHA **-$519.46439487**, HEDGE **+$51.60179592**, MACRO **-$56.79950536**.
2. **Risk.** Gross **$124,911.39447500** = **8.3%** of the $1,500,000 firm cap, headroom **$1,375,089**;
   net **$-33,192.05447500** = **3.3%** of the $1,000,000 net cap. Gross rose **+$53,463.22** this window
   and **+$105,946.97** over 3 runs — the book is fully deployed (21 equity positions + the ES hedge).
   Not a danger state: no `NEAR FIRM CAP` flag, `riskCuts []`, `riskCutStoppedNames 0`, `bookVolBrake 1.0`,
   breaker untripped.
3. **Cause.** No change had shipped in five cycles, so this window is the running code's own behaviour.
4. **Danger.** No — bleeding, but at 8.3% of the gross cap. So the answer is to fix the loss mechanism,
   not to de-risk; cutting here would forfeit $1.375M of unused headroom against ADR-0132.

## The finding: every sizing control is σ-relative, so nothing constrains NOTIONAL

Last cycle established that cost, not direction, is the loss (`firmTotal -524.66210431` against
`totalFees 369.021411` → pre-fee trading of only **-$155.64**, so fees are the majority of the deficit).
It could not say *why* the desk churns. This cycle the mechanism is in the open, and it is structural.

Summing `/api/fusion/targets` gives a planned target book of **$1,356,452.14** gross against:

| quantity | value | ratio |
| --- | --- | --- |
| planned target book (Σ \|targetQty × price\|, 22 names) | **$1,356,452.14** | — |
| `jethro.risk.max-gross-exposure` (guardrail's cap on the routing book) | $500,000 | **2.71×** |
| held equity gross | $91,999.52 | **14.74×** |

**Why nothing stopped it:** ADR-0083 shares a per-name cash budget out by measured σ; ADR-0079 scales the
book by how much of it is one bet; ADR-0104 caps its ex-ante σ at the median of its own series. All three
are σ-*relative*. **None states a notional**, and on a calm tape a measured σ is small, so none binds —
live: `volBudgetLeverCap 1.0`, `bookVolBrake 1.0`, `portfolioRiskMultiplier 0.8726…`.

That never put risk on — the guardrail still refuses the order. The damage is that the target became
**unreachable**, and the entire ADR-0080/ADR-0094 trading path is a function of the *distance* to it:

- **The aim never converges.** PFE live: `targetQty 3319.769516`, `aim 530.737491`, `currentQty -267.0` —
  short a name whose own target is long twelve times the size, closing at 43 shares per 30s cycle.
- **Turnover scales with the inflation.** The step is `a × gap` at `a = 1 − exp(−30/3600) = 0.0082987…`;
  inflating the target inflates `gap` by the same factor, paid in turnover every cycle. That is the
  missing explanation for cumulative LIVE turnover of **$4,120,150.44** and `orders_by_status` of
  **1,930 CANCELLED** vs **5,214 FILLED**.
- **It over-trades the few and freezes the many.** The ADR-0094 band is also ∝ |target|, so an inflated
  book widens the band past the gap for most names while a handful chase: `insideBuffer` **19** of 22.
- **It never holds for its horizon.** The edge is measured at 3600s; a desk permanently in transit never
  holds through it, so it pays round trips against credit it never collects.

**Order-level post-mortem confirms it.** PFE took ~20 consecutive `BUY 2.000000` fills, one per 30s cycle
from 16:18:33Z to 16:28:42Z, while its `[forecast=…]` walked **+0.0968 → +2.045 → +2.822 → +3.526 →
+4.797 → +6.032 → +9.144**; two minutes later the same name's target had flipped to **-$69,827** at
forecast **-6.04**. That is not a direction call the desk got wrong — it is a book grinding toward a
destination that moves faster than it can travel.

## The change (ADR-0137, `**Status:** Implemented`)

`GrossNotionalCap`, applied after ADR-0104 and before the ADR-0064 gate / ADR-0086 cut / ADR-0094 buffer
so the operator's target book shows what routes:
`plannedGross = Σᵢ |qᵢ·pᵢ·mᵢ|`, `GNM = min(1, capUsd / plannedGross)`, `qᵢ' = qᵢ · GNM`.

**No new money number.** `capUsd` is *wired from* `jethro.risk.max-gross-exposure` — the very cap the
deterministic guardrail already enforces on the routing book. The decision is an identity the desk was
violating, not a dial: *do not plan a book you are forbidden to hold*. One-way (the `min` with 1 means it
can only shrink, never lever up), uniform (no name flips side; the σ controls' cross-sectional shape is
untouched), exact decimal rounded DOWN so the scaled gross is ≤ cap by construction. Byte-identical when
the plan already fits.

**This is not de-risking (ADR-0132).** It shrinks the *target*, not the position — the desk holds $91,999
and will now plan against $500,000. Held gross should *rise* toward a book it can actually reach and hold
through a horizon, instead of chasing one it cannot. The deterministic floor is untouched: guardrail,
firm drawdown breaker, conviction floor, edge gate and instrument cap all still stand.

**VERIFY-BY next run:** from `/api/fusion/targets`, `Σ |targetQty × price|` must be **≤ 500,000** (it was
**1,356,452.14**); secondarily `insideBuffer` should fall from **19** of 22 and the per-cycle
`Σ |deltaQty × price|` should fall materially. `./gradlew -Pci test` green.
