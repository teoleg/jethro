Every one of the 23 planned fusion targets is short and each is sized at a full per-name budget, so the desk was one gate-opening away from ~$1.8M of one-way exposure; the book is now scaled by its measured portfolio diversification, so a cross-section that is really one bet can no longer carry N budgets of it (ADR-0079).

## Situation (live, in words)

**Money.** Total PnL is **unchanged** run-over-run — the book was flat at both endpoints of this window,
so there is no mark drift and nothing traded. Across the last three runs PnL is **up**, and growth sits
comfortably above the 1%-per-3-iterations bar. Every dollar of it is realized; there is no open PnL to
give back. Underwater overall, but not bleeding.

**Risk.** Gross and net exposure are both **zero**. Nowhere near the drawdown breaker, no VaR to report,
nothing to cut. The problem is the mirror image of danger: no forward earning power, and — as it turns
out — a badly-shaped book queued behind the gate.

**Cause, and change-vs-market.** Last cycle's change (ADR-0078, fusion sizes in contract terms) scored
⚠️ MIXED at exactly zero movement on both PnL and exposure, which is what a correctness fix on an
untraded book should score. It is not the culprit and I am not reverting it. Attribution this window is
trivial and exact: **zero orders, flat at both endpoints, so 0% of the (zero) move is market and 0% is
my change.** Nothing about the last change is confirmed or refuted by this window — it is simply
unmeasured, and I should not read the flat score either way.

**Danger.** Not bleeding-with-rising-exposure. But there is a live danger in the *planned* book, and it
is the second cycle running where the flags did not point at it.

## What I found

The fusion target book is **short every single name** — GOOGL, TSLA, BRK.B, GS, NFLX, SAP, GOOG, JNJ,
ES, NQ, ZF, ZT, EURUSD, all of them — with combined forecasts clustered between −13 and −18. The reason
is visible in the contributions: `reversion` is pinned at its −20 **cap** simultaneously on name after
name. That is not 23 views. A common-mode move in a correlated universe reads identically on every name
at once, and the cohort telemetry confirms it — `reversion` has 46 resolved observations in **2 cohorts**,
i.e. two draws of the market measured 23 times each.

Each of those names is nonetheless sized at a full per-name budget (~$65k–$89k of notional apiece),
because `unit-notional-usd` is applied by `TargetPlanner` in isolation and **nothing downstream looks at
the aggregate**. ADR-0076's diversification multiplier measures concentration across *sources within one
name*; there has never been an equivalent across *names*. The intended book is therefore ~$1.8M gross
and essentially the same net, on a firm whose entire recorded exposure history peaks near $415k.

This is armed, not dormant — the same lesson the findings memory already records. The edge gate holds
every name but ES reduce-only right now and re-opens by itself the moment a source earns its measured
cost. And it compounds with the effect I logged last cycle: a cost-based gate admits the *cheapest*
names first, and cheap-to-trade is the same property as index-future, so it steers the desk straight
into the correlated macro names where the aggregation error is worst.

## The change (ADR-0079, Proposed)

The fused book is now scaled by a **portfolio diversification multiplier** measured from the EWMA(λ=0.94)
daily-return covariance the parametric VaR and the ADR-0038 hedge advisor already price risk with — so
the sizer and the risk engine cannot disagree about how correlated the book is. With signed USD
notionals `eᵢ = qtyᵢ × priceᵢ × multiplierᵢ`:

    σ_actual = √(ΣᵢΣⱼ eᵢeⱼ Σᵢⱼ)    σ_indep = √(Σᵢ eᵢ² Σᵢᵢ)    PDM = min(1, σ_indep/σ_actual)

Worked example, asserted as an exact-decimal test: three names each $100,000 short at 2%/day vol and
ρ = 1 give σ_indep = $3,464.10, σ_actual = $6,000.00, PDM = 0.5773503 = 1/√3 — each target becomes
$57,735.03 and the book's σ lands back on $3,464.10.

**No new money number.** Sizing each name alone *is* the independence assumption, so `σ_indep` is
precisely the risk Oleg's per-name dial was already claiming; this makes the book honour that claim
rather than re-setting his dial. Uncorrelated names give PDM = 1 and a byte-identical book, so genuine
diversification is never penalised. The cap at 1 means an internally-hedged book is never levered *up*
on an estimated correlation — the same one-way property ADR-0076 established. The scale is uniform and
positive, so no name flips side: this is a size control, not a view. An uncovered name enters neither
the sums nor the scaling — no measurement, no claim.

**What I expect, and what it costs.** Exposure on the covered names falls sharply for an unchanged view;
that is the objective's denominator, directly. The honest downside: coverage is 13 of 35 names (ES, NQ,
GOOG, JNJ, SAP, EURUSD among them), so the control bites unevenly and reshapes the cross-section in a
way the forecast did not intend, and the correlations come from daily closes over a 250-day window that
is longer-horizon than anything this desk holds — if they overstate correlation the book is under-sized
and gives up PnL. Both are surfaced on the target book as `portfolioRiskMultiplier` and
`covarianceCoveredNames` so the next cycle can see them rather than infer them.
