# ADR-0054: Event-keyed hypothesis identity — the model classifies the catalyst, deterministic code dedups on it

- **Status:** Proposed
- **Date:** 2026-07-20
- **Deciders:** Oleg
- **Tags:** ai, strategy, risk

## Context

The LLM hypothesis layer (ADR-0022) re-proposes the same trade every cycle while a headline sits in the
narrative window, reworded just enough to look new. We already guard against this two ways, and both are
failing in practice on the Finnhub build: RAG semantic dedup (ADR-0035) compares embedding cosine to a
threshold — reword the thesis and it slips under; the deterministic guard keys on the news-source-ids
the *model chooses to cite* — omit or vary them and the key changes. The observed symptom is duplicate
`Hypothesis: LONG AAPL` rows and, worse, **multiple auto-submitted orders off one piece of news**.

The root confusion is the unit of identity. The thing that should fire *once* is not an article and not a
sentence — it is the underlying **market event** (an FOMC decision, an earnings beat, an M&A headline).
One rare event spawns many articles across outlets, so keying on article-ids never collapses them, and
keying on prose is fuzzy by construction. Recognising that N differently-worded headlines are *one*
event is a genuine semantic judgment — the model has to do that part. But a model output must never *be*
the guard on the order path (ADR-0016 / invariant 7): guardrails are deterministic code.

The leverage is that real catalysts are **rare and discrete**, so they have a small, canonical
descriptor — *what kind* of event, *on whom*, *when* — which is a low-cardinality classification the SLM
is reliable at, unlike free-text similarity.

## Decision

We will give each hypothesis a structured **event key** the model *classifies* — `{ catalyst ∈
{FOMC, EARNINGS, M&A, GUIDANCE, RATING, MACRO_PRINT, PRICE_ACTION, OTHER}, entity: instrumentId | MARKET,
eventDate }` — a categorical tuple, never prose or a number (ADR-0016). The **deterministic** idempotency
guard, the ledger row, and the autonomy cooldown then all key on `instrument | direction | eventKey`, so
the same rare event fires **once** however it is reworded, while a genuinely new event (new date or new
catalyst) still fires once. When the model cannot classify (returns `OTHER` with no entity/date), we fall
back to today's news-id/thesis-signature key — degrade to the current guard, **never to no guard**. RAG
returns to an *assist* (retrieval/outcome memory), not the thing standing between us and a duplicate order.

## Alternatives considered

- **Keep embedding-cosine as the primary dedup (status quo).** A threshold on reworded prose is
  inherently soft — the exact failure we are seeing — and it silently vanishes whenever the embedder is
  down. Fine as an assist, unfit as the guard on the money path.
- **Dedup on the model-cited news-article ids.** One event = many articles, and the ids are
  model-supplied and vary cycle to cycle, so id-sets split rather than collapse. Rejected: it keys on the
  wrong unit (article, not event) and trusts the model's bookkeeping.
- **Upstream event-resolution service (cluster headlines → event id before the model sees them).** The
  "correct" heavy answer, mirroring the ADR-0050 corroboration idea for news. Deferred, not rejected:
  it is a whole entity-resolution pipeline; revive it if catalyst classification proves too noisy in
  measured use (the stated trigger).
- **Pure deterministic `instrument | direction` collapse (no event notion).** Simple and RAG-free, but
  it swallows a *genuinely new* same-direction catalyst on a name while an old one is still in the window
  — suppressing real signal. Rejected: the event date/type is what distinguishes repeat from new.

## Consequences

- **Positive:** one trigger and one order per real event, deterministically, independent of wording or
  embedder health; an auditable event key on every call; RAG stops being load-bearing on the order path.
- **Negative:** a model-supplied field now sits on the identity path — a mis-classification can *split*
  one event into two (a duplicate order, the very failure) or *over-merge* two real events into one
  (a missed second trade). Mitigated by the coarse day-grain key, the deterministic fallback, and the
  autonomy cooldown as a second floor — but it is a real new failure mode to monitor. Also a prompt +
  parse-schema change (the model must emit the tuple) and a duplicate-delivery test.
- **Follow-ups:** telemetry on classification quality (split/merge rates); per-catalyst dedup windows if
  a flat window proves wrong (an earnings pop decays faster than an M&A rerating); extend the same event
  key to the social/news advisory cards so cross-subsystem duplicates of one event collapse too.
