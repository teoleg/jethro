# ADR-0032: History-anchored market simulation with first-class volume and liquidity

- **Status:** Proposed
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** market-data, sim, algo, execution, data

## Context

Two gaps surfaced testing the sim as a model lab. (1) **Dynamics are synthetic.** The
correlated factor model (ADR-0026) draws parametric Gaussian/Student-t innovations; the paths
"wiggle" in a way that doesn't match how real names move — vol clustering, gap structure and
cross-asset behaviour are approximated, not real. You can't trust a model tuned against a tape
that doesn't behave like the market. (2) **Volume and depth are absent from the alpha.** A code
sweep confirms volume is used *only* on the execution cost side (ADR-0025: ADV participation
cap + √-impact), and ADV is a static config number, not a live quantity. No signal, sizing or
AI feature reads volume; order-book **depth does not exist** — `onQuote` carries top-of-book
prices with no sizes, and fills match the touch ± a fixed spread. Real markets move *because of*
flow; a price process with no volume coupling and algos blind to liquidity is the root of the
"moves feel arbitrary" complaint.

These are one problem: to make volume/liquidity meaningful to the algos, the sim must first
*produce real volume and liquidity*, and the pipeline must carry them. Constraints: unit tests
and CI must stay network-free and deterministic (ADR-0009); the hot path stays allocation-
conscious (invariant 1, ADR-0014); external symbology never leaks (invariant 2); a bootstrapped
path is system-generated, so it is **SIM** provenance, not REPLAY (ADR-0029).

## Decision

We will add an **empirical, history-anchored sim engine** and carry **volume and synthesized
depth as first-class market data end to end**, consumed by the execution, sizing and signal
layers — built in phases where **every sim capability ships with the algo consumer that
exercises it** (no volume feature without a sim that emits real volume).

- **Data:** a checked-in **OHLCV snapshot** (daily, multi-year) for the core universe, refreshed
  on demand from Yahoo's historical endpoint (free, already integrated) and cached to disk —
  offline/CI run from the seed, online refreshes it.
- **Engine:** `HistoricalBootstrapSimulator` — a stationary **block bootstrap** over the real
  *cross-sectional* daily return+volume vectors, so real fat tails, vol clustering and
  cross-asset correlations come from the data itself; paths are novel and seedable, and the
  SimControl panel (ADR-0031) overlays on top. ADR-0026's factor engine stays as the fast,
  data-free engine for tests and offline.
- **Pipeline:** `onQuote` gains **bid/ask sizes**; a `MarketDepth` at-touch is synthesized from
  real volume + the ADR-0025 spread. ADV becomes **derived from the live sim** rather than a
  config constant.
- **Algos:** relative-volume confirmation in the strategies and **liquidity-aware sizing**
  (size vs live ADV/depth, not vol alone); the execution impact model reads live ADV.

## Alternatives considered

**Keep the factor model, just recalibrate it (status quo+).** Cheaper, no data dependency, but
you rejected it — no parametric calibration reproduces real vol clustering and gap microstructure,
and it leaves volume synthetic. Kept only as the offline/test engine.

**Verbatim historical replay as the sim.** Maximally realistic per path, but one fixed path with
no variation to test a model against, and it stops at "today". Deferred to REPLAY mode (ADR-0029)
for regression tapes; it complements, not replaces, the bootstrap.

**Real L2/order-book depth feed.** The honest way to model liquidity, but no free L2 source exists
and it's a large build. Rejected now: synthesized depth-at-touch from real volume is enough for
liquidity-aware sizing; revive when a real depth feed (or real-money routing) is on the table.

**Inject volume/depth only on the execution side, leave signals price-only.** Smallest change, but
it re-buries the gap you flagged — the point is for the *algos* to use flow. Rejected.

## Consequences

- **Positive:** a tape that behaves like the market (real tails/clustering/correlation); volume
  and liquidity become live, first-class, and consumed by sizing/signals/execution; the sim
  becomes a credible model lab and the control panel steers a realistic base.
- **Negative:** a checked-in data snapshot to maintain (staleness, size — keep it small/core);
  bootstrap destroys calendar-time causality (a Tuesday can follow a Friday) — fine for stress
  paths, wrong for anything date-dependent, so date-keyed logic must stay on REPLAY; synthesized
  depth is a model, not a book, and must be labelled as such so it isn't mistaken for real L2;
  `onQuote` gains fields (additive, backward-compatible per invariant 4).
- **Follow-ups:** phased plan below; real L2 feed and verbatim REPLAY tapes are separate future
  ADRs; depends on ADR-0025 (execution/ADV), ADR-0026 (retained engine), ADR-0029 (SIM
  provenance), ADR-0031 (control panel overlays the new engine).

## Implementation plan (phased — sim capability paired with its algo consumer)

Each phase is independently shippable, CI-green, and pairs a **sim** change with the **consumer**
that makes it meaningful. Guiding rule: never add an algo feature the sim can't exercise.

- **P1 — Real volume through the pipeline.** Sim: OHLCV snapshot loader + a checked-in seed
  snapshot for the core universe; the sim emits **real (bootstrapped) volume** per tick and a
  live **ADV** derived from it. Consumer: execution reads live ADV (participation cap + √-impact
  become data-driven, not a config constant); a `VolumeStats` service exposes rolling ADV/relative-
  volume. *Ships the data path; nothing downstream breaks.*
- **P2 — History-anchored engine.** Sim: `HistoricalBootstrapSimulator` (stationary block
  bootstrap over real cross-sectional return+volume vectors), selectable as provider/engine;
  factor engine retained for tests/offline. Consumer: backtest (ADR-0027) can run on the
  empirical tape; SimControl overlays it. *Realistic dynamics.*
- **P3 — Depth at the touch.** Sim: synthesize bid/ask **sizes** from real volume + spread;
  `onQuote` gains sizes; a `MarketDepth` view. Consumer: **liquidity-aware sizing** (cap size vs
  live depth/ADV) and a depth-aware fill nuance in the sim executor. *Liquidity becomes real.*
- **P4 — Flow in the alpha.** Consumer: **relative-volume confirmation** in momentum/mean-
  reversion (discount a move on thin volume), volume features into the hypothesis/AI layer.
  Sim already emits the volume that makes this testable. *Closes the "algos ignore flow" gap.*

Status: P1 in progress.
