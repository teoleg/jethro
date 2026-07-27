# ADR-0101: The no-trade buffer's WIDTH is the desk's own cost-to-edge ratio, not a published convention

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, execution, cost

## Context

ADR-0094 put the desk's no-trade region where it can bind — around the **aim**, at a fraction of the
name's average position, trading only to the region's near edge. That fixed *what the band is measured
against*. It left the band's **width** at `jethro.fusion.position-buffer.fraction = 0.10`, Carver's
published buffering convention.

A convention is what you use when you cannot measure. **This desk measures both inputs the width is a
function of, every cycle, for the edge gate:**

- `roundTripBpsByInstrument` — each name's own measured round-trip execution cost (ADR-0072/0075),
  bootstrapped from its own quoted touch before its first fill (ADR-0099);
- the gross expectancy in bps of every forecast source, at the horizon rung the evidence selected
  (ADR-0077 cohort standard errors, ADR-0081 Student-t hurdle, ADR-0082 ladder).

Both already sit inside `EdgeGate.Decision`, and that decision is already handed to `PositionBuffer`.
The width was the one number in the chain still being asserted rather than read.

**And 0.10 is the wrong width for this desk's numbers.** The live cycle this was diagnosed from:

- one source clears the gate — `reversion`, `avgReturnBps = 8.82` over 500 resolved calls on 65
  cohorts, `t = 9.3` at the 900 s rung; every other source (`trend −4.69`, `momentum −4.80`,
  `social −5.92`) is measured *significantly negative* and is held at the ADR-0097 MIN weight;
- measured one-way slippage on the names actually traded runs `0.49–1.01` bps (`AAPL 0.487`,
  `JPM 0.581`, `JNJ 0.647`, `MSFT 0.668`, `GOOG 1.010`) on top of a 1 bp fee per side;
- and the desk filled **2,254 orders in a day** to hold a **$18.1k** book, paying **$355.89** of ALPHA
  fees against **$879.66** of ALPHA P&L. Roughly two fifths of what the strategy books make is being
  handed to the cost of getting there — while the firm total is `$626.57` on `$34,531.90` of gross.

That is what a band five times too narrow looks like from the fee bill. It is not the ADR-0080 rate
that is wrong — the rate is a derived identity and the position's time constant is correctly one
evidence horizon. It is that the last tenth of every position is being bought and sold at a price the
desk's own arithmetic says the position is not worth paying.

## Decision

**We will set each name's buffer half-width to `max(carverFraction, min(1, 2·C/μ))`**, where `C` is that
name's own measured round-trip cost in bps and `μ` is the gross expectancy in bps of the best-evidenced
source that clears the gate. Carver's 0.10 becomes the **floor** under a measured width, not the width.

### The derivation

Hold a position `n` (in multiples of the name's average position) whose view earns `μ` per unit over the
horizon its edge was measured at, against the quadratic risk penalty every step of this planner is
linear in:

```
U(n)         = μ·n − ½λσ²n²                 maximised at the aim   a = μ/(λσ²)
U(a) − U(h)  = ½λσ²(a−h)²                   the value of closing a gap g = a − h
cost(g)      = C·|g|                        proportional in quantity, as this desk's cost is
```

Closing the gap is worth its cost exactly when `½λσ²g² > C|g|`, i.e. `|g| > 2C/(λσ²)`. Substituting
`λσ² = μ/a` from the aim's own first-order condition:

```
band = a · (2C/μ)        ⇒        width = 2C/μ
```

A pure ratio of two measured basis-point figures. No dial, no money number, nothing chosen.

The *policy shape* is unchanged and is the one proportional costs call for — a no-trade region traded to
its near edge (Constantinides, *JPE* 1986; Davis & Norman, *Math. of OR* 1990). What changes is its
width, and `2C/μ` is the standard myopic benefit-versus-cost threshold for it (Grinold & Kahn, *Active
Portfolio Management* 2e ch. 16). We state it as the myopic threshold, not as the exact dynamic
boundary; the exact boundary under this cost model is wider still, so the myopic form errs toward
trading more, not less.

### Worked example (pinned as a test)

`C = 2.0` bps, `μ = 8.0` bps ⇒ width `= 2 × 2.0 / 8.0 = 0.50` exactly. A name with `target = 200` and
`forecast = 16` has average position `200 × TARGET_ABS/16 = 200 × 10/16 = 125` exactly, so

```
band (measured)  = 125 × 0.50 = 62.500000
band (Carver)    = 125 × 0.10 = 12.500000
```

From a flat book at the derived rate `a = 1 − e^(−30/900) = 0.0327838995…` the aim path is
`0 → 6.557800 → 12.898603`, so on the second cycle the gap is `+12.898603`: **outside** Carver's band
(which trades the `0.398603` beyond its near edge) and **inside** the measured one (which trades
nothing). Repeated every 30 s, that difference is the fee bill above.

### Inputs, and their absence

`C` is the name's own measured round trip, falling back to the desk blend for a name that has never
filled — the identical convention `EdgeGate.Decision.mayIncrease(String)` already applies, so cost is
read one way everywhere in the desk. `μ` is **gross**, not net: the cost is charged once, on the `C`
side, and netting it off `μ` as well would charge it twice.

With no gate, no passing source, a non-positive measured edge or a non-positive measured cost there is
no measurement and therefore **no claim to make** — the convention stands exactly as today. That is the
house rule (invariant 7 / ADR-0016): a number that gates money is measured or cited, never invented.

### One-way

The measured width is used **only where it is wider** than the convention, and is capped at one average
position. Therefore:

- it can only ever **suppress** a trade the desk would have made — never add one, never enlarge one, so
  turnover and cost can only fall relative to today;
- the **aim path is untouched quantity for quantity**, so the desk's intended position — and therefore
  its intended exposure — is unchanged by construction, exactly as under ADR-0094;
- an **exit is still not buffered at all**: a flat target snaps the aim to zero and the whole position
  is traded that cycle, so the ADR-0086 chandelier cut, the ADR-0065 orphan unwind, the ADR-0064
  reduce-only clamp and the deterministic floor above them (pre-trade guardrail, ADR-0027 firm breaker)
  are unaffected by any width whatsoever.

It is feed-agnostic (invariant 9): both inputs are measured off whatever stream is running, so the width
self-calibrates to a live feed's costs and edges with no re-tuning and no sim special-case.

## Alternatives considered

**Leave 0.10 and cut turnover with the ADR-0080 rate instead.** The rate is a derived identity — its
exposure time constant equals the horizon the edge is measured over — so moving it would break the
dimensional soundness ADR-0080 established, and it is the wrong instrument anyway: the rate says how
fast to converge, the buffer says when convergence is worth paying for. Only the second is a cost
question.

**Raise 0.10 to a larger hand-picked fraction.** That is the `$250k` hedge-cap mistake in miniature: a
self-chosen number that hardens into a rule. `2C/μ` costs nothing extra to compute and is defensible
line by line.

**Winsorise or blend the per-name width toward the desk mean.** Dispersion across the names actually
traded is small (`0.49–1.01` bps), so the robustness would buy nothing; the cap at one average position
already bounds the one case that matters, a name whose round trip exceeds half its edge.

**Use the exact Constantinides/Davis-Norman boundary (`~(C)^{1/3}`).** It needs a risk-aversion
parameter this desk has never stated, which would be exactly the invented number the myopic form avoids
— `λσ²` cancels out of `2C/μ` through the aim's own first-order condition and never has to be named.

**Charge the ADR-0084 passive entry cost rather than the measured round trip.** The measured round trip
is what the desk actually paid, and it is already the number every other cost decision in the fusion
path reads. One cost concept, one source.

## Consequences

- **Positive.** Turnover falls precisely where the desk's own arithmetic says a rebalance does not pay,
  and nowhere else. Intended exposure is unchanged, so this is a P&L improvement at constant risk — the
  numerator of the objective, not the denominator. The last hand-set dial in the ADR-0094 buffer becomes
  a measured quantity, and it inherits every improvement to the cost and edge estimates automatically.
- **Negative.** A wider band tracks the aim more loosely, so a fast-moving view is expressed slightly
  later and slightly smaller — that is the trade-off `2C/μ` prices, and it is priced from measurement
  rather than assumed away. A name whose measured cost is stale or over-estimated is buffered too wide;
  the damage is bounded (it under-trades, never over-trades) and self-correcting (the next fill
  displaces the estimate, ADR-0099).
- **Follow-ups.** The realised effect belongs in the ledger as fee-and-slippage per dollar of P&L, which
  needs the `turnover_cost_by_name` Postgres aggregate in the loop report — still erroring, and now the
  measurement that would grade this decision. If the width binds at the cap on names the gate still lets
  increase, that is the cost model and the edge gate disagreeing about the same name and is worth its
  own look.
