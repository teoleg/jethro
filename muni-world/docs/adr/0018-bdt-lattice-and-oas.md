# ADR-0018 — BDT lattice and the first OAS calculation

Status: Accepted (2026-08-12)

## Context

Every input the mission's north-star calculation (ADR-0002: Kalotay-style OAS on callable munis) needs now
exists: terms and quarterly fund-attested prices per CUSIP (ADR-0016), call schedules for OS-read bonds
(ADR-0015), a daily zero curve on any date since 1961, and a measured rate volatility with a band
(ADR-0017). What does not exist is the model. This ADR fixes the model family and the conventions of the
first implementation, so its numbers mean one stated thing.

## Decision

### 1. Model: Black-Derman-Toy, constant σ

A recombining binomial **lognormal short-rate lattice** — node rates `r(i,j) = a_i · exp(σ√Δt · (2j − i))`,
risk-neutral probability ½ up/down. Chosen because:

- it is the model family Kalotay co-authored and built his muni practice on — the book this project follows;
- lognormal dynamics keep rates positive, and the σ it needs is exactly the **measured LOGNORMAL** estimate
  ADR-0017 already produces (the normal/Hull-White estimate stays available for a later comparison model);
- constant σ (flat term structure of vol) is the simplest model that prices the call; a calibrated vol term
  structure is a later refinement, not a v1 requirement.

**Calibration is exact by construction:** forward induction with Arrow-Debreu state prices, solving each
step's `a_i` (bisection) so the lattice reprices the benchmark discount factor `P(0, t_{i+1})` at every
step. The lattice therefore reproduces the GSW zero curve to solver tolerance before any bond is priced.
At σ = 0 the construction degenerates to the curve's exact forward rates — a testable identity.

### 2. Dates: the curve is read as-of the PRICE's date

OAS is solved from the quarterly filing mark (`val_per100`), so the discount curve is the GSW fit **on or
before `val_as_of`** — the curve the market implied on the day the price is from. Pricing a March mark on
today's curve would manufacture spread out of rate moves; that is a bug this decision forbids. (Quarter-ends
fall on weekends; the Fed publishes business days, hence *on or before*, with the chosen curve date reported
in the output.)

### 3. Conventions (each stated in the output, none silent)

- **Steps:** `n = max(2, round(2T))`, `Δt = T/n`, `T` in ACT/365.25 years from the price date to maturity —
  near-semiannual steps that land exactly on maturity.
- **Coupons:** fixed-rate semiannual (the muni convention), `coupon/2` per step. Filing-attested
  `couponKind` of Floating/Variable is **refused**, not approximated.
- **Price basis:** `val_per100` is treated as a **clean** price (fund fair-value practice books accrued as a
  separate receivable). N-PORT does not state this explicitly, so it is a named convention, not a fact.
- **Call:** American on/after the OS call date, checked at each lattice step from the first step time at or
  after the call date; exercised by the issuer to minimise value (`V = min(continuation, call price) +
  coupon`). A stated call date with no stated price uses **par (100)** — the cited modern-muni convention —
  and the output flags `assumedParCall`.
- **OAS:** a continuously-compounded spread added to every node rate; solved by bisection in ±1,000bp to
  match the clean price; reported in **basis points at 2dp, rounded once** (the ADR-0017 §4 exactness
  boundary: the solve is transcendental floating-point; every stored/displayed result is rounded once at a
  declared scale).

### 4. σ enters as the ADR-0017 band, never one number

Each OAS is computed at the measured σ **and** at the p10/p50/p90 of rolling one-year realized vol. The four
numbers ship together. A single-σ OAS display is a bug (ADR-0017 consequence, restated here because this is
the component that could most easily violate it).

### 5. Refusal is a first-class output

No OAS is computed — and the reason is named — when: the bond has no price; terms are incomplete; the coupon
is not fixed; the fund flags say default/arrears (the price reflects credit distress, and a rate-model OAS
on it would be noise); **or callability is UNKNOWN** (no OS read — treating unknown as non-callable would
invent the most important term in the model, ADR-0011). A NON_CALLABLE bond gets a straight-bond OAS — the
same solve with no call check.

### 6. Basis honesty: this is OAS versus TAXABLE Treasury

Until the ADR-0017 muni-ratio leg has measurable coverage, the benchmark is the Treasury curve. Tax-exempt
yields sit **below** taxable yields, so most munis will show **negative** OAS on this basis. That is
expected, not a bug, and the UI must say so where the number renders. The v1 output is therefore a
**relative-value ranking** across bonds on a common stated basis — not an absolute cheap/rich verdict. The
verdict becomes absolute only when the tax-exempt curve leg exists.

## Consequences

- The OAS-ready set is exactly the readiness panel's `modelableCallable` (+ confirmed non-callable) bonds:
  loading Official Statements remains the way the priced universe grows.
- Results are as-of quarter-end (the price's date) — a valuation-history OAS series per bond falls out of
  the same code by iterating `valuation_history`, deferred until the single-date output is trusted.
- Model risk is bounded and visible: calibration is exact against the curve, σ carries its band, every
  convention above is echoed in the API response.
