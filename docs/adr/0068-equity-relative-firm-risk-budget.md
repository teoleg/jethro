# ADR-0068 — Equity-relative firm risk budget & bleed cutoff

- Status: **Proposed**
- Date: 2026-07-26
- Supersedes: none (extends ADR-0018 pre-trade guardrail, ADR-0027 firm drawdown breaker)

## Context

The deterministic floor already has the machinery to stop a bleeding, exposure-piling book: the
`PreTradeGuardrail` enforces per-book and firm-wide gross/net/loss caps (a book past its max-loss may
only reduce risk), and the `FirmBreakerMonitor` halts all auto-execution at a configured drawdown from
peak firm P&L. The mechanism is sound and wired.

The defect is **calibration, not mechanism.** The loss/drawdown dials are absolute dollars sized for a
large book — firm drawdown breaker `$50,000`, firm max-loss `$75,000`. After `scripts/reset-sim.sh` the
book restarts at ~`$0`, so those thresholds let it lose **tens of thousands** before any cutoff fires.
On a book that should sit near flat, that is effectively **no bleed cutoff at all** — which is exactly
what was observed: PnL bleeding and gross exposure rising, with nothing in the fast path reacting (the
15–30 min improvement loop is far too slow to be the risk manager). The loop itself flagged it: *"no
firm risk budget, so breadth growing from 7 to 23 names multiplied gross exposure with nobody deciding
that it should."*

Two distinct dials are involved and must not be conflated:
- **Gross/net exposure caps** are sized to the instruments' notional scale (one swap lot = `$1M`
  exposure, V22 convention). These are defensible as-is; slashing them would silently block legitimate
  rates trades. Not the problem.
- **Loss / drawdown cutoffs** are the risk-appetite dials that decide *how much loss halts the desk*.
  These are what went stale on reset.

## Decision

**1. (Now, config) Recalibrate the loss/drawdown dials to bind on the current book, as explicit
placeholders.** The firm drawdown breaker and firm/book max-loss are lowered to conservative values that
actually fire on a near-flat book. Per CLAUDE.md these are money-risk dials: they are marked
`PLACEHOLDER — Oleg to set`, tighter-is-safer, with the legacy values cited. Gross/net caps are left
untouched.

**2. (Durable, to implement) Make the risk budget equity-relative so a reset can never render it
toothless again.** The firm bleed cutoff and gross budget become a **fraction of deployed capital / the
configured account size** rather than an absolute dollar figure, so they auto-scale with the book:
- firm bleed cutoff = `drawdownFrac × capital` (halt auto-execution when firm drawdown from peak exceeds
  this), and
- firm gross budget = `grossMultiple × capital`,
with `capital` a configured account-size dial and `drawdownFrac` / `grossMultiple` documented risk dials
(`PLACEHOLDER — Oleg to set`). The absolute dollar caps remain supported as an override for the
notional-scale instruments (swaps). This keeps the mechanism in the deterministic floor (invariant 7),
adds no model in the risk path, and matches the owner's feed-agnostic / self-calibrating philosophy —
the budget adapts to the book instead of going stale on every reset.

The improvement loop must **never** edit these — they are the floor. The fast bleed cutoff (breaker,
10 s cycle) remains the real-time risk manager; the loop only improves what happens *above* it.

> **Status note (2026-07-26, added by the improvement loop).** Step 1's property edits were drafted in
> the working tree by an interrupted cycle and have been **reverted, unshipped**: retuning the firm
> drawdown breaker and the max-loss caps is precisely the edit the paragraph above forbids the loop from
> making, and an unscored dial change would also confound the ledger's attribution of the cycle's one
> coherent change. This ADR therefore stands as a **Proposed, not-yet-built** record of a real finding —
> both steps are Oleg's to ratify and apply. Nothing here is implemented; the legacy dials are live.

## Consequences

- The book is protected immediately (step 1): a small drawdown now flips the firm to reduce-only, so a
  no-edge experiment bleeding money is stopped in ~10 s rather than after `$50k`.
- The placeholders may be too tight and trip on ordinary mark swings; that is the safe failure mode
  (over-halt, never under-halt) and surfaces as an attention alert + a loop finding, prompting Oleg to
  set the real numbers.
- Step 2 (equity-relative) needs code in `RiskLimits` / `RiskLimitProperties` / `FirmBreakerMonitor` /
  `PreTradeGuardrail` plus tests; tracked in the deferred register. Until then step 1's absolute
  placeholders stand.

## Alternatives considered

- **Do nothing / keep the big-book dials** — rejected: it is the observed failure (unbounded bleed on a
  reset book).
- **Slash the gross caps too** — rejected: they are notional-scale-sized (swaps); cutting them blocks
  legitimate rates trades without addressing the bleed.
- **Let the improvement loop manage risk** — rejected: it runs every 15–30 min; risk management must be
  deterministic and fast, in the floor.
