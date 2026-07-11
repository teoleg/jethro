---
name: design-review
description: Review a proposed design, ADR, or significant change for the Jethro trading platform before it is accepted or implemented. Use when asked to review/critique a design, or before accepting a Proposed ADR.
---

# Design review for Jethro

Produce a verdict, not a book report. Structure the response as:

1. **Verdict** — accept / accept with changes / rework, in one sentence.
2. **Blocking issues** — things that make the design wrong, each with the concrete
   failure scenario ("when X happens, Y breaks because Z").
3. **Non-blocking suggestions** — clearly separated; the owner may ignore these freely.

## Checklist to reason through (do not paste into the response)

- **ADR consistency** — does it contradict any Accepted ADR or the invariants in
  `CLAUDE.md`? (decimals for money, symbology containment, fills-as-truth,
  schema compatibility, dual timestamps, local-dev-first)
- **Failure modes** — what happens on: provider disconnect, Kafka partition
  reassignment, duplicate/replayed events, out-of-order ticks, stale marks, service
  restart mid-stream? Idempotency and recovery must be stated, not assumed.
- **Trading correctness** — sign conventions (long/short), currency of every figure,
  contract multipliers, timezone/session handling. A design that is vague on units is
  not done.
- **Scale honesty** — is the msg/sec or data volume claim actually estimated? Does
  anything melt at 10× — and do we care yet?
- **Cost** — new AWS components: rough $/month, and does dev stay cheap/shut-downable?
- **Reversibility** — what would migrating away cost? Irreversible choices need
  stronger justification.
- **Simplicity** — is anything here serving a requirement we don't have yet? Name it
  and propose deferral.

## Reasoning discipline

- Steelman the design first — understand why it's shaped this way before criticizing.
- Every blocking issue needs a scenario, not a vibe. "I'd worry about X" is not blocking.
- If you lack information to judge (e.g. expected tick rates), say exactly what number
  is missing rather than hedging the whole review.
