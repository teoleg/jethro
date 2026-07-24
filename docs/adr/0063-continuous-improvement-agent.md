# ADR-0063: Continuous improvement agent — automate the observe→diagnose→advise→correct→rerun loop

- **Status:** Proposed
- **Date:** 2026-07-24
- **Deciders:** Oleg
- **Tags:** ai, ops, risk, strategy

## Context

Improving the platform today is a **manual loop**: let it run a session, pull telemetry
(P&L, per-signal live edge, cost/churn, attribution, promotion outcomes), have an expert
read the results, write a diagnosis with corrections, apply them, and run again. The
2026-07-24 live post-mortem is one full turn of that loop done by hand — and it is a
*recurring* exercise, not a one-off. The owner wants that experience **automated**: a
model that continuously watches the running system, produces expert-grade diagnosis and
correction advice, and closes the loop so the system keeps improving as it runs.

The forces are in tension. The platform is **built for** this: `signals_telemetry`
(ADR-0055) measures live edge; the attention feed (ADR-0017) is an agent-curated surface
with a **deterministic floor**; `ai.decisions` is the audit topic for every AI action;
the local SLM (Ollama, ADR-0016) already does narration/triage/scenario-proposal. But two
invariants bound hard against the naive version: **invariant 7** (AI never sits on the
tick path; risk guardrails are deterministic code; every AI decision is an event on
`ai.decisions`) and **ADR-0016** (a model output is NEVER parsed for a number feeding
positions/PnL/risk). An LLM that *auto-applies code or money/risk dial changes* to a
running trading system is precisely the failure those rules exist to prevent — a model
manufacturing silent money decisions at machine speed, on a system that just proved it can
lose money while looking flat (post-mortem: hedge masking a bleeding book).

## Objective (multi-dimensional — the state the agent optimizes)

The objective is a **vector of outcomes re-measured every cycle, not a single scalar.** Its
core dimensions are **ΔPnL** and **exposure**; the other outcome dimensions that move with
them — realized cost/slippage, drawdown, turnover — ride alongside. The agent treats
**everything else as free variables** (algo selection and params, position sizing, the
universe, config, the code itself) and its job is to **plan**: search over those variables to
move the objective vector the right way — **PnL up, exposure down** — cycle after cycle. The
dimensions are kept **separate on purpose**: +$500 made by doubling exposure and +$500 made
flat are different outcomes, and collapsing them into one number up front hides which one
happened. A ratio (Sharpe / return-over-VaR / Grinold's IR, *JPM* 1989) is *one* way to
weigh the dimensions against each other, but that weighting — or whether to scalarize at all
— is an **owner-set** choice, not fixed here.

Constraints and anti-gaming rules, both hard: (a) the **firm drawdown breaker** (ADR-0027) is
a floor no dimension may trade away — it bounds the search, it is not a dimension to optimize;
(b) PnL/exposure are measured on **strategy alpha** (attribution panel: alpha vs hedge vs
cost), **not the firm total** — a directional hedge or a lucky up-day must never mask a
bleeding book (2026-07-24 post-mortem); (c) **flat is an allowed, often-optimal plan** — when
no `(name, algo)` has positive live edge (ADR-0062), doing nothing dominates on every
dimension, so the agent is never forced to act. The specific metrics and the risk budget are
owner-set money-risk dials (CLAUDE.md provenance rule); this ADR fixes the *shape* — a
multi-dimensional objective on alpha, exposure-aware, breaker-floored, no-trade allowed — not
the numbers.

## Decision

We will build a **continuous improvement agent** whose deploy channel is **git**: the agent
runs the observe→diagnose→**fix**→rerun loop and ships fixes as **commits on a branch the
running system pulls and restarts on**, judged against the objective above. Each cycle it: (1) reads the existing
telemetry/attribution/edge state (never the tick path — batch/near-real-time reads only);
(2) produces an **expert diagnosis** (the recurring post-mortem, automated) + ranked
corrections, each emitted as an `ai.decisions` event and surfaced on the attention feed;
(3) generates the fix as **code + config, commits, and pushes to a branch**; (4) the
running node (the Pi/dev host) **pulls that branch and restarts** — no AWS/CD system
required. Two integration modes, owner's choice and interchangeable: **API-driven** (a
Claude session receives the report, analyses, commits; caller waits for session completion)
or **pull-driven** (the agent just pushes; the node polls git and restarts on change).

The **gate is the branch the node tracks**, not a manual ceremony: point the node at a
branch the agent pushes to for full autonomy, or keep it on `main` and have the agent push
to a `claude/*` branch the owner fast-forwards to stay in the loop with one command — same
code, owner picks the coupling. Two hard engineering floors hold under **either** coupling:
the agent **never edits the deterministic floor** (guardrails, firm breaker, the invariant-7
/ ADR-0016 gates) — it may change strategy/analysis/config code but not the code that stops
a bad trade; and the **real-money path stays behind ADR-0015** (order-module extraction), so
no agent commit can reach a real broker regardless of coupling — the platform is paper on
every feed (ADR-0061), so the agent has free rein with zero money risk. Every pushed change
is an auditable git commit (revertable) tied to its `ai.decisions` diagnosis; a rollback
trigger (edge/cost/breaker regression vs the pre-change baseline) opens a revert commit.

## Alternatives considered

- **Agent edits the deterministic floor too (unbounded self-modification).** Rejected: lets
  a model rewrite the guardrail/breaker/gates that stop a bad trade (invariant 7, ADR-0016) —
  a negative-edge "fix" could disable the very control that bounds it and churn hundreds of
  fills, recorded only as a commit. The floor is off-limits *precisely so* code-push can be
  autonomous everywhere above it.
- **Deploy via a heavyweight CD system (AWS pipeline, approval gates in CI).** Deferred/over-
  built for a single-node test box: git-pull-restart is the owner's actual deploy path, needs
  no external infra, and the branch-tracking choice already encodes the human-in-loop gate.
  Revive the CD pipeline when the order module is extracted for real money (ADR-0015).
- **Propose-only, push nothing (PR + human merge for every change).** Safe but keeps the
  owner as the bottleneck the whole exercise is meant to remove — kept as the `main`-tracking
  coupling above (agent pushes a `claude/*` branch, owner fast-forwards), not as a separate
  design; the owner can opt into it per-branch without new code.
- **A frontier model in the loop instead of the local SLM for diagnosis.** Deferred behind
  ADR-0010's cost/latency triggers; the local SLM covers narration/triage, and diagnosis
  reads deterministic telemetry, so no frontier call is required to start.
- **Reinforcement-style online "training" of the trading models from live P&L.** Rejected
  for now: online-learned weights feeding position size is exactly the ADR-0016 boundary,
  and live P&L is a tiny, non-stationary, overfit-prone sample (Bailey–López de Prado 2017);
  the honest version is the bounded-config controller above, not gradient updates on money.

## Consequences

- **Positive:** the recurring expert post-mortem becomes continuous and hands-off; the owner
  detaches from reading code/APIs (the stated goal) and reviews *decisions*, not diffs;
  deploy is just `git pull && restart` on the existing node — no new infra; turns
  already-collected live telemetry into a live control loop; every fix is a git commit tied
  to an `ai.decisions` diagnosis, so the loop is fully auditable and revertable.
- **Negative:** a standing agent that pushes code is a new faulty/attack surface — mitigated
  by the off-limits deterministic floor, the branch-tracking gate, the rollback-commit
  trigger, and ADR-0015 keeping any real broker out of reach; model-written commits can still
  introduce ordinary bugs, so CI must run on the agent's branch before the node pulls (green
  build is the minimum bar even when the owner isn't reviewing the diff); a chatty agent can
  bury the attention feed, so its cards obey the deterministic-floor priority (ADR-0017). The
  agent **will try to game its objective** (Goodhart) — keeping the dimensions separate
  (ΔPnL *and* exposure, not one collapsed number), measuring on alpha-not-firm, the breaker
  floor, and the allowed no-trade outcome are the specific defenses; any dimension or metric
  added later must carry the same anti-gaming framing.
- **Follow-ups:** wire CI-on-agent-branch as the pre-pull bar; define the rollback
  baseline/metric that triggers a revert commit; decide SLM-vs-frontier per ADR-0010; specify
  the node-side pull-and-restart hook (poll interval, restart safety around open positions).
  This agent is the natural driver of the ADR-0062 live-edge gate once that is Accepted. Does
  not change invariant 7 or ADR-0016 — it operates strictly above them.
