# Jethro — Multi-Asset Quant Engine (target architecture)

Status: north star for the risk/pricing engine. Decisions live in [`docs/adr/`](../adr/README.md);
this sits under [`overview.md`](overview.md) and elaborates the `risk-pnl` + `algo-engine`
analytics layer. Foundation: **OpenGamma Strata** as the analytics substrate (ADR-0020).

## How to use this doc

This is the anti-tunnel-vision anchor. Every non-trivial risk/pricing change must name the
**layer** and **asset class** it serves here, and what it defers. If a task doesn't map to
a box below, it's either mis-scoped or missing from the plan — stop and reconcile before
building. "It computes the number we need right now" is not a location on this map.

## The layered model

The engine is layered so each asset class plugs into the *same* pipeline; only the market
data and product mapping differ. The **exact money ledger** runs alongside as the record of
truth — analytics never overwrite it.

```mermaid
flowchart TB
    subgraph in[Inputs]
      RD[reference data<br/>instruments, calendars, day counts]
      MD[market data<br/>marks, rates, vol quotes]
      FILLS[(fills — source of truth)]
    end
    subgraph strata[Analytics substrate · OpenGamma Strata · double]
      CURVES[curves & vol surfaces]
      PRICE[pricing<br/>PV + Greeks]
      MEAS[risk measures<br/>sensitivities · VaR · scenario/stress]
    end
    subgraph ledger[Money ledger · common-domain · exact BigDecimal]
      POS[positions & average-cost PnL]
      EXP[exposure & cash]
    end
    POLICY[deterministic policy<br/>limits + pre-trade guardrail]
    OUT[risk snapshot → attention feed · UI · strategy · limits]

    RD --> CURVES
    MD --> CURVES
    CURVES --> PRICE --> MEAS
    FILLS --> POS --> EXP
    POS --> MEAS
    EXP --> MEAS
    MEAS -->|money figures, at the boundary| OUT
    POS --> OUT
    EXP --> POLICY
    MEAS --> POLICY
    POLICY --> OUT
```

## The money/analytics boundary (invariant 1)

The single rule that keeps this from becoming a toy *or* violating exactness:

- **Exact `BigDecimal` (record of truth):** positions, average-cost PnL, realized cash,
  quantities, notional exposure. Owned by `common-domain` / `risk-pnl`. Never `double`.
- **`double` analytics (estimates):** curves, discount factors, implied vols, Greeks, VaR,
  scenario P&L. Owned by Strata. These are *estimates*, not ledger money.
- **Conversion happens only at the money boundary:** a measure that becomes a currency
  figure (VaR in USD, scenario P&L, option PV booked to the ledger) crosses back to
  `BigDecimal` at a single, reviewed conversion point — never mingled mid-calculation.

## Asset-class coverage matrix

The pipeline is one shape; asset classes differ in what market data and pricing they need.
Priority reflects the current book and the build order (overview step 7→8).

| Asset class | Market data needed | Strata product / pricer | Risk beyond notional | Priority |
|---|---|---|---|---|
| Equity (cash) | last mark | (mark × qty; no curve) | vol, VaR, concentration | **now** |
| Index/commodity futures | last mark, multiplier | future price × multiplier | vol, VaR, basis (later) | **now** |
| FX spot | pair mark | FX rate; cross via triangulation | vol, VaR, **ccy conversion** | next |
| Rates (bond/IRS futures & cash) | yield/discount curve | curve pricer → PV, DV01 | curve sensitivities, VaR | after FX |
| Options (listed) | vol surface + underlier | Black/curve pricer → PV, Greeks | delta/gamma/vega, scenario | after rates |
| Credit | credit curve | CDS pricer → PV, CS01 | spread sensitivities | later |

FX is the pivotal one: it unlocks **cross-currency** so the risk rollups stop reporting
`MIXED` and sum in a base currency (today all instruments are USD to defer this).

## What we own vs what Strata owns vs QuantLib

- **We own:** the domain ledger (positions/PnL/cash, exact), the event flow (fills →
  projection, `risk.snapshots`), deterministic **policy** (limits + pre-trade guardrail,
  invariant 7), and the attention/UI surface. These never move into a library.
- **Strata owns:** market-data calibration (curves, surfaces), pricing (PV, Greeks),
  and the measures + scenario/stress framework. The analytics layer only.
- **QuantLib:** reserved as an **out-of-process** pricing service (ADR-0002/0010) for
  exotics Strata can't cover — never JNI-embedded on the Pi.

## Current state vs target (honest gap)

Built (overview steps 1–8 partial):
- exact positions + average-cost realized PnL (`Positions`), mark-to-market unrealized,
  **notional** gross/net exposure, per-book/asset-class/firm rollups (`RiskProjection`);
- deterministic **limits + pre-trade guardrail** (`RiskLimitEvaluator`, `PreTradeGuardrail`),
  including loss gates, instrument concentration, firm caps and in-flight reservations;
- the Strata substrate (sequencing steps 1/3/4): **FX cross-currency** rollups via
  `FxMatrix` (`FxConversion`), a calibrated **USD SOFR zero curve** from live sim quotes
  (`CurveService`), and **swap PV / par / DV01** via the discounting pricers
  (`SwapPricingService`) — analytics in `double`, money at the boundary (invariant 1);
- **rates & swaps as market participants** (layer: market data + strategy; asset class:
  rates): Treasury futures priced *from* the factor curve (duration link + small basis),
  the curve coupled to the sim's market regimes (trends/vol/shocks), and swap par rates
  quoted live (`USD_IRS_*`) and tradeable under the V9 quoting convention — price = par
  rate %, 1 lot = $1M, multiplier = inception DV01×100. Named limitation: constant-DV01
  first-order PnL; exact revaluation stays with the Strata pricer;
- a deterministic momentum **strategy** — z-score (vol-adaptive) signals, vol-scaled
  sizing, position-aware participation, per-class routing/caps → guardrailed
  suggestions/auto-exec (ADR-0018/0019);
- an interim parametric **VaR/vol/concentration** stat helper (`RiskStats`) — a stopgap
  Strata's measures framework subsumes.

Not built (the gap this doc frames):
- Strata measures: real (measure-based) VaR + **scenario/stress** (curve ±100bp, equity
  ±5% → portfolio P&L → attention feed) — sequencing step 2, the biggest open box;
- curve *sensitivities as risk state* (bucketed DV01 per book), Greeks/options, credit;
- portfolio (correlation-aware) VaR; regime-aware strategy behaviour (step 5 remainder);
- swap lifecycle beyond first-order: accrual/roll-down, DV01 refresh, per-trade economics.

## Sequencing (every future task has a home here)

1. **Adopt the substrate.** Verify the Strata artifact resolves; introduce a `pricing`
   seam in `risk-pnl` (or a sibling module) that maps `Instrument` → Strata product and
   `Mark`/curve data → Strata market data. Equities/futures first (mark-based, trivial map).
2. **Measures over the substrate.** Replace the `RiskStats` stopgap with Strata measures:
   volatility, VaR, sensitivities, and a first **scenario/stress** (parallel shocks) — the
   real tail-risk answer parametric VaR only approximates.
3. **FX + cross-currency.** FX products + triangulation; risk rollups sum in a base
   currency; retire the `MIXED` fallback.
4. **Rates, then options, then credit.** Curves → DV01/curve sensitivities; vol surfaces →
   Greeks; credit curves → CS01. Each is "new market data + product map," not a new pipeline.
5. **Close the loop.** Feed the enriched snapshot into the strategy and into limits (VaR
   cap, concentration cap); scenario results become attention triggers.

Each step reaffirms the invariants: exact money ledger (5), AI/strategy never computes the
risk numbers (7), guardrails deterministic (7), drops/gaps counted not silent (9).
