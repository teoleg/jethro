# ADR-0040: Structural sector hedging — a history-free fundamental floor under the statistical hedge

- **Status:** Accepted
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** risk, hedging, quant, refdata

## Context

ADR-0038's minimum-variance hedge is *statistical*: it needs an EWMA return covariance, which
needs a daily-return history to estimate. That history has proven fragile to source — the free
providers wall bulk history — so the hedger sits unhedged through warm-up and whenever the feed
can't supply enough days. But a desk does not stop hedging because it lacks an estimated
covariance: it hedges from what an instrument *is*. A tech name's systematic risk is hedged with
a tech proxy because it is a tech name (GICS membership), not because a matrix said so; a book
long defensives and short cyclicals is partly self-hedged by construction. These **structural /
fundamental** relationships are known a priori and need no price history. What's missing is a
hedge tier built on them, and a deterministic rule for when it yields to the statistical hedge.

## Decision

We will add a **structural hedge tier** that hedges from instrument refdata — a hedge group
(sector) and an assigned, provenance-labeled fundamental beta — requiring **no return history**,
and make it the deterministic **floor**. ADR-0038's statistical min-variance hedge becomes the
**upgrade** that engages once the covariance clears its observation gate and the proxy clears the
ρ² floor, falling back to structural otherwise. Both feed the one ADR-0039 lifecycle.

1. **Hedge-group refdata.** Each equity carries a hedge group, a hedge proxy (sector ETF or index
   future), and an assigned fundamental beta — all in instrument refdata (extends the GAP-4
   refdata move). Every beta carries its source (a published/vendor beta or an explicit
   `PLACEHOLDER — Oleg to set` / stated conservative convention); an unlabeled or invented number
   is a bug (CLAUDE.md risk-number rule). The scheme is GICS-shaped, each group with a liquid ETF
   proxy and a **regime tag** used only for the balance view (item 3), never to size:

   | Hedge group | Proxy | Regime tag | Current names → recommended adds |
   |---|---|---|---|
   | Information Technology | XLK | cyclical, long-duration | AAPL, MSFT, NVDA, SAP |
   | Communication Services | XLC | cyclical | GOOG |
   | Consumer Discretionary | XLY | cyclical | AMZN → + HD |
   | Financials | XLF | rate-benefiting | JPM → + BAC |
   | Health Care | XLV | defensive | JNJ → + UNH |
   | Consumer Staples | XLP | defensive | *(add PG or KO)* |
   | Energy | XLE | cyclical, inflation | *(add XOM or CVX)* |
   | Industrials | XLI | cyclical | *(add CAT or HON)* |
   | Utilities | XLU | defensive, rate-sensitive | *(add NEE or DUK)* |
   | Real Estate | XLRE | rate-sensitive | *(add O or PLD)* |
   | Materials | XLB | cyclical, inflation | *(optional)* |

   The universe is tech-heavy today, so cross-sector balance is thin; populating at least one
   defensive (staples/utilities) and one inflation-cyclical (energy) name is what makes the
   balance view real rather than a single-name hedge (follow-up).
2. **Structural equity hedge.** Net USD exposure per hedge group; hedge each group to flat at its
   assigned beta: `hedgeNotional = −β_assigned × netGroupUsd`, `qty = hedgeNotional/(price×mult)`.
   No covariance. Effectiveness is **asserted** (from the fundamental model), tagged distinctly so
   it is never read as ADR-0038's *measured* ρ².
3. **Balance is expressed by netting, not asserted.** The regime tags define three balance axes —
   **defensive vs cyclical** (staples/healthcare/utilities vs discretionary/energy/industrials/
   tech), **rate-benefiting vs rate-sensitive** (financials vs utilities/REITs/long-duration
   tech), **exporter vs domestic** (USD). Positions the desk runs on opposite sides of an axis
   shrink the aggregate net, so the structural hedge sizes less. We surface the per-group and
   per-axis nets so the balance is visible; we do **not** hardcode "sector A cancels sector B at
   ratio X" — the netting expresses it, and the residual net is what gets hedged to the proxy.
4. **Deterministic tier selection.** Per axis: covariance ready ∧ ρ² ≥ floor → statistical
   (ADR-0038); else → structural. The active tier and the reason ride on the proposal
   (invariant 7); tiers are never silently blended.
5. **Crisis regime is out of scope here.** Structural balance fails when correlations converge to
   1 in a selloff; the overlay tail hedge (index puts/VIX) stays deferred (no options universe,
   ADR-0038 alternative). Until it lands, the floor is the sector hedge plus the ADR-0039 breaker
   one-shot de-risk.

## Alternatives considered

**Statistical-only (ADR-0038 alone).** Cleanest when data exists, but leaves the book unhedged
through covariance warm-up and whenever history can't be sourced — the exact failure we hit.
Rejected as the *sole* method; kept as the upgrade tier.

**Hardcoded pairwise sector-offset matrix** (assert defensives cancel cyclicals at a fixed ratio).
Invents numbers that masquerade as stable relationships and break across regimes. Rejected — let
netting express balance; don't assert it.

**β = 1 dollar-for-dollar index hedge.** The crudest structural hedge. Rejected as the default (an
unlabeled β = 1 misstates systematic exposure — the placeholder we banned), but allowed as an
explicit, *labeled* per-group convention where no better fundamental beta exists yet.

**Full fundamental factor model (Barra/Axioma-style).** The institutional answer — multi-factor
loadings from characteristics — but a large build (factor set, exposure calc, factor-cov). Deferred
— trigger: single-proxy structural residual staying above target.

## Consequences

- Positive: a real hedge with **zero history dependency** — the book is protected from tick one and
  when the feed can't supply history (directly fixes the seed fragility); tier selection is
  deterministic and auditable; the desk sees per-sector net balance.
- Negative: structural effectiveness is **asserted, not measured** — cruder, leaves style +
  idiosyncratic residual; assigned betas need governance (a stale published beta silently
  mis-hedges); the sector→proxy map is refdata to maintain; the correlations→1 crisis regime is
  uncovered until the tail overlay lands.
- Follow-ups: hedge-group + assigned-beta refdata (with provenance); **expand the equity universe
  to populate the thin balancing sectors** (≥1 staple/utility defensive + ≥1 energy/industrial
  cyclical) so the balance view isn't single-name; tier-selection wiring in the advisor;
  assigned-vs-realized beta telemetry (promote to statistical once data confirms); tail-hedge
  overlay (options); fundamental factor model behind the stated trigger.
