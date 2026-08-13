# ADR-0140 — The fusion aim is durable derived state

**Status:** Implemented
**Date:** 2026-08-05
**Supersedes:** none. Amends the state handling of ADR-0094 (the aim/no-trade buffer); changes no
number in ADR-0080 (the adjustment rate), ADR-0094/0101 (the band and its width), ADR-0102 (the
within-target clamp) or ADR-0137 (the gross cap).

## Context

The desk has been unable to open a position. On the live book this cycle, `insideBuffer` read **19**
of **20** planned instruments, `currentQty` was **0** for most names, and firm gross exposure sat at
**0.3%** of the firm cap with the owner objective (ADR-0132) calling for capital to be deployed. The
planner was producing targets the whole time — `targetQty` **-846.588220** for WMT, **-599.105696**
for KO, **139.136530** for JPM — and routing nothing against any of them.

The mechanism is a composition of three things that are each individually sound.

**1. The aim's time constant is a whole evidence horizon.** ADR-0080 derives the partial-adjustment
rate from the identity `a = 1 − e^(−c/h)` — cycle length `c` over the horizon `h` the sources'
expectancy is measured at — so the desk's exposure e-folds toward its target in exactly the period
the edge gate priced. At the shipped configuration (`c` = 30 s, `h` = 3600 s) that is
`a = 0.008298…`. The aim therefore rises toward the target as `1 − (1−a)^n`, reaching only
`1 − e^(−t/h)` of the way after `t` seconds.

**2. The buffer releases an order only past a fixed fraction of the target.** ADR-0094's band is
`bufferFraction × |target| × TARGET_ABS / |forecast|` — Carver's buffer at a fraction of the name's
average position. Against a flat book the gap is the aim itself, so the release condition reduces to

```
|aim| / |target|  >  bufferFraction × TARGET_ABS / |forecast|  =  1 / |forecast|
```

at the live `bufferFraction` **0.10** and `TARGET_ABS` **10.0**. Combining with (1), the time a name
must accumulate intent before its FIRST order is

```
t  >  −h · ln(1 − 1/|forecast|)
```

At the live combined forecasts this cycle — WMT **-4.710957**, GOOG **-2.668468**, JPM **2.334897**,
CAT **2.327157**, MCD **-2.180863**, KO **-2.107082** — that is between roughly 900 s and 2 400 s of
uninterrupted accumulation.

**3. The aim was reset far more often than that.** `PositionBuffer.aims` was a plain in-memory
`HashMap`, and it was reset by *two* independent mechanisms:

- **Per cycle.** `aims.keySet().retainAll(planned)` deleted the intent of any name absent from a
  single cycle's target list. Membership demonstrably churns: consecutive telemetry snapshots read
  `instruments` **21** then **20**. A name whose sensor goes quiet, whose print goes stale, or which
  the selector rotates out for one cycle lost its entire accumulated intent and restarted from the
  held quantity.
- **Per process.** The map is in-memory, and observed process lifetimes in this harness are
  **1344–1439 s** — shorter than the accumulation time (2) requires for a typical forecast.

The observable signature is exactly what a repeatedly-reset path predicts and what a warming path
does not: six names sharing one derived rate, one band and one seed (`currentQty` **0** for every
one) showed `|aim|/|target|` spanning **12.7×** — AAPL **0.017274** to CVX **0.219300**. Under a
common clock those ratios would be identical; they differ because each name's clock had been
restarted at a different time. Every one of them sat below its `1/|forecast|` release threshold, so
`deltaQty` read **0.0** and the desk stayed flat.

This is the defect: **the aim is derived state with a horizon-length memory, stored in a container
whose lifetime is shorter than that memory.** It is not a flaw in the rate, the band, or the clamp.

## Decision

**The aim is durable derived state.** Both resets are closed, and nothing else changes.

1. **Absence ages the intent; it does not erase it.** A name absent from a cycle's plan keeps its aim
   for a bounded window and is dropped past it. The window is *derived, not dialled*: inverting the
   ADR-0080 identity gives `−1 / ln(1−a) = h/c` — the number of planner cycles in exactly one
   evidence horizon (120 at the shipped configuration). An intent whose name has been unplannable for
   a full horizon describes a view the desk no longer has evidence for, and is dropped. No number is
   introduced (invariant 7 / ADR-0016). A degenerate rate (`a ≤ 0`, a path that cannot advance) keeps
   the previous drop-at-once semantics.

2. **The aim survives a restart.** It is written through each cycle to a new `fusion_aim` table (V51)
   and restored once on the first cycle of a process. `NUMERIC(20,6)` against `BigDecimal` — exact
   decimal at the boundary, no float on a quantity (invariant 1) — scoped by `feed_mode` so a
   sim↔live switch starts a fresh intent (ADR-0029 / invariant 8). Derived data only (ADR-0014):
   every row is recomputable from the planner's own target sequence, and `fills` remains the source
   of truth for positions (invariant 3).

### Why this is safe — what a restored or retained aim can never do

A restored value is **not** acted on directly. It enters `nextAim` as the previous aim, is stepped
toward *this* cycle's target at *this* cycle's rate, and is then clamped by ADR-0102's `withinTarget`
into the closed interval between flat and the current target. So by construction it can never exceed
the current target, never oppose it, and never survive a change of view — a flipped forecast clamps a
restored aim to flat in one step. It can only spare the desk from re-paying a transient it has
already served.

Nothing above the deterministic floor is touched and nothing in the floor is: the band, its width,
the adjustment rate, the conviction floor, the edge gate, the gross/net caps, the firm drawdown
breaker and the pre-trade guardrail all keep their exact semantics. A store failure logs and leaves
the in-memory aims in force — a broken database must never stop the desk trading — and no store at
all (`aimStore == null`) leaves every path byte-identical to ADR-0094.

## Worked example

Shipped configuration, `c` = 30 s, `h` = 3600 s:

```
a               = 1 − e^(−30/3600)      = 0.008298…
retentionCycles = −1 / ln(1 − a)        = 3600 / 30 = 120 cycles   (exact, by construction)
```

WMT at the live plan — `combinedForecast` **-4.710957**, `targetQty` **-846.588220**, held **0**:

```
averagePosition = 846.588220 × 10 / 4.710957 = 1 796.99…
band            = 0.10 × 1 796.99…          =   179.69…
release needs   |aim| > 179.69…, i.e. |aim|/|target| > 1/4.710957 = 0.212270
time to release t > −3600 · ln(1 − 0.212270) = 858 s
```

858 s is inside one process lifetime, so WMT can release **if** its intent is never reset. Under the
previous behaviour a single absent cycle — or the process restart that arrives every ~1 400 s — put
it back to zero, and the observed `|aim|/|target|` for the name was **0.023290**, an order of
magnitude short.

For MSFT at `combinedForecast` **-2.513915** the requirement is `1/|f|` = **0.397786** and
`t > −3600 · ln(0.602214) = 1 826 s` — longer than any process lifetime this harness has recorded.
That name is reachable only once the aim persists across restarts, which is what (2) provides.

## Consequences

- The desk can complete the ADR-0080 transient and reach the fraction of target the ADR-0094 buffer
  requires, so names with a sustained view can open. Exposure should rise from the current 0.3% of
  the firm cap — which is the objective (ADR-0132), not a risk to be avoided, while every cap and
  breaker still stands.
- One extra table and ~20 upserts per 30 s cycle on the fusion tick thread. Not the hot path (the
  ring-buffer consumers are untouched) and not the order path.
- A restored aim reflects the target sequence of the *previous* process. ADR-0102's clamp bounds that
  to the current target every cycle, so a stale intent decays to the current view rather than acting
  against it.
- **Risk accepted:** if the aim path itself is wrong, persisting it makes the desk wrong for longer
  rather than being rescued by a restart. The clamp bounds the magnitude and the sign; the scorer's
  evaluation window and the auto-revert are the backstop.

## Alternatives rejected

- **Widen or cap the band** so a weak forecast releases sooner. ADR-0133 capped the band at the
  target it polices and was scored ❌ BAD and reverted; re-attempting a variant of a rejected
  mechanism is exactly what the loop's own rules forbid. This ADR changes no band.
- **Raise the adjustment rate** so the aim moves faster. That breaks the ADR-0080 identity outright:
  a faster rate pays N round trips against one horizon's return and makes the edge gate
  over-permissive by that factor. The problem is not that the path is too slow — it is the correct
  speed — it is that the path was being restarted.
- **Persist across restarts only**, leaving `retainAll`. Rejected on this cycle's own evidence: four
  of six frozen names had release requirements inside a single process lifetime and still routed
  nothing, which is the intra-boot churn reset, not the boot reset. Closing one without the other
  leaves the freeze in place — which is why the earlier framing of this item failed its own test.
- **Age on wall-clock time** rather than cycles. A clock is a second input where the rate already
  carries the horizon; deriving the window from the rate keeps one source of truth and introduces no
  number.
