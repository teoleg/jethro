# Architecture Decision Records

Index of ADRs for the Jethro trading platform. See [template.md](template.md) for the format.

| # | Title | Status |
|---|-------|--------|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-backend-language-java-with-cpp-hot-paths.md) | Backend in Java 21, C++ reserved for latency-critical hot paths | Accepted |
| [0003](0003-event-driven-service-architecture.md) | Event-driven services around a streaming backbone | Accepted |
| [0004](0004-messaging-backbone-kafka.md) | Kafka (Amazon MSK) as the messaging backbone | Superseded by 0012 |
| [0005](0005-data-storage.md) | Aurora PostgreSQL for state, Kafka + S3 for tick history | Accepted |
| [0006](0006-ui-stack.md) | UI in TypeScript + React (Vite), streaming over WebSocket | Superseded by 0028 |
| [0007](0007-aws-runtime-ecs-fargate-cdk.md) | Runtime on ECS Fargate, infrastructure as code with AWS CDK (Java) | Accepted |
| [0008](0008-domain-model-books-instruments-positions.md) | Domain model: books, instruments, positions, marks | Accepted |
| [0009](0009-market-data-provider-abstraction.md) | Market data provider abstraction | Accepted |
| [0010](0010-ai-algo-engine-embedded-vs-external-model.md) | AI-driven algo engine — model inference SPI, external API first, embedded behind triggers | Accepted |
| [0011](0011-finops-cost-monitoring-in-platform.md) | FinOps in the platform — finops-service, tagged AWS costs, Costs tile in the UI | Accepted |
| [0012](0012-redpanda-backbone-idempotent-consumers.md) | Redpanda as the Kafka-API backbone; at-least-once + idempotent consumers | Accepted |
| [0013](0013-dev-cost-posture-single-node-aws.md) | Dev cost posture — one EC2 node runs the compose stack; managed services behind triggers | Accepted |
| [0014](0014-trading-core-in-process-market-path.md) | Fuse the market path into trading-core — in-process ticks, durable log for transactions only | Accepted |
| [0015](0015-single-jvm-modular-monolith.md) | Single-JVM modular monolith — one app now, extraction seams preserved | Accepted |
| [0016](0016-local-slm-tier-agentic-elements.md) | Local SLM inference tier — Ollama adapter first, agentic elements pulled forward | Accepted |
| [0017](0017-attention-first-ui.md) | Attention-first UI — agents curate with a deterministic floor; grids become drill-down | Accepted |
| [0018](0018-ai-trade-suggestions-human-in-loop.md) | AI trade suggestions — frontier tier proposes, deterministic guardrails gate, human executes | Accepted |
| [0019](0019-simulated-auto-execution.md) | Simulated auto-execution — deterministic strategy may auto-trade in sim, hard-gated off real brokers | Accepted |
| [0020](0020-enriched-risk-model.md) | Multi-asset quant foundation — OpenGamma Strata as the analytics substrate, exact money ledger on top | Accepted |
| [0021](0021-operational-chat.md) | Operational chat — SLM parses the question, deterministic code answers, every turn audited to Postgres | Accepted |
| [0022](0022-llm-hypothesis-layer-bounded-autonomy.md) | LLM hypothesis layer — model synthesises structured theses, quant layer computes, a deterministic risk envelope decides auto-execute vs human approval | Accepted |
| [0023](0023-yahoo-market-data-adapter.md) | Yahoo Finance market-data adapter — a free, delayed, dev/demo-only provider behind the ADR-0009 port | Accepted |
| [0024](0024-finnhub-realtime-market-data.md) | Finnhub real-time market data — free WebSocket equities feed, composed with Yahoo/sim for the rest | Accepted |
| [0025](0025-realistic-execution-cost-model.md) | Realistic simulated execution — spread/fee cost model, working-order matching, cancel/TIF | Proposed |
| [0026](0026-correlated-factor-market-simulator.md) | High-fidelity market simulator — correlated cross-asset factor model, regime switching, calibrated from real history | Proposed |
| [0027](0027-evaluation-and-risk-rigor.md) | Evaluation & risk rigor — hypothesis outcome scoring, out-of-sample backtests, VaR + firm breaker, day boundary | Proposed |
| [0028](0028-ui-static-pages-react-deferred.md) | UI stays server-served static pages; React deferred behind concrete triggers | Accepted |
| [0029](0029-runtime-feed-switching.md) | Runtime feed switching and hard sim/live/replay data separation | Accepted |
| [0030](0030-schema-registry-serde.md) | Schema-registry serde — writer-schema resolution on the wire | Accepted |
| [0031](0031-sim-control-panel.md) | Sim control panel — live UI dials over the simulator for model testing | Accepted |
| [0032](0032-empirical-sim-volume-liquidity.md) | History-anchored market simulation with first-class volume and liquidity | Proposed |

## Lifecycle

`Proposed` → `Accepted` (or `Rejected`) → possibly `Superseded by ADR-XXXX`.

An ADR is **Proposed** when written, **Accepted** once the owner (Oleg) signs off.
Never edit the decision of an Accepted ADR — write a new ADR that supersedes it.
