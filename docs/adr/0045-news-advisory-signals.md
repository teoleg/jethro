# ADR-0045: News-driven advisory signals — RSS → SLM verdict → bounded, audited overlay

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

We will add a **news → SLM-verdict → bounded-advisory** sidecar, off the hot path:

1. **Source: RSS first, no scraping.** Free, structured, ToS-friendly feeds — major wires, company
   IR feeds, SEC EDGAR filings, central-bank (Fed/ECB) RSS — plus Finnhub's free news endpoint
   (ADR-0024). HTML scraping is a last resort, never the default (the Yahoo lesson).
2. **The SLM emits a categorical VERDICT, never a number** (ADR-0016): direction
   (bullish/bearish/neutral) + a confidence *bucket* + one line of rationale + the instruments it
   bears on. Deterministic code maps that verdict to a **bounded advisory signal** inside the
   ADR-0022 envelope — raise/lower conviction, flag an event window (widen stops / stand down) —
   and it can **never** size a trade or override a deterministic guardrail (ADR-0017 floor: a model
   may not suppress a triggered alert). Every headline, verdict, and resulting action is an event on
   `ai.decisions` (invariant 7) — replayable and audited.
3. **The sim emits synthetic headlines** through the *same* pipeline (extends ADR-0034), so the whole
   path is exercised identically in sim, live, and replay — the feature never depends on the
   internet cooperating.
4. **Advisory overlay only.** It sits *on top of* the deterministic price-derived trend floor
   (ADR-0044); it adds event context, it does not replace the price signal or the measured-edge gate.

## Alternatives considered

**HTML scraping of news sites.** Same ToS/rate-limit/layout wall as bulk Yahoo history — brittle and
blockable. Rejected as the default; RSS is the structured, allowed equivalent.

**Let the LLM size or place trades directly.** Fast, but violates invariant 7 and ADR-0016 (a model
output steering money) — the failure mode the whole guardrail stack exists to prevent. Rejected.

**Paid news/sentiment API (Bloomberg, RavenPack, …).** Higher quality, real cost and a subscription
dependency. Deferred behind a production trigger (measured value from the free tier first).

**No news, price-only (ADR-0044 alone).** Simplest and fully honest, but leaves event-driven context
on the table that a desk clearly uses. Rejected as the end state; it stays the floor the overlay
builds on.

## Consequences

- Positive: event context the price detector cannot see, from a free/structured source, fully
  audited and off the tick path; the model advises within hard deterministic bounds, never steering
  money directly; runs offline in sim exactly as in prod.
- Negative: news is noisy and latent and SLM verdicts can be wrong — hence advisory-only behind a
  deterministic gate; free RSS coverage is uneven (not every name has a timely feed); crawling ToS
  must be respected; the sim's synthetic headlines are a modelled proxy, not real news semantics, so
  sim exercises the *pipeline* faithfully but not the *content*.
- Follow-ups: a paid feed behind its trigger; verdict-quality telemetry (did flagged events actually
  move the name); wire the advisory into the ADR-0044 regime overlay as a fast event signal.
