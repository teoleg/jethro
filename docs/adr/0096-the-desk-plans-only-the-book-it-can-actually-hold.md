# ADR-0096: The desk plans only the book it can actually hold

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, risk, fusion, sizing

## Context

The fusion loop plans a target for every name that has a fresh forecast, sizes each one, then asks —
per order, at the very last step, inside `FusionExecutor.route` — whether the ADR-0049 OOS selector
will let the desk put risk on that name at all. That veto is hard and fail-closed, and it is correct
where it stands. The problem is that it stands *below* the two controls that look at the book as a
whole.

Measured on the live desk this cycle: the planner published **23 instruments**; the selector reports
**7 tradable** (`measured: 17, tradable: 7`). Sixteen names — AMZN, NVDA, TSLA, GS, BRK.B, ORCL, NFLX,
EURUSD, GBPUSD, AUDUSD and the ZB/ZN/ZT/ZF/ES/NQ futures sleeve — receive a target and an order delta
every 30 seconds and are vetoed every 30 seconds. They have never produced a fill. They are more than
half of the planned book's gross notional: the FX and futures targets alone are roughly `$299k` of a
planned book of roughly `$569k`, against `$29.6k` actually held.

That is invisible in the order flow, which is why it has survived nine cycles of this loop. It is not
invisible in the sizing, because two controls aggregate over exactly that set:

- **ADR-0079's portfolio diversification multiplier**, `PDM = min(1, σ_indep/σ_actual)`, computed over
  the planned book and then applied **uniformly to every covered name**. A phantom name still
  contributes `eᵢ²Σᵢᵢ` to `σ_indep` and its cross-terms to `σ_actual`. A phantom sleeve in a weakly
  correlated asset class therefore *raises* the measured diversification — and the names that do trade
  are scaled up on the strength of risk the desk was never going to take. Live reading:
  `portfolioRiskMultiplier: 0.487` over `covarianceCoveredNames: 23`.
- **ADR-0083's volatility budget**, whose reference σ is the harmonic mean over the covered names.
  Phantom names move that reference and so move every real name's share of the per-name budget.
  Live reading: `volBudgetNames: 23`, `dispersion: 2.20`.

Both controls exist for one purpose: to stop the **name count** — which nobody ever set as a dial —
from levering the owner's per-name `unit-notional-usd` (`50000`, OLEG-SET 2026-07-21). Feeding them a
book that is three-quarters unreachable is what let it happen anyway. The desk is currently ramping
from `$19k` to `$29.6k` to `$40.9k` of gross inside forty minutes, toward a tradable-name target of
roughly `$117k`, while total PnL grows by tens of dollars a run. The numerator is fine; the
denominator is on a ramp nothing has measured honestly.

There is a second, cost-free symptom that confirms the reading. The ADR-0094 buffer keeps a per-name
aim, and nothing re-seeds it when the *executor* (rather than the edge gate) refuses the order. On the
sixteen unreachable names the aim has wound up until it sits on the opposite side of zero from the
target — live `aims`: `GS +103.02` against a target of `−87.51`, `BRK.B +49.57` against `−150.05`,
`AUDUSD −17,394` against `+17,628`. Integral windup on names that can never fill. It costs nothing
directly, and it has already misled one cycle's diagnosis.

## Decision

**We will plan only the actionable book: the names the desk may actually put risk on, plus the names
it already holds.** Concretely, `FusionLifecycle` filters the fresh forecast set through
`FusionExecutor.mayOpen` — the executor's **own** ADR-0049 predicate, called as a method reference,
never restated — unioned with the held set, *before* `FusionPlanner.plan` and therefore before the
ADR-0083 budget and the ADR-0079 multiplier measure anything.

Three properties make this safe rather than clever:

1. **It can never let a name trade that could not trade before.** The executor's veto still runs on
   every order, unchanged. This change only stops the desk *sizing against* names that veto rejects.
   It is the same gate, asked earlier.
2. **A held name is always kept, whatever the selector says, and keeps its own forecast.** ADR-0065's
   orphan unwind, ADR-0086's trailing cut and the ADR-0027 breaker all still see every position the
   desk carries, and nothing about how an existing position is planned changes — the executor's
   reduce-only path continues to do exactly what it does today.
3. **It asserts no money number.** No new dial, no new formula, no threshold. The same statistics are
   computed by the same code over a different — truthful — set of names. Invariant 7 / ADR-0016 hold
   trivially: there is nothing here for a model to have chosen.

With no order path wired (shadow mode) or no selector wired (persistence off) the predicate is
fail-open and the book is byte-identical to before.

### Worked example (pinned as a test)

Three names, each planned at signed USD notional `e = 10,000`, each with daily return variance
`σ² = 0.0004`. A and B are tradable and perfectly correlated with each other; P is a phantom in
another asset class, uncorrelated with both.

```
Σ_AA = Σ_BB = Σ_PP = 0.0004     Σ_AB = Σ_BA = 0.0004 (ρ=1)     Σ_AP = Σ_BP = 0

real book {A,B}:   σ_indep² = 2·e²·0.0004          = 80,000
                   σ_actual² = 4·e²·0.0004         = 160,000
                   PDM = √(80,000/160,000) = 1/√2  = 0.70710678…

with the phantom:  σ_indep² = 3·e²·0.0004          = 120,000
                   σ_actual² = 160,000 + e²·0.0004 = 200,000
                   PDM = √(120,000/200,000) = √0.6 = 0.77459667…
```

A and B carry the entire risk, they are one bet, and the honest multiplier on them is `1/√2`. The
phantom lifts it to `√0.6` — **the two tradable names are sized 9.54% larger** on the strength of a
diversification the book will never have. Scale that from one phantom to sixteen, half the planned
notional of which sits in FX and Treasuries that genuinely diversify against a one-sided US equity
book, and it is the dominant term in how big this desk gets.

## Alternatives considered

**Leave it and cap gross at the firm level instead.** A fourth control on the same quantity, and one
that would need a money number nobody has set. It would also treat the symptom: the desk would still
believe it held a diversified 23-name book and would still allocate its budget as if it did. Rejected
— the existing controls are the right ones, they were simply being fed fiction.

**Prune inside `PortfolioRiskNormaliser` / `VolatilityBudget` only.** Narrower, but it would leave two
more code paths deciding independently what "the desk's book" means. The ADR-0091 defect was exactly
two suppliers three lines apart disagreeing about scope; the lesson recorded from it is that a scope
decided in more than one place is a scope that will eventually disagree. Rejected in favour of one
filter at the top of the cycle, using the executor's own predicate.

**Force a held-but-unsupported name flat.** Tempting — such a name can only ever be reduced anyway —
but it converts a *stable* selector flicker into a full round trip, and the selector's verdict is
re-measured on a cadence. Rejected: held names keep their own forecast and the existing reduce-only
clamp handles them, which is strictly the smaller change.

**Drop the ADR-0049 veto and let everything trade.** Off the table; that veto is the deterministic
backtest floor (ADR-0049/0059) and this loop does not touch the floor.

## Consequences

- **Positive.** The two book-level risk controls finally measure the book that can exist. Expected
  direction is **lower gross**: removing the FX/futures phantom sleeve strips a genuine diversifier
  out of `σ_indep` without removing much of `σ_actual`, so `PDM` should fall and the tradable names
  should size down. The `aims` windup on unreachable names disappears with them (the buffer already
  drops state for names that leave the book). The operator's target book stops showing sixteen
  intentions the desk will never act on.
- **Negative / risk accepted.** The two effects do not point the same way. ADR-0083's reference σ is a
  harmonic mean, so removing the lower-σ futures *raises* it and nudges the equity names' budget share
  **up**, partly offsetting the `PDM` reduction. Measured dispersion is only `2.20`, so that term is
  small relative to the halving of the notional base `PDM` is computed over — but the net is an
  expectation, not an identity, and it is the ledger's job to say which won. If gross rises, this is
  the wrong lever and should be reverted, not tuned.
- **Also accepted.** Should the selector's tradable set collapse to nothing, the planned book collapses
  with it and the desk holds what it holds until the selector speaks again. That is the fail-closed
  behaviour ADR-0059 already chose for this path; it is now visible in the target book instead of only
  in the order rejections.
- **Follow-ups.** The ADR-0094 aim is still not re-seeded when the *executor* declines an order (only
  when the edge gate does). This change removes the population where that mattered, but the asymmetry
  remains and should be closed on its own. Separately, the loop's auto-revert reverts
  `docs/loop-findings.md` along with a ❌ BAD change, which discards precisely the lessons worth
  keeping — worth excluding the memory files from the revert.
