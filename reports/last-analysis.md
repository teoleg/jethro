The desk lost its largest position to a 30-second collapse in source breadth to ZERO — the same defect ADR-0135 was shipped to close, recurring through the one branch that change deliberately left out of scope.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `recent_orders`,
`logs/report.md`, the scorer's output, or the repo source. None is authored here — invariant 7 / ADR-0016.)*

## No code change this cycle — the pending change is still under measurement

`scripts/score-change.py score` prints **`74a47adee still accumulating evidence (3/6 cycles) — held, not
scored this run`**, and `reports/.pending-baseline.json` is present. Under ADR-0116 a new change stacked on
a pending one destroys its evidence. So this run verifies, diagnoses, ranks and records; it ships no code.

## Situation — the four questions

1. **Money.** Total PnL **`-13.88198097`**, against **`5.00354203`** at the last deterministic heartbeat
   (`2026-08-03T14:37:28Z`) — the book crossed back under water inside the window. By book:
   HEDGE **`+107.90433891`**, ALPHA **`-64.98681452`**, MACRO **`-56.79950536`**. The desk is UNDERWATER
   and off the growth target (`on_track=false`, `stale=true`).
2. **Risk.** Gross **`23679.80075000`** — **1.6%** of the $1,500,000 firm cap, **$1,476,320** of headroom.
   Net **`5528.92075000`**, 0.6% of the net cap. Nowhere near a cap, breaker `halted: false`. Exposure is
   not the danger here; the near-total *absence* of it is the standing opportunity.
3. **Cause.** ADR-0135's own branch never fired (zero `sources=1` orders in 44), so it is unscored and
   unexercised — do not grade it on this window. The window's real event is elsewhere: at 14:32:21Z NQ
   was still being worked in on `forecast=-6.867533453373563, sources=2`; thirty seconds later, at
   14:32:51Z, it exited on **`fusion exit — target decayed to flat [forecast=0.0, sources=0]`**. The
   MACRO book is now `grossExposure 0.00000000` with `realizedPnl -56.79950536` and nothing left on. NQ
   has dropped out of `/api/fusion/targets` entirely.
4. **Danger.** No — not bleeding near a cap, not near the breaker. The pathology is the opposite shape: a
   book that deploys 1.6% of its allowance and then surrenders the largest thing it manages to build the
   moment a sensor stops reporting.

## The mechanism, and what is honestly attributable

`ForecastCombiner` maps "no source spoke" to a combined value of `0.0`; `TargetPlanner` maps `0.0` to a
target of flat; ADR-0090 works a flat target at FULL urgency. So an *absence of information* is executed
as a *measured decision to be flat*. ADR-0135 named exactly this failure and fixed it at one effective
source, explicitly leaving zero sources to ADR-0065's orphan sweep. The next live window collapsed through
the branch left open.

**Attributed honestly (Rule 237):** the **-$56.80 is market** — mark-to-market on a fresh ~$23.6k NQ short
the tape moved against between 14:21Z and 14:32Z. It is *not* the sweep's loss, and the sweep gets no
credit for "de-risking" either. What the sweep costs is the decision itself: it crystallised a position its
own forecast had backed thirty seconds earlier, and paid a full round trip to do it. I checked and
discarded the obvious explanation — NQ's provider clock is stale *now* (`647s`), but it froze at 14:49Z,
sixteen minutes **after** the liquidation, so staleness did not cause it. The trigger for the 2→0
transition is not yet localised and I will not guess it; that is next cycle's first measurement.

**The asymmetry is the expensive part.** Entry took 17 orders across 18 minutes; the exit took one cycle at
full urgency. A desk that accumulates slowly and liquidates at once turns every lapse in sensor
availability into a one-way ratchet down — which is also why `orders_by_status` reads `FILLED 4901` against
`CANCELLED 1750`, with NQ's forecast frozen bit-identical at `-6.867543315104213` across 13 consecutive
cycles while ADR-0084 cancelled and re-issued the resting order on every one of them.

## What I did, and what next cycle does

Re-ranked `reports/must-fix.md`: **#1** is now the zero-source full-urgency sweep, with a VERIFY-BY that
demands a *paced* unwind — a derouted name genuinely must still be exited, so the fix is the urgency, not
the responsibility. The old #1 — 9 of 10 carried targets holding nothing against six-figure targets while
the firm deploys 1.6% of its cap — is **#2**, with last block's σ-coverage explanation explicitly marked
unproven so the next cycle does not inherit a trace this register has already had falsified once. Fixing #2
before #1 would only feed more capital into the same sweep.
