# Architecture Decision Records

Index of ADRs for the Jethro trading platform. See [template.md](template.md) for the format.

| # | Title | Status | Implementation |
|---|-------|--------|----------------|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted | ✅ Implemented |
| [0002](0002-backend-language-java-with-cpp-hot-paths.md) | Backend in Java 21, C++ reserved for latency-critical hot paths | Accepted | ✅ Implemented |
| [0003](0003-event-driven-service-architecture.md) | Event-driven services around a streaming backbone | Accepted | ✅ Implemented |
| [0004](0004-messaging-backbone-kafka.md) | Kafka (Amazon MSK) as the messaging backbone | Superseded by 0012 | ✖ Superseded |
| [0005](0005-data-storage.md) | Aurora PostgreSQL for state, Kafka + S3 for tick history | Accepted | ◐ Partial |
| [0006](0006-ui-stack.md) | UI in TypeScript + React (Vite), streaming over WebSocket | Superseded by 0028 | ✖ Superseded |
| [0007](0007-aws-runtime-ecs-fargate-cdk.md) | Runtime on ECS Fargate, infrastructure as code with AWS CDK (Java) | Accepted | ◐ Partial |
| [0008](0008-domain-model-books-instruments-positions.md) | Domain model: books, instruments, positions, marks | Accepted | ✅ Implemented |
| [0009](0009-market-data-provider-abstraction.md) | Market data provider abstraction | Accepted | ✅ Implemented |
| [0010](0010-ai-algo-engine-embedded-vs-external-model.md) | AI-driven algo engine — model inference SPI, external API first, embedded behind triggers | Accepted | ◐ Partial |
| [0011](0011-finops-cost-monitoring-in-platform.md) | FinOps in the platform — finops-service, tagged AWS costs, Costs tile in the UI | Accepted | ○ Not started |
| [0012](0012-redpanda-backbone-idempotent-consumers.md) | Redpanda as the Kafka-API backbone; at-least-once + idempotent consumers | Accepted | ✅ Implemented |
| [0013](0013-dev-cost-posture-single-node-aws.md) | Dev cost posture — one EC2 node runs the compose stack; managed services behind triggers | Accepted | ✅ Implemented |
| [0014](0014-trading-core-in-process-market-path.md) | Fuse the market path into trading-core — in-process ticks, durable log for transactions only | Accepted | ◐ Partial |
| [0015](0015-single-jvm-modular-monolith.md) | Single-JVM modular monolith — one app now, extraction seams preserved | Accepted | ✅ Implemented |
| [0016](0016-local-slm-tier-agentic-elements.md) | Local SLM inference tier — Ollama adapter first, agentic elements pulled forward | Accepted | ✅ Implemented |
| [0017](0017-attention-first-ui.md) | Attention-first UI — agents curate with a deterministic floor; grids become drill-down | Accepted | ✅ Implemented |
| [0018](0018-ai-trade-suggestions-human-in-loop.md) | AI trade suggestions — frontier tier proposes, deterministic guardrails gate, human executes | Accepted | ✅ Implemented |
| [0019](0019-simulated-auto-execution.md) | Simulated auto-execution — deterministic strategy may auto-trade in sim, hard-gated off real brokers | Accepted | ✅ Implemented |
| [0020](0020-enriched-risk-model.md) | Multi-asset quant foundation — OpenGamma Strata as the analytics substrate, exact money ledger on top | Accepted | ✅ Implemented |
| [0021](0021-operational-chat.md) | Operational chat — SLM parses the question, deterministic code answers, every turn audited to Postgres | Accepted | ✅ Implemented |
| [0022](0022-llm-hypothesis-layer-bounded-autonomy.md) | LLM hypothesis layer — model synthesises structured theses, quant layer computes, a deterministic risk envelope decides auto-execute vs human approval | Accepted | ✅ Implemented |
| [0023](0023-yahoo-market-data-adapter.md) | Yahoo Finance market-data adapter — a free, delayed, dev/demo-only provider behind the ADR-0009 port | Accepted | ✅ Implemented |
| [0024](0024-finnhub-realtime-market-data.md) | Finnhub real-time market data — free WebSocket equities feed, composed with Yahoo/sim for the rest | Accepted | ✅ Implemented |
| [0025](0025-realistic-execution-cost-model.md) | Realistic simulated execution — spread/fee cost model, working-order matching, cancel/TIF | Proposed | ✅ Implemented |
| [0026](0026-correlated-factor-market-simulator.md) | High-fidelity market simulator — correlated cross-asset factor model, regime switching, calibrated from real history | Proposed | ✅ Implemented |
| [0027](0027-evaluation-and-risk-rigor.md) | Evaluation & risk rigor — hypothesis outcome scoring, out-of-sample backtests, VaR + firm breaker, day boundary | Proposed | ✅ Implemented |
| [0028](0028-ui-static-pages-react-deferred.md) | UI stays server-served static pages; React deferred behind concrete triggers | Accepted | ✅ Implemented |
| [0029](0029-runtime-feed-switching.md) | Runtime feed switching and hard sim/live/replay data separation | Accepted | ✅ Implemented |
| [0030](0030-schema-registry-serde.md) | Schema-registry serde — writer-schema resolution on the wire | Accepted | ✅ Implemented |
| [0031](0031-sim-control-panel.md) | Sim control panel — live UI dials over the simulator for model testing | Accepted | ✅ Implemented |
| [0032](0032-empirical-sim-volume-liquidity.md) | History-anchored market simulation (sim price engine) | Accepted | ✅ Implemented |
| [0033](0033-flow-aware-algos-and-depth.md) | Flow-aware algorithms (volume confirmation + liquidity sizing) and synthesized depth | Accepted | ✅ Implemented |
| [0034](0034-news-driven-sim-shocks.md) | News-driven sim shocks — news moves the tape (correlated volume surge + bp momentum) | Accepted | ✅ Implemented |
| [0035](0035-rag-for-ai-layer.md) | Retrieval-augmented context for the AI layer — local embeddings + pgvector | Accepted | ◐ Partial |
| [0036](0036-ai-signal-balance.md) | AI signal balance — measure directional skew, feed it back to the model, never quota | Accepted | ✅ Implemented |
| [0037](0037-clean-vs-comprehensive-pnl.md) | Clean vs comprehensive P&L — lock realized FX at booking, break out translation | Proposed | ◐ Partial |
| [0038](0038-minimum-variance-proxy-hedging.md) | Minimum-variance proxy hedging — beta to index futures, DV01 to Treasury futures, direct FX | Proposed | ◐ Partial |
| [0039](0039-hedge-lifecycle-band-triggers.md) | Hedge lifecycle — always-flat deterministic hedging, advisory-first | Proposed | ◐ Partial |
| [0040](0040-structural-sector-hedging.md) | Structural sector hedging — a history-free fundamental floor under the statistical hedge | Accepted | ◐ Partial |
| [0041](0041-estimation-rigor.md) | Estimation rigor — covariance burn-in, par-curve bootstrap, honest VaR windows | Accepted | ✅ Implemented |
| [0042](0042-hedge-proxy-selection.md) | Hedge proxy selection — best measured proxy, tradability-gated, switch hysteresis | Accepted | ✅ Implemented |
| [0043](0043-per-instrument-strategy-selection.md) | Per-instrument strategy selection from out-of-sample results (no blind global algo) | Proposed | ✅ Implemented |
| [0044](0044-regime-aware-strategy-selection.md) | Regime-aware strategy selection from a price-derived trend detector (no regime oracle) | Accepted | ✅ Implemented |
| [0045](0045-news-advisory-signals.md) | News-driven advisory signals — multi-source RSS → queued SLM sector-classify → bounded, audited overlay | Accepted | ◐ Partial |
| [0046](0046-no-sim-tick-archive.md) | Don't archive SIM ticks — a seeded sim run is reproducible, not stored | Proposed | ⏸ Deferred |
| [0047](0047-postgres-vs-lmdb-role-split.md) | Keep PostgreSQL as the system-of-record; LMDB stays derived-only (re-evaluation) | Proposed | — n/a (decision) |
| [0048](0048-persist-strategy-actions.md) | Persist deterministic strategy actions (entries/exits + reasons) so the UI survives restart | Proposed | ○ Not started |
| [0049](0049-ai-signals-never-order-backtest-gated.md) | AI/news signals never place orders — the deterministic backtest is a hard gate | Accepted | ✅ Implemented |
| [0050](0050-social-media-adversarial-source.md) | Social media as an adversarial source — spam/credibility/corroboration controls | Accepted | ◐ Partial |
| [0051](0051-price-derived-volatility-regime.md) | Risk-off sizing from a price-derived volatility regime (no sim-regime oracle) | Accepted | ✅ Implemented |
| [0052](0052-live-strategy-tuning-panel.md) | Live, DB-persisted strategy tuning — every dial editable, every change audited as its own provenance | Accepted | ✅ Implemented |
| [0053](0053-learned-advisory-signal.md) | Learned advisory signal — a model predicts a tradeable label, backtest-gated (no price-curve oracle) | Accepted | ◐ Partial |
| [0054](0054-event-keyed-hypothesis-identity.md) | Event-keyed hypothesis identity — the model classifies the catalyst, deterministic code dedups on it | Accepted | ✅ Implemented |
| [0055](0055-signal-fusion-target-portfolio.md) | Signal fusion and a target portfolio — signals stop placing orders | Accepted | ✅ Implemented |
| [0056](0056-multi-source-market-data-coverage.md) | Multi-source market-data coverage — source precedence via a freshness-guarded merge | Accepted | ✅ Implemented (guard + Yahoo fallback + Alpaca; no priority table — freshness subsumes it) |
| [0057](0057-intraday-strategy-family.md) | Intraday strategy family — VWAP-deviation reversion first, pairs deferred | Proposed | ☐ Not started |
| [0058](0058-var-partial-history-coverage.md) | VaR coverage — include names with sufficient history, revalue on the common window | Proposed | ✅ Implemented (Oleg-directed batch) |
| [0059](0059-fusion-execution-gate.md) | Fusion execution gate — trade only OOS-validated, convicted names (stop the churn) | Proposed | ✅ Implemented (Oleg-directed batch) |
| [0060](0060-dynamic-discovery-driven-universe.md) | Dynamic discovery-driven universe — daily promotion into the refdata master, provisional refdata | Accepted | ✅ Implemented (gate + dry-run proposer; runtime refdata write path + eviction; a promoted name is a first-class tradable instrument in the refdata universe — no monitor-only; enabled. Owner dropped monitor-only probation) |
| [0061](0061-paper-execution-any-feed.md) | Paper auto-execution runs on any feed (amends ADR-0019's sim-only routing) | Accepted | ✅ Implemented (removed the `feedMode==SIM` veto in FusionExecutor + AUTO hedging; execution stays internal SimulatedExecutor — paper on real marks; ADR-0015 is the real-money guard) |
| [0062](0062-live-edge-gated-execution.md) | Gate execution on positive *measured live* edge, not only sim-OOS | Proposed | ◐ Partly built by ADR-0064 (the reduce-only, cost-aware half is live in fusion). Still open: the stricter per-`(name, algo)` positive-edge requirement to route at all |
| [0063](0063-continuous-improvement-agent.md) | Continuous improvement agent — automate the observe→diagnose→fix→rerun loop, gated on the PnL/exposure objective | Proposed | ◐ Scaffolding landed, inert (Claude Code headless on Max; one local cycle report→analyse→change→test→commit→push→rebuild+restart via `ops/loop-control.sh`; `report.md`+logs added to system-report.py). Never edits the deterministic floor (invariant 7 / ADR-0016); real money stays behind ADR-0015. Not enabled — awaits owner Accept + `loop-control.sh on`. Open: open-position restart safety; logs-in-bundle done) |
| [0064](0064-cost-aware-edge-gate.md) | Gate risk-increasing fusion trades on measured edge net of measured cost | Proposed | ✔ Built (fusion goes reduce-only unless a source's measured expectancy beats measured round-trip slippage with significance; monotone — it can only remove trades — and self-healing as the measurements move) |
| [0065](0065-target-book-spans-held-positions.md) | The fusion target book spans held positions, and a risk-reducing delta needs no conviction | Proposed | ✔ Built (a held name with no fresh forecast is planned flat and worked down at the existing adjustment rate; reducing deltas skip the ADR-0059 conviction floor and the ADR-0049 support veto; hedge book excluded; the deterministic floor — breaker + guardrail — untouched) |
| [0066](0066-ewmac-trend-forecast-source.md) | A continuous, self-calibrating trend sensor (EWMAC) as a fusion forecast source | Proposed | ✔ Built (`trend` publishes a vol-normalised, efficiency-ratio-weighted crossover per name into the forecast registry every cycle and records its calls in the phase-1 telemetry; self-normalising so it is scale- and feed-agnostic; no gate relaxed — edge gate, conviction floor, backtest veto, guardrail and breaker all still upstream of any fill) |
| [0067](0067-expectancy-weighted-source-trust.md) | Weight fusion sources by measured expectancy, not by hit rate | Proposed | ✔ Built (source trust is Φ of the source's own t-statistic of expectancy, replacing the floored hit-rate advantage that read every below-coin-flip source as identical; credibility shrinkage, min-sample floor and MIN/MAX bound unchanged; Σ-normalised, so it rotates conviction between sources and cannot resize the book) |
| [0068](0068-equity-relative-firm-risk-budget.md) | Equity-relative firm risk budget & bleed cutoff — the danger-cut machinery exists (guardrail + firm breaker) but its loss/drawdown dials are big-book absolutes that go toothless on a reset | Proposed | ✗ Not built — the dials are the deterministic floor, which the improvement loop may not edit; a drafted config recalibration was reverted unshipped. Both steps are Oleg's to ratify (see the ADR's status note) |
| [0069](0069-scale-relative-hedge-no-trade-band.md) | The hedge no-trade band is scale-relative, not an absolute dollar floor | Proposed | ✔ Built (`HedgeAdvisor.noTradeBand` = min(ADR-0039 $10k guard, 25% × max(|target|,|held|) notional), so an absolute guard can no longer strand a hedge smaller than itself; large-book behaviour bit-for-bit unchanged, and a zero target is now always reachable) |
| [0070](0070-range-reversion-forecast-source.md) | A continuous mean-reversion sensor (range position) as a fusion forecast source — the chop-regime counterpart of the ADR-0066 trend sensor | Proposed | ✔ Built (`reversion` publishes a bounded Donchian range-position reading per name, faded and weighted by `1 − ER` so it refuses to stand in front of a trend; self-normalising and scale-/feed-agnostic; records its calls in the phase-1 telemetry so it must earn its measured expectancy — deliberately NOT the trend source negated; no gate relaxed) |
| [0071](0071-sensor-warm-restart-from-mark-history.md) | Continuous forecast sensors warm-restart from the durable mark history instead of re-learning the stream after every redeploy | Proposed | ✔ Built (`SensorWarmup` replays the LMDB `md.marks` series into the `trend` and `reversion` sensors on first sight of a name, thinned to the sensor's own cadence and stopping at a hole; seed prices are never recorded as telemetry calls, and the store is now namespaced by feed mode per invariant 8 — no sizing, gate or floor changed) |
| [0072](0072-per-name-execution-cost-in-the-edge-gate.md) | Execution cost is a per-name property — once a source clears the desk-wide ADR-0064 hurdle, each name is re-tested against its own measured round-trip cost before risk may be put on in it | Proposed | ✔ Built (the gate carries the passing source's gross expectancy plus the per-instrument measured round trip from the ADR-0025 TCA table, and fusion clamps a name to reduce-only when its own round trip costs more than that edge; unmeasured and rate-quoted names are never vetoed on an assumed cost; no new dial, no gate loosened, deterministic floor untouched) |
| [0073](0073-feed-mode-scoped-daily-close-series.md) | The daily close series is feed-mode scoped — a handover between feeds is not a market move | Proposed | ✔ Built (`daily_close` gains `feed_mode`, PK `(day, instrument, feed_mode)`; both session writers stamp `Provenance.mode()` and the bootstrap history is tagged `SEED`; `DailyCloseSeries` admits only the running mode + seed and takes a return only within one stream, so VaR and per-name vol stop measuring a feed handover as a ±40–75% market day; V47 re-tags history deterministically from `firm_equity.feed_mode`; no risk formula, dial or gate changed) |
| [0074](0074-credibility-counts-the-sample-the-estimate-was-made-from.md) | Source trust is shrunk over the sample its estimate was made from — credibility counts RESOLVED observations (flats included, as the expectancy itself does) and the hard min-sample floor is removed | Proposed | ✔ Built (`TelemetryWeights` credibility switches from wins+losses to `resolved`, so a flat-heavy source is no longer permanently "thin"; the 1.0 floor — which was strictly MORE trusting than the shrunk value for every below-average source, laundering measured-negative evidence into neutral — is gone, leaving Bühlmann `shrinkage-k` as the single continuous thin-sample dial; `jethro.fusion.weights.min-sample` removed; weights are still ratios normalised downstream, so conviction rotates and the book cannot be scaled; deterministic floor and every gate untouched) |
| [0075](0075-per-name-cost-on-both-sides-of-the-edge-gate.md) | The edge gate tests every name against its own measured round-trip cost on BOTH sides — the desk-wide verdict is the same test at the cheapest round trip the desk can actually pay, not a blended hurdle | Proposed | ✔ Built (`EdgeGate` evaluates `(avgReturnBps − cost_n)/stdErrorBps ≥ tHurdle` per name, with an unfilled name charged the desk's measured blend so its bar is unchanged; strictly tighter than the ADR-0072 raw-mean veto for any name costing more than the blend, and only a name measured cheaper than the blend can gain permission; a measured-negative source still clears nothing at any cost; no new dial, deterministic floor untouched) |
| [0076](0076-diversification-multiplier-on-the-weights-actually-used.md) | Size the diversification multiplier from the concentration of the weights actually used, not from the source count | Proposed | ✔ Built (`ForecastCombiner` computes `DM = 1/√(Σwᵢ² + ρ(1−Σwᵢ²))` over the normalised weights — the count formula evaluated at the inverse-Herfindahl EFFECTIVE number of sources; identical to the old rule at equal weights (cold start, `weights.mode=equal`), never larger than it by Cauchy–Schwarz, and never below 1; a source held at the MIN floor for measured-negative expectancy no longer buys a full extra unit of diversification leverage; amends the ADR-0067 invariant to "re-weighting can only SHRINK the book"; no new dial, deterministic floor untouched) |

## Status vs Implementation

**Status** is the ADR lifecycle (Proposed / Accepted / Superseded); **Implementation** is what is
actually in the code — the two diverge, which is why this column exists. Several ADRs are still
*Proposed* yet fully built (0025, 0026, 0027, 0043), and some *Accepted* ones are only partial.

**Implementation legend:** ✅ built & in the codebase (tests where applicable) · ◐ core built, parts
deferred · ○ accepted but no code yet · ⏸ deferred behind a stated trigger · ✖ superseded/rejected ·
— decision-only (no code artifact).

### Partial / not-built — what's missing (details in `deferred-register.md` / `gap-register.md`)

- **0005 Aurora PG** — Postgres + Flyway built (dev compose); managed **Aurora** is the prod shape,
  behind a production trigger (ADR-0013).
- **0007 Fargate/CDK** — `infra/` CDK **dev** stack exists; the Fargate/ALB/Aurora **prod** shape is
  not deployed.
- **0010 AI SPI** — inference SPI + **local Ollama** tier built; the **external frontier tier** is
  behind cost/latency triggers, not wired.
- **0011 FinOps** — module scaffolded only (no Java yet); **planned**.
- **0014 trading-core** — in-process ring buffer + **LMDB** warm/dedupe built; the **S3 Parquet
  write-behind tick archiver is NOT built**.
- **0035 RAG** — embeddings + outcome memory built; the **pgvector** store is still in progress.
- **0037 clean/comprehensive P&L** — the split + realized-FX translation are built; **unrealized-FX
  decomposition** and the Avro-event fields are deferred.
- **0038 proxy hedging** — **EQUITY** axis built; the **DV01-neutral rates** axis and **direct-FX**
  axis are unbuilt.
- **0039 hedge lifecycle** — advisory modes built; **AUTO order submission** + the firm-breaker
  one-shot de-risk are unbuilt.
- **0040 structural hedging** — the structural **sector tier** is built; per-sector **ETF proxies** +
  assigned-vs-realized beta telemetry are unbuilt.
- **0045 news advisory** — real **RSS news** + the **universe-discovery** register are built (via
  0050 §7); the **SLM sector-classify** step and the full queued pipeline are not built.
- **0046 no sim archive** — a scoping decision that only applies **once the 0014 archiver exists**;
  nothing to build until then.
- **0048 persist strategy actions** — **not started** (the Strategy-actions UI panel is still
  in-memory / lost on restart).
- **0050 social** — the adversarial pipeline + StockTwits/Telegram adapters + discovery are built
  (Phase 1 / 2a / 2b); **SLM classify (P3)** and outcome-scored **channel ranking (P4)** are not.

## Lifecycle

`Proposed` → `Accepted` (or `Rejected`) → possibly `Superseded by ADR-XXXX`.

An ADR is **Proposed** when written, **Accepted** once the owner (Oleg) signs off.
Never edit the decision of an Accepted ADR — write a new ADR that supersedes it.
