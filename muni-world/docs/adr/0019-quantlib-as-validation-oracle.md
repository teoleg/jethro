# ADR-0019 — QuantLib as the validation oracle; own engine stays the runtime

Status: Accepted (2026-08-13)

## Context

The owner, reading the analytics code with Kalotay's book in hand, asked the right question: hand-rolled
math or a real library — "if we need QuantLib, we use it." The candidates:

- **QuantLib** (C++): the industry's open-source pricing library, and the only candidate that actually has
  the instrument — `CallableFixedRateBond` + lattice engines. Its official Python and Java packages are
  SWIG bindings over the same C++ engines; there is no "Python QuantLib", only C++ called from elsewhere.
- **Strata** (pure Java): excellent schedules/day-counts/conventions, **no callable-bond lattice engine**.
- **finmath** (pure Java): short-rate models for derivatives, no callable muni bond OAS.

So "use a library for the whole engine" means QuantLib at runtime: native ARM builds for the Pi, SWIG
binding maintenance, and an engine whose internals (generalized trinomial trees, its own conventions) sit
much further from the book's presentation than our ~300-line binomial BDT, which maps line-for-line to the
method Kalotay describes. The owner is verifying formulas against the book; traceability to the book is a
feature.

The real requirement behind the question is **trust**: proof the numbers are right, not faith in tests we
wrote against our own hand calculations.

## Decision

1. **QuantLib becomes the independent oracle, not a runtime dependency.**
   `scripts/quantlib-reference.py` (run offline, QuantLib version pinned in the output) generates
   convention-matched reference prices from QuantLib's C++ engines; the JSON is committed at
   `src/test/resources/quantlib-reference.json` and `QuantLibCrossValidationTest` replays every case
   against our engine on every build. Convention matching is exact by construction: flat continuous curve,
   Thirty360 so periods are exactly 0.5y, clean-strike Bermudan calls on coupon dates, Black-Karasinski
   with a≈0 — the same continuous model as constant-σ BDT.

2. **The measured result (QuantLib 1.43, 24 cases), asserted in CI from now on:**
   - **Bullets: worst |diff| = 1.1e-13** per 100 — floating-point identical. Curve construction,
     discounting and lattice calibration are exactly right, by an engine we didn't write.
   - **σ→0 callable: diff 0.000000** — the deterministic-call limit agrees to the 6th decimal.
   - **Callables at σ = 10–20%: |diff| 0.003–0.028 points** vs QuantLib's 500-step trinomial tree — the
     production semiannual-step discretization error, ≈ **0.05–0.5bp of OAS** at 6y duration. Two orders
     of magnitude below the data-quality lines in `docs/model-assumptions.md`.

3. **Our engine stays the runtime.** Pure Java, no native builds, identity-tested AND oracle-validated,
   and directly readable against the book. If a future case ever exceeds the asserted tolerance, that is a
   bug investigation first and a reason to revisit this ADR second.

4. **Re-generation discipline.** The fixtures are regenerated (and the diff reviewed) only when the engine
   changes semantically — new conventions, new instrument features. The generator script, its QuantLib
   version, and the evaluation date travel inside the JSON so a regeneration is diffable and attributable.

## Consequences

- "How accurate are the calculations" now has a measured, externally-anchored answer in CI, not an
  argument: exact on deterministic cash flows, sub-bp on the option component.
- The accuracy roadmap (real 30/360 coupon schedules and accrued — `model-assumptions.md` Tier J; a finer
  exercise grid near the call window) now has a harness that will measure each improvement against the
  same oracle.
- If MMD/BVAL or richer instruments (sinking funds, make-wholes) arrive, the oracle pattern extends: add
  QuantLib cases first, then implement until they pass.
