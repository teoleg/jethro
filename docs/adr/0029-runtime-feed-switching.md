# ADR-0029: Runtime feed switching and hard sim/live/replay data separation

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** Oleg
- **Tags:** market-data, ops, data, trading-core

## Context

Two coupled needs. (1) The market-data provider is fixed at boot (`jethro.trading.provider`);
changing sim↔live means a rebuild/redeploy — ~20 min on the AMI path (ADR-0013). We want an
operator to flip it on a *running* box in seconds for demos and testing. (2) We must never
co-mingle data the system generated under **simulation** with data derived from a **live**
feed — not in the durable log, not in the positions/PnL projection, not in the S3 archive. Sim
marks, sim fills and sim PnL sharing a stream or a projection with live ones makes every number
ambiguous and every audit suspect.

These interact: a runtime feed switch is exactly the moment mixing would occur. Constraints:
one feed at a time (provider quota/ToS — ADR-0023/0024); events already carry provider + ingest
timestamps (invariant 5); the platform deliberately runs sim, live and replay through the
**identical pipeline** (ADR-0009/0014) — a property worth preserving. Doing nothing keeps feed
changes a redeploy and leaves separation to convention.

## Decision

We will (a) add an **operator-only runtime feed switch** — `POST /api/admin/feed {provider}`
stops the current adapter and starts the new one behind the same ADR-0009 port and ring buffer,
no JVM restart — and (b) enforce **hard mode separation instead of forking schemas**:

- Every event carries **`feedMode` (SIM|LIVE|REPLAY)** and a **`sessionEpoch`** (run id) in
  `EventMeta` — additive, defaulted, backward-compatible under ADR-0030.
- State is **namespaced by mode**: topic prefix (`sim.*` / `live.*`), DB schema/table partition,
  and S3 prefix. A live consumer subscribes only to `live.*` — a physical read-firewall without
  duplicate schemas.
- **One mode per session.** A switch that crosses the sim/live boundary **rolls a new
  `sessionEpoch` and namespace** (fresh projection) — never soft-continues; it also forces
  `TradingHaltSwitch` on, flags marks stale until the new feed warms, and emits an audited
  `FeedSwitched` event. Re-arming auto-execution (ADR-0019) is a separate, deliberate step.

## Alternatives considered

**Separate schemas per mode (`SimMarkEvent`/`LiveMarkEvent`, …).** A type-level firewall, but it
doubles every contract and all downstream code — the two drift the first time a shared change
lands in one and not the other — and it abandons the single-pipeline property that makes the sim
trustworthy (ADR-0009/0014). It also doesn't stop aggregation on its own (one topic/projection
still sums both). Rejected — the read-firewall is bought better with per-mode topics + a
`feedMode` tag.

**Restart with new env (status quo).** Downtime, loses warm state, 20-min AMI rebuild. Rejected
for the runtime requirement.

**Convention-only separation (a `source` string, no namespacing).** Cheapest, but one
mis-subscribed consumer silently mixes modes; separation you can't enforce isn't separation.
Rejected.

**Separate deployments for sim vs live (two boxes).** The hardest guarantee and the right shape
for production — but overkill for one dev box (ADR-0013/0015) and it forecloses the fast in-box
demo switch. Deferred: real-money running is the trigger to physically split them.

## Consequences

- **Positive:** sim↔live in seconds, no rebuild; sim and live data are un-mixable across the log,
  projections and archive; every record proves its own provenance (audit; strengthens invariant 5).
- **Negative:** a boundary switch **resets in-session projections** (new epoch) — intended, but
  you can't "continue" a book across a mode flip. Namespacing multiplies topics and DB schemas
  and must be honored by every producer/consumer/archiver — a mis-namespaced write is the new bug
  class to watch. The admin control is privileged and the `:8080` edge is unauthenticated
  (ADR-0013), so the switch ships **disabled until an auth gate** fronts it. Persisted selection
  means a reboot comes up in the last runtime mode, not the baked default.
- **Follow-ups:** on acceptance this graduates to a **hard invariant** — "sim/live/replay data is
  never aggregated across modes; every event carries `feedMode` + `sessionEpoch`" — in CLAUDE.md
  and the architecture overview. Depends on ADR-0030 for the additive `EventMeta` fields;
  `FeedSwitched` on an ops/audit topic; Config-page UI control; auth on `/api/admin/*`.
