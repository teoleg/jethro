# ADR-0010: AI-driven algo engine — model inference SPI, external API first, embedded behind triggers

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** algo, ai, backend, aws, cost

## Context

The algo engine (ADR-0003) will be AI-driven: models reason about market state, select
and parameterize strategies, and generate trading decisions — this is the core of the
platform, not an add-on. Two hosting shapes compete: **embedded** (self-hosted
open-weights model — e.g. Llama/Qwen on our own GPU, or a small model in a sidecar) and
**external** (hosted frontier API — Anthropic API directly, or through AWS: Amazon
Bedrock / Claude Platform on AWS, which keeps traffic and billing inside our AWS account
per ADR-0007). The owner leans embedded.

Forces: **(1) Inference latency** — even embedded LLM inference is 50–500ms and external
is 1–5s; neither fits the tick path, so AI cannot sit on the µs–ms path regardless of
hosting. **(2) Quality** — decision quality is the P&L; frontier models materially
out-reason open-weights models that fit on affordable GPUs. **(3) Cost shape** — external
is per-token (~$3/$15 per Mtok mid-tier, ~90% off repeated context via prompt caching): a
decision cycle every 30s over a 6.5h session at ~2K in/300 out per call is ~780
calls/day ≈ **$100–250/month** uncached, less with caching. Embedded has a flat floor:
one always-on inference GPU (g6e.xlarge, 48GB) is ~**$900/month** before ops, and models
above ~70B need more. **(4) Privacy** — strategies and positions are the IP.
**(5) Reproducibility** — backtest/live parity needs replayable decisions; pinned local
weights are deterministic, external models drift across provider updates.
**(6) Solo-team ops budget** — a GPU serving stack (vLLM, CUDA, model upgrades) is a
standing distraction.

## Decision

We will structure AI in the algo engine in three tiers and make model hosting a
swappable port:

1. **Tick path (µs–ms): no AI, ever.** Signal execution, order placement, and risk
   guardrails are deterministic Java. AI proposes; code disposes — position/order limits
   are enforced outside the model regardless of hosting.
2. **Decision cadence (seconds–minutes): a `ModelInference` SPI** in the algo engine,
   mirroring the market-data SPI (ADR-0009): `decide(marketContext, bookState) →
   ProposedActions`, one adapter per backend. **First adapter: external frontier API via
   AWS (Bedrock or Claude Platform on AWS)** — chosen for quality and near-zero cost at
   our call volume. An **embedded adapter** (self-hosted open-weights) is deferred, not
   rejected — revived by any of: sustained token spend > ~$700/month, a measured need
   for sub-500ms decisions, or a hard requirement that strategy context never leave the
   VPC.
3. **Research tier (offline): frontier API** for strategy generation, backtest analysis,
   post-mortems — quality-dominant, latency-irrelevant.

Invariant: **every AI decision is an event** on `ai.decisions` (prompt hash, full
response, model ID + version, latency, token usage). Replay and backtests consume the
recorded decisions, not live re-inference — this neutralizes external-model drift and
makes the hosting choice invisible to the rest of the platform.

## Alternatives considered

**Embedded-first (owner's lean).** Real advantages: predictable tail latency, strategies
never leave the VPC, flat cost at high volume, pinned-weights determinism, no rate limits
during volatility spikes, offline dev. Not adopted *first* because at our stage the two
decisive factors point the other way: the ~$900/month GPU floor is ~4–8× the expected API
spend, and open-weights models that fit that budget reason markedly worse — and decision
quality is the product. Deferred with explicit revival triggers (above); the SPI makes
adoption a config swap, and the `ai.decisions` log already provides the reproducibility
benefit embedded would have bought.

**Direct Anthropic API instead of via AWS.** Same models, marginally simpler setup, but
leaves AWS IAM/billing/network boundary (ADR-0007). Bedrock/Claude Platform on AWS keeps
data flow and audit inside the account for negligible extra effort. Rejected.

**No LLM — classical quant strategies only.** Cheapest and fastest path, but contradicts
the platform's stated purpose (AI for all algos). Rejected; classical signals may still
exist as *inputs* the model reasons over.

## Consequences

- Positive: frontier-quality decisions from day one at ~$100–250/month; no GPU ops;
  hosting reversible via SPI; full decision audit trail; tick-path latency unaffected
  by any AI choice.
- Negative: decision cadence floor is seconds (external latency) — strategies requiring
  faster reaction are out of scope until the embedded trigger fires; live dependency on
  an external service during market hours (mitigation: engine degrades to
  hold/flatten-only mode on API failure, never trades blind); market data leaves the VPC
  in prompts (positions can be pseudonymized; strategies stay server-side as prompts we
  control).
- Follow-ups: mini-ADR to pick Bedrock vs Claude Platform on AWS at implementation time;
  define the `ai.decisions` Avro schema alongside ADR-0004 schemas; cost alarm on token
  spend wired to the embedded-trigger threshold.
