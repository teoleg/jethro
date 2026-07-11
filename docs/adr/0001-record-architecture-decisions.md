# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** process

## Context

Jethro is a greenfield trading platform that will grow to span market data ingestion,
algo execution, risk/PnL, and a UI, developed partly with AI assistance. Decisions made
early (language, messaging, data model) are expensive to reverse and easy to forget the
rationale for. Without a written record, future contributors — human or AI — will
re-litigate settled questions or unknowingly violate their constraints.

## Decision

We will record every architecturally significant decision as an ADR in `docs/adr/`,
numbered sequentially, using the format in [template.md](template.md). "Architecturally
significant" means: hard to reverse, affects more than one service, affects cost or
latency materially, or constrains future choices.

ADRs are written **before** the code that implements them. AI assistants working in this
repo must propose an ADR for any such decision and wait for it to be Accepted before
building on it.

## Alternatives considered

**Design wiki / ad-hoc docs.** Drifts from reality, no lifecycle, hard to diff and review
alongside code. Rejected.

**No formal record.** Fine for a weekend project; this platform is intended to grow.
Rejected.

## Consequences

- Positive: rationale survives; AI sessions can be grounded in accepted decisions.
- Negative: small writing overhead per decision.
- Follow-ups: keep the [index](README.md) table current when adding ADRs.
