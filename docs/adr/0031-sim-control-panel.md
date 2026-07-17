# ADR-0031: Sim control panel — live UI dials over the simulator for model testing

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** Oleg
- **Tags:** market-data, sim, ui, testing

## Context

The simulator (ADR-0026 correlated factor model) is configured once at boot — tick interval,
per-instrument start prices and vols, regime behaviour — from `jethro.trading.*` properties.
To actually develop and stress models you need to *stage scenarios interactively*: speed the
feed up, push a name's price, crank volatility on one instrument, force a RISK_OFF regime, drop
volume, pause. Today every one of those is a config edit + restart, which is far too slow a loop
to iterate a strategy against, and you can't watch the model react in real time.

The forces: the sim is the one market source that's fully ours and deterministic (seedable) —
so it's the right place to add controls without touching any real-feed code path. But mutating
the market path at runtime is delicate: the tick generator runs on the hot `sim-feed` thread
(allocation-conscious, ADR-0014), and a control surface must never bleed into LIVE mode (a dial
that moved a real book would be a serious bug) or break determinism when untouched.

## Decision

We will add a **sim-only control panel**: a `SimControl` handle the simulator reads each tick
for its live parameters, a REST surface (`/api/sim/*`) that mutates it, and a UI page (`/sim`)
of dials. It is **hard-gated to `feedMode == SIM`** — the endpoints and page are absent/reject
outside sim, so it can never perturb live or replay data. Dials, all live and audited: global
**tick frequency**, **pause/resume**, **reseed**; per-instrument **price nudge / drift bias /
volatility** and **volume(ADV) scale**; and a **regime override** (force CALM/VOLATILE/RISK_OFF/
INFLATION_SHOCK or AUTO). Untouched, the sim behaves exactly as its seeded config does today
(controls default to the configured values — determinism preserved). The panel lists **exactly
the instruments currently in the sim universe** (from refdata/config), not a hardcoded set.

## Alternatives considered

**Config edit + restart (status quo).** Zero new surface, but a multi-minute loop and no live
reaction — useless for iterating a model. Rejected as the interactive path; still fine for the
baseline seed.

**Scenario script files replayed through the sim.** Great for *repeatable* regression scenarios
and worth having later, but it's author-then-run, not turn-the-dial-and-watch. Deferred — it
complements the panel (capture a panel session into a script) rather than replacing it.

**Drive scenarios by injecting marks on the topic directly.** Bypasses the sim engine, so marks
wouldn't carry the factor-model correlations/regimes that make the sim realistic, and it would
need the same LIVE guard anyway. Rejected.

## Consequences

- **Positive:** a real model-development loop — stage a shock, watch risk/PnL/AI react live,
  without restarts; the sim becomes a lab, not a fixed tape. All instruments + sensible ranges
  exposed in one view.
- **Negative:** the tick generator gains **mutable, thread-safe** live parameters (volatile/
  atomic reads on the hot path — measured against the perf guardrails); a new control surface to
  keep **strictly sim-gated** (the guard is a tested invariant, not a comment); dials can create
  deliberately unrealistic markets (the point — but panel-driven sessions are labelled so a
  screenshot isn't mistaken for organic sim output).
- **Follow-ups:** scenario capture/replay (script files); a "shock" one-click preset library;
  wiring the panel behind the same auth as `/api/admin/*` once that exists (sim-only lowers the
  urgency). Depends on ADR-0029 for the `feedMode` gate.

## Implementation status (2026-07-17)

Built:
- **`SimControl`** (`trading-core/market-data`) — the live dials the `sim-feed` thread reads each
  tick: global speed / pause / regime-override / reseed, per-instrument nudge / drift / idio-vol /
  volume. Thread-safe (volatile globals, `AtomicLongArray` per-instrument), every dial defaulting
  to the identity so an untouched panel reproduces the exact seeded tape (a test asserts the
  bit-for-bit match against the no-control constructor).
- **Engine wiring** — `CorrelatedFactorSimulator` applies reseed/nudges, honours the forced
  regime, and folds per-instrument vol/drift into the return; `CorrelatedMarketDataAdapter` honours
  pause + speed pacing and the volume dial. A second constructor keeps the old call sites unchanged.
- **REST + UI** — `SimControlController` (`/api/sim/*`) hard-gated to `feedMode == SIM` **and** the
  correlated engine being present (403 otherwise, a tested invariant); `/sim.html` panel with the
  live instrument universe (names from refdata) and a panel-driven-vs-seeded label.

Deferred (per "Follow-ups" above): scenario capture/replay, a preset shock library, auth on the
panel, and stamping a panel-driven flag onto emitted events (today the label is UI-only, derived
from `anyDialActive()`).
