---
name: adr
description: Author or supersede an Architecture Decision Record for Jethro. Use whenever a design/technology/structure decision is being made that is hard to reverse, spans services, or affects cost/latency — BEFORE writing the implementation.
---

# Writing an ADR for Jethro

## Procedure

1. Read `docs/adr/README.md` (index) and any ADRs touching the same area. If an Accepted
   ADR already answers the question, stop and follow it — or propose a superseding ADR if
   circumstances changed, stating what changed.
2. Take the next sequential number. Filename: `NNNN-short-kebab-title.md`.
3. Fill the structure from `docs/adr/template.md`. New ADRs start as **Proposed** —
   never self-accept; Oleg flips the status.
4. Add a row to the index table in `docs/adr/README.md`.
5. If the ADR supersedes another, mark the old one `Superseded by ADR-NNNN` (status line
   only — never rewrite its content).

## Quality bar

- **Context** states the forces (constraints, costs, skills, latency budget), not the
  solution. A reader should be able to guess at the decision from context alone.
- **Decision** is one or two "We will..." sentences plus essential specifics. Concrete
  enough that a violation is detectable in code review.
- **Alternatives**: 2–4 serious ones, each with the real reason it lost — "rejected
  because X", not straw men. If an alternative is merely deferred, say what evidence
  would revive it.
- **Consequences** must include at least one genuine negative. An ADR with no downside
  is advocacy, not a record.
- Reference related ADRs by number. Keep the whole file under ~80 lines.

## Reasoning discipline

- One recommendation. The alternatives section explains the losers; do not present a menu
  and ask the owner to pick without a stance.
- Quantify where possible (msg/sec, ms budget, $/month) — even rough numbers beat
  adjectives.
- Distinguish "rejected" (we believe it's wrong) from "deferred" (right later, trigger
  stated).
