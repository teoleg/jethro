# ADR-0049: AI/news signals never place orders — the deterministic backtest is a hard gate

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** ai, strategy, risk, execution

## Context

The AI hypothesis layer is fed primarily by **news/narrative** (Finnhub news, the sim narrative
engine) — a model reads a headline and proposes a directional thesis. Under ADR-0022 (bounded
autonomy), amended by the ADR-0027 correction, such a thesis can **auto-execute** once the
`AutonomyEnvelope` clears it, and that envelope gates on the **AI's own live track record**, not on
any backtest. The ADR-0027 correction explicitly demoted the sim-tape backtest to **"advisory only …
does not gate autonomy"** — because it measured the deterministic *strategy's* edge on the
instrument, not the thesis's specific claim, and combined with honest costs it kept silently revoking
all autonomy.

The result, visible in the diagnostics export: hypotheses with `backtested = N`, `autonomous = true`,
`FILLED` — **news-driven orders reaching the book with no deterministic validation of any kind.** For
a platform whose whole risk stance is "guardrails are deterministic code, the model never sits on the
money path" (invariant 7, ADR-0016/0018), a model reading a headline and moving the book on its own
track record is the wrong direction of trust. A news signal is a *hypothesis*, and an untested
hypothesis must not trade.

## Decision

We will make two rules absolute, **amending ADR-0022 and reversing the ADR-0027 "backtest advisory"
stance**:

1. **An AI/news signal NEVER originates an order.** The AI layer produces proposals only — theses,
   narration, and attention items. The `AutonomyEnvelope` no longer submits orders on the strength of
   a track record. The order decision always belongs to the deterministic execution path (or a human,
   ADR-0018) — never to the model.
2. **Every AI thesis is dispatched to the deterministic backtest, which is now a HARD GATE.** A thesis
   is eligible only when the deterministic OOS harness (ADR-0027/0043/0044) independently measures a
   positive, cost-honest edge for that **instrument and direction** — i.e. the deterministic model
   would itself take that side. No such measured edge → the signal is surfaced as information on the
   attention feed and is **never** turned into an order. Not-yet-measured names are ineligible until
   the backtest runs (dispatch-always means no order without a completed measurement).

The AI is thereby confined to *timing and context within the deterministic model's proven names*; it
can never move the book where the deterministic model has no measured edge. This is deliberately the
consequence the ADR-0027 correction backed away from — here it is the goal, not a bug.

## Alternatives considered

**Keep track-record autonomy, backtest advisory (status quo, ADR-0022/0027).** Lets the AI earn size
from live results. Rejected as the reported problem: a news thesis on an unmeasured name auto-executes
with zero deterministic validation, which is precisely the trust the platform is built to withhold.

**Gate on a per-thesis walk-forward backtest (translate the thesis into a rule, test on real
history).** The most faithful "test THIS signal" — it measures the thesis's own claim, not the
strategy's. Deferred, not chosen now: thesis→rule translation is unbuilt (HypothesisEvaluator note).
The ADR-0043/0044 instrument+direction OOS edge is the buildable gate today and already answers "does
the deterministic model want this trade"; the walk-forward gate is the richer follow-up on top.

**Require human approval for every AI order, no auto-path at all.** Simplest and safe. Rejected as
stricter than needed: a signal the deterministic model independently validates and would trade anyway
does not need a human in the loop — the gate, not a click, is the control. Human review remains the
path for anything the deterministic model does not support.

## Consequences

- Positive: no news-driven order ever reaches the book unvalidated; the model is decisively off the
  money-origin path (reinforces invariant 7 / ADR-0016/0018); the two engines share one honest edge
  gate; "backtested = N and filled" becomes impossible by construction.
- Negative: AI-path order flow will drop sharply — often to zero on names the deterministic model has
  no measured edge for (accepted, and the point). AI value shifts to narration/attention/context, not
  autonomous trading. The instrument+direction OOS edge is still not the thesis's *own* edge — an
  honest approximation until the walk-forward gate lands. The `AutonomyEnvelope` track-record machinery
  is demoted to at most an additional cap on top of the mandatory gate, never a substitute for it.
- Follow-ups: implement the hard gate in the hypothesis execution path (block submit unless the OOS
  edge for instrument+direction is positive; route unsupported theses to the attention feed only);
  the per-thesis walk-forward backtest as the richer gate; mark ADR-0022 and ADR-0027 amended by this
  ADR; surface "gated: no deterministic edge" as the reason on the hypotheses panel so the block is
  legible.
