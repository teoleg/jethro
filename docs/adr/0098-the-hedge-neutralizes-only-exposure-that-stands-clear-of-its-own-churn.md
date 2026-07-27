# ADR-0098: The hedge neutralizes only the systematic exposure that stands clear of its own churn

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, hedging, risk

## Context

The HEDGE book is the firm's single worst position. On the reported cycle it carried
`−$509.21` against a firm total of `+$326.72` — the hedge loses more than the whole desk makes —
and the loss is **not execution cost**: `$40.02` of fees plus 355 ES fills at a measured `0.204` bps
of slippage is under `$50` all-in. The other `~$460` is directional. The hedge is not paying too much
to trade; it is holding the wrong position.

It holds the wrong position because it is sized from a number that is inside its own noise.
ADR-0039 holds the book **target-flat** with `equity-rebalance-floor-usd = 0`, so the target is
literally `−Σβ·E` at every evaluation. On the reported cycle `Σβ·E = −$195.49` — against `$37,162` of
firm gross, the beta-weighted net is **0.5%**, i.e. the strategy book is already very nearly
beta-neutral. Meanwhile the desk sent one ES order per cooldown, `0.0009`–`0.011` contracts each
(`$250`–`$3,000` of notional), alternating BUY and SELL. The hedge trades several times its own
position size every minute to neutralize a number smaller than the amount that number moves between
one hedge and the next. Under a target that is pure churn, the resulting proxy position is a signed
bet taken on noise, and it accrues losses even when each round trip is cheap.

Worse, the noise is not symmetric. The target is minus the beta-weighted net of a book whose measured
edge is **mean reversion** (`reversion` is the one source passing the ADR-0097 gate, `+10.24` bps at
the 3600s rung, hit rate 0.845). A reversion book is short after a rally and long after a selloff, so
`−Σβ·E` is long the proxy after a rally and short after a selloff: the hedge is a **momentum position
on ES**, taken on the one view the desk measures as significantly negative (`trend`: `−8.61`/`−6.51`/
`−3.51` bps across the ADR-0082 ladder). Sized off a real exposure that would be the price of risk
control. Sized off churn it is a losing bet with no risk being controlled.

The existing guards do not catch this. The ADR-0069 no-trade band is measured against the hedge's
**own scale** (`max(|target|,|held|)`), so a hedge oscillating around zero has a band near zero and
trades every cycle — the same defect ADR-0094 fixed on the fusion side, where a band measured against
a moving quantity stopped bounding anything. And ADR-0039's floor is an absolute dollar dial that
would have to be hand-set per feed, which is exactly the invented number `CLAUDE.md` forbids.

## Decision

**We will subtract the hedge target's own churn from the hedge target before trading it.** Let `T` be
the tier's raw target notional on the proxy (USD, signed) and `σ` the EWMA standard deviation of `T`'s
**step between the moments the hedge can act** — sampled once per hedge cooldown, `λ = 0.94`, the same
RiskMetrics decay `CovMath` already uses for the firm covariance. The traded target is the
soft-threshold

```
T' = sign(T) · max(0, |T| − k·σ)          k = jethro.hedge.churn-sigma-multiple (1.0)
```

re-divided by the contract's money value (`price × multiplier`) to a quantity, in exact decimal.

Three properties make this safe to run unattended:

- **One-way.** `|T'| ≤ |T|` and `sign(T') ∈ {sign(T), 0}` by construction. An over-estimated `σ` can
  only ever make the hedge *smaller*; it can never reverse it, and it can never lever the book up.
- **A real hedge is untouched.** `$456,000` of systematic exposure (the ADR-0038 worked example) at
  `σ = $900` goes `1.676711 → 1.673402` ES — a 0.2% trim. The shrinkage only bites when the target is
  the same order as its own churn.
- **Self-calibrating, so it is feed-agnostic.** `σ` is measured from the running stream, not dialled.
  A quiet feed shrinks by little; a churning one shrinks by a lot. No hardcoded dollar level.

`σ` is published only from the second step (one squared difference is not an estimate) and the first
step **seeds** the EWMA rather than decaying from zero, so warm-up does not bias it low. While
warming, the advisor behaves exactly as it did before this ADR. The executing `HedgeLifecycle` is the
only writer of the series: a `/api/hedging` poll reads `σ` and never samples it, so a busy REST client
cannot shorten the step and understate the churn. `k = 0` restores the pre-ADR-0098 behaviour exactly.

## Alternatives considered

**Widen the ADR-0069 no-trade band (a cost-derived region traded at its boundary).** The natural
first read, and the one the loop's own findings memory pointed at. Rejected on the measurement: the
hedge's loss is `~$460` directional against `<$50` of fees and slippage, so a policy that only reduces
*trade frequency* leaves the position — and therefore the loss — substantially in place. A band also
does nothing about a target that is noise; it just chases it more slowly.

**Unwind the hedge when its measured effectiveness fails the ρ² floor.** Tried as ADR-0095 and scored
❌ BAD on the next cycle, so it is off the table on its own terms. It is also a different question:
ρ² asks whether the *proxy* tracks the book, this ADR asks whether the *exposure* is real. A perfectly
effective proxy hedging a `$195` exposure is still churn.

**Set `equity-rebalance-floor-usd` to a dollar figure.** The mechanically simplest fix and the one the
config already invites. Rejected because any figure would be mine and arbitrary — it would have to be
re-guessed for every feed and every book size, and it would harden into an assumed rule exactly as the
`$250k` hedge cap did. A measured σ is the same idea with its number supplied by the stream.

**Stop hedging a book whose net exposure is its alpha.** Arguably the deepest reading: for a
mean-reversion book the directional net *is* the bet, so a beta overlay converts alpha into cost.
Deferred, not rejected — it is a much larger change to ADR-0039's target-flat premise and it should
not be made on one cycle's attribution. Revive it if, with the churn shrinkage in, the HEDGE book is
still materially negative while the `reversion` source still passes the edge gate.

## Consequences

- **Positive:** the desk stops taking signed proxy positions on a beta-weighted net that is
  indistinguishable from its own noise; the hedge's turnover, its fee line and its directional bleed
  all fall together. The rule is measured rather than dialled, so it carries over to a live feed with
  no re-tuning. `rawTargetNotionalUsd` and `churnSigmaUsd` are surfaced on `/api/hedging`, so the
  shrinkage is auditable from the panel and the loop report.
- **Negative:** the book is no longer held to *exactly* flat — it runs up to `k·σ` of unhedged
  systematic exposure by design, which is a real, if small, widening of firm risk, and in a genuine
  market shock that residual is unhedged for the length of one cooldown. `σ` is an EWMA over ~16
  samples, so a regime change in the strategy's turnover takes about that long to be reflected. The
  multiple `k = 1.0` is a one-standard-error soft threshold; beyond "one σ" the choice is arbitrary
  and is marked `PLACEHOLDER — Oleg to set` in `application.properties`.
- **Follow-ups:** if the HEDGE book stays negative with the shrinkage in, take the deferred
  alternative above (does a mean-reversion book want a beta overlay at all?) as its own ADR. The
  ADR-0069 band is left as it is; if churn shrinkage makes the relative leg redundant it should be
  retired in a separate change, not folded into this one.
