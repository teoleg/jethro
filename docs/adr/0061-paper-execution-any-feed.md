# ADR-0061: Paper auto-execution runs on any feed (amends ADR-0019's sim-only routing)

- **Status:** Accepted
- **Date:** 2026-07-24
- **Deciders:** Oleg
- **Tags:** order, risk, strategy, execution

## Context

ADR-0019 allowed deterministic/fusion auto-execution but **gated it to `feedMode == SIM`**, on the
reasoning that we should not auto-act on real data before the order module is extracted for a real broker
(ADR-0015). In practice that gate blocked the one thing needed to validate the platform: **testing the
full order→risk→P&L loop on a real market feed.** Under a live feed (Yahoo/Finnhub/Alpaca) the fusion
router simply vetoed every order, so nothing traded and nothing could be observed end-to-end.

The gate conflated two different things. The *safety* guarantee is that **execution is simulated** —
`OrderService` fills only through `SimulatedExecutor`, and there is **no real-broker code anywhere** in the
repository (the `order` module is deliberately broker-free until ADR-0015 extraction). That guarantee holds
**regardless of the market-data feed.** So routing under a live feed is **paper trading against real
marks** — no real money can move — which is exactly how a paper/sim-execution engine is meant to be tested
(the standard "paper trading" step before live capital; e.g. the walk-forward/paper-then-live progression
in López de Prado, *Advances in Financial Machine Learning*, 2018, ch. on backtesting through time).

## Decision

We will **remove the `feedMode == SIM` gate from the order path** (fusion routing and AUTO hedging).
Auto-execution runs on **any** feed as **paper trading** (internal `SimulatedExecutor` fills against live
marks). The real-money guard is **not** a feed-mode check but **ADR-0015**: a live-execution gate is added
only at the *extracted* `order` module, behind an explicit real-trading-policy ADR, before any real broker
is wired. All other ADR-0019 constraints stand (deterministic/fusion source only; pre-trade guardrail
downstream; firm breaker; off by default; per-instrument cooldown).

## Alternatives considered

- **Keep sim-only routing (ADR-0019 as-is).** Rejected: it makes live-feed validation impossible while
  adding no real safety — execution is simulated on every feed, so the gate guarded nothing that ADR-0015
  does not already guard.
- **Wire Alpaca's paper *broker* API for order submission.** Deferred: that is real order submission to an
  external venue (even if paper), which is precisely what ADR-0015 says must wait for the order-module
  extraction. Internal `SimulatedExecutor` fills need none of that and carry zero external-submission risk.
- **A separate `allow-live-paper` flag.** Rejected as redundant: `jethro.fusion.route-orders` already
  gates whether the loop places orders at all; a second flag adds config surface for no safety gain.

## Consequences

- **Positive:** the full strategy→fusion→order→risk→P&L→hedge loop can be tested on real market data
  (paper) — the only way to measure *live* edge and cost (see ADR-0062); no real-money exposure.
- **Negative:** auto-trading now acts on live data, so a live-feed data error (stale/thin marks) can drive
  paper churn and misleading P&L — mitigated by the guardrail/breaker and made visible by the alpha-vs-cost
  attribution panel; it also means the sim-only gate can no longer be relied on as the real-money backstop,
  so **ADR-0015's order-module-extraction guard becomes the sole real-money guard and must hold.**
- **Follow-ups:** the live-execution gate at the extracted order module (ADR-0015); ADR-0062 (gate routing
  on positive *live* edge, since paper live trading exposed negative live edge in the deterministic algos).

*Amends ADR-0019 constraint 1 (which is updated to point here); does not supersede its other constraints.*
