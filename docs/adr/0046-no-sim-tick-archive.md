# ADR-0046: Don't archive SIM ticks — a seeded sim run is reproducible, not stored

- **Status:** Proposed
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** data, sim, cost

## Context

ADR-0014 §3 defines a write-behind archiver that batches raw ticks to S3 Parquet, off the hot
path, so backtests and incident replay can play the tape back through the replay adapter. That
justification is a *live-data* one: a live feed is a one-time, unrepeatable event — if we don't
capture it, it's gone, and post-fix PnL verification and incident replay depend on having it.

A SIM tape is the opposite. The correlated-factor simulator (ADR-0026) and the backtest tick
generator are **seedable and fully deterministic** — the same seed + config + `sessionEpoch`
regenerate the identical tape on demand, for free. Archiving sim ticks therefore stores bytes we
can always recompute, at real cost: storage grows every idle sim day, the archiver burns write-
behind CPU on the single dev node (ADR-0013), and — worse — it risks co-mingling sim and live
history that invariant 8 / ADR-0029 says must never be aggregated. We already stamp `feedMode`
(SIM/LIVE/REPLAY) on every event, so the archiver can see exactly what it's holding.

## Decision

We will **archive ticks only when `feedMode == LIVE`**. The write-behind archiver drops SIM (and
REPLAY — that tape already exists in the archive) before batching; it never writes a sim tick to
S3 Parquet. A sim run is "replayed" by re-running the generator with the same **seed + sim config
+ `sessionEpoch`**, not by reading a stored tape. To keep an *interactively* driven sim session
reproducible (ADR-0031 panel dial changes and ad-hoc news injections mutate the tape at wall-clock
moments, so the seed alone is not enough), the sim persists a **compact control-event log** — the
dial/news deltas with their tick offsets — never the tick stream itself. LMDB warm-restart/dedupe
state (ADR-0014 §4, derived data) is unaffected; this ADR is only about the replay archive.

## Alternatives considered

**Archive sim ticks like live (the ADR-0014 default).** Uniform code path, one archive to reason
about. Rejected: it pays storage + write-behind cost to persist perfectly reproducible data, and
puts sim bytes next to live history that must stay separated — cost and risk for no capability the
seed doesn't already give us.

**Archive sim but into a separate, short-retention sim bucket.** Keeps the exact tape for a while
without polluting live history. Rejected as unnecessary for a seeded source; the control-event log
already reproduces the only non-seed-determined part (panel/news mutations) at a fraction of the size.

**Persist nothing sim-related, not even the control log.** Simplest. Rejected: a hand-driven sim
session (dials moved, news fired) would then be unreproducible, losing the one sim case where the
seed isn't sufficient — the control log is cheap insurance for exactly that.

## Consequences

- Positive: no storage or write-behind cost for sim; the live archive stays live-only (reinforces
  ADR-0029 separation); one less way to accidentally aggregate sim into live history.
- Negative: reproducing a sim run now requires its seed + config + `sessionEpoch` (and, for a
  panel-driven session, the control-event log) to be recorded — if those inputs are lost, the run
  is gone, whereas a stored tape would have survived. A raw seeded tape is also no longer byte-
  addressable for ad-hoc inspection; you regenerate it instead.
- Follow-ups: build the archiver with the `feedMode == LIVE` gate from day one (ADR-0014 §3 impl);
  define the sim control-event log format + where the seed/epoch are recorded so a sim session is
  reconstructable; the replay adapter must accept "regenerate from seed" as a source alongside
  "read Parquet".
