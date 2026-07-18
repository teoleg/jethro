# ADR-0042: Hedge proxy selection — best measured proxy, tradability-gated, switch hysteresis

- **Status:** Accepted (directed by Oleg: "make evaluation and check what makes sense and buy best option")
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** risk, hedging, quant

## Context

The hedger buys ONE configured proxy (ES) unconditionally. Two failure modes: the proxy can be
the wrong instrument for the current book (NQ hedges a tech-heavy book with higher ρ² than ES),
and it can be in bad shape to trade (stale mark, mark-jump quarantine) — the ADR-0039 breaker
already refuses quarantined proxies, but everyday hedging did not. A desk evaluates its hedge
instruments and buys the best available one; it also does not flip instruments on estimation
noise, because every switch pays two spreads.

## Decision

We will select the equity hedge proxy each cycle from a configured candidate list
(`jethro.hedge.equity-proxy-candidates`, default `ES,NQ`), as follows:

1. **Tradability gate first.** A candidate must have a live price and must not be
   mark-quarantined. An untradable candidate is excluded this cycle — never hedged into.
2. **Statistical selection.** Among tradable candidates, size the ADR-0038 min-variance hedge
   per candidate (per-candidate contract multiplier from refdata) and pick the highest measured
   ρ² that clears the effectiveness floor.
3. **Switch hysteresis.** If a hedge is already held in a different candidate, switch only when
   the challenger's ρ² beats the incumbent's by `jethro.hedge.proxy-switch-margin`
   (default 0.10 — PLACEHOLDER, Oleg to set; every switch pays two spreads, so a margin below
   realized ρ² estimation noise would churn instruments). Within the margin, keep the incumbent.
4. **One order per cycle, unwind before build.** Per-proxy targets: the selected proxy gets the
   sized target, every other held proxy targets zero. The cycle executes the largest delta above
   the ADR-0039 min-trade notional — so a proxy switch unwinds the old hedge first, then builds
   the new one on subsequent cycles, always inside the cooldown/churn guards.
5. **Structural fallback unchanged.** When no candidate clears the statistical gate, the
   STRUCTURAL tier hedges with the configured `equity-proxy` (assigned betas are quoted vs that
   proxy; per-candidate assigned betas are a follow-up with the sector-ETF work, ADR-0040).

## Alternatives considered

**Score = variance removed per dollar of round-trip cost** (ρ²·σ² per spread+fee+impact).
The fuller desk answer; needs per-candidate cost integration in the advisor. Deferred — with two
liquid index futures the cost difference is second-order versus ρ²; trigger: candidate lists
with materially different cost profiles (sector ETFs).

**Always ES (status quo).** Simple, but hedges a tech-heavy book with the broad index when a
better instrument is quoting — measured ρ² exists precisely to make this call. Rejected.

**Switch on any ρ² improvement (no hysteresis).** Estimation noise around two close ρ² values
would flip instruments and pay two spreads per flip. Rejected — documented estimator noise is
exactly why the margin exists.

## Consequences

- Positive: the hedge instrument is chosen by evidence each cycle; a quarantined/stale proxy is
  never bought; switches are deliberate and pay their costs knowingly; cross-proxy residuals
  unwind automatically through the same delta machinery (review P1-1 semantics).
- Negative: a switch leaves the book partially hedged for one cooldown cycle (old unwound before
  new built — accepted: it is at most one cycle and inside the churn guards); the switch margin
  is a placeholder until set from realized ρ² noise; structural tier still single-proxy.
- Follow-ups: cost-aware scoring (with sector-ETF candidates); per-candidate assigned betas for
  the structural tier; effectiveness telemetry (promised ρ² vs realized variance reduction) to
  calibrate the switch margin from data.
