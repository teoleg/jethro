# ADR-0100: The hedge closes the gap to its target at that target's own directional efficiency

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, hedging, risk, execution

## Context

The HEDGE book remains the firm's single worst position and the largest, most persistent drag on the
objective. Across the last five scored cycles its total PnL has gone
`−323.33 → −451.51 → −509.21 → −550.99 → −599.22`: a monotone bleed of roughly `$50` a cycle,
independent of everything the loop changed above it. Against a firm total of `+$572.09` the strategy
books make `+$1,171.32` and the overlay hands back **51% of it**. Nothing else on the board is that
large, and five monotone readings is signal, not a window.

It is not execution cost. HEDGE fees are `$42.75` and the measured ES round trip is `0.204` bps; on
the ~`$15k` of ES gross the overlay turned over in the reported quarter-hour that is cents. The other
`~$556` is **directional**, and its mechanism is now well understood — ADR-0098 recorded it: the
target is `−Σβ·E` on a book whose one measured edge is mean reversion, so the overlay is long the
proxy after a rally and short after a selloff. It is a **momentum position on ES**, taken on the one
view the desk measures as significantly negative (`trend`, `−7.99` bps at the 3600s rung over 17
cohorts). Every round trip it makes is expected to lose.

ADR-0098 attacked the **level** of that target — it subtracts one σ of the target's own step, so a
target inside its own noise is set flat — and it scored ✅ GOOD. But the level is only half of the
problem, and the live telemetry shows exactly which half is left. The reported cycle has a raw target
of `−$6,039.12` against `σ_step = $958.51`: the target is 6.3 σ, so the ADR-0098 shrink is barely
binding (it removes 16%) and the overlay is free to chase. And chase it does. Over eleven consecutive
cooldowns the desk sent BUY `0.0068`, BUY `0.0097`, BUY `0.0022`, BUY `0.0060`, BUY `0.0037`, SELL
`0.0046`, SELL `0.0050`, SELL `0.0008`, SELL `0.0064`, SELL `0.0036`, BUY `0.0058` — `0.0545`
contracts of ES traded to end up holding `−0.00494`. The target is not noise; it is a large quantity
that **wanders across zero**, because it is minus the net of a book that re-signs its names. The
overlay tracks every excursion in full and pays for every one of them.

That is a defect of **rate**, not of level, and the house already has the identity for it. ADR-0080
established on the strategy side that a desk should approach its target at a rate set by how long its
target survives, and that the rate applies only to the risk-increasing leg so a cut always trades in
one cycle. The hedge overlay never got it: `HedgeAdvisor` computes `target − held` and trades the
whole delta the moment it clears the ADR-0069 band.

What is missing is a rate with provenance. It cannot be a hand-set dial — an invented number that
gates money is exactly what `CLAUDE.md` forbids, and a dial tuned to the sim would not survive a feed
switch. It has to be measured from the stream, and the thing to measure is already named in the
owner's thesis: **the efficiency ratio** — of the distance a series travels, how much is displacement
and how much is round trip.

## Decision

**We will make the hedge close the fraction of the gap to its target that the target's own path has
earned, and only when the move grows the overlay.**

### The rate

The executing hedge loop already samples the raw target notional `T` once per cooldown for ADR-0098.
On that same series, with the same `λ = 0.94` (RiskMetrics — the decay `CovMath` uses for the firm
covariance and `HedgeTargetChurn` uses for `σ`; the desk keeps **one** memory constant), take

```
D_t = λ·D_{t−1} + (1−λ)·s_t          s_t = T_t − T_{t−1}        D_0 = 0
A_t = λ·A_{t−1} + (1−λ)·|s_t|                                    A_0 = 0
E   = |D| / A   ∈ [0, 1]
```

`E` is Kaufman's efficiency ratio in exponential form. `|D| ≤ A` by the triangle inequality on
identical weights, so `E ∈ [0,1]` with no clamp and no dial. Both recursions start at **zero**, not
seeded: the warm-up factor `(1−λⁿ)` is then identical in numerator and denominator and cancels exactly
in the ratio, so `E` is unbiased from its first reading. `n` steps of `+x` give `E = 1` exactly; a
perfect `+x, −x` round trip gives `(1−λ)/(1+λ) = 0.0309`; the three-step path `+x, −x, +x` reads
`0.334183` against Kaufman's `0.3333` over the same window. `E` is published only from the second
step; before that the hedge tracks in full, exactly as it does today.

### The application

Let `d` be the ADR-0098 target quantity on the selected proxy and `h` the held quantity. The traded
target is

| case | | |
|---|---|---|
| same sign (or `h = 0`) and `\|d\| ≤ \|h\|` | a reduction | `q = d` |
| same sign (or `h = 0`) and `\|d\| > \|h\|` | growing the overlay | `q = h + E·(d − h)` |
| opposite signs | cut free, rebuild rated | `q = E·d` |

in exact decimal at 6dp, `HALF_EVEN`. Everything downstream — the per-proxy target map, the ADR-0069
no-trade band, the delta, the order — is unchanged and consumes `q` in place of `d`.

### Why this is safe to run unattended

- **One-way.** In every branch `q` lies on the segment between `h` and `d`, so `|q| ≤ |d|` whenever
  the move grows the overlay. A rate estimated from the stream can only ever leave the hedge *smaller*
  than ADR-0098 already allows — never larger, never reversed, never levering the book. `E = 1`
  reproduces the previous behaviour exactly.
- **The unwind promise is untouched.** A reduction, including a full unwind and including the free leg
  of a move that crosses flat, still trades in one cycle. ADR-0069's guarantee that a hedge can always
  be got out of at any book size survives verbatim.
- **It cannot mistake a real hedge for churn.** A target that is genuinely going somewhere reads
  `E → 1` and is tracked in full — the protection is intact precisely when systematic exposure is
  actually building. It is only a target that returns to where it came from that goes untraded, and by
  construction that target had nothing to protect.
- **Feed-agnostic.** `E` is a ratio of the stream to itself. No price level, no dollar threshold, no
  volatility assumption; the same code self-calibrates on sim, live or replay (invariant 9).

### Worked example (the live cycle)

Held `h = −0.004940` ES, ADR-0098 target `d = −0.018641` ES, contract money value
`5451.18 × 50 = $272,559.00`. Same sign, `|d| > |h|` → growing.

- At `E = 1` (today): `q = d = −0.018641`, delta `−0.013701` ES = **`$3,734.18`** sent this cycle.
- At `E = 0.25`: `q = −0.004940 + 0.25 × (−0.013701) = −0.00836525 → −0.008365`, delta `−0.003425` ES
  = **`$933.55`**.

And the amplitude, which is the thing that actually costs money. Under a target that alternates
`±0.018641`, today's overlay swings the full `0.037282` contracts per cycle. Under the rule, the free
cut takes it to flat and the rated rebuild caps it at `E × 0.018641`, so the steady-state round trip
is `2·E × 0.018641` — at the oscillation floor `E = 0.0309` that is a **32× reduction in the distance
the overlay travels**, while a trending target still gets hedged in full.

## Consequences

- The HEDGE book's directional bleed should fall roughly in proportion to the distance the overlay
  travels; firm gross falls with it, since the overlay's notional is part of it. Both axes of the
  objective move the right way, or the change is wrong and the ledger will say so.
- The firm carries slightly more systematic exposure while a genuinely new exposure is being hedged
  in — bounded by `E`, which rises toward 1 exactly when the exposure is persistent. The deterministic
  floor is unchanged: the pre-trade guardrail and the firm drawdown breaker are untouched, and this
  control can only ever reduce a position.
- `/api/hedging` gains `trackingRate` alongside `rawTargetNotionalUsd` and `churnSigmaUsd`, so the
  rate the overlay is being held to is visible rather than inferred, and the rationale string states
  the arithmetic.
- This is the **rate** half of the problem ADR-0098 opened on the **level**; the two compose and are
  independently revertable. If the ledger scores this BAD, the sizing and rate sides of the hedge are
  both closed, and the next question is the one ADR-0098 deferred: whether a mean-reversion book wants
  a beta overlay at all.

## Alternatives considered

- **A hand-set smoothing constant** (`jethro.hedge.tracking-rate: 0.25`). Rejected: a number that
  gates money with no source, and it would need re-tuning on every feed. The whole point is that the
  stream sets the rate.
- **Gârleanu–Pedersen optimal trading** — the position as an EWMA of the target at a rate set by the
  ratio of risk aversion to trading cost. The correct theory, and the right thing to reach for later;
  rejected now because it requires a risk-aversion parameter that would be invented.
- **A wider no-trade band on the hedge.** Rejected already by the 2026-07-27 post-mortem: a band
  trades the same wrong position less often and leaves the position in place. The loss is in the
  amplitude of the overlay, not in the frequency of its orders.
- **Turning the equity hedge off.** The honest candidate, and it would improve both axes today. Held
  back deliberately: it is a strictly larger, harder-to-reverse decision, and it should be taken
  against a measured overlay rather than a churning one. This ADR makes the overlay behave; if it
  still bleeds afterwards, that decision is next and it will be made on clean evidence.
