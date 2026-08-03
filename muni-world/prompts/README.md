# muni-world prompt library

Governed by [ADR-0012](../docs/adr/0012-claude-assisted-analysis-and-prompt-library.md). Every LLM analysis
task is a **named, versioned prompt artifact** here — never inline chat. A runtime registry loads these at a
configurable path; changing one is a tracked change (prompt provenance).

## Rules (non-negotiable)

1. **Grounded + cited.** The prompt reasons over muni-world's own data/documents and its output must cite
   the `raw_artifact_id` / source rows it used. The model never invents muni facts from training memory;
   unsupported claims are flagged, not asserted.
2. **Number guardrail.** No prompt output silently becomes a canonical money/valuation number. A model
   figure is a *hypothesis with evidence*; canonical numbers come from deterministic computation or cited
   source data, else are labelled model-derived + confidence + review.
3. **Tiered.** `tier: slm` (Ollama, cheap/high-volume) or `tier: frontier` (Claude, deep reasoning).
   Escalate to frontier only where it earns its cost.
4. **Structured output where a field results** — declare an `output` schema so results are validated, not
   free text.
5. **Versioned + evaluated.** Bump `version` on any change; ship an eval expectation so changes are measured.
6. **Audited.** Every invocation is logged to `muni.ai_analysis` (prompt id+version, model, inputs, output,
   tokens/cost, latency).

## File format

Each `*.md` prompt carries a metadata block then the prompt body:

```
id: <kebab-id>
version: <n>
tier: slm | frontier
purpose: <one line>
inputs: <the muni.* data this consumes>
output: <schema | "prose (advisory)">
grounding: <the source it must cite>
guardrails: <task-specific limits, on top of the rules above>
---
## System
<role + rules the model runs under>
## Task
<the templated instruction; {{placeholders}} are filled from `inputs`>
```

## Founding prompts

| id | tier | purpose |
|----|------|---------|
| [`credit-summary`](credit-summary.md) | frontier | Summarise an issuer's credit from its own financials, cited. |
| [`official-statement-extract`](official-statement-extract.md) | frontier | Extract call schedule + key terms from an Official Statement, structured. |
| [`relative-value-screen`](relative-value-screen.md) | frontier | Draft relative-value *observations* (hypotheses only) across a peer set. |
