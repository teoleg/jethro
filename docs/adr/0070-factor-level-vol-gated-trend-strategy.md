# ADR-0070 — Factor-level, vol-gated trend strategy

- Status: **Proposed**
- Date: 2026-07-26
- Relates to: ADR-0026 (factor simulator), ADR-0069 (exploitable trend), ADR-0044 (regime-aware selection),
  ADR-0051 (price-derived vol regime), ADR-0018/0019 (guardrail + lifecycle sizing/exits)

## Context

The book has consistently lost money in sim, and the improvement loop kept landing on "no edge / reduce-only".
Reading the sim calibration (`sim-calibration.json` + `CorrelatedFactorSimulator`) shows **why**, and where the
edge actually is:

- Per-name returns are `r_i = β_i·f_eq + β_i,usd·f_usd + idio_i`. The **idiosyncratic** term is fresh noise
  each tick and, for most names, dominates the variance. So a **per-name** trend/momentum sensor
  (`MomentumStrategy`, per-name `TrendDetector`) reads mostly noise — it fires late and often wrong, and any
  trading it does pays the spread. That is the observed bleed.
- The exploitable structure is at the **equity-factor** level and is twofold:
  1. **Regime persistence** — regimes carry very different equity drift and **persist for days**
     (measured from the calibration): CALM +5%/yr (~14d), TREND_UP +25%/yr (~20d) vs TREND_DOWN −30%/yr (~10d),
     RISK_OFF −120%/yr (~3d), INFLATION_SHOCK −60%/yr (~5d).
  2. **Vol clustering** — the down-drift regimes are exactly the **high-vol** ones (1.6×–3.0× vs 0.9×–1.0× up).
  3. Plus the weak AR(1) factor momentum added in ADR-0069.
- Round-trip equity cost is ~6–7 bp (ADR-0025), i.e. **≪** the regime drift gap. So the market is winnable
  **net of costs by positioning on the factor regime**, and lost by churning on per-name noise. This is the
  owner's thesis exactly: *the risk sensor IS the trend sensor* — rising factor vol is the cut signal, calm
  positive drift is the hold-long signal.

Nothing in the stack takes a **directional factor stance**: `TrendDetector` is unsigned (trend-vs-chop, picks
momentum-vs-mean-reversion per name); `VolatilityRegime` only **shrinks size** in elevated vol. Neither goes
*long the calm equity uptrend* and *flat on the vol spike*.

## Decision

Add `FactorTrendStrategy` (algo-engine) — a factor-level, vol-gated trend follower, wired as an **opt-in** live
strategy (`jethro.strategy.factor-trend.enabled`, **default false**). Its basket is the **refdata EQUITY
universe** (invariant 9 — refdata is the universe, never a sim list). Each cycle, from observable prices only
(invariant 8 — no sim oracle; identical in sim/live/replay):

1. **Factor step return** `f_t = mean_i ln(p_{i,t}/p_{i,t−1})` over the basket (equal-weight breadth).
2. **Signed factor trend** `z = Σf / (σ(f)·√n)` over the last `trendWindow` steps.
3. **Factor vol** `v = mean|f|` over the last `volWindow` steps; ratio `r = v / baseline`, `baseline` a slow
   EWMA of `v` (lagged, frozen upward while ELEVATED — the exact ADR-0051 mechanism), `ELEVATED` when
   `r ≥ volUpper`, back to CALM under `volLower` (hysteresis).

**Stance → per-name candidates** (danger overrides trend — CLAUDE.md triage rule 4):

| Condition | Stance | Emits |
|---|---|---|
| vol ELEVATED **or** `z ≤ −threshold` | **CUT** | SELL every basket name |
| vol CALM **and** `z ≥ +threshold` | **LONG** | BUY every basket name |
| otherwise (chop / weak trend) | **HOLD** | nothing |

The `Strategy` port emits only advisory candidates; the **lifecycle owns money** (ADR-0018/0019): it
vol-scales the size, caps max position per direction, and — with shorts off (the default) — a SELL only ever
**reduces a long toward flat**, never opens a short. So CUT = de-risk to flat, which sidesteps the high-vol
down-drift regimes; LONG builds/holds the vol-scaled long in the calm uptrend; re-emitting each cycle is
self-limiting (max-long BUYs are suppressed; flat SELLs have nothing to reduce). Independent stop/de-risk exits
(ADR-0019) and the deterministic floor (guardrail, firm breaker) are untouched and still bind.

Each candidate carries the **name's own** reference/price/changeBps/z, so the lifecycle sizes it exactly as a
momentum signal; the factor read decides only *whether* and *which side*.

### Worked example

Basket {ES, AAPL}, `trendWindow=volWindow=4`, `threshold=1.0σ`, `volUpper=1.5`. Factor step returns over the
last 4 steps: `+0.0012, +0.0008, +0.0011, +0.0009`.

- `Σf = 0.0040`, mean `0.0010`; sample `σ = 1.826e-4`; `z = 0.0040 / (1.826e-4 · √4) = 10.95` → `≥ 1.0`.
- `v = mean|f| = 0.0010`; with `baseline ≈ 0.0010`, `r ≈ 1.0 < 1.5` → **CALM**.
- ⇒ **LONG**: BUY ES and AAPL (lifecycle sizes/caps each).

Next step the factor jumps `+0.05` (a 5% shock): `v = (0.0008+0.0011+0.0009+0.05)/4 = 0.0132`,
`r = 0.0132/0.0010 = 13.2 ≥ 1.5` → **ELEVATED** ⇒ **CUT**: SELL ES and AAPL → the lifecycle reduces both to
flat. The book is out **before** riding the (high-vol) down-drift regime that a vol spike heralds.

## Consequences

- A strategy that positions on the factor regime instead of per-name noise — directly targets the measured
  edge, so "grow firm PnL" becomes reachable in sim (ADR-0069) and the mechanism transfers to live.
- **Default OFF**: when enabled it *replaces* the per-name selector as the live strategy (it is a whole-book
  directional stance, not a per-name overlay). It must be OOS-validated (ADR-0027 harness) before being trusted
  with real positioning; the loop/owner flip it on deliberately. No behaviour change until then.
- All numbers that gate money stay in the deterministic lifecycle/scorer (invariant 7). The strategy emits
  advisory sides only; `z`/vol-ratio are statistical `double`s, never money (invariant 1).
- `threshold-sigmas=1.0` is **PLACEHOLDER — Oleg to set** (mine, arbitrary; OOS to calibrate). The vol bands
  reuse ADR-0051's `volregime` dials so no second number is invented.

## Alternatives considered

- **Add a third algo inside `SelectingStrategy`** (per-name momentum/mean-reversion/**factor-long**) — rejected
  for v1: the factor stance is a *whole-book* decision, awkward to express as a per-name choice, and the change
  is riskier. A standalone opt-in strategy is cleaner and safely default-off.
- **Short the down-regimes** instead of cutting to flat — deferred: shorting policy (`allow-short`) is a
  separate risk decision; flat already avoids the down-drift and is the conservative default. Deferred-register.
- **Keep tuning per-name signals** — rejected: the edge is not there (idio noise dominates); more per-name
  tuning is overfitting noise.

## Deferred (see deferred-register)

- Live-tunable dials (ADR-0052 `SignalParams`) for this strategy; OOS calibration of `threshold-sigmas`.
- Optional short leg for confirmed contained-vol down-trends (behind `allow-short`).
- Extend the factor basket / stance beyond equities (USD, rates) once the equity sleeve is validated.
