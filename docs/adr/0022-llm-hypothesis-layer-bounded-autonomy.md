# ADR-0022: LLM hypothesis layer — multi-source synthesis proposes structured theses; the quant layer computes; a deterministic risk envelope decides auto-execute vs human approval

- **Status:** Proposed (extends ADR-0010/0016/0018/0019 — adds bounded autonomy above 0018's human-in-loop)
- **Date:** 2026-07-13
- **Deciders:** Oleg
- **Tags:** ai, algo, risk, order

## Context

Today AI narrates (ADR-0016) and answers questions (ADR-0021); trade suggestions are a
single deterministic trigger → model proposes → human executes (ADR-0018). The owner wants
more: an LLM layer that **synthesises heterogeneous inputs** — news, earnings calls,
economic data, market data, portfolio — and **generates trading hypotheses**, and a system
that, "within certain risk boundaries, can still decide automatically" rather than always
waiting for a click. That is the judgment work no factor model captures.

Two forces bound it. **The invariant that protects the whole platform** (7 / ADR-0016): a
fluent model must never emit a number that feeds a position, order, PnL, or risk — a
confident wrong size is finance's most expensive failure. **Local-dev-first** (ADR-0009):
news/earnings/econ feeds cannot depend on a paid provider to run on the Pi. So the value
(qualitative reasoning + bounded autonomy) has to be captured without ever letting the model
be the source of a number or of the autonomy decision.

## Decision

We will insert an **LLM hypothesis layer** that emits *structured, number-free* theses, have
the **deterministic quant layer own every number** (backtest, factor/portfolio checks,
sizing, risk), gate with the existing policy layer, and let a **deterministic risk envelope**
— not the model — decide whether an admissible trade auto-executes or escalates to a human.

- **Hypothesis contract (model output, never a number):** `{thesis, instruments/sector/factor,
  direction, horizon, conviction∈{low,med,high}, rationale, sourceRefs}`. `conviction` is an
  ordinal used only for **prioritisation/ordering**, never as a probability or expected return
  in sizing math. Each hypothesis + its evaluation is an `ai.decisions` event (audit/replay).
- **Quant layer is the sole number source:** it maps a thesis to instruments, backtests it over
  replay history (build step 8), checks factor/portfolio fit, sizes it with the existing
  vol-scaled sizer, and computes its risk. An unbacktested/unsized hypothesis never reaches the
  order path.
- **Bounded autonomy (extends ADR-0018, which stays the default):** a deterministic **risk
  envelope** — per-order and per-book caps, instrument liquidity/whitelist, book distance from
  its limits, and simulated-only (ADR-0019) — is evaluated in code. **Inside** the envelope an
  admissible, guardrail-passing trade auto-executes; **outside** it, the trade lands in the
  human ticket (ADR-0018). The envelope is config the human sets; the model never widens it.
- **Inputs run offline:** a seedable **narrative sim feed** (fixtures correlated to the market
  regime — e.g. a shock regime emits a "surprise CPI print") stands in for real news/econ, like
  the market sim. Real providers are deferred behind production triggers.

## Alternatives considered

**LLM emits sized orders directly** (the diagram read literally). Rejected — the model becomes
the source of the number and the autonomy decision, violating invariant 7 / ADR-0016; the
structured contract exists precisely to prevent this.

**Free-form model output, parsed downstream.** Rejected — unstructured text is unauditable and
unbacktestable; the typed hypothesis is what lets the quant layer test and the audit replay.

**Keep ADR-0018 always-human; no autonomy.** Not rejected — it stays the default and governs
everything outside the envelope. Bounded autonomy is opt-in on top, sim-only until a real-broker
policy ADR (per ADR-0015/0019).

**Build the factor-model / portfolio-optimiser layer first.** Deferred — nothing to optimise over
until hypotheses and the backtest bridge exist; revive once step 8 replay and a factor library land.

## Consequences

- Positive: the LLM finally does qualitative synthesis and the system can act unattended inside a
  human-set risk boundary — while every number stays deterministic, auditable, and replayable.
- Negative: a new standing input (narrative feed) and event type to maintain; bounded autonomy is
  a new way trades reach the venue — mitigated by sim-only scope, the deterministic envelope, the
  guardrail, and default-off. The **must-re-gate-before-real-broker** footgun (ADR-0019) now also
  covers autonomy width — a forgotten wide envelope on a live broker is the tail risk.
- Follow-ups: the `Hypothesis` schema in `common-messaging`; a narrative sim feed; a
  `HypothesisGenerator` agent (SLM local, frontier when keys/cost triggers allow, ADR-0010) and a
  deterministic `HypothesisEvaluator`; the risk-envelope config + evaluator; real news/earnings/econ
  providers and the factor/optimisation layer behind their own triggers; a real-broker autonomy ADR.
