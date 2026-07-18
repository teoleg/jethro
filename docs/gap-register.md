# Gap register — open work threads

Distinct threads raised in review, tracked **separately** so none gets buried inside another
(e.g. inside the sim work). Each row has ONE home. This is a roadmap index; implementation-level
"deferred" notes still live in `docs/deferred-register.md`, settled decisions in `docs/adr/`.

Status: `shipped` · `in progress` · `needs ADR` · `planned` (ADR accepted, not started).

| # | Thread | Scope (what it is — and is NOT) | Home | Status |
|---|--------|--------------------------------|------|--------|
| G1 | Sim price realism | The sim ENGINE only: history-anchored block bootstrap so prices behave like the market. NOT volume-in-algos, NOT depth, NOT news coupling. | ADR-0032 | shipped (engine + adapter + wiring + Yahoo snapshot capture) |
| G2 | Volume live through the pipeline | Traded volume no longer dropped at the ring buffer; measured ADV drives execution cap + impact. | commit (ADR-0032 P1) | shipped |
| G3 | Volume & depth in the ALGORITHMS | Signals/sizing consume flow: relative-volume confirmation, liquidity-aware sizing. Distinct from G1 (that's price generation). | ADR-0033 (to write) | needs ADR |
| G4 | Order-book depth modelled | `onQuote` gains sizes; synthesized depth-at-touch from real volume; depth-aware fills. No real L2 feed exists. | ADR-0033 (with G3) | needs ADR |
| G5 | News → tape coupling | A (sim) news item injects a CORRELATED shock: bp-momentum move + volume surge, faded over a horizon. Makes news↔price↔volume real. SIM-gated. | ADR-0034 (to write) | needs ADR |
| G6 | Hypothesis idempotency | Same news must not re-fire a hypothesis; deterministic news-id/text guard + model told the live calls. | ADR-0022 follow-up | shipped |
| G7 | Ops screen traffic state | Feed throughput (ticks/s, drops, marks, instruments) on the Ops page + `/api/traffic`. | commit | shipped |
| G8 | RAG for the AI layer | Retrieval so the model detects same-event news semantically (dedup across rewordings/sources) and grounds theses in past events/outcomes. Advisory only, never a number into risk (invariant 7). | ADR-0035 | in progress (semantic dedup shipped; outcome-memory + pgvector next) |
| G9 | AI signal quality: balance | Hypotheses skew one-sided ("unbalanced"). Separate from dedup (G6): a directional-balance / calibration concern in generation + conviction stats. | needs ADR | open |
| G10 | Sim control panel | Live UI dials over the sim for scenario staging. | ADR-0031 | shipped |
| G11 | README refresh | Bring the top-level README in line with the current architecture/build. | commit | shipped |

## Notes

- **G1 vs G2/G3/G4/G5 are deliberately separate.** ADR-0032 was over-scoped when it carried a
  P1–P5 plan; it is being narrowed to the sim *engine* (G1). Volume-in-algos + depth (G3/G4) and
  news coupling (G5) get their own ADRs so they can be accepted, built, and reviewed on their own
  merits — and so none is lost as a "phase" of something else.
- Reserved ADR numbers: **0033** flow-aware algos + synthesized depth (G3/G4), **0034** news→tape
  coupling (G5), **0035** RAG for the AI layer (G8). Written when each is picked up, not before.
