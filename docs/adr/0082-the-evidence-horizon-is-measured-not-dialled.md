# ADR-0082: The horizon the desk's edge is measured over is chosen by the data, not by a dial

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** backend, fusion, signals, statistics, edge-gate

## Context

The edge gate asks one question — does a source's measured expectancy beat the desk's measured
round-trip cost, with significance? — and it is the only change in the improvement ledger ever scored
✅ GOOD. But the comparison has an asymmetry that nothing has yet examined: **expectancy is a return
over a period; cost is a charge per round trip.** The period is `jethro.signals.horizon-seconds`, set
to 3600 s long before any of this book's evidence existed, and it silently fixes three separate things:

1. **The evidence rate.** `SignalTelemetry` holds one open call per (source, instrument), so a
   cross-sectional source emits exactly **one independent cohort per horizon**. Since ADR-0077 the
   standard error is estimated across cohorts, and since ADR-0081 the hurdle is read on `cohorts − 1`
   degrees of freedom. At an hour, the gate accrues **one degree of freedom per hour**.
2. **The expectancy credited** against one round trip.
3. **The holding period**, since ADR-0080 derives the trading rate from this same property.

The live consequence is a deadlock, and it is the whole current situation. `reversion` measures
+13.51 bps at an 88% hit rate over 69 resolved observations — in **3 cohorts**. Its t-statistic is
≈ 2.02 against a 4.85 hurdle at 2 df. Every other source is measurably *negative* (`trend` −11.59 bps
over 7 cohorts, `momentum` −6.57, `social` −5.06) and is correctly refused. So the desk is flat at
**$0 gross**, PnL frozen at **−$826.06** for ten consecutive scored cycles, and the one source that
might pay cannot accumulate evidence faster than one cohort an hour. Three cycles of findings have
named this the binding constraint and deferred it, correctly, on one condition: **shortening the
horizon must be justified by evidence that the edge is fast, not by a wish for more samples.**

That condition is the design. Shrinking the horizon shrinks the expectancy per observation roughly in
proportion, while the round trip charged against it does not move at all. At the desk's cheapest
measured round trip (2 × 1.43 bps one-way on MSFT ≈ 2.87 bps), `reversion`'s hourly +13.51 bps nets
+10.64; the same edge spread linearly over 225 s would net **−2.03** and must be refused. A short rung
can only clear if the move is genuinely realised inside it. So the horizon is not a dial to be guessed
at — it is a **property of the edge that can be measured**, and cost is what keeps the answer honest.

## Decision

**We will grade every signal call over a geometric ladder of horizons and let the edge gate select the
rung the evidence supports, paying the multiple-testing haircut for having looked.**

- **The ladder.** `HorizonLadder.rungs(base, n)` = `base, base/4, base/16, …`, longest first, at the
  same 4× span ratio the trend sensor already uses. Shipped `horizon-rungs=3` ⇒ 3600 / 900 / 225 s.
  One rung reproduces the previous behaviour exactly. A measurement length, not a money dial.
- **Recording.** `SignalTelemetry.record` opens one observation per rung, sharing one entry instant and
  one entry mark — the same decision, graded over different lengths of the future. `horizon_seconds` is
  part of the open-call key and of the resolved-history query, so no rung starves another (the sample
  limit is applied **per rung**: a short rung resolves 16× as often and would otherwise fill the window).
- **The haircut.** Reporting the best of `m` rungs is `m` tests. `EdgeGate.Params.alpha()` becomes
  `(1 − Φ(tHurdle)) / m` — **Bonferroni**, valid under arbitrary dependence, which matters because
  nested measurements of one stream are strongly positively dependent. Consequently **every rung,
  including the base, faces a strictly harder bar than the single-horizon gate did.**
- **Selection.** `HorizonLadder.select` evaluates the gate at each rung and takes the smallest best
  p-value among rungs that open (the only scale comparable across differing df — ADR-0081); ties break
  toward the **longer** horizon, which pays fewer round trips for the same credited return. If nothing
  opens, the base rung stands.
- **One rung drives everything.** The selected rung supplies the gate's verdict, the ADR-0055 source
  weights, and — via ADR-0080's identity `a = 1 − exp(−cycle/horizon)`, now evaluated per cycle in
  `FusionLifecycle` — the holding period. The desk must never grade a source over one period, weight it
  over a second and hold it for a third. `jethro.fusion.adjustment-rate > 0` still pins the rate.

Nothing here is a money, risk or exposure number: in go measured bps, counts and probabilities; out
come a horizon and a boolean (ADR-0016 / invariant 7). The deterministic floor is untouched.

## Alternatives considered

- **Shorten `horizon-seconds` to a faster fixed value.** Rejected: it swaps one unmeasured guess for
  another, and in the wrong direction — it would credit ~1/16 the expectancy against an unchanged round
  trip while *looking* like progress, because cohorts would pile up. The failure mode is a gate that
  opens on a fast-accruing, cost-negative edge. The ladder tests exactly this and refuses it.
- **Drop the significance requirement / lower `t-hurdle`.** Rejected: the gate's value on this desk is
  demonstrably that it *stops* trading; every dollar of the strategy book's −$895.94 is transaction cost.
  Buying permission by lowering the bar is the one change we already know loses money.
- **Šidák (`1 − (1−α)^(1/m)`) instead of Bonferroni.** Rejected: it is exact only under independence,
  and the rungs are nested measurements of the same stream. The two differ by <0.1% at m = 3 anyway.
- **Harvey–Liu–Zhu / BHY false-discovery control across the whole hypothesis inventory.** Deferred: the
  right frame once the desk searches many sources × horizons × names. Revive when the searched-hypothesis
  count exceeds ~20, where Bonferroni's conservatism starts costing real discoveries.
- **Estimate a decay curve and read the optimal horizon off it** (Gârleanu–Pedersen style). Deferred:
  strictly more informative, but it needs a fitted decay per source, and with 3 cohorts of history there
  is nothing to fit. Revive once any source has ~30 cohorts at two or more rungs.

## Consequences

- **Negative — the gate gets harder to open in the short run.** The Bonferroni haircut applies to the
  base rung too, so `reversion`'s already-failing test at 3600 s now needs a p below 0.0076 rather than
  0.0228. If the edge is *slow*, this change delays trading rather than enabling it. That is the correct
  trade — the alternative is buying permission — but the ledger may well score this ⚠️ MIXED or worse on
  a flat book before the short rungs have accrued.
- **Negative — 21× the telemetry rows** at 3 rungs (1 + 4 + 16 emissions per base horizon): ~2,500
  rows/hour at 5 sources × 23 names. Trivial for Postgres, but `sample-limit` and `rolling-days` now
  bound a per-rung window rather than a per-source one.
- **Negative — the holding period can now change between cycles** as the selected rung moves, by up to
  the full 16× ladder ratio. Turnover is therefore evidence-driven and less predictable; the no-trade
  band and the ADR-0080 asymmetry (cuts trade in full, adds are rated) remain the churn defences.
- **Positive — the deadlock becomes measurable rather than structural.** If `reversion`'s edge is fast,
  the 225 s rung reaches 48 cohorts in the time the base rung reaches 3, and the desk can act on it
  16× as often for the same net edge per round trip. If it is slow, the desk correctly stays flat and
  we will have *measured* that rather than assumed it — which is the finding that would justify
  recommending a live feed.
- **Positive — one horizon, three uses.** Grading, weighting and holding can no longer disagree.

Related: ADR-0055 (fusion + telemetry), ADR-0064/0072/0075 (the cost-aware gate), ADR-0077 (cohort
standard error), ADR-0080 (holding period = evidence horizon), ADR-0081 (the reference distribution).
