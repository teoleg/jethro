# ADR-0052: Live, DB-persisted strategy tuning — every dial editable, every change its own provenance

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** strategy, ui, data, governance

## Context

The deterministic strategy's behaviour is governed by ~20 numbers in `application.properties`
(`jethro.strategy.*`): the signal-sensitivity dials (`threshold-sigmas`, `min-signal-bps`,
`lookback`, `volume-confirm-min`), the turnover throttle (`auto-cooldown-seconds`), and the
sizing/exposure gates (`target-notional`, `max-position-notional`, `regime-volatile-scale`,
`stop-loss-pct`, `take-profit-pct`, `risk-budget-daily`, per-class order caps). Tuning any of
them today means editing the file and restarting the app — too slow to answer questions like
"how sensitive is the book to small fluctuations?", which is a *sweep-and-watch* exercise.

The sim already has a live control panel (ADR-0031), but that is hard-gated to SIM feed mode and
its dials are explicitly *never money*. Strategy dials are different: some of them gate money,
risk, and exposure. Our standing convention (CLAUDE.md) is that every such number must carry its
source in the same change and must never harden from a silent default into an assumed rule — the
scar tissue from a `$250k` cap that was only a `@Value` default yet read as a policy. A naive
UI that lets those numbers be typed and forgotten would re-create exactly that failure.

The owner has decided (2026-07-19) that **all** dials should be runtime-editable and that changes
should **persist across restarts**. The design problem is therefore not *whether* to expose them
but *how to do it without losing provenance* — and how to keep the parts of the system that must
stay reproducible (the OOS edge measurement) insulated from a live dial.

## Decision

We will add a **`strategy_param_override` layer in Postgres** (Flyway V33) plus an append-only
**`strategy_param_change` audit log**, fronted by a `StrategyControl` bean and a REST/UI panel.
The **effective** value of any `jethro.strategy.*` dial is: the override if one exists, else the
`application.properties` value. Precedence is **DB override > config**; config remains the seed
and the "reset to default" target.

- **Every write is its own provenance.** A change row records `param, old_value, new_value, actor,
  note, changed_at`. A live-set risk number is thus an *owned, timestamped decision*, which is the
  provenance our convention demands — not a silent default. The UI always shows, per dial, the
  config default and "overridden → X by <actor> at <time>", so an override can never masquerade as
  the original rule.
- **The OOS backtest and selector always read the static config**, never the overrides. Edge
  measurement stays seeded and reproducible (ADR-0027), and a live dial can never silently move the
  ground truth the selector compares against.
- **Auto-execution stays sim-gated (ADR-0019).** Live tuning is allowed in all feed modes, but no
  dial can route a real-broker order because none can exist until the order module is extracted
  (ADR-0015). So "editable in LIVE" carries no real-money execution risk today.
- **Bounds are validated on write** (e.g. `threshold-sigmas ∈ [0.1, 10]`, non-negative notionals);
  an out-of-range write is rejected, not clamped silently.
- Structural, non-tuning settings (`interval-seconds` scheduler cadence, book routing) stay static
  for now — they are wiring, not sensitivity, and changing them live needs a reschedule/rewire.

## Alternatives considered

- **In-memory overrides, SIM-only (mirror ADR-0031).** Safest, and it was my first instinct, but
  it loses on both axes the owner asked for: overrides vanish on restart, and you cannot tune while
  watching a live/replay tape. Rejected against the explicit decision.
- **Fence off the money/risk gates — sensitivity dials only.** Cleanest governance (no exposure cap
  ever becomes a UI dial), but the owner wants full control. We keep the *governance* intent instead
  via the audit log: the fence becomes "every risk change is recorded and attributed", not "you may
  not change it."
- **Overrides in `application.properties` via Spring Cloud Config / refresh scope.** Reuses the
  config mechanism, but there is no such infrastructure here and it muddies "the file is the seed"
  with "the file is also mutated at runtime". A dedicated table with explicit precedence is simpler
  to reason about.
- **Let the backtest read the live overrides too (one source of truth).** Rejected: it would make
  the OOS median — the thing the selector trusts to gate real trading — depend on whatever a dial
  was set to mid-run, destroying reproducibility and the seeded-sim guarantee.

## Consequences

- **Positive:** sensitivity/sizing can be swept live and the book's response watched immediately;
  every risk change is attributed and reversible ("reset to config"); the OOS ground truth is
  provably insulated from live tuning.
- **Negative:** `application.properties` is no longer the whole truth for strategy params — an
  operator must consult the panel to know the effective value, and a bad live value degrades live
  trading instantly (mitigated by write-bounds, the audit trail, and reset-to-default). The DB is
  now on the strategy's config path; a DB outage falls back to config defaults (fail-safe), which
  means overrides silently lapse until it recovers — an accepted, logged degradation.
- **Follow-ups:** surface the audit log in the UI (who changed what, when); consider per-instrument
  overrides (today it is global per dial); a future ADR if `interval-seconds`/book-routing ever need
  to become live too.
