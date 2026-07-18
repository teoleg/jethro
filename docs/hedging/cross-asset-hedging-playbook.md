# Cross-asset hedging playbook — what moves with what, and how to offset it

The relationships a hedge relies on are not new: they are decades of observed market
structure. This is the practitioner's map of "when X moves, what usually compensates",
the standard hedge for each, and **how Jethro already encodes it** (the sim factor model
and `sim-calibration.json`, refreshable from real history via `scripts/calibrate_sim.py`).

The one formula under all of it (Ederington 1979, minimum-variance hedge):

```
h* = Cov(ΔS, ΔH) / Var(ΔH)          size of hedge H per unit of exposure S
variance removed = ρ²(S, H)          the hedge's effectiveness; residual σ = σ_S·√(1−ρ²)
```

A hedge works only to the extent S and H are correlated. ρ near ±1 → near-complete
offset; ρ near 0 → you are not hedging, you are adding a second bet. Jethro always shows
`ρ²`, so a weak proxy is flagged "reduce, don't hedge".

## The established co-movements (the "concerts")

**1. Single stock ↔ equity index — the primary equity hedge.**
A stock's return decomposes into *systematic* (market) and *idiosyncratic* (name-specific).
`r_i = βᵢ·r_market + εᵢ`. The systematic part is hedged by shorting β·notional of the index
future (ES for S&P, NQ for Nasdaq). What's left is the pure stock-picking bet (ε) — which
is usually the point. High-β names (NVDA ≈ 1.7) need a large index short; low-β defensives
(JNJ ≈ 0.55) need little. This is the deepest, most stable relationship (ρ typically
0.6–0.9 for large caps), which is why it's the first axis built.

**2. Equities ↔ Treasuries — flight-to-quality, and when it inverts.**
For most of 2000–2021 stocks and bonds were *negatively* correlated: risk-off → equities
down, investors buy Treasuries → yields down → bond futures (ZN/ZB) up. So long bonds
hedge long equities (and vice versa) — the classic 60/40 diversification. **The sign is
not permanent.** In an inflation shock (2022) both fell together: equities down *and* yields
up (bonds down), because inflation/hiking hits both. A hedger that assumes a fixed
stock-bond sign gets destroyed in that regime — which is why Jethro carries **per-regime
correlations**, not one number.

**3. USD ↔ risk assets and FX — the dollar smile.**
The dollar tends to bid in global risk-off (safe-haven demand) and in US-outperformance
booms, and to weaken in "risk-on, rest-of-world catching up" phases. So a strong USD move
usually coincides with pressure on risk assets and on non-USD FX. FX exposure is hedged
*directly* in the pair (a EUR asset → sell EURUSD); no proxy needed, ρ² ≈ 1.

**4. The rates curve — level and slope, hedged by DV01.**
Rates instruments don't share the equity factor; they move on the *curve*. Two factors do
most of the work: **level** (parallel shifts, ~90% of variance) and **slope** (2s10s
steepening/flattening). Hedge rates risk by **DV01-neutralizing** each bucket with the
Treasury future that keys off that tenor: ZT (2y), ZF (5y), ZN (10y), ZB (long). Hedge
size = −bucketDV01 / contractDV01. This is a *structural* hedge (ρ² ≈ 1 within a bucket),
not a statistical one.

**5. Within equities — sectors and style factors.**
Beyond the market, names load on sectors (financials like JPM carry rate sensitivity;
tech is long-duration) and styles (value/growth, quality, momentum). A market-neutral book
still has factor tilts. Full factor hedging (a covariance-QP overlay) is deferred; the
single-index β-hedge captures the dominant piece first.

**6. Volatility and gold — the risk-off assets Jethro doesn't (yet) hold.**
VIX/vol and gold rally in stress and are textbook tail hedges, but there are no options or
gold instruments in the universe, so they're out of scope until added (an options ADR would
be the trigger). Noted so the map is honest about what it can and can't hedge.

## How the regime changes the concert

Correlations are conditional — they tighten and flip by regime. This is the heart of the
"one moves, another compensates" question: *sometimes it compensates, sometimes it
amplifies.* Jethro's calibrated per-regime factor correlations (order: EQUITY, RATES_LEVEL,
RATES_SLOPE, USD):

| Regime | EQ–RATES(level) | EQ–USD | What it means for hedging |
|---|---|---|---|
| CALM | +0.15 | −0.10 | Mild links; diversification roughly works |
| TREND_DOWN | +0.40 | −0.35 | Correlations rising as things sell off |
| RISK_OFF | **+0.60** | **−0.60** | Everything couples: stocks↓, yields↓ (bonds↑ hedge works), USD↑. Tail hedges pay |
| INFLATION_SHOCK | **−0.60** | −0.40 | **Sign flip**: stocks↓ WHILE yields↑ (bonds↓) — the bond hedge FAILS; only cash/USD/short helps |

The practical rule a desk lives by, and the reason Jethro measures rather than assumes:
**correlations go to 1 exactly when you need diversification most** (RISK_OFF), and the
one reliable cross-asset hedge (long bonds vs long equities) **inverts** in an inflation
regime. A hedge sized off a trailing covariance adapts to this; a hedge sized off a
hard-coded beta does not.

## What Jethro already encodes, and how to ground it in real data

- **The factor model** (`CorrelatedFactorSimulator`, ADR-0026) *is* the "what hedges what":
  every instrument carries `betaEquity`, `betaUsd`, and rates instruments price off the
  level/slope factors. Those betas are the hedge ratios in structural form.
- **`sim-calibration.json`** holds the per-name vols/betas, per-regime correlation matrices,
  and the regime transition matrix — the numbers in the table above.
- **`scripts/calibrate_sim.py`** regenerates all of it from **real Stooq daily history**
  (SPY→ES, QQQ→NQ, IEF→10y, spot FX), classifying each real day into a regime and computing
  within-regime correlations. Run it on a box with internet (the platform never fetches at
  runtime — ADR-0009):
  ```
  python3 scripts/calibrate_sim.py > app/src/main/resources/sim-calibration.json
  ```
- **For the live hedger's covariance** (the warm-up problem): the honest seed is a snapshot
  of **real daily returns** for the universe, loaded at boot, so the EWMA covariance starts
  from measured history instead of a cold start — identical in sim and prod (prod seeds from
  the same real daily bars). This extends `calibrate_sim.py` to also emit a returns snapshot;
  it is the next build if we go that route.

## References (the source literature)

- Ederington, L. (1979), *The Hedging Effectiveness of the New Futures Markets* — the
  minimum-variance hedge ratio and ρ² effectiveness used throughout.
- J.P. Morgan/Reuters, *RiskMetrics Technical Document* (1996) — EWMA covariance (λ=0.94),
  the estimator Jethro uses for VaR and the beta-hedge.
- Standard cross-asset macro: the stock–bond correlation regime literature (negative
  1998–2021, positive in inflationary regimes such as 1970s and 2022) motivates the
  per-regime correlations rather than a single constant.
