# ADR-0020 — Benchmark curve validation and the assumption ledger

Status: Accepted (2026-08-13)

## Context

Two owner directives, reading the analytics with Kalotay's book in hand:

1. *"Be aware of bad benchmark curves"* — and Kalotay indeed treats curve validation as a precondition of
   everything downstream, which our pipeline did not implement: it parsed the Fed file and trusted it.
   A mis-parsed row (shifted column, unit slip), a corrupt vintage, or a stale curve would have silently
   poisoned every OAS discounted on it.
2. *"Filter out all your assumptions and judgement calls out of the calculation"* — a result must be able
   to prove which of its inputs are documented facts and which rest on a convention someone chose, and it
   must be possible to demand facts-only.

**Citation discipline for this ADR:** the validation steps below follow Kalotay's published discipline
*by concept*. Page/table numbers are NOT cited here because the assistant does not have the book text and
will not fabricate references — the owner is reading the book and attaches real page numbers in the
"book references" table of `docs/model-assumptions.md` as he verifies each mechanism.

## Decision

### 1. Every curve day is validated BEFORE it is stored — failures are quarantined (ADR-0011)

`GswCurveIngest.validate` runs three mechanical checks per day; a failing day is counted, its first
objection reported on the status panel, and it is **never written**:

1. **Internal consistency (repricing the constituents).** The fitted parameters must reproduce the file's
   own published `SVENYnn` zeros where the row carries them, within 1bp — the Fed publishes ~6 decimals, so
   genuine agreement is ~1e-6bp and a 1bp gap means the row was mis-parsed. This is a *parse-error
   detector*, not a model tolerance.
2. **No-arbitrage.** Discount factors strictly decreasing across the tenor grid — equivalently, every
   implied forward positive. A lognormal lattice cannot honestly calibrate to a violation.
3. **Level plausibility.** `CurveSanity`: zeros at 1y/10y/30y inside [−2%, +35%] — bounds reasoned from the
   published record itself (the file's own worst prints: ~17% in 1981, marginally negative bills), set at
   roughly double the historical extremes so they reject corruption, never history.

### 2. The same guards run again at read time, plus staleness

The OAS path re-checks sanity on the stored fit (the gate may tighten after data landed) and enforces
**staleness**: a price is discounted on the curve of *its* market, and the nearest stored curve older than
`muni.curve.max-staleness-days` (default 14 = one missed weekly Fed publication + a long weekend;
**PLACEHOLDER — Oleg to set**) refuses by name rather than pricing a June mark on a May curve.

### 3. The lattice itself refuses what it cannot reprice

`BdtLattice.calibrate` now verifies the bisection residual at every step and throws rather than silently
carrying a mispriced step — Kalotay's repricing discipline enforced *inside* the model, not only at ingest.

### 4. The assumption ledger and strict mode

Every OAS response carries `assumptions[]` — the per-bond non-fact inputs the result rests on (par call
assumed because the OS stated no price; coupon kind treated as fixed without a fund attestation) — and
`assumptionFree: true/false`. `?strict=true` refuses any result that would depend on an assumption,
naming it. Universal *declared* conventions of the model frame (clean-price treatment, Treasury basis,
ACT/365.25 geometry) remain in `conventions` on every response; they are the model's stated frame per
ADR-0018 §3, not silent per-bond choices.

## Consequences

- A corrupt or stale curve now produces a **named refusal or a quarantine count**, never a number.
- "Which of these numbers rest on somebody's call?" is answerable per bond, at a glance, and enforceable
  with one query parameter.
- The UI curve panel states the three validations and shows the quarantine count from the last ingest.
- Tests: `CurveValidationTest` (each check, each direction, plus the lattice's own refusal),
  `OasServiceGatesTest` (staleness, read-time sanity, ledger, strict mode).
