# ADR-0104: The book carries no more risk than it typically carries — an absolute volatility anchor

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, risk, sizing

## Context

Every size control in the fusion desk is **relative**. None of them states how much risk the book
should carry.

- **ADR-0083 (`VolatilityBudget`)** splits the per-name cash budget by each name's own measured σ so
  every name contributes equal standalone risk. It is *budget-neutral by construction* — its own
  derivation says so: `Σᵢ kᵢ = |C|`. It redistributes; it does not set a level.
- **ADR-0079 (`PortfolioRiskNormaliser`)** scales the book by how much of it is one bet,
  `PDM = min(1, σ_indep/σ_actual)`. Its benchmark, σ_indep, is explicitly *"the risk the per-name
  budget already implies"* — it corrects a book back to an assumption, it does not choose the
  assumption.
- **ADR-0086** cuts a *held* position that has gone wrong. It is an exit, not a level.

So the book's actual planned risk is
`unit-notional-usd × (how many names happen to clear the edge gate) × (how strong their forecasts
happen to be) × the two relative multipliers`. Both of the middle factors are properties of the
cross-section on the day, not of anybody's appetite. The consequence is visible in the loop's own
measurements: firm gross exposure has read `$14.5k → $62.1k → $43.4k → $46.6k → $7.9k → $27.6k` across
consecutive evaluations while nothing about the desk's risk appetite changed, and the loop's
`docs/loop-findings.md` has carried "no absolute book-level volatility target — nothing anchors gross,
which is why it swings 4×" as an open lever for three cycles.

That matters directly for this desk's objective, which is **PnL per unit of exposure**. A denominator
that moves several-fold on its own makes the ratio unmeasurable — and, worse, means the desk carries
its largest risk in whichever cycles the cross-section happened to shout, which is not a decision
anybody made.

**The empirical claim being relied on** is the standard volatility-targeting result: returns are *not*
proportional to risk across risk states, so holding risk constant — in particular scaling **down** in
the high-risk states — raises return per unit of risk. Harvey, Hoyle, Rattray, Sargaison & van Hemert,
"The Impact of Volatility Targeting" (*Journal of Portfolio Management*, 2018); Moreira & Muir,
"Volatility-Managed Portfolios" (*Journal of Finance*, 2017); the same scaling behind the
time-series-momentum results the trend sensor already cites (Moskowitz, Ooi & Pedersen, *JFE* 2012).

**The obstacle that kept this deferred** is the number. A conventional vol target ("12% annualised",
"$X of daily σ") is a money/risk figure with no provenance on this desk, and the house rule
(CLAUDE.md, invariant 7 / ADR-0016) forbids presenting a self-chosen one as a rule. The firm's declared
appetites — `max-firm-drawdown = 50000`, `firm.max-loss-pnl = 75000` — are deterministic breaker levels
two orders of magnitude above this book; a target derived from them would never bind and would be a
control in name only.

## Decision

**Cap the fused book's measured ex-ante volatility at the MEDIAN of its own planned-volatility series.**

Over the names `C` the measured covariance `Σ` covers, with signed USD notionals `eᵢ` — the same
construction, the same coverage rules and the same money boundary as ADR-0079:

```
  σ_planned = √( Σᵢ Σⱼ eᵢ eⱼ Σᵢⱼ )     this cycle's book, at its measured correlation
  σ_ref     = median{ σ_planned }       over the last `span` planning cycles, this one included
  brake     = min(1, σ_ref / σ_planned)
```

and every covered target is multiplied by `brake`, with its order delta recomputed against the braked
target under the cycle's own band and rate.

**The reference is not a number anybody chose.** It is the desk's own typical planned risk, measured on
whatever stream it is trading. So the control introduces no money/risk figure, and it self-calibrates:
the same code on a sim feed, a live feed or a replay anchors to that feed's own volatility, with no
special-casing (invariant 9). What it asserts is only this: **the desk does not carry more risk than it
typically carries.**

It runs **after** ADR-0083 and ADR-0079 (the level is only well posed once the shape is settled) and
**before** the ADR-0064 gate, the ADR-0086 exit and the ADR-0094 buffer — so the anchor is on the risk
the desk *intends*, and the operator's target book shows sizes that will actually route.

### Worked example

Names priced 100.00, contract multiplier 1, daily return vol 2% (Σᵢᵢ = 0.0004), uncorrelated.

- A one-name book targeting 1,000 units is $100,000 of notional, so `σ_planned = 0.02 × 100,000 =
  $2,000/day`. Three such cycles give the series {2000, 2000, 2000}.
- The fourth cycle's cross-section shouts and plans 4,000 units — $400,000, `σ_planned = $8,000/day`.
  The series is now {2000, 2000, 2000, 8000}; its median is (2000 + 2000)/2 = **$2,000**.
- `brake = 2000/8000 = 0.25`, so the 4,000-unit target is scaled to exactly **1,000 units** — and
  because σ is homogeneous of degree 1 in `e`, the braked book's σ is **$2,000 = σ_ref**, precisely.

Two equicorrelated names at ρ = 0.5, $100,000 each: `σ = e·σ_name·√(N + N(N−1)ρ) = 2000√3 =
$3,464.10/day` — the correlation is priced, not assumed. Both are encoded as exact tests.

### Properties (all provable, all asserted as tests)

- **Never grows the book.** `min(1, ·)` discards the case where planned risk sits below its own median.
  One-way, like ADR-0076 / 0079 / 0083 / 0086 before it: an estimated covariance can shrink the desk and
  can never lever it up. This is what makes an estimated statistic safe to size on.
- **Exactly on target when it binds** (homogeneity, above).
- **Sign- and view-preserving.** The multiplier is positive and uniform: no name flips side, the
  cross-sectional shape of the forecast is untouched. A size control, not a view.
- **No ratchet.** The series samples the RAW planned σ arriving at this step, never the braked one, so a
  cycle that was cut cannot drag the reference down and cut the next one further. Without this the
  control converges to zero.
- **Silent when it cannot measure.** Fewer than `min-sample` observations, an incomplete covariance
  matrix, or a degenerate σ all leave the book byte-identical — no measurement, no claim.
- **Never sets a target flat.** Cutting a position that has gone wrong stays ADR-0086's job alone, and
  the deterministic floor (pre-trade guardrail, ADR-0027 breaker) is untouched.

### Dials

`jethro.fusion.book-vol-brake.{enabled,span,min-sample}`. Neither `span` nor `min-sample` is a
money/risk/exposure number — they are estimation conventions, and the control is scale-invariant in σ so
neither can move a size by itself:

- `span = 120` — the estimation window in planning cycles; at the 30 s fusion cadence that is one hour,
  the same window `jethro.fusion.risk-cut.vol-span` and `jethro.fusion.stream-covariance.span` already
  use.
- `min-sample = 30` — the house `jethro.fusion.edge-gate.min-sample` convention, which at this cadence is
  also one selected holding horizon (900 s, ADR-0080/0082) of planning history. Not a separately
  invented number.

## Consequences

**Intended.** Planned gross stops being an accident of the cross-section: the top half of the
planned-risk distribution is scaled back to the median, so the several-fold cycle-to-cycle excursions
are removed while the quiet cycles are untouched. Exposure falls or is flat, never rises, and the
objective's denominator becomes something the desk chose rather than something that happened to it.

**Honest costs and limitations.**

- The brake is silent for its first 30 planning cycles after a restart — fifteen minutes at the current
  cadence. On a desk that redeploys every improvement cycle, a material share of each window is
  unbraked. The σ series is in-memory only; persisting it (as ADR-0071 does for marks) is the obvious
  follow-up and is deliberately **not** in this change.
- The median is a property of the *planned* series, so it drifts with the desk. If the desk's
  planned risk trends up over hours, the reference follows it. This anchors the *dispersion* of risk,
  not its long-run level. A level anchored to a declared appetite still requires Oleg to state one; this
  change deliberately does not invent it.
- Scaling the book down scales expected PnL down with it in the braked cycles. The bet being made is the
  volatility-targeting result above — that the trimmed cycles earn less per unit of risk than the
  average. If the loop's scorer says otherwise on the measured vector, this is revertible in one commit
  and leaves nothing behind.

**Alternatives rejected.**

- *A declared σ or VaR target.* The honest form of this control, and the one to adopt the day Oleg
  states an appetite. Rejected now only because the number would be mine.
- *Deriving the target from the firm drawdown breaker.* `$50,000` against a `~$25k` book: it would never
  bind, and the mapping from a loss limit to a σ needs a confidence convention that would itself be
  invented. A control that cannot bind is worse than none — it reads as risk management and is not.
- *A rolling percentile other than the median* (e.g. the 75th). Better tail behaviour, but "which
  percentile" is exactly the invented dial this avoids. The median is the unique parameter-free choice.
- *Braking on realised rather than planned σ.* Realised σ is measured on positions already held, so it
  answers "was the risk we took large?" — one cycle late. The plan is the only place a size can still be
  changed.
