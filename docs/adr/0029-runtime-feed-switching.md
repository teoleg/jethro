# ADR-0029: Runtime market-data feed switching — hot-swap sim/live without redeploy

- **Status:** Proposed
- **Date:** 2026-07-17
- **Deciders:** Oleg
- **Tags:** market-data, ops, trading-core

## Context

The market-data provider is fixed at boot: `jethro.trading.provider` (sim/yahoo/finnhub)
is read once when `trading-core` builds its adapter (ADR-0009 port). Changing it means
editing env and redeploying — on the baked-AMI path that is a ~20-minute rebuild and a new
instance (ADR-0013). For demos and testing we routinely want to flip sim↔live on a *running*
box in seconds: show live equities, then drop back to the offline sim without losing the
process, its warm marks, or open sim positions.

The forces: (1) live providers cost quota and carry ToS limits (Yahoo delayed/unofficial —
ADR-0023; Finnhub free-tier caps — ADR-0024), so we can only run **one** feed at a time.
(2) Mixing marks from two providers is unsafe — marks carry provider + ingest timestamps
(invariant 5) and internal code keys on `instrumentId` while providers speak their own
symbology (invariant 2). (3) Auto-execution (ADR-0019) acting across a feed change, or on
stale marks mid-swap, is a foot-gun. Doing nothing keeps feed changes a redeploy.

## Decision

We will add an **operator-only runtime control that hot-swaps the `trading-core` feed
adapter without restarting the JVM**: an authenticated `POST /api/admin/feed {provider}`
stops the current `MarketDataAdapter`, builds the new one behind the same ADR-0009 port and
ring buffer, and resumes — process, positions and durable log untouched. The swap is
**safety-gated**: it forces `TradingHaltSwitch` on (halting auto-execution and AI autonomy),
flags all existing marks stale until the new feed re-warms each instrument, and emits a
`FeedSwitched` audit event (operator, from→to, timestamp). Re-arming auto-execution after a
switch is a deliberate, separate action — a switch never silently keeps trading live. The
selection is persisted so it survives restart and overrides the boot default.

## Alternatives considered

**Restart with new env (status quo).** Simple and already works, but incurs downtime, throws
away in-process warm state, and on the AMI path is a 20-min rebuild — the exact cost we want
to remove. Rejected for the runtime requirement.

**Run all adapters at once, select downstream.** No swap latency, but burns live-provider
quota continuously and invites mark-mixing bugs; the single-feed constraint above makes it
wrong. Rejected.

**Config hot-reload (actuator `/refresh` / Spring Cloud Config).** Reloads properties, but an
adapter is a lifecycle with threads and native/quirky provider clients, not a bean property;
bolting refresh semantics onto it is more fragile than an explicit stop/start. Rejected.

**Feed router as a separate process (per-provider).** Matches the eventual order-JVM-style
extraction (ADR-0015) and isolates provider crashes, but is overkill for one JVM today and
adds a hop on the market path (ADR-0014). Deferred — revisit if a provider destabilises the
core or feed fan-out outgrows one process.

## Consequences

- **Positive:** sim↔live in seconds on a live box, no rebuild; safer than hand-editing env
  then redeploying; the forced halt + stale-flag + audit make the swap observable and hard to
  fire by accident.
- **Negative:** a live-feed switch on a running system is inherently risky (real data,
  provider cost/ToS, symbology re-mapping) — mitigated but not removed by the halt and
  stale-flagging. It adds a privileged control surface that **must** be authenticated; the
  dev edge is currently unauthenticated (ADR-0013), so this control ships disabled until an
  auth gate fronts it. Persisted selection can surprise: a box reboots onto the last runtime
  choice, not its baked default.
- **Follow-ups:** auth gate on `/api/admin/*` before enabling in any shared environment;
  `FeedSwitched` on an ops/audit topic; a Config-page control in the UI; interaction with the
  schema-registry serde (ADR-0030) is clean — `MarkEvent.source` already records the provider.
