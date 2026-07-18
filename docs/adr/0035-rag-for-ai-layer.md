# ADR-0035: Retrieval-augmented context for the AI layer

- **Status:** Proposed
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** ai, hypothesis, data, retrieval

## Context

The hypothesis generator (ADR-0022) and narrative feed prompt the local SLM with only the *current*
cycle's marks + a handful of recent headlines. Two quality problems follow. (1) **Semantic
duplication.** The deterministic idempotency guard (ADR-0022 follow-up) keys on the news id the
model cites, or on near-identical thesis text; it cannot tell that two *differently worded*
headlines from two sources are the *same event* — so the same story, rephrased, still drives
duplicate hypotheses. That is genuinely a semantic-similarity problem, not a string one. (2) **No
memory.** Each cycle reasons from scratch: the model can't see that a near-identical setup was
proposed last week and lost, or what happened around similar past headlines. On a small local model
(ADR-0016), thin, memory-less context is a large part of why the output is weak and one-sided.

Retrieval could supply both: embed news + past hypotheses/outcomes, and at prompt time retrieve the
semantically nearest items. Constraints: it must stay **advisory** — a retrieved neighbour or a
similarity score is never a number fed into sizing/PnL/risk (invariant 7 / ADR-0016); it must run
**locally and offline** for dev/CI (ADR-0009/0013) — no external embedding API on the default path;
and it must not sit on the market/tick path (invariant 7). Doing nothing leaves dedup blind to
rewordings and the model without memory.

## Decision

We will add **local retrieval-augmented context** to the AI layer: embed narrative items and past
hypothesis records with a **local embedding model via Ollama** (e.g. `nomic-embed-text`), store the
vectors in **Postgres with `pgvector`** (the DB is already there — no new infra, ADR-0005/0013), and
at hypothesis time retrieve (a) the nearest prior news to the current headlines — a
cosine-similarity above a threshold marks a **semantic duplicate**, extending the idempotency guard
beyond the id/text key — and (b) the nearest past theses + their scored outcomes, injected into the
prompt as "similar past situations." Retrieval is **advisory context only**: it changes what the
model reads, never a computed number; the deterministic risk envelope (ADR-0022) is unchanged. It
runs off the market path, best-effort — a retrieval or embedding failure degrades to today's
no-memory prompt, never blocks a cycle.

## Alternatives considered

**No RAG — bigger prompt / longer news window.** Simplest, but stuffing more raw headlines into a
small model's context worsens latency and doesn't give semantic dedup or outcome memory. Rejected;
it treats the symptom.

**Embeddings for dedup only (no outcome retrieval).** Solves the semantic-duplicate half cheaply.
Kept as the first slice — but the memory half (past theses/outcomes) is where response *quality*
improves, so it's a slice, not the whole decision.

**External embedding/vector API (OpenAI, Pinecone, …).** Best models, least code — but breaks the
offline/CI and cost postures (ADR-0009/0013) and sends our narrative off-box. Deferred behind the
same measured trigger as the external inference tier (ADR-0010); local pgvector first.

**A dedicated vector DB (Qdrant/Milvus).** More capable at scale, but another service to run on the
one dev node (ADR-0013). Rejected now: pgvector on the existing Postgres covers this universe's
volume comfortably; revisit if the corpus outgrows it.

## Consequences

- **Positive:** semantic news dedup (rewordings/multi-source of one event collapse); the model gains
  memory — similar past setups and how they scored — which is the most direct lever on a small
  model's output quality and its one-sidedness; all local, offline, no new infra.
- **Negative:** an embedding call per news/thesis (off the hot path, but Ollama load to watch); a
  `pgvector` extension + migration and an embedding-model pull to provision; a similarity **threshold
  to tune** (too high misses dups, too low suppresses genuinely new news) — it must be configurable
  and measured, and the deterministic id/text guard stays as the floor under it; embeddings are a
  model output, so retrieval is advisory only — never parsed for a number (invariant 7).
- **Follow-ups:** gap-register G8; slice 1 = dedup, slice 2 = outcome memory; ties to G9 (signal
  balance) since retrieved counter-examples may reduce one-sidedness. Depends on ADR-0022 (hypothesis
  layer), ADR-0016 (local SLM tier), ADR-0005 (Postgres).
