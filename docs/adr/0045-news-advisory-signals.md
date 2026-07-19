# ADR-0045: News-driven advisory signals — multi-source RSS → queued SLM sector-classify → bounded, audited overlay

- **Status:** Proposed
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** ai, strategy, market-data, sim-parity

## Context

The price-derived trend detector (ADR-0044) is blind to *events* — an earnings print, a CPI
surprise, a guidance cut — that a desk reacts to before the price fully reflects them. Production
should pull news and let the SLM tier (ADR-0016) form a quick verdict that adds context. The pieces
exist: the SLM tier (ADR-0016), the bounded-autonomy hypothesis layer (ADR-0022), and news that
already moves the sim tape (ADR-0034). The constraints are hard and non-negotiable: a model output
is **never** parsed for a number feeding positions/PnL/risk (ADR-0016); AI never sits on the tick
path and guardrails stay deterministic (invariant 7); and the feature must run offline on the sim,
never depending on a live/paid feed (local-dev rule). And the sourcing must not repeat the Yahoo
HTML-scraping wall.

## Decision

We will add a **multi-source news → queued SLM classification → sector fan-out → bounded-advisory**
sidecar, off the hot path:

1. **Sources: RSS first, many of them, no scraping.** Free, structured, ToS-friendly feeds — major
   wires, company IR feeds, SEC EDGAR, central-bank (Fed/ECB) RSS — plus Finnhub's free news endpoint
   (ADR-0024). The design assumes **N sources** from day one, not one. HTML scraping is a last resort
   (the Yahoo lesson).
2. **The SLM only CLASSIFIES, never a number** (ADR-0016). Per event it emits a bounded categorical
   verdict: a **GICS sector** drawn from the *fixed taxonomy we already carry* (`hedge_group` in
   refdata — the same sectoring the structural hedger uses, ADR-0040) or `MACRO/NONE`, plus a
   direction (bullish/bearish/neutral), a confidence *bucket*, and one line of rationale — classified
   **from the headline text only** (see §4: never a sim-attached tag). **Deterministic code owns
   "which securities":** it fans the sector out to its constituent instruments via a plain
   `hedge_group` join, and every resulting `(instrument, direction)` is put through the **ADR-0049
   hard gate** (deterministic edge or information-only). The model picks the *sector*, code picks the
   *names*, and code decides *whether it trades* — the model is off every money-touching step.
3. **Multi-source ingestion is a bounded, deduplicated event QUEUE drained in paced batches.** Each
   source polls on its own schedule and **enqueues** normalized events; a single batch worker drains
   the queue on the news cadence (**~30 min, Oleg's target — a scheduling knob, not a risk dial**),
   processing events **sequentially under a per-cycle cap** so the local SLM (the scarce resource on a
   small box, ADR-0013/0016) sees a *smooth, capped* load and never a burst when several sources
   deliver at once. Same-subject headlines **coalesce** to one classification (dedup by stable
   event-id, reusing the ADR-0022 idempotency window + ADR-0035 semantic layer), so N sources
   reporting one story cost one SLM call. Events over the per-cycle cap or a staleness TTL **defer to
   the next drain or are shed with a counter + metric — never silently dropped** (data-path rule).
4. **The sim emits synthetic headlines** through the *same* queue and classifier (extends ADR-0034),
   so the whole path runs identically in sim, live, and replay. The classifier reads the headline
   **text**, never a sector/subject tag the sim attaches — the same sim=prod parity discipline as the
   ADR-0044 regime label (invariant 8): a sim-supplied answer would work in sim and break on real news.
5. **Advisory overlay only.** The verdict maps to a **bounded advisory signal** inside the ADR-0022
   envelope — raise/lower conviction, flag an event window (widen stops / stand down) — and can
   **never** size a trade or suppress a triggered alert (ADR-0017 floor). It sits on top of the
   ADR-0044 price floor and the ADR-0049 edge gate; it adds event context, it replaces neither. Every
   headline, verdict, sector fan-out, and resulting action is an event on `ai.decisions` — audited.

## Alternatives considered

**HTML scraping of news sites.** Same ToS/rate-limit/layout wall as bulk Yahoo history — brittle and
blockable. Rejected as the default; RSS is the structured, allowed equivalent.

**Let the LLM size or place trades directly.** Fast, but violates invariant 7 and ADR-0016 (a model
output steering money) — the failure mode the whole guardrail stack exists to prevent. Rejected.

**Paid news/sentiment API (Bloomberg, RavenPack, …).** Higher quality, real cost and a subscription
dependency. Deferred behind a production trigger (measured value from the free tier first).

**Process each source inline as it arrives (per-source scheduler → SLM immediately).** Lowest latency
per item. Rejected: independent sources fire the SLM concurrently and spike the small box exactly when
news clusters (the moment it matters most); the queue + paced batch drain decouples arrival from
processing and bounds the inference load. Latency is a non-issue at a 30-min news cadence.

**SLM picks the securities directly (not just the sector).** One step, no refdata join. Rejected: it
puts an unbounded, hallucination-prone list on the money path and is hard to test; classifying into
the *fixed* sector taxonomy (bounded output) then joining `hedge_group` in code is deterministic,
reproducible, and reuses one canonical sectoring.

**No news, price-only (ADR-0044 alone).** Simplest and fully honest, but leaves event-driven context
on the table that a desk clearly uses. Rejected as the end state; it stays the floor the overlay
builds on.

## Consequences

- Positive: event context the price detector cannot see, from free/structured sources, fully audited
  and off the tick path; the SLM only classifies into a bounded taxonomy while deterministic code
  picks names and gates the trade — the model touches no money step; the queue turns bursty
  multi-source arrivals into a smooth, capped SLM load; runs offline in sim exactly as in prod.
- Negative: news is noisy/latent and SLM verdicts can be wrong — hence advisory-only behind the
  ADR-0049 gate; free RSS coverage is uneven; sector classification is a new failure surface (a
  misclassification routes context to the wrong sector — bounded downstream by the edge gate, but
  attribution accuracy still needs measuring); batch processing adds up to one cycle (~30 min) of
  latency, so genuinely intraday events stay the price detector's job, not this; queue depth, the
  per-cycle cap and the staleness TTL are tuning knobs, and a true flood (a macro event hitting every
  source at once) is shed by the caps — counted and metered, never silent; the sim's synthetic
  headlines exercise the *pipeline* faithfully but not real news *semantics*.
- Follow-ups: a paid feed behind its trigger; sector-classification + verdict-quality telemetry (did
  flagged events actually move the sector/name — scored via the existing hypothesis outcomes); wire
  the advisory into the ADR-0044 regime overlay as a fast event signal; per-source fairness/weighting
  once several feeds run.
