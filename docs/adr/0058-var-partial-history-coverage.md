# ADR-0058: VaR coverage — include names with sufficient history, revalue on the common window

- **Status:** Proposed
- **Date:** 2026-07-23
- **Deciders:** Oleg
- **Tags:** risk, quant, var

## Context

Historical VaR (ADR-0027) required an instrument to have a return on **every** day in the window
(`allMatch`, strict coverage) or it was dropped to `skippedExposure`. In a 2-day live run this blinded
VaR to almost the whole book: covered exposure was **$9,964 (AUDUSD alone)** while **$44,583** — every
equity (JPM/GOOG/MSFT/ES, all marked) — was skipped, so **VaR95 read $84 against a ~$55k gross book.**
Cause: `daily_close` is **sim-contaminated at the tail** (the training layer already keeps a separate
store for exactly this reason), so a name with 1,500+ real days but a gap on the sim-generated tail
days fails `allMatch` and is excluded. A risk number that silently ignores the equity book is worse
than no number.

## Decision

We will relax coverage from "return on every day" to **sufficient history + a common window**: (1) an
instrument is **included** if it has returns on **≥ `minObservations`** days (else its |exposure| is
still disclosed as `skipped` — never treated as riskless); (2) the P&L series is revalued only on the
days where **every included name** has a return (the intersection / common window), which drops the
gappy or sim-contaminated tail days **rather than the names**, keeping the portfolio composition
consistent day-to-day; (3) if that common window itself falls below `minObservations`, VaR reports the
honest shortfall note instead of a number. `observations` now reports the common-window length.

## Alternatives considered

- **Keep strict every-day coverage.** Rejected: it is the bug — one gappy tail day silently removes a
  fully-historied name from the risk figure.
- **Per-instrument windows, summed independently.** Rejected: revaluing each name on a different set of
  days breaks the joint distribution — historical VaR's whole point is the *same* day's cross-asset
  move; you cannot sum losses drawn from different days.
- **Fix `daily_close` sim-contamination at the source (per-mode scoping).** Deferred, not rejected: the
  correct durable fix (ADR-0029 follow-up), but a schema + write-path change; the common-window rule is
  robust to contamination *and* to ordinary listing gaps regardless, so it stands on its own. Trigger:
  the daily_close mode-scoping work (registered).

## Consequences

- **Positive:** VaR sees the whole marked book again; robust to sim-tail contamination and to real
  listing gaps (a name added mid-window); still honest — genuinely history-less names stay in `skipped`,
  and a too-thin common window returns a note, never a fake number.
- **Negative:** the common window can be shorter than the raw day count when one held name is gappy (a
  single very-gappy name shrinks it for all) — disclosed via `observations`; a name with exactly
  `minObservations` scattered days is included on thin evidence (bounded by the floor). Does not fix the
  underlying `daily_close` contamination — that stays a registered follow-up.
