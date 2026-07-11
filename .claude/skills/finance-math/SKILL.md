---
name: finance-math
description: Rigor rules for implementing or reviewing any PnL, risk, pricing, position, or currency-conversion calculation in Jethro. Use whenever code or a design touches financial arithmetic.
---

# Financial calculation rigor for Jethro

## Non-negotiables (mirror CLAUDE.md invariants)

- `BigDecimal` (Java) / decimal logical type (Avro) / `NUMERIC` (Postgres) at
  boundaries; scaled-long decimal fixed-point (declared scale per field, conversions
  only via `common-domain` helpers) in the hot path. Never `double`/`float` for prices,
  quantities, PnL, rates — in either representation. Specify scale and `RoundingMode`
  explicitly at every division — an unspecified rounding is a bug. Scaled-long
  multiplication/division must state its overflow and rescaling strategy (`Math.multiplyHigh`
  / widening to `BigDecimal` when ranges can overflow a `long`).
- Every monetary value has an explicit currency. Every conversion names its FX mark and
  timestamp. No "number that is probably USD".
- Signed conventions stated once and reused: quantity > 0 = long; sells reduce; PnL
  positive = profit for the book.

## Reference formulas (average-cost method, per ADR-0008)

For a fill of signed quantity `q` at price `p` into position `(Q, avgCost)`,
with contract multiplier `m`:

- **Increasing** (same sign or from flat):
  `avgCost' = (Q·avgCost + q·p) / (Q + q)`; `Q' = Q + q`; no realized PnL.
- **Reducing** (opposite sign, |q| ≤ |Q|):
  `realized += (p − avgCost) · (−q) · m` (note: −q, a sell has q < 0);
  `Q' = Q + q`; avgCost unchanged; position flat when Q' = 0 (reset avgCost).
- **Crossing through flat** (|q| > |Q|): split into reduce-to-flat + open-new-position;
  never compute it as one step.
- **Unrealized:** `Q · (mark − avgCost) · m`, in instrument currency, then convert to
  book currency at the current FX mark.

## Working practice

- Before writing code, state the formula and compute **one worked numeric example by
  hand** (e.g. buy 100 @ 10, buy 50 @ 13 → 150 @ avgCost 11.00; sell 120 @ 12 →
  realized (12 − 11) × 120 = 120.00, position 30 @ avgCost 11.00). Put that example in the response and encode it as a unit test
  with exact decimal assertions.
- Test the edge set every time: flat → long, flat → short, partial reduce, full close,
  cross through zero, zero-quantity fill rejected, repeated identical fill (idempotency).
- When a market convention is uncertain (multiplier, day count, settlement lag,
  timezone of a session boundary), say so explicitly, choose the conservative default,
  and leave a `// CONVENTION:` comment at the site.
- Stale marks: risk output must carry the mark timestamp; never present PnL computed
  from a stale mark as current without flagging it.
