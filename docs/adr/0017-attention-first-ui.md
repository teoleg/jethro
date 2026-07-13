# ADR-0017: Attention-first UI — agents curate, humans see what needs them

- **Status:** Accepted (reshapes the information architecture of ADR-0006; stack unchanged)
- **Date:** 2026-07-12
- **Deciders:** Oleg
- **Tags:** ui, ai, architecture

## Context

ADR-0006 designed a classic monitoring console: tiles opening blotters and grids. The
owner's position: grids are a tax on human attention — the system, with SLM assistance
(ADR-0016), should do the watching and surface only what needs a human. The risk of
naive AI curation is the inverse failure: a model that hallucinates calm while a book
bleeds. Curation must never be able to *hide* a real problem.

## Decision

We will make the landing page a **consolidated risk/PnL header above an attention
feed**, not a tile menu, built on a two-layer curation rule:

0. **The landing leads with a deterministic consolidated risk/PnL summary.**
   Firm-wide realized/unrealized PnL, net/gross exposure, and a per-asset-class
   rollup sit at the top of the page, computed by risk-pnl from fills+marks — plain
   numbers, never model-sourced (invariant 7). This is the standing "how are we
   doing" answer; the attention feed below it is the "what needs me now" answer.
   The two are complementary: neither replaces the other, and the SLM annotates the
   feed but never the headline numbers.

1. **Deterministic triggers decide WHAT surfaces (the floor).** Threshold rules in
   plain code — PnL swing, exposure breach, mark staleness, feed drops/gaps, order
   rejects, budget burn (ADR-0011) — always produce an attention item. **No model
   sits between a deterministic trigger and the screen; the SLM can never suppress,
   only add.**
2. **The SLM decides HOW it reads (the assist).** Agents (commentator now, triage
   next) annotate items with plain-language explanation, group related items, rank
   ordering, and add periodic "all quiet" digests. Cosmetic stakes only — exactly the
   ADR-0016 division of labor.
3. **Every item carries its evidence**: links to the decision id / context hash
   (ai.decisions) and to the underlying data view. Cards answer "why am I seeing
   this"; one click shows the numbers.
4. **Detail views survive as drill-down, not as the primary surface.** Market /
   Orders / Books / Risk / Costs become evidence pages behind the feed (grids
   demoted to inspection tools — AG Grid stays for exactly that). Each has a "show
   everything" mode: curation is a lens, never a wall.
5. Attention items are events like everything else (`ui.attention` topic when broker
   wiring lands), so the feed is replayable and testable.

## Alternatives considered

**Grid-first console (ADR-0006 as written).** Proven, zero curation risk — and
precisely the human-attention tax the owner is building this platform to avoid.
Rejected as the primary surface; retained as drill-down.

**Pure-AI curation (model decides everything shown).** Maximally quiet, but a model
miss becomes a silent risk event — unacceptable failure mode for a trading system.
Rejected; hence the deterministic floor.

**Chat-first UI ("ask the book").** Attractive later — a conversational surface over
the same events; deferred until the feed and evidence views exist to ground it.

## Consequences

- Positive: the human watches a short ranked feed instead of four grids; agents built
  in ADR-0016 get a product surface immediately; "all quiet" becomes an explicit,
  auditable statement rather than an empty screen. The consolidated PnL header gives a
  standing risk answer without demoting the attention model — the two coexist.
- Negative: curation bugs can still mis-rank or over-group (mitigated: floor rules +
  show-everything mode + evidence links); threshold tuning is now product work —
  bad thresholds mean noise or silence; slightly more to build before the first
  screen (feed component + rules) than a plain grid page.
- Follow-ups: attention-item schema (`ui.attention`) next to the other contracts;
  deterministic trigger rules land with each domain module (risk thresholds with
  risk-pnl, budget with finops); triage agent joins the ADR-0016 roadmap between
  commentator and scenario proposer.
