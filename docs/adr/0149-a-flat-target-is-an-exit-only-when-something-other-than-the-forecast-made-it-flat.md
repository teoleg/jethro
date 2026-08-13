# ADR-0149: A flat target is an exit only when something other than the forecast made it flat

- **Status:** Implemented
- **Date:** 2026-08-12
- **Deciders:** improvement loop (ADR-0063); owner ratification pending
- **Tags:** backend, fusion, execution, cost, turnover

## Context

ADR-0145 made the ADR-0059 conviction floor symmetric: a name may be opened only at
`|f| ≥ min-forecast-to-route`, and a reduction the *forecast alone* authored is held back unless that
forecast would have been strong enough to open the position. A reduction a *risk control* authored is
never held — separated exactly by capturing the planner's target before any control runs.

That change was scored ⚠️ INCONCLUSIVE and kept. This ADR is the postmortem on why it had no
measurable effect, and it is not "tune it harder": ADR-0145 shipped with an exemption wide enough to
admit the single worst instance of the behaviour it was written to stop.

**The exemption.** `ConvictionHold.apply` returns the delta untouched when the controlled target is a
literal zero, on the reasoning that "every control that means *get out* says so by planning the name
FLAT" — the ADR-0086 chandelier cut, the ADR-0065 orphan unwind, the ADR-0027 breaker. That reasoning
is sound about controls and wrong about its converse. `TargetPlanner.targetQuantity` is *linear* in the
combined forecast and returns `BigDecimal.ZERO` the moment that forecast reaches zero:

```java
if (unitNotional == null || price == null || price.signum() <= 0 || combinedForecast == 0.0 || …) {
    return BigDecimal.ZERO;
}
```

So a view that merely finished decaying arrives at the exemption wearing a chandelier cut's clothes.
ADR-0107's own javadoc states the principle — *"an EXIT is what a control ORDERED, not what the
arithmetic happens to read"* — and then reads `target == 0` as the control.

**Why it is the worst case rather than a rounding detail.** Two paths downstream key on the same zero:

- `PositionBuffer.nextAim` **snaps** the aim to flat (`if (target.signum() == 0) return ZERO`) instead
  of stepping it at the ADR-0080 rate, and
- `PositionBuffer.bufferedDelta` works a flat target **in full, unbuffered and unrated**
  (`if (target.signum() == 0) return gap`).

The ADR-0080 rate limit and the ADR-0094 no-trade band are both bypassed. So the cycle carrying the
*least* conviction available produces the *largest* order the desk can place — the entire position,
liquidated at market — where the same position was accumulated one rated step at a time. Buy slowly,
sell instantly, repeat: a ratchet that pays a full round trip per oscillation of a series this desk's
own telemetry measures as uninformative, and that drives the held book toward zero regardless of the
sign of the forecast.

**The live tape names it.** `recent_orders` carries the ADR-0134 reason string, and the trigger is
written out in full: `PFE BUY 240 — fusion exit — target decayed to flat [forecast=-0.0, sources=1]`.
A source is still speaking, no control fired, the forecast is a signed zero, and 240 shares go back at
market. The prior window shows the same reason on WMT, NVDA and BAC. Every number in this paragraph is
read from the run report, not authored (invariant 7).

## Decision

The flat-target exemption is attributed to an **author**, using facts already known at the call site,
never inferred from magnitude. When the controlled target is flat, the reduction routes in full when —
and only when — one of these holds:

1. **A risk control planned this name flat on this cycle.** `FusionLifecycle` already builds the set of
   names the ADR-0086 trailing cut flattened, for the ADR-0134 reason string; that set is now computed
   before the buffer runs and passed into it. The caller also sets this for a name the planner could
   not **value** (`price` null or non-positive), whose zero is a data fact rather than a view.
2. **`sources == 0`** — the ADR-0065 orphan. No live source has a view on a name the desk holds. That
   is an unwind, not a decayed opinion.
3. **The planner's own target was non-zero** before any control ran. The planner wanted a position and
   a control took it to flat, which is exactly the ADR-0145 split working as designed.

Anything else — live sources, no control, and a planner target that is itself flat — was authored by
the **forecast**, and the ADR-0145 arithmetic applies unchanged. With `p = 0` it already yields
`max(0, min(h, 0) − min(h, 0)) = 0`, so the position is held whole and the buffer re-seeds the aim to
where the desk actually is, exactly as it does for the ADR-0064/0075 clamp one branch above.

**No number is introduced.** The threshold remains `jethro.fusion.min-forecast-to-route`, the ADR-0059
floor the desk already applies to entries; setting it to zero disables both halves together
(invariant 7 / ADR-0016). Both new arguments default to the pre-ADR-0149 reading — the six-argument
overload and a null predicate leave every path byte-identical.

### Worked example — the PFE order above

Held `−240`; the combiner's forecast decays to `−0.0` with one live source; no control fired, so the
planner's target and the controlled target are both `0`. Write `s = sgn(held) = −1`, `h = 240`,
`p = s·planned = 0`, `c = s·controlled = 0`:

| quantity | before ADR-0149 | after |
|---|---|---|
| destination the planner alone asks for, `min(h, p)` | — (exempted) | `0` |
| destination the controls impose, `min(h, c)` | — (exempted) | `0` |
| reduction the CONTROLS author, `max(0, min(h,p) − min(h,c))` | — | `0` |
| order routed | `+240` (whole position, at market) | `0` |

And the mirror, unchanged: if the ADR-0086 chandelier cut had flattened PFE on the same cycle, case 1
fires and the full `+240` routes. If the planner had wanted `−0.25` before the gross cap took it to
flat, case 3 fires and the full `+240` routes.

## Consequences

**Intended.** The reason string `fusion exit — target decayed to flat` disappears for any name with a
live source and no control behind it; the desk stops liquidating whole positions at zero conviction,
and the ADR-0080 rate limit and ADR-0094 band once again bound every order the forecast authors. The
ratchet — accumulate at `a·gap`, shed in full — becomes symmetric.

**Accepted.** The desk will hold positions whose forecast has decayed to nothing, for as long as that
lasts. That is the ADR-0132 objective (exposure inside the budget is a resource to use, and dead
exposure is cut by a risk control, not by a decayed opinion), and it moves the exit decision from the
forecast to the risk sensor, which is the owner's stated thesis. Four independent ways out remain and
none is touched: conviction returning on either side, the ADR-0086 chandelier stop, the ADR-0118
trapped-exit path once the edge gate shuts the name, and the ADR-0065 unwind when its sources fall
silent. Gross exposure should RISE — at ~3% of the firm gross cap that is the intent, not a relaxed
limit. The pre-trade guardrail, the firm drawdown breaker and every control above still have the last
word on the order that does route; nothing in the deterministic floor is edited.

**Risk.** If the decayed positions are systematically the losing ones, holding them costs money that
the old liquidation was accidentally saving. The ADR-0086 trailing σ cut is the control that exists for
exactly that, and it is exempt here. The scorer settles it either way over the ADR-0116 window.

## Verification

Next run, from live telemetry only:

- **Primary.** In `recent_orders`, the count of orders whose reason is `fusion exit — target decayed to
  flat` **with `sources ≥ 1`** must be **zero**. Orders with the same reason and `sources = 0` (the
  ADR-0065 orphan) may still appear and are correct.
- **Secondary.** `/api/attribution` `totalFees` as a share of `firmTotal`, and ALPHA's fee share, should
  fall against the pre-change window; `/api/risk` `.total.grossExposure` should not fall.

## Alternatives considered

- **Gate the flat-target liquidation by magnitude** — hold it when the *planner's* target is also flat.
  Rejected: it blocks the ADR-0086 chandelier stop on precisely the names most likely to need it, since
  a stopped-out position's forecast has often decayed too. Author identity, not magnitude.
- **Stop `nextAim` snapping to flat / stop `bufferedDelta` working a flat target in full.** Rejected:
  both behaviours are correct *for a control-ordered exit* and were written for one (ADR-0090/0086/0065).
  The defect is the attribution, and fixing it at the attribution leaves the control path exact.
- **Widen the no-trade band.** Rejected and already refuted: ADR-0133 did that and was scored ❌ BAD. A
  wider band vetoes weak-conviction *names*; this filters weak-conviction *exits*.
