# ADR-0018: AI trade suggestions — frontier tier proposes, deterministic guardrails gate, human executes

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** Oleg
- **Tags:** ai, ui, order, risk

## Context

The owner wants AI to move from *narrating* the book to *proposing action*: "based on
the market situation and risk calculation, suggest buy or sell." risk-pnl now exists
(step 7), so the inputs a suggestion must reason over — positions, marks, unrealized
PnL, exposures — are live. The question is how a model-originated buy/sell reaches the
order path without breaking the invariants the whole platform is built on.

Two invariants bound the design hard. **Invariant 7**: AI never sits on the tick path,
risk guardrails are deterministic code, and every AI decision is an event on
`ai.decisions`. **ADR-0016**: a local SLM output is *never* parsed for a number that
feeds positions/PnL/risk. A buy/sell with a size is exactly such a number — so it
cannot originate from the local SLM, and it cannot flow to the order module as an
unchecked model number. The failure mode to prevent is a confident model sizing a
position that blows a limit, or auto-trading on a hallucinated signal.

The counter-force is usefulness: a suggestion the human must retype is barely better
than no suggestion. So the suggestion has to land *in the order ticket*, pre-filled and
one review-click from submission — without ever being the thing that submits.

## Decision

We will add an **advisory** trade-suggestion flow: the frontier model tier (ADR-0010)
proposes, deterministic code validates against risk limits, and a human executes from a
pre-filled ticket. The AI never submits an order and never bypasses the guardrail.

The pipeline, each stage an explicit step:

1. **Deterministic trigger (candidate generation).** Plain-code signals decide *when*
   to ask the model at all — e.g. exposure approaching a limit, a large unrealized
   swing, a stale/again-live mark, a risk-snapshot threshold. No model gates this;
   it mirrors the ADR-0017 deterministic floor. Triggers are rate-limited (cost).
2. **Frontier tier proposes.** The model reasons over a *context snapshot* (marks,
   positions, risk snapshot, applicable limits) and returns a structured proposal:
   `{ instrumentId, side, suggestedQty, orderType, limitPrice?, rationale, confidence }`.
   This is the ADR-0010 "trading decision" tier — **not** the local SLM (ADR-0016).
3. **Deterministic guardrail validates and clamps.** Plain code checks the proposal
   against risk limits (per-book/instrument exposure, notional caps, instrument
   tradability, sane size). Out-of-bounds → **rejected or clamped**, never passed
   through. This is the only thing that decides whether a suggestion is admissible —
   the model's number is an input to validation, never an output that moves money.
4. **Audit.** The proposal *and* the guardrail verdict are emitted to `ai.decisions`
   (invariant 7), carrying the context snapshot + hash, so every suggestion is
   replayable and explains itself.
5. **Surface as an attention item with "Apply to ticket."** Admissible suggestions
   appear in the attention feed (ADR-0017) with their rationale and evidence link.
   "Apply" pre-fills the order modal (already instrument-type aware); the human
   reviews and submits through the existing `/api/orders` path — which re-runs its own
   validation. The human is the execution floor.

Auto-execution (removing the human) is explicitly **out of scope** here and would be a
separate ADR, gated behind a deterministic auto-trade policy and the ADR-0015 rule that
the order module is extracted to its own JVM before any real-money broker.

## Alternatives considered

**Model writes orders directly (AI on the order path).** Maximally "agentic" and
maximally forbidden: it puts an unchecked model number on the path that moves money and
can bypass deterministic risk code. Violates invariant 7. Rejected.

**Local SLM originates the suggestion.** Cheaper and already wired (ADR-0016), but the
suggestion carries a size — a number feeding positions/risk — which ADR-0016 bars the
local model from producing. The local SLM may *explain* an admissible suggestion in the
feed; it may not originate the trade. Rejected as the originator.

**Suggestion bypasses the ticket (one-click execute from the card).** Tempting for
speed, but it collapses the human-review floor into a single mis-click. Rejected for
now; the pre-filled ticket keeps review explicit. Revisit only with an auto-trade ADR.

**No structured proposal — free-text "the model says buy AAPL".** Un-validatable and
un-auditable against limits. Rejected: the proposal must be structured so the
deterministic guardrail can act on it.

## Consequences

- Positive: AI proposes action without ever holding the trigger; the guardrail makes
  "the model suggested something unsafe" a caught, audited event instead of a fill;
  suggestions land in the ticket so they're actually useful; every suggestion is on
  `ai.decisions`, replayable with its context.
- Negative: needs the risk-limit model (per-book/instrument caps) that doesn't fully
  exist yet — the guardrail is only as good as the limits behind it; a new
  suggestion-proposal schema and a frontier-tier prompt/parse path to build and test;
  frontier calls cost money, so trigger rate-limiting is product work; a well-formed
  but poor suggestion can still waste the human's attention (mitigated by confidence +
  rationale + the deterministic trigger deciding *when* to ask).
- Follow-ups: define risk limits + the guardrail as deterministic code (extends
  risk-pnl); add the `ai.suggestions` / proposal schema next to the other contracts;
  wire the frontier tier behind ADR-0010's SPI; "Apply to ticket" action in the UI;
  a future auto-execution ADR (deterministic policy + order-module JVM extraction per
  ADR-0015) if/when the human-in-loop step is to be removed.
