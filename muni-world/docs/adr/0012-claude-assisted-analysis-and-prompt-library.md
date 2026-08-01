# ADR-0012: Claude-assisted analysis and the muni prompt library

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** ai, claude, prompts, analysis, governance

## Context

muni-world's value comes from *analysis* of the collected data (ADR-0002): reasoning over issuer financials
and disclosure, extracting complex bond terms, drafting credit and relative-value observations, explaining
anomalies. Much of that is exactly where a strong LLM helps — and jethro already runs two AI tiers with
hard guardrails (local SLM via Ollama for narration/triage; an **external frontier API** for deeper work,
behind cost/latency triggers — jethro ADR-0010/0016), where **a model output never sets a money/risk number
unchecked** and **every AI decision is an audited event**. muni-world should use **Claude** for the heavy
analysis, and do it as a **governed, versioned prompt library**, not ad-hoc chat.

## Decision

### Two tiers (inherit jethro ADR-0010/0016)
- **Local SLM (Ollama, shared)** for cheap, high-volume work: document classification and section
  detection (ADR-0010), first-pass triage/summaries.
- **Claude frontier API** for deep analysis: multi-document credit reasoning, complex-term extraction,
  relative-value hypotheses, anomaly explanation. **Cost/latency-gated** — SLM first, batch + cache,
  escalate to Claude only where it earns it. API credentials are env-provided (no secrets in the repo);
  model id and limits are config with provenance, not hardcoded in prose.

### The prompt library (`muni-world/prompts/`) — git-tracked and versioned
Every analysis task is a **named, versioned prompt artifact**, never inline text (governance in
`prompts/README.md`). Each prompt declares: `id`, `purpose`, `tier`, `inputs` (which muni data it consumes),
`output` (a **schema** when a field/number results), `grounding` (the source rows/documents it must cite),
`guardrails`, and `version`. A runtime registry loads them from a configurable path. **Changing a prompt is
a tracked change** — prompt provenance, the same discipline as config provenance.

### Grounded, cited, provenance-preserving
Analysis prompts are **retrieval-augmented over muni-world's own Postgres data + raw documents**
(ADR-0005/0006): Claude reasons over **our** collected facts and every output **cites the
`raw_artifact_id`/source rows it used**. The model reasons; it does not invent muni facts from training
memory. Unsupported claims are flagged, not stored as fact.

### Guardrail — a model number is advisory, never authoritative unchecked (inherit jethro invariant 7)
Any analytic figure that will feed a decision (a credit read, an OAS input, a relative-value flag) is a
**hypothesis carrying its evidence**. The canonical number comes from deterministic computation or cited
source data; where a value is genuinely model-derived it is labelled as such with a confidence and a review
path. **No Claude output silently becomes a money/valuation number.**

### Audit + evals
- **Audit:** every SLM/Claude call logs prompt `id`+`version`, model, input references, output, token/cost,
  latency, timestamp → `muni.ai_analysis` (the muni analogue of jethro's `ai.decisions`) for
  reproducibility and cost tracking.
- **Evals:** each prompt ships a small eval set (expected behaviour on sample inputs) so a prompt change is
  *measured*, not vibes — the analysis analogue of jethro's OOS discipline for signals.

### "Probably more"
The library is the substrate for uses beyond analysis (disclosure summarisation, anomaly triage,
natural-language query over the warehouse). Any new prompt is governed by the same rules — grounded, cited,
versioned, audited, number-guardrailed.

## Consequences

- Analysis is **reproducible and governed** (versioned prompts + audit log), not one-off chat.
- Cost is controlled and observable (SLM-first, cache, frontier gated; per-call token/cost logged).
- jethro's "a model never sets an unverified number" discipline carries over — protecting the eventual
  investment views the north star produces.
- New capabilities are additive prompts under one contract, so the library compounds without re-litigating
  governance each time.
