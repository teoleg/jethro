# ADR-0032: History-anchored market simulation (sim price engine)

> **Scope narrowed** — this is the sim *price engine* only (gap-register G1). Volume-in-algos +
> depth are ADR-0033, news→tape coupling ADR-0034, RAG ADR-0035. See the Scope section below.

- **Status:** Accepted
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

## Scope (narrowed — see `docs/gap-register.md`)

This ADR is the **sim price ENGINE** decision (G1): the history-anchored block bootstrap. It was
briefly over-scoped with a P1–P5 plan that folded in unrelated threads; those are now tracked and
decided **separately** so none is lost inside "the sim work":

- **Volume live through the pipeline** (G2) — already shipped (measured ADV drives execution). It
  needed no new engine, so it landed ahead of this ADR; kept here only as the reason ADV is real.
- **Volume & depth consumed by the algorithms / synthesized depth** (G3/G4) — **ADR-0033**.
- **News → tape coupling** (news drives a correlated volume surge + bp momentum) (G5) — **ADR-0034**.
- **RAG for the AI layer** (G8) — **ADR-0035**.

This ADR delivers only: the OHLCV snapshot + loader (seed checked in, refreshed from Yahoo),
`HistoricalBootstrapSimulator`, selectable as an engine, with the factor model retained for
tests/offline and the SimControl panel (ADR-0031) overlaying it.

## Implementation status (2026-07-18) — built

- `HistoricalSnapshot` + `HistoricalBootstrapSimulator` (stationary block bootstrap; a test proves
  it reproduces the source return dispersion), `HistoricalMarketDataAdapter` (real per-tick volume,
  curve alongside), wired as `jethro.trading.sim-engine=historical` with a labelled synthetic seed
  fallback so it runs offline. Correlated stays the default.
- **Real snapshot capture** — `YahooHistoryClient` + `SnapshotCapture` align each instrument on the
  intersection of trading days and write the snapshot JSON; `POST /api/sim/snapshot/fetch` (and a
  button on `/sim.html`) capture 5y of real daily history, flipping the synthetic seed to genuine
  dynamics. Run it on a box with Yahoo access; CI/offline use the seed.
