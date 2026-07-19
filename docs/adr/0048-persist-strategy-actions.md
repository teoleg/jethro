# ADR-0048: Persist deterministic strategy actions (entries/exits + reasons)

- **Status:** Proposed
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** data, strategy, ui

## Context

The landing page shows two engines side by side: the AI hypothesis ledger and the deterministic
signal engine's "Strategy actions" (ALPHA/MACRO entries and risk-reducing exits, each with its
reason — the momentum rationale, or the stop-loss/take-profit/de-risk trigger, so a SELL is never
a mystery). The two are meant to be equally legible.

They are not equally durable. The AI side persists to `hypothesis_records` (+ scored `outcomes`)
and reloads on boot, so it survives restart — the UI comment literally says executed rows "don't
vanish." The deterministic side is an **in-memory `ArrayDeque` in `StrategyLifecycle`** (its javadoc:
"Live telemetry, in-memory only"). After any app restart the panel is empty and shows "no strategy
actions yet" until fresh signals fire, even though the strategy has a long history. On this platform
restarts are routine (dev iteration, the stop-when-idle EC2 node, redeploys), so the panel is blank
much of the time and the *reasons* behind past auto-trades are gone.

The orders/fills those actions produced **are** persisted (blotter), but a generic order row does
not carry the strategy's entry/exit semantics or its rationale, and it mixes provenance (AI, hedge,
manual, strategy). So "reconstruct the panel from orders" is lossy: the *why* only ever existed in
the in-memory `StrategyActivity`.

## Decision

We will **persist each deterministic strategy action to a `strategy_actions` table** and reload the
recent window into the in-memory buffer on boot — mirroring the hypothesis ledger, so both engines
have a durable, queryable, reason-carrying audit trail. Each row carries timestamp, kind
(ENTRY/EXIT), instrument, book, side, quantity, the reason string, order status, and the **order/fill
id** it came from, so a re-applied event is idempotent (invariant 6 — write is keyed on that id). The
ledger is **advisory telemetry only**: like the hypothesis records, it is never read for positions
or PnL — `fills` remains the sole source of truth (invariant 3). When persistence is off
(`PersistenceConfig` sim-only mode) the store degrades to the current in-memory behaviour.

## Alternatives considered

**Reconstruct the panel from persisted orders + fills at boot.** No new table; the order is already
durable ("the order itself is the durable record on the blotter", per today's javadoc). Rejected:
orders don't carry the entry/exit kind or the strategy rationale, and filtering strategy-originated
orders out of AI/hedge/manual flow is lossy — you'd end up adding strategy columns to the order
schema anyway, coupling it to strategy internals.

**Emit a `strategy.actions` Redpanda event and project it (event-sourced).** The most architecturally
consistent shape (ADR-0003/0012 cross-domain flow over topics) and it would feed other consumers.
Deferred, not rejected: the hypothesis ledger persists via a direct table write, so match that for
symmetry now and add the topic when a second consumer needs the stream — noted as a follow-up.

**Leave it in-memory (status quo).** Zero work. Rejected: it's the reported gap — the deterministic
engine's audit trail and its trade reasons evaporate on every restart while the AI side survives.

## Consequences

- Positive: the Strategy-actions panel survives restart and the reason behind every past auto-trade
  is durable and queryable; audit parity between the two engines; a natural "Strategy actions" sheet
  can join the diagnostics export next to "AI hypotheses".
- Negative: one more table + Flyway migration and a boot-time reload query; a second representation
  of something the order also records (kept honest by keying on order/fill id and never reading this
  ledger for money — it is telemetry, not truth).
- Follow-ups: the `strategy.actions` topic if/when a second consumer appears; add the persisted
  actions to the diagnostics export; a retention/prune policy so the table doesn't grow unbounded
  (the in-memory buffer was self-capping; the table needs an explicit trim window).
