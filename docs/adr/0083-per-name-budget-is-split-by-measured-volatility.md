# ADR-0083: Split the per-name cash budget by each name's MEASURED volatility, not equally

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** trading, risk, sizing, fusion

## Context

`jethro.fusion.unit-notional-usd` is ONE flat cash figure applied to every instrument in the fused
book. A name's contribution to the book's risk is therefore proportional to its own volatility, and on
this universe that dispersion is large: measured over the stored daily closes, σ runs from **0.435%/day
(EURUSD) to 3.236%/day (NVDA)** — a **7.4× spread** across 13 names with enough history to measure.
Flat cash means NVDA brings 7.4× the risk of EURUSD for the same dollars. A "23-name cross-section" is
then, in risk terms, a handful of names plus rounding, and the desk's realised PnL per unit of exposure
is set by whichever of those few happened to move — not by the forecast that was supposed to be
expressing a view.

ADR-0079 already prices the *correlation* half of this concentration problem, and its own derivation
names the gap: the independence benchmark it scales back to is `σ_indep = √(Σᵢ eᵢ² Σᵢᵢ)`, whose terms
are unequal across `i` **precisely because `Σᵢᵢ` is**. It corrects for the names being one bet; nothing
corrects for one name being seven times the bet of another. The two are orthogonal and both are needed.

Doing nothing keeps a risk-budgeting error that costs exactly the quantity the improvement loop
optimises — total PnL per unit of total exposure — and does so silently, because gross notional looks
evenly spread while risk is not. The measurement needed to fix it already exists and is already trusted
for money: the EWMA(λ=0.94) daily-return covariance behind the parametric VaR, the ADR-0038 hedge
advisor and the ADR-0079 multiplier.

## Decision

**We will split the per-name cash budget by each name's own measured daily volatility, so every covered
name contributes the same standalone risk instead of the same cash.** Over the names `C` being sized
that the covariance covers:

```
σᵢ    = √Σᵢᵢ                              each name's measured daily return vol
σ̃ᵢ    = winsorise(σᵢ) into [p, 100−p]     robust: one degenerate estimate cannot dominate
σ_ref = |C| / Σᵢ (1 / σ̃ᵢ)                 the HARMONIC mean of the winsorised σ
kᵢ    = σ_ref / σ̃ᵢ                        this name's share; notionalᵢ = unit-notional × kᵢ
```

Targets are linear in the budget, so a new pure `VolatilityBudget` scales each covered target quantity
by `kᵢ` and recomputes its order delta. It runs **before** `PortfolioRiskNormaliser`: "how much of this
book is one bet" is only well posed once the names are comparable.

The harmonic mean is not a taste — it is the **unique** reference for which `Σᵢ kᵢ = σ_ref · Σᵢ(1/σ̃ᵢ) =
|C|`, i.e. the covered budgets still sum to exactly what they summed to before. **No new money number
is introduced and `unit-notional-usd` is untouched**, keeping Oleg's meaning exactly: the cash for a
name of *typical* volatility. Because realised gross also carries each name's forecast, the book is
additionally **capped at the gross it replaced** — this can redistribute risk or shrink the book, never
lever it up on the strength of an estimated σ (the one-way property of ADR-0076 and ADR-0079).

A name with no measured σ keeps the flat dial: no measurement, no claim (ADR-0016 / invariant 7).

## Alternatives considered

**Leave it flat and let ADR-0079's multiplier absorb it.** Rejected: PDM is a single *uniform* scalar
over the book. It can shrink the whole book, but it cannot move budget from a loud name to a quiet one,
so it cannot change the *shape* of the risk — which is the entire defect. The two controls are
orthogonal, and PDM's own derivation assumes the per-name budgets are the risk statement they claim.

**Target each name's σ against a stated annualised risk budget** (`notionalᵢ = budget / σᵢ`, the
textbook vol-target form). Rejected *for now*: it requires a new firm-level money dial with no
provenance, and it can grow the book without limit as σ falls. The harmonic-mean form gets the same
`1/σ` shape with zero new money numbers and a hard no-lever-up property. Revive it when Oleg sets an
explicit firm risk budget — at that point this becomes its special case.

**Use realised intraday tick volatility rather than daily closes.** Deferred, not rejected: it would
react faster to a regime change. But it would be a *second*, differently-measured volatility sitting
beside the one the VaR and hedge advisor use, and two risk engines that can disagree about how volatile
a name is are worse than one that is slightly slow. Revive it if the daily estimate is shown to lag a
measured vol spike the desk then pays for.

**Equal-risk via inverse-variance (`1/σ²`) rather than inverse-vol.** Rejected: `1/σ²` is the
minimum-variance/Kelly weight under an assumed *equal Sharpe*, i.e. it embeds a return forecast. Risk
parity is `1/σ`, and the return view here already lives in the forecast — expressing it twice would
double-count it.

## Consequences

- **Positive:** the cross-sectional forecast, not the accident of which names are volatile, decides
  where the book's risk sits — a direct improvement to PnL per unit of exposure. Each covered name's
  standalone risk becomes identical by construction. Every σ comes from the estimate the risk engine
  already uses, so sizer and risk engine cannot disagree. Gross is capped at what it replaced; no dial
  is re-set; degenerate inputs leave the book byte-identical.
- **Negative:** the book will hold *more units* of quiet names (FX, rates futures) and fewer of loud
  ones, so **turnover and per-name execution cost shift toward the cheap-but-large names** — the edge
  gate's per-name cost test (ADR-0075) now bites on a different set of names than before. Sizing is
  also now coupled to covariance warm-up: until the estimate is warm the book is planned flat-budget as
  today, so behaviour changes *when* the estimate arrives rather than smoothly. And an EWMA daily σ is
  backward-looking — it will under-size a name entering a vol spike until the spike is in the window.
- **Negative (bounded, stated):** nearest-rank winsorisation only clips the lowest name once
  `⌈p/100·n⌉ ≥ 2`, i.e. above `100/p` covered names (>10 at the shipped 10%). Below that a degenerate σ
  is not clipped; what still holds is the structural bound `Σkᵢ = |C|` (no name can take more than the
  whole covered budget) plus the gross cap. The residual failure mode below the threshold is
  concentration, not leverage. This is documented at the call site rather than papered over.
- **Follow-ups:** `volBudgetNames` / `volBudgetDispersion` / `volBudgetLeverCap` are surfaced on the
  target book so the split is auditable from the report. `jethro.fusion.vol-budget.winsor-pct=10` is a
  robust-scaling convention, **PLACEHOLDER — Oleg to set**; it moves no total. If Oleg sets an explicit
  firm risk budget, supersede this with the stated-budget vol-target form above.
