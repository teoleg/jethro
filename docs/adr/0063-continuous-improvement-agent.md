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
(b) PnL/exposure are measured on the **firm total** (`/api/risk` `.total`: total PnL net of
all costs, total gross/net exposure — **all books including the hedge**) — the real money made
and the real money at risk, matching the Overview headline. **(Owner decision 2026-07-26,
superseding the earlier alpha-only choice: the hedge costs real money and carries real
exposure, so it must count in the number that is monitored and optimized. The attribution
alpha-vs-hedge split remains a diagnostic for *where* the total comes from — it is not the
objective.)** (c) **flat is allowed only when genuinely on-track — staleness is a monitored FAILURE.** Owner target
(2026-07-26): **total PnL must grow ≥ 1% every 3 iterations** (`JETHRO_LOOP_PNL_TARGET_PCT` /
`_WINDOW`); the scorer records `pnl_growth_pct`, `on_track`, `stale`, `underwater` in the heartbeat. A
flat or negative PnL that is off target — *especially* with exposure still high — is a failure the agent
must attack that cycle, not an acceptable rest state. Doing nothing is legitimate only while on-track;
if the agent has truly exhausted the levers and the *sim* has no edge, it must say so plainly and
recommend a live feed — never hide behind "flat is fine". (This supersedes the earlier unconditional
"flat is often optimal" framing, per owner direction.) The specific metrics and the risk budget are
owner-set money-risk dials (CLAUDE.md provenance rule); this ADR fixes the *shape* — a
multi-dimensional objective on **total PnL/exposure**, exposure-aware, breaker-floored, no-trade allowed — not
the numbers.

## Decision

We will build a **continuous improvement agent** whose deploy channel is **git**: the agent
runs the observe→diagnose→**fix**→rerun loop and ships fixes as **commits on a branch the
running system pulls and restarts on**, judged against the objective above. Each cycle it: (1) generates the
**existing full report bundle** — `scripts/system-report.py` → `jethro-report-<ts>.zip` (the same
`diagnostics.xlsx` + `ops-telemetry.xlsx` + `db-aggregates.xlsx` the owner uploads today: risk/P&L,
positions, fills, TCA, hypotheses, strategy dials + change history, live `signals_telemetry` edge,
equity curves, `signal_observations` by `feed_mode`) — and feeds that **whole bundle** to the model, plus
the repo working tree (the agent runs inside the checkout) and recent WARN/ERROR **logs with stack traces**;
never the tick path — batch reads only. The bundle is the comprehensive-analysis contract: parity with the
manual post-mortem is guaranteed because it is the *same* collector;
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
/ ADR-0016 gates). *Above* the floor its authority is broad — it may add or alter any logic that
improves risk-adjusted PnL: config/dials, signal computation, sizing, hedging, **new strategies and
risk models**, and bug fixes from stack traces — one coherent, attributable change per run, and with a
**Proposed ADR written in the same commit** for architecturally-significant additions (design-first per
CLAUDE.md; it does not wait for approval but leaves the record). It just may never edit the code that
stops a bad trade. And the **real-money path stays behind ADR-0015** (order-module extraction), so
no agent commit can reach a real broker regardless of coupling — the platform is paper on
every feed (ADR-0061), so the agent has free rein with zero money risk. Every pushed change
is an auditable git commit (revertable) tied to its `ai.decisions` diagnosis.

The loop is a **measured experiment against the objective, not an open-ended edit stream** — the whole point
is to **see what actually makes PnL/exposure better**. Each cycle records the objective vector (ΔPnL,
exposure, cost, on the **firm total**) *before* the change, ships **one tagged change**, then reads the vector
*after* and **attributes the delta to that change**: a change that improved the vector is kept; one that
regressed it — or trips the breaker floor — auto-opens a revert commit. So the KPI is not an end-of-run
report, it is the **gate on every commit**, and the accumulating tagged history becomes the record of
*which changes moved PnL/exposure which way* — the thing the owner wanted to see. That record is a
committed file, **`reports/improvement-ledger.md`**: each change is scored on the next run with an
explicit verdict — ✅ **GOOD** (PnL up **and** exposure down), ❌ **BAD** (PnL flat/down **and** exposure
up → **auto-reverted**), ⚠️ **MIXED** (judged by the risk-adjusted read) — on the **firm total** (total
money, total exposure, all books). **All scoring arithmetic is done by a deterministic script, `scripts/score-change.py`, not by
the model** (invariant 7 / ADR-0016 — a number that gates money/risk is produced by code, never by an
LLM). The loop wrapper runs the scorer *before* it invokes the agent: it reads the live
`/api/risk` `.total` (and `/api/attribution` for fees) in exact decimal, computes the vector/deltas/verdict against a
recorded baseline (with a documented noise deadband — `PLACEHOLDER — Oleg to set`), writes the ledger row,
commits an audited `reports/attribution/<ts>.json` snapshot that makes the verdict recomputable from
source, and on ❌ BAD opens the `git revert` itself. The agent's only ledger interaction is running
`score-change.py baseline <sha> "<summary>"` after a change — passing the sha and prose, never a number;
the script reads and records the vector. The agent authors code and words; the script authors every
figure. (The engine that reads a
report, edits code, and pushes is **Claude Code headless / the Claude Agent SDK** — a tool-enabled coding
agent — **not** a plain text-completion Messages API call, which returns text and cannot edit files or push.)

**Implementation (landed as inert scaffolding, not yet enabled).** The engine is **Claude Code headless
(`claude -p`) running on the box** — one self-contained local cycle, not a remote push into a chat: report
the live app → analyse → (only if warranted) one change → `./gradlew -Pci test` → commit to
`claude/auto-improve` → push → rebuild+restart. It **runs on the Claude Max subscription** (the wrapper
`unset`s `ANTHROPIC_API_KEY` so cost is plan-usage, not per-token API billing), and **rebuilds/restarts only
when a commit actually happened** (a no-change cycle leaves the app running). Restart is the owner's own
build+restart command (`JETHRO_DEPLOY_CMD`); enable/disable is one crontab line via `ops/loop-control.sh`.
Files: `ops/loop-control.sh`, `ops/improve-loop.sh`, `ops/improve-prompt.md`, and the `report.md`/logs
addition to `scripts/system-report.py`. Nothing runs until `ops/loop-control.sh on`; this ADR stays
**Proposed** until the owner turns it on.

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
  added later must carry the same anti-gaming framing. The owner runs it **fully autonomous** — the box
  tracks `claude/auto-improve` and he monitors the report/ledger in the UI rather than approving each
  diff. That removes the human gate, so a **fabricated ledger verdict** becomes the sharpest failure
  mode (a faked ✅ hides a losing change and compounds it). Mitigations: the agent must score only from
  real data and commit the `/api/attribution` snapshot it scored from (`reports/attribution/<ts>.json`)
  so any verdict is recomputable; the auto-revert on ❌ BAD limits the damage of a genuinely-bad change;
  the green-test gate and untouchable deterministic floor bound the rest. The honest-self-scoring
  requirement is load-bearing and warrants an occasional spot-audit.
- **Follow-ups:** **add a logs input to the report bundle** — `scripts/system-report.py` today
  carries risk/P&L/ops/DB workbooks but **not** application logs, yet the code-level causes it
  must fix often live only in a stack trace (e.g. the `/api/universe/proposals`
  `ClassCastException` was found from a pasted trace, not the xlsx); a recent WARN/ERROR + stack-trace
  sheet is the one gap between "comprehensive P&L analysis" and "enough to find and fix a code
  issue." Then: wire CI-on-agent-branch as the pre-pull bar; define the rollback baseline/metric
  that triggers a revert commit; decide SLM-vs-frontier per ADR-0010 (a raw-log→code-fix diagnosis
  is frontier-tier work, not the local SLM's job). The node-side rebuild-and-restart hook is **done**
  (`ops/improve-loop.sh` + `JETHRO_DEPLOY_CMD`, restart only on a committed change) — the one piece still
  open there is **restart safety around open positions** (a rebuild mid-session drops in-memory state; on a
  paper/sim box that is acceptable, but a guard/flat-first step is owed before this ever nears real money).
  This agent is the natural driver of the ADR-0062 live-edge gate once that is Accepted. Does not change
  invariant 7 or ADR-0016 — it operates strictly above them.
