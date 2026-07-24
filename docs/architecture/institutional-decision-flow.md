# The institutional decision flow — reference model and Jethro gap analysis

*2026-07-20. Research companion for the signal-fusion discussion (pre-ADR-0055). Sources at the end;
each stage cites the canonical text it comes from.*

## Why this document

Jethro now has many signal sources (deterministic strategies, AI hypotheses, news/social, a learned
label, regime posture, a hedger) and several of them can act independently. Oleg's instinct — *"run a
combined score of all sources before making any order decision"* — is exactly how institutional
systematic firms are structured. This document writes down that canonical flow with references, maps
Jethro against it, and lists the gaps in priority order.

## The canonical pipeline (systematic hedge fund / quant desk shape)

Every serious systematic operation — AHL/Man, AQR, the multi-strategy pod shops — converges on the
same layered flow. The defining property: **signals never place orders**. Signals produce *forecasts*;
forecasts combine; the combination produces *target positions*; orders are the *delta* between the
current book and the target.

### Stage 1 — Signal generation (many, independent, bounded)

Independent forecasting rules, each emitting a **dimensionless, bounded forecast** on a common scale so
they can be compared and averaged. Carver (ex-AHL) is the cleanest published treatment: each rule's
forecast is scaled and **capped (±20 in his convention)** — capping supports diversification, tames
estimation error on limited data, and cuts negative skew from extreme readings [Carver 2015].
Grinold & Kahn formalise why *many weak* signals beat one strong one: **IR = IC × √breadth** — the
information ratio grows with the number of independent applications of skill [Grinold & Kahn].

### Stage 2 — Forecast combination (the step Jethro is missing)

All forecasts for an instrument are combined into **one number** before anything downstream sees them:

- **Weighted average of bounded forecasts**, weights from *evidence* (historical performance /
  correlation structure, with shrinkage toward equal weight), then a **diversification multiplier**
  (capped ~2.5) to restore the scale a diversified average shrinks [Carver 2015].
- Grinold & Kahn treat the same step as alpha combination: forecasts weighted by information
  coefficient into a conditional expected return fed to the optimiser [Grinold & Kahn].
- The modern ML flavor is **meta-labeling** [López de Prado 2018; Hudson & Thames / JFDS]: a primary
  model (or rule) proposes *direction*; a **secondary model** sees all the context and outputs
  *P(this trade wins)* — used to **gate and size**, never to pick direction. This decouples
  side-prediction from bet-sizing and is the published, named version of "a combined score decides
  whether any order happens."

### Stage 3 — Portfolio construction (forecasts → target positions, not orders)

The combined forecast becomes a **target position**: scaled by volatility targeting (cash-at-risk per
unit of forecast [Carver 2015]), shaped by the covariance structure (quadratic-utility optimisation in
the full Grinold-Kahn treatment), and critically **cost-aware**: Gârleanu & Pedersen's closed-form
result says the optimal policy is to (1) *aim in front of the target* (weight slow-decaying signals
more) and (2) **trade only partially toward the aim** each period — never jump to the full target,
because transaction costs make small frequent corrections optimal and create a de-facto no-trade band
[Gârleanu & Pedersen 2013]. Orders are then just `target − current`, netted across every sleeve —
which structurally eliminates duplicate orders and wash trades between subsystems.

### Stage 4 — Pre-trade risk controls (deterministic, non-negotiable)

A separate deterministic layer every order must pass, **regulatory reality, not taste**: SEC Rule
15c3-5 (Market Access Rule) requires automated pre-trade risk controls on *all* orders — explicitly
including machine-generated ones — plus kill switches / automated halts on aberrant algo behaviour
[SEC 15c3-5; FINRA]. Fat-finger/notional caps, restricted lists, credit limits, and the firm-level
kill switch live here.

### Stage 5 — Execution (parent → schedule → child orders)

The order layer receives a **parent order** and schedules child slices to minimise **implementation
shortfall** — the Almgren-Chriss framework trades market impact against timing risk and is the
industry-standard IS algo shape [Almgren & Chriss 2000]. Participation caps, venue rules, TCA capture.

### Stage 6 — Post-trade feedback

TCA vs arrival (execution quality back into the cost model), P&L attribution by signal, and **signal
health monitoring**: rolling IC / hit-rate per signal so decayed alphas get down-weighted by evidence
(Stage 2 weights are re-estimated, not set once).

## Jethro vs the reference model

| Stage | Reference | Jethro today | Verdict |
|---|---|---|---|
| Data/validation | Point-in-time data, purged CV [LdP] | ADR-0029 sim/live separation; ADR-0053 training bars + purged walk-forward; multi-seed OOS gate (ADR-0027) | ✅ genuinely institutional-shaped |
| 1 Signals | Many bounded forecasts | Momentum/mean-rev (OOS-picked, ADR-0043/44), AI hypotheses (ordinal, ADR-0022), news/social (ADR-0045/50), learned label (ADR-0053), regime (ADR-0051) | ◐ sources exist; **not on a common bounded scale** |
| 2 Combination | One combined forecast per instrument; evidence weights; meta-label gate | **Missing.** Each subsystem decides alone; ADR-0054 event-keying stops same-event repeats but nothing reconciles sources | ✖ **the gap** |
| 3 Portfolio construction | Target positions; vol-targeted, cost-aware partial adjustment [G&P]; orders = netted delta | Vol targeting + covariance VaR exist, but **per-trade**; no target-book concept; strategy/hypothesis/hedge each submit orders directly | ✖ **the structural gap** |
| 4 Pre-trade risk | Deterministic checks + kill switch [15c3-5] | Risk envelope, ADR-0049 hard gate, firm breaker (ADR-0027), sim-gated autonomy (ADR-0019) | ✅ strong — maps 1:1 |
| 5 Execution | IS scheduling [A-C] | Cost/impact model + working orders (ADR-0025), TCA — but no parent/child scheduling | ◐ fine at current size; A-C deferred |
| 6 Feedback | TCA + per-signal IC monitoring | TCA ✅, hypothesis outcome scoring ✅; **no per-signal decay telemetry**, no evidence-based re-weighting | ◐ |

## Gaps in priority order

1. **Forecast combination + target-position layer (Stages 2–3).** All autonomous order flow (strategy
   auto-exec, hypothesis autonomy, hedge AUTO when it lands) should route through ONE layer that (a)
   normalises every source to a bounded forecast, (b) combines with evidence-based weights (start
   equal-weight as a stated placeholder; re-weight from the ADR-0053 harness), (c) produces a
   vol-targeted target position per instrument, (d) trades partially toward it (G&P buffer), and (e)
   emits netted delta orders into the existing ADR-0049/0019 gates. Carver's capped-forecast algebra
   fits Jethro's deterministic-sizing invariant better than a full Markowitz optimiser at this scale.
   → **ADR-0055.**
2. **Per-signal health telemetry (Stage 6).** Rolling hit-rate/IC per source, feeding the Stage-2
   weights by evidence — also the ADR-0053 drift-monitoring follow-up.
3. **Cross-sleeve netting.** Falls out of gap 1 automatically; until then strategy and hedge can churn
   against each other paying spread twice.
4. **Almgren-Chriss parent/child scheduling (Stage 5).** Deferred: matters when order sizes become a
   meaningful fraction of ADV; the sim impact model already penalises size (ADR-0025/0033).

## Sources

- Robert Carver, *Systematic Trading* (Harriman House, 2015) — capped/combined forecasts, forecast
  weights, diversification multiplier, volatility targeting. https://www.harriman-house.com/systematic-trading ;
  summary of the forecast algebra: https://the7circles.uk/systematic-trading-3-frameworks-and-forecasts/
- Richard Grinold & Ronald Kahn, *Active Portfolio Management* (McGraw-Hill, 2nd ed. 2000) — IR = IC·√breadth,
  alpha combination, transfer coefficient. https://www.amazon.com/Active-Portfolio-Management-Quantitative-Controlling/dp/0070248826 ;
  law overview: https://corporatefinanceinstitute.com/resources/career-map/sell-side/capital-markets/fundamental-law-of-active-management/
- Marcos López de Prado, *Advances in Financial Machine Learning* (Wiley, 2018), ch. 3 — meta-labeling;
  purged CV. Framework papers & code: https://github.com/hudson-and-thames/meta-labeling ;
  worked example: https://hudsonthames.org/meta-labeling-a-toy-example/
- Nicolae Gârleanu & Lasse H. Pedersen, "Dynamic Trading with Predictable Returns and Transaction
  Costs", *Journal of Finance* 68(6), 2013 — aim portfolio, partial trading toward target.
  https://onlinelibrary.wiley.com/doi/abs/10.1111/jofi.12080 ; https://www.nber.org/papers/w15205
- Robert Almgren & Neil Chriss, "Optimal Execution of Portfolio Transactions", *Journal of Risk* 3, 2000 —
  implementation-shortfall execution. https://www.smallake.kr/wp-content/uploads/2016/03/optliq.pdf
- SEC Rule 15c3-5 (Market Access Rule) — mandatory automated pre-trade risk controls incl. algo flow;
  kill switches. https://www.sec.gov/files/rules/final/2010/34-63241-secg.htm ;
  FINRA exam guidance: https://www.finra.org/rules-guidance/guidance/reports/2021-finras-examination-and-risk-monitoring-program/market-access
