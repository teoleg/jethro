# ADR-0075: The edge gate tests every name against its own cost — on both sides, not just the veto

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** execution, tca, fusion, risk

## Context

ADR-0064 put a cost-aware gate at the sole order origin: unless some source's **measured** expectancy
beats the desk's **measured** round-trip execution cost with significance, fusion is reduce-only.
ADR-0072 then established the principle that **cost is a per-name property, not a desk-wide constant** —
the desk's own TCA spans two orders of magnitude across the names it trades, from a rates future giving
up a fraction of a basis point to a wide-spread equity giving up double digits — and added a per-name
veto: once a source has passed the desk-wide test, a name whose own round trip costs more than that
source's gross expectancy is held reduce-only.

That fixed one half of the defect. The blended hurdle is wrong in **both** directions, and only the
under-charging half was repaired:

- **Under-charging the expensive names** admitted round trips whose cost exceeded the very expectancy
  that opened the gate. ADR-0072's veto refuses those. ✔
- **Over-charging the cheap names** suppresses real, executable edge. A source whose expectancy
  comfortably survives a 0.3 bp round trip is still refused **everywhere**, because the *average* name
  in the book costs twenty times that — a hurdle set by the cost of names the trade was never going to
  be put on in. ✘

The second error is the one that binds today. The desk has been flat for many consecutive cycles with
every source measured against a single hurdle that is correct for approximately none of the universe.
The lesson was already written down when ADR-0072 shipped — *"a gate that compares edge to cost must
compare them at the granularity cost is incurred"* — and then applied to only one of the two comparisons
the gate makes.

There is a second, quieter inconsistency. The desk-wide test is a **t-test** (surplus over standard
error, against a significance hurdle); the ADR-0072 per-name veto is a **raw comparison of means**
(`grossEdge > cost`). So a name whose round trip ate 94% of the measured edge passed the per-name test
while a name with a *larger* surplus could fail the desk-wide one. Two different bars for the same
question.

## Decision

**One rule, applied at the granularity cost is incurred, on both sides.** For a source `s` over the
rolling telemetry window and a name `n`:

```
cost_n = measured round trip for n, or the desk's blended measured round trip when n has never filled
net    = avgReturnBps_s − cost_n                 // the expectancy the desk actually keeps in n
t      = net / stdErrorBps_s                     // is that surplus distinguishable from zero?
clears = resolved_s ≥ minSample  ∧  t ≥ tHurdle
```

- **Per name:** risk may be increased in `n` when **any** source clears in `n`.
- **Desk-wide:** the gate is open when some source clears **somewhere** — which, because the test is
  monotone decreasing in cost, is exactly the same test evaluated at the **cheapest round trip the desk
  can actually pay**. One evaluation answers it; no separate blended hurdle is needed or used.
- **A name with no measured cost of its own** (never filled in this feed mode) is charged the desk's
  **blended measured** round trip. Inventing a cost for it would be a number without provenance
  (ADR-0016 / invariant 7), and the blend is the desk's own measurement — not a guess. This leaves such
  a name facing *precisely* the bar it faced before this change.

`minSample` and `tHurdle` are unchanged, and remain statistical conventions rather than money numbers.
**No new dial is introduced and no existing one is retuned.** Every cost in the rule is measured by the
desk from its own fills.

### Worked example

A source with 36 resolved observations, measured mean +5.00 bps and dispersion 12.00 bps, so
`stdError = 12.00/√36 = 2.00`. Against measured round trips (2 × one-way implementation shortfall):

| name | round trip (bps) | net = 5.00 − cost | t = net / 2.00 | verdict |
|---|---|---|---|---|
| blended (desk) | 6.92700 | −1.92700 | −0.9635 | ✘ — the **old** desk-wide verdict: SHUT, everywhere |
| ES | 0.29122 | +4.70878 | **+2.3544** | ✔ increases allowed |
| MSFT | 2.92748 | +2.07252 | +1.0363 | ✘ reduce-only |
| GOOGL | 20.10561 | −15.10561 | −7.5528 | ✘ reduce-only |
| JNJ (never filled) | 6.92700 (blend) | −1.92700 | −0.9635 | ✘ reduce-only — the bar it faced before |

The old rule refused all five. The new rule opens exactly one — the name where the edge demonstrably
survives what trading it demonstrably costs.

And the tightening half, a source measured at +18.00 bps with `stdError = 5.00` against a name costing
17.00 bps: ADR-0072's veto admitted it (18.00 > 17.00); the surplus is 1.00 bps on a 5.00 bps standard
error, `t = 0.20`, so it is now refused. A round trip that eats 94% of the measured edge is not a trade
the measurement supports.

### Why this is not a loosening of the safety floor

The edge gate sits strictly **above** the deterministic floor — the pre-trade guardrail and the firm
drawdown breaker are untouched and still decide what is *safe*; this decides only what is *worth paying
for*. Within the gate itself the change is provably **monotone**:

- For a name costing **more** than the blend, the new rule is **strictly tighter** than the old one. If
  a source clears at `cost_n > blend` then it also cleared at the blend (so the old gate was open too),
  and `avg > cost_n + tHurdle·se > cost_n` (so the old per-name veto also passed). New ⊆ old.
- For a name with **no measured cost**, the bar is bit-for-bit the old desk-wide test.
- Only a name whose **own measured cost is below the blend** can gain permission — and only when a
  source's expectancy clears *that* cost with the same significance hurdle as before.

A measured-**negative** source cannot be rescued by any cost cross-section: its surplus is negative even
at a zero round trip, so `t < 0 < tHurdle` for every name. This is the material difference from the
continuous risk-appetite gate the loop tried and reverted (❌ BAD, exposure grew with no PnL gain): that
change let below-hurdle and measured-negative sources size at reduced conviction. This one keeps the
binary switch and the significance hurdle exactly as they are, and changes only *which cost* the
expectancy is measured against.

## Consequences

**Good**

- The desk can trade an edge that survives in cheap names without needing an edge large enough to
  survive in its most expensive ones. That is the whole point of having a cost cross-section.
- One rule instead of two different bars for the same question; the desk-wide verdict is now a derived
  consequence of the per-name test rather than an independent, differently-shaped test.
- Expensive names get *stricter*: significance, not a raw comparison of means.
- Naturally concentrates the first risk the desk puts on into its cheapest-to-trade names, which is also
  where a wrong signal costs least to unwind.

**Bad / risks**

- The gate can now open on evidence from a single cheap name. That is intended, but it means the first
  positions will be concentrated rather than diversified across the universe. Breadth is restored as
  more names' costs fall below the passing source's edge, not by relaxing anything.
- The blended figure is a documented **lower bound** on true round-trip cost (it omits the cash
  commission, which `execution_quality` stores without the multiplier needed to express it in bps — see
  the deferred register). That understatement is unchanged by this ADR but now applies per name; a name
  whose commission is large relative to its spread is charged too little. Tracked, not fixed here.
- Per-name TCA is a thinner sample than the blend (tens of fills, not hundreds), so a name's measured
  cost is itself noisy. The significance hurdle is applied to the *edge*, not to the cost estimate.

**Neutral**

- `EdgeGate.Decision` carries the per-source standard error and the gate's `Params` so any verdict —
  desk-wide or per name — can be recomputed from the record alone. `bestGrossEdgeBps` is removed: with
  every name tested directly, there is no longer a single "claimable edge" number.
- Rate-quoted names are still excluded from the cost map (a basis point of *rate* is not a fraction of
  notional); they are charged the blend like any other unmeasured name.

## Alternatives considered

- **Keep the blend for the desk-wide test and only lower it.** Any chosen blend is a number without
  provenance and would be tuning the measurement until it passes. Rejected.
- **Charge an unmeasured name the cheapest measured cost.** Would let one cheap name's TCA grant
  permission across the whole unmeasured universe. Rejected — the blend is the conservative, measured
  choice and preserves today's behaviour for those names.
- **Weight the blend by intended notional rather than by fills.** A better blend is still a blend; it
  does not answer the per-name question, and the intended notional is not yet known when the gate is
  evaluated. Rejected.
