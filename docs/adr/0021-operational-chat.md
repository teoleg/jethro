# ADR-0021: Operational chat — the SLM parses the question, deterministic code answers, every turn audited to Postgres

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** Oleg
- **Tags:** ui, ai, data

## Context

The operator needs conversational visibility on the landing page — "PnL for ALPHA?",
"exposure to AAPL?", "why is a book breaching?". ADR-0017 deferred a conversational
surface "until the feed and evidence views exist to ground it"; they now do. But a
free-form LLM chat is exactly the failure mode from the AI-math analysis: a small model
confabulates numbers and rambles, and a model-produced number could be read as truth —
unacceptable for a trading tool (invariant 7 / ADR-0016). The design question: how to make
chat useful *and* safe.

## Decision

We will build a **constrained operational chat** where the model's only job is to
**understand the question**, not to answer it:

1. **SLM = intent parser.** The model turns the natural-language question into a structured
   `{intent, slots}` (e.g. `{pnl, book=ALPHA}`, `{exposure, instrument=AAPL}`) against a
   fixed intent set — the one thing small models do adequately (classification / slot-fill).
2. **Deterministic code answers.** A handler per intent fetches from the existing services
   (risk snapshot, positions, attention, limits) and renders a **short, factual** reply.
   Numbers always come from code, never the model — no arithmetic, no long prose.
3. **Read-only in v1.** No action intents yet; if added later, they route through the
   pre-trade guardrail (ADR-0018) with human confirmation.
4. **Every turn audited to Postgres** — question, parsed intent, data, reply, model,
   latency — in a `chat_audit` table, and emitted as an `ai.decisions` event. Replayable,
   compliance-grade, and the corpus for improving NL later.
5. **Local SLM by default** (offline on the Pi); frontier tier (ADR-0010) opt-in for better
   parsing. The chat lives in the split-right pane of the reworked Overview (notes above,
   chat below); the deterministic "here is what I can answer" fallback covers misses.

## Alternatives considered

**Free-form LLM chat (model writes the answer).** Rejected: confabulates numbers, rambles,
and blurs the line invariant 7 draws — the whole reason for this ADR.

**Pure command/keyword UI (no model).** Rejected as primary (brittle NL), but it *is* the
deterministic fallback when intent parsing fails or confidence is low.

**Model with tool-calling that computes.** Deferred: even with tools, numbers stay from our
deterministic services, not model arithmetic; intent-parse is simpler and enough for v1.

## Consequences

- Positive: concise, trustworthy, operational answers; the SLM is used only where it's
  competent, sidestepping confabulation; every turn audited (DB + `ai.decisions`); runs
  offline on the Pi.
- Negative: bounded to a known intent set — it can't answer arbitrary questions (mitigated
  by a clear capability fallback); a misclassified intent yields a wrong-but-grounded answer
  (mitigated: the reply echoes the understood intent, e.g. "PnL — ALPHA:"); adds a
  `chat_audit` table + migration and a second AI surface to maintain.
- Follow-ups: grow the intent set; frontier tier for parsing quality; action intents via the
  guardrail (ADR-0018); use the audit corpus to improve NL. DB persistence can phase in after
  an in-memory + `ai.decisions` v1 if needed.
