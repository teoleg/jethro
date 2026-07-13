# ADR-0016: Local SLM inference tier — Ollama adapter first, agentic elements pulled forward

- **Status:** Accepted (extends ADR-0010; build-order change to ADR-0014/0015 sequence)
- **Date:** 2026-07-12
- **Deciders:** Oleg
- **Tags:** ai, algo, cost, architecture

## Context

ADR-0010 put AI at the platform's center but at step 7 of the build order — weeks of
building with no model running, which is how AI-centric designs quietly become
"AI later." The owner wants AI involvement now, locally, and asked whether a local
SLM can do "some risk calc." Two facts collide: (1) small language models run
locally for free (3B-class via Ollama on CPU, one compose container, fully offline —
a better fit for the local-dev-first invariant than the frontier API); (2) language
models of any size are unreliable at arithmetic — an SLM asked for an exposure
produces a *plausible wrong number*, the most expensive failure mode finance has.
Invariant 7 already draws this line: risk guardrails and calculations are
deterministic code.

## Decision

We will add a **local SLM tier** behind the ADR-0010 model-inference SPI and pull
agentic elements forward in the build order:

1. **The SPI's first implemented adapter is Ollama** (`OllamaClient`, plain HTTP,
   default model a 3B-class instruct model, configurable). Runs in compose locally
   and on the dev node. $0, offline, no API keys.
2. **Division of labor is hard law:** deterministic Java *computes* every number
   (positions, PnL, exposures, shocks); the SLM *narrates, triages, and proposes*
   — risk commentary from computed snapshots, anomaly labeling, candidate scenarios
   that the risk engine then computes. **A model output is never parsed for a number
   that feeds a position, order, PnL, or risk figure.** Violations are review-blockers.
3. **First agentic element, built now: the risk commentator** — an agent loop in the
   app that snapshots computed market/risk state on a cadence, asks the model for
   commentary, and records every run as an `AiDecision` event (model ID, latency,
   token counts, context snapshot + hash — the full ADR-0010 audit shape).
   v0 sink logs decisions and buffers them in memory; they move onto the
   `ai.decisions` topic the moment broker wiring lands (next step) — the event
   *shape* is exercised from day one.
4. **Agentic roadmap:** commentator (now) → scenario proposer (with the risk module)
   → AI trading decisions via the frontier-API adapter (ADR-0010, unchanged — the
   quality argument for real trading decisions still holds) → finops/ops agent.
5. Build order: SPI + Ollama adapter + commentator become **step 3**; UI (now step 4)
   ships with AI commentary visible on its first screen.

## Alternatives considered

**SLM computes risk figures (the original ask).** Rejected outright: LLM arithmetic is
non-deterministic and confidently wrong; exact-decimal invariants (1) can't be met by
sampled text; latency is milliseconds-to-seconds against a µs-budget path (invariant
7). The deterministic engine computes in exact decimals, provably, already.

**Frontier API for the commentary tier.** Better prose, but adds per-call cost and an
API-key/network dependency to local dev for a tier whose stakes are cosmetic.
Rejected for this tier; unchanged for trading decisions (ADR-0010).

**Keep AI at step 7.** Zero rework, but the platform's habits form without a model in
the loop and the ADR-0010 invariants stay theoretical for weeks. Rejected — sequencing
is a design force too.

## Consequences

- Positive: a model runs in the platform this week at $0; ai.decisions audit shape,
  SPI, and prompt/context plumbing get exercised long before real money; commentary
  gives the UI an AI surface from its first screen; two SPI adapters (ollama +
  frontier) prove the port is real.
- Negative: 3B-class commentary will sometimes be banal or subtly misread the data —
  acceptable for narration, and every output is labeled with its model ID; Ollama is
  one more container (~2–4GB RAM for a 3B model) on the dev box; the v0 logging sink
  means ai.decisions events are not yet on the broker (temporary, closed next step —
  tracked as a known deviation from invariant 7's letter, not its audit intent).
- Follow-ups: Kafka sink for AiDecision when broker wiring lands; commentary panel in
  the Market Monitor view; scenario-proposer ADR note when the risk module arrives.
