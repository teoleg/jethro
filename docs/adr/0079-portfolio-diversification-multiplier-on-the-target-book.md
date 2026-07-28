# ADR-0079: Scale the fused target book by its measured portfolio diversification, not name by name

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** backend, risk, fusion, portfolio-construction

## Context

`jethro.fusion.unit-notional-usd` (OLEG-SET 2026-07-21: 50k typical / 100k at cap) is the desk's
cash-at-risk **per name** at a typical forecast. `TargetPlanner.targetQuantity` applies it to each
instrument in isolation, and nothing downstream of it says anything about the **book**. So a
cross-section of N names is N independent applications of a per-name budget — which is the correct
answer if and only if the names are mutually uncorrelated.

They are not, and the live target book is the extreme case. Every one of the 23 planned targets is
**short**: the `reversion` source reads its −20 cap simultaneously on GOOGL, TSLA, BRK.B, GS, NFLX and
ES, because a common-mode move in a correlated universe looks identical on every name at once. Each of
those names is then sized at ~$65k–$89k of notional, so the intended book is roughly **$1.8M gross and
essentially the same net** — one bet carrying 23 budgets of it — against a firm whose entire recorded
exposure history peaks near $415k. Nothing in the system objects, because no component is looking at
the aggregate: ADR-0076's diversification multiplier is measured across **sources within one name**,
never across names.

Doing nothing leaves this armed rather than dormant. The ADR-0064/0072/0075 edge gate currently holds
every name but ES reduce-only, and it re-opens by itself the moment a source earns its measured cost —
which is the design. The concentration is therefore one gate-opening away from being real, and it
compounds with a second effect already recorded in the loop findings: the cost-based gate admits the
**cheapest** names first, and cheap-to-trade is the same property as index-future, so the gate steers
the desk into exactly the correlated macro names where the aggregation error is largest.

The estimate needed to fix this already exists and is already trusted with money: the EWMA(λ=0.94)
daily-return covariance in `CovMath`, which prices the parametric VaR and feeds the ADR-0038 hedge
advisor, and which currently covers 13 names — including ES, NQ, GOOG, JNJ, SAP and EURUSD.

## Decision

We will scale the whole fused target book by a **portfolio diversification multiplier**, computed from
the measured daily-return covariance over the names that estimate covers. With signed USD notionals
`eᵢ = qtyᵢ × priceᵢ × multiplierᵢ` (ADR-0078) and covariance `Σ`:

```
σ_actual = √( Σᵢ Σⱼ eᵢ eⱼ Σᵢⱼ )      the book's daily σ as its names actually correlate
σ_indep  = √( Σᵢ eᵢ² Σᵢᵢ )           the same book if its names were mutually independent
PDM      = min(1, σ_indep / σ_actual)
```

Every covered target is multiplied by PDM, and its Gârleanu-Pedersen delta recomputed against the
scaled target, before the edge gate clamps anything — so the operator's book shows the sizes that will
actually route. `σ_indep` introduces no new money number: sizing each name alone **is** the
independence assumption, so `σ_indep` is precisely the risk the per-name dial was already claiming.
This makes the book honour that claim; it does not re-set Oleg's dial or add a book-level budget.

Four properties hold exactly. Uncorrelated names give `PDM = 1` and a byte-identical book — genuine
diversification is never penalised. N equal, perfectly correlated names give `PDM = 1/√N`, leaving the
book carrying one per-name budget of risk. The cap at 1 means an internally-hedged book is **never
levered up** on the strength of an estimated correlation — the same one-way safety property ADR-0076
gave the source-level multiplier. And PDM is uniform and positive, so no name flips side: this is a
size control, not a view.

A name outside the estimate's strict-coverage intersection enters neither the sums nor the scaling —
no measurement, no claim, the fallback `PortfolioCorrelationSource` already mandates. A warm-up, an
absent estimate or a failed read all degrade to leaving the book exactly as planned.

## Alternatives considered

**Lower `unit-notional-usd` until the aggregate looks acceptable.** Rejected: it is an owner-set money
dial, and shrinking it is the wrong shape of fix — it penalises a genuinely diversified 3-name book
exactly as hard as a 23-name one-way book, and it silently re-tunes itself wrong every time the
cross-section's width changes.

**Cap firm gross exposure with a new book-level limit.** Rejected as the primary control: a hard cap is
a cliff that truncates whichever names happen to be planned last, distorting the cross-sectional shape
of the forecast, and the cap level would be an invented money number with no provenance. The existing
`RiskLimitMonitor` and firm breaker remain the deterministic floor beneath this; PDM is the sizing
policy above it.

**Carver's full IDM: per-instrument weights summing to 1, times an instrument diversification
multiplier.** This is the textbook construction and is strictly more principled. Deferred, not
rejected: it redefines `unit-notional-usd` from a per-name budget into a book-level one, which is
Oleg's dial to reposition, and it *raises* leverage on a well-diversified book — a change that should
follow evidence that the desk's problem is too little risk, not too much. PDM is the subset of that
construction which can only ever reduce, so it needs no such evidence. Revive when the book is
routinely diversified and measured under-sized.

**Assume ρ = 1 for uncovered names (the conservative fill).** Rejected: it would shrink the book on a
correlation nothing measured, which is a risk number without provenance (ADR-0016 / invariant 7), and
it contradicts the established house fallback of standalone sizing when ρ is unmeasured.

## Consequences

- **Positive:** the desk stops being one gate-opening away from ~$1.8M of one-way index-and-equity
  exposure it never chose. Total exposure — the denominator of the loop's objective — falls for an
  unchanged view, which is the cleanest available improvement to risk-adjusted PnL. Sizer and risk
  engine now read concentration from the same covariance, so they cannot disagree.
- **Negative:** a real cost is paid when the estimate is wrong. The EWMA covariance is built from daily
  closes over a 250-day window that is presently dominated by the seeded history, so the correlations
  scaling an intraday book are longer-horizon than the holding period; if they overstate correlation,
  the book is under-sized and gives up PnL. Coverage is also partial (13 of 35 names), so the control
  bites unevenly — the covered names shrink and the uncovered ones do not, which changes the book's
  cross-sectional shape in a way the forecast did not intend. Both are visible: `portfolioRiskMultiplier`
  and `covarianceCoveredNames` are surfaced on the target book.
- **Follow-ups:** widen covariance coverage so the control applies evenly (the SEED history covers 13 of
  35 names — the rest need seeded daily closes, which is a refdata backfill, not a risk change).
  Re-examine whether a holding-period-matched correlation estimate should replace the daily one, which
  is the same question the loop findings already raise about the expectancy measurement horizon.
