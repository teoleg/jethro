# ADR-0037: Clean vs comprehensive P&L — lock realized FX, break out translation

- **Status:** Proposed
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** pnl, risk, fx, reporting

## Context

A book that is **flat in every position** still shows a moving P&L. The cause is FX: realized
P&L booked in a non-USD instrument (e.g. a EUR name) is held in the instrument's currency, and
the USD rollup re-translates it at the **live** spot mark on every ~1 Hz snapshot
(`RiskProjection` accumulator). Positions closed, nothing traded — yet the headline wanders as
EURUSD ticks. That is real economic exposure (un-repatriated foreign cash carries FX risk), but
smearing it into the strategy's headline conflates *trading* performance with *treasury/FX*
performance and makes a closed book look alive.

Two figures are being conflated:
- **Clean (trading) P&L** — what the strategy made from taking and closing positions. Once flat,
  it is frozen.
- **Comprehensive (actual) P&L** — every dollar of book-value change: clean P&L **+** FX
  translation of foreign cash (+ fees/funding, already booked). This is what reconciles to the
  ledger and what risk limits must bind.

Constraint: invariant 1 (exact decimals) and invariant 3 (only this projection writes positions)
stand. Risk controls (drawdown breaker, per-book/firm loss caps, pre-trade loss gate) must keep
binding on **actual** money — quieting the headline must not quietly exclude a real FX loss from a
limit.

## Decision

We will **lock realized P&L to USD at the FX rate in force when each closing fill books**, and
**report clean and comprehensive P&L as distinct lines**:

- **Lock at booking.** On a closing fill, translate realized (net of fee) to USD at the current
  \*USD pair mark and freeze it (`realizedBaseUsd`). If no pair mark exists yet, hold the amount
  pending and lock it at the first snapshot that can convert. Per position the row carries both the
  local-currency fact (`realizedPnl`) and the locked USD (`realizedPnlBase`).
- **Split the rollup.** `realizedPnl`/`totalPnl` become **clean** (locked realized + live
  unrealized) — a flat book's `totalPnl` no longer moves. `fxTranslationPnl` = live-translated
  realized − locked realized (the wander, made explicit). `comprehensivePnl` = `totalPnl` +
  `fxTranslationPnl` — identical to the previous `totalPnl`, so it is the actual-money figure.
- **Risk controls read comprehensive.** Drawdown breaker, loss caps (evaluator + strategy
  de-risk), pre-trade loss gate, EOD equity curve and firm-equity history all read
  `comprehensivePnl` — same economics as before the change; only the *name* moved. The UI shows
  "Trading P&L" (clean) with an "FX transl." line when non-zero.

Unrealized stays live-translated: an open foreign position genuinely revalues (price **and** FX),
and it is not flat, so it is not the reported bug.

## Alternatives considered

**Do nothing (translate realized at live spot).** Simple and already shipped, but it is exactly the
complaint: a flat book's headline wanders and strategy P&L is polluted by FX. Rejected.

**Book a separate FX/treasury position for foreign cash and let it revalue there.** The "correct"
full-fat answer — a real cash ledger per currency with its own P&L. Rejected *for now* as far more
plumbing (cash accounts, sweeps, funding) than the reporting split needs; revived if we take real
multi-currency cash management. The locked-realized + translation line is the same number without
the ledger.

**Report only comprehensive (drop the clean line).** Keeps one number, but then the strategy view
still wanders when flat and P&L attribution (clean-vs-actual, the FRTB PLA shape) is impossible.
Rejected; the two-line split is the point.

## Consequences

- **Positive:** a flat book's trading P&L holds still; FX translation is visible and attributable
  to treasury, not alpha; the data model now carries the clean/comprehensive split that P&L-explain
  and back-testing need; risk limits are unchanged (still on actual money).
- **Negative:** realized locking needs an FX rate at fill time — with no pair mark it locks lazily
  at the first available rate (a small, disclosed approximation of the true booking rate; in sim,
  FX marks are always present so it locks immediately). `realizedPnl + unrealizedPnl ≠
  comprehensivePnl` by the translation term, which must be read, not assumed. One more line on the
  books page and two more fields on the rollup records.
- **Follow-ups:** decompose **unrealized** FX for open foreign positions (price vs FX attribution);
  optional `fxTranslationPnl`/`comprehensivePnl` fields on the risk snapshot Avro event (additive,
  invariant 4); a real per-currency cash ledger if multi-currency funding is taken on. Depends on
  ADR-0020 (FX rollup), ADR-0025 (fees as cash), ADR-0027 (breaker/loss caps).

## Implementation status (2026-07-18) — built

- `RiskProjection` locks realized to USD at each closing fill (`realizedBaseUsd`, lazy-lock
  fallback `realizedPendingCcy`); `PositionRisk.realizedPnlBase` carries it; the `Acc` accumulator
  splits clean vs comprehensive. `ConsolidatedRisk.Totals`/`Group` gain `fxTranslationPnl` +
  `comprehensivePnl`.
- Controls switched to `comprehensivePnl`: `FirmBreakerMonitor`, `RiskLimitEvaluator` (book+firm
  loss), `PreTradeGuardrail` loss gate, `StrategyLifecycle` loss-cap de-risk, `EodService` equity
  curve, `RiskConfig` firm-equity history. `RiskController`/`books.html` surface the split.
- Tested: a EUR round-trip locks at booking rate; EURUSD then rallies and clean `totalPnl` holds
  still while `fxTranslationPnl`/`comprehensivePnl` move; USD realized has zero translation. USD-only
  and existing FX-rollup tests unchanged.
