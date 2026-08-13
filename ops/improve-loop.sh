#!/usr/bin/env bash
# Jethro continuous-improvement loop (ADR-0063) — ONE full cycle, all local on the box.
#
#   report the live app -> build this run's prompt (contract + freshest situation/memory)
#   -> Claude deep analysis -> (if warranted) one code change -> run tests
#   -> commit to claude/auto-improve -> push -> rebuild + restart the app -> done till next run.
#
# ONE branch: the loop works on, commits to, pushes, and deploys `claude/auto-improve` — the same
# branch the maintainer pushes to. There is no second "upstream" branch and no auto-merge (that fork
# conflict-aborted every cycle and split the system in two). To land a maintainer change, push it to
# claude/auto-improve and let the box `git pull` it (see ops/README.md).
#
# Runs on the Claude MAX subscription (NOT an API key): `unset ANTHROPIC_API_KEY` keeps every run
# on the plan you already pay for. One-time setup + on/off switch: see ops/README.md.
set -euo pipefail

REPO="${JETHRO_REPO:-$HOME/kernel-code/jethro}"
BRANCH="claude/auto-improve"
cd "$REPO"

# cron runs with a bare PATH (usually just /usr/bin:/bin), so tools the cycle needs go missing —
# notably `claude` (the native Max installer puts it in ~/.local/bin), plus gradle/docker/psql. Put the
# usual locations back so a cron cycle behaves like an interactive one.
export PATH="$HOME/.local/bin:$HOME/bin:/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin:$PATH"

unset ANTHROPIC_API_KEY || true   # bill to Max, never the API account

mkdir -p logs
LOG="logs/improve-$(date +%F).log"

# Push that self-heals when a maintainer pushes to the same branch mid-cycle. A plain retry re-sends
# the IDENTICAL rejected push and fails again; instead, on rejection we fetch and REBASE our local
# commit on top of origin, then retry — so a concurrent maintainer push never wedges the loop into a
# stuck, diverged state. The loop and the maintainer normally touch different areas (fusion/reports vs
# ops/scripts), so the rebase is clean; a genuine content conflict aborts and stops (left for a human)
# rather than resolving code blind.
push_branch() {
  local i
  for i in 1 2 3 4; do
    if git push -u origin "$BRANCH" >> "$LOG" 2>&1; then return 0; fi
    echo "push rejected (origin moved?) — fetch + rebase onto origin/$BRANCH + retry ($i)" >> "$LOG"
    git fetch origin "$BRANCH" >> "$LOG" 2>&1 || true
    if ! git rebase "origin/$BRANCH" >> "$LOG" 2>&1; then
      git rebase --abort >> "$LOG" 2>&1 || true
      echo "rebase onto origin/$BRANCH CONFLICTED — not pushed; left for the maintainer" >> "$LOG"
      return 1
    fi
    sleep $((2 ** i))
  done
  echo "push still failing after retries + rebase" >> "$LOG"
  return 1
}

# ---------------------------------------------------------------------------------------------
# Deploy (steps 4b + 5), as ONE function both cycle shapes call — open-market and market-closed.
#
# It used to be inline at the tail of the open-market path only, which made a code change that
# arrived while the market was CLOSED undeployable in principle (ADR-0123): the closed branch
# fast-forwards the working tree to origin and exits before any deploy, and it captured its BEFORE
# sha AFTER that fast-forward — so the commit was already in HEAD by the time the next open cycle
# took its own BEFORE, and `git diff BEFORE AFTER` could never see it again. A change pulled in
# overnight was therefore in the repo, in every later diff's PAST, and never in the JVM. That is
# exactly the failure §5 below exists to prevent, arriving through the one door §5 did not watch.
# ---------------------------------------------------------------------------------------------

# 5. Rebuild the binary + restart the app — and VERIFY the running process actually turned over.
#
#    A change that never reached the JVM is not a change (ADR-0110). The loop's whole feedback circuit
#    assumes the app the scorer measures next cycle is running the commit it is scoring; when the
#    deploy silently fails that assumption breaks and the ledger records a verdict for code that never
#    ran. It happened: a crontab carrying the systemd EXAMPLE from ops/README.md on a box that runs the
#    app via scripts/svc.sh failed every cycle ("Unit jethro.service not found"), two commits were
#    scored against a binary that predated them, and the `./gradlew :app:bootJar` half of that command
#    kept succeeding — overwriting app-0.1.0-SNAPSHOT.jar underneath the LIVE JVM, which then threw
#    ClassNotFoundException on every lazily-loaded class.
#
#    So: run the deploy, then ask the app itself when it started. Reading uptime off the running
#    process works for any deploy mechanism (systemd, svc.sh, container) — no pidfile convention
#    assumed, and unlike an exit status it cannot report success for a process that never turned over.
#
#    This is YOUR command — set JETHRO_DEPLOY_CMD in the crontab or environment. Unset, the repo's own
#    `scripts/svc.sh deploy app` is the default rather than doing nothing; it is also the fallback when
#    verification fails, and it is always correct on this box: it STOPS the running JVM before it
#    rebuilds the jar (the Spring Boot loader reads classes lazily from app/build/libs, so rebuilding
#    under a live process corrupts its classloader) and it re-reads local.env, so provider/keys/profile
#    come back identical rather than reverting to run-local.sh's defaults — a silent feed switch is an
#    invariant-8 event, not a restart. Set JETHRO_DEPLOY_CMD=none for review-before-live.
DEPLOY_FALLBACK_CMD="scripts/svc.sh deploy app"
# `:-` so EMPTY behaves like UNSET → the safe default (a cron line baking JETHRO_DEPLOY_CMD='' must not
# silently mean "never deploy", the footgun that scored changes against a binary that never ran them).
# Explicit review-before-live is JETHRO_DEPLOY_CMD=none.
DEPLOY_CMD="${JETHRO_DEPLOY_CMD:-$DEPLOY_FALLBACK_CMD}"

# Epoch second the running app booted, or empty if it isn't answering. Uses the same JETHRO_URL the
# scorer and the report read, so "the app" means the app the ledger's numbers come from.
app_start_epoch() {
  python3 - <<'PY' 2>/dev/null || true
import json, os, time, urllib.request
base = os.environ.get("JETHRO_URL", "http://localhost:8080").rstrip("/")
try:
    with urllib.request.urlopen(base + "/api/ops/jvm", timeout=5) as r:
        print(int(time.time() - float(json.load(r)["uptimeSeconds"])))
except Exception:
    pass
PY
}

# True once the app answers AND the process behind it started at/after $1 — i.e. this deploy restarted
# it. Polls rather than sleeping blind: a cold start on this box takes a couple of minutes. Strict
# `>=`: a false negative only costs a redundant restart, a false positive is the bug we are fixing.
wait_for_restart() {
  local since="$1" deadline started
  deadline=$(( $(date +%s) + ${JETHRO_DEPLOY_TIMEOUT:-300} ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    started="$(app_start_epoch)"
    if [ -n "$started" ] && [ "$started" -ge "$since" ]; then return 0; fi
    sleep 5
  done
  return 1
}

# 9>&- as above: the deploy starts the long-lived app (and may spawn Gradle); neither must inherit
# the single-flight lock, or it stays held for the life of the app and every later cycle skips.
run_deploy() {
  echo "deploy: $1" >> "$LOG"
  bash -c "$1" >> "$LOG" 2>&1 9>&- || echo "deploy command exited non-zero: $1" >> "$LOG"
}

# Paths that CANNOT end up in the app binary, so a commit touching only these must never bounce the
# app (ADR-0142). `reports/` is the ledger/heartbeat/analysis the scorer and the agent write every
# cycle; `docs/` is the ADR set plus the loop's own mandated memory (`docs/loop-findings.md`), which
# ops/improve-prompt.md requires the agent to append EVERY run — change or not; `ops/` is this cron
# wrapper and its prompt, re-read by cron each fire and never compiled. None is a Gradle input.
# Anything else is treated as capable of changing the binary and does deploy.
NON_BINARY_PATHS='^(reports|docs|ops)/'

# 4b. Rebuild+restart ONLY if a path that can affect the app binary changed between $1 and $2. A
#     ledger-only, heartbeat-only or memory-only commit (scoring the previous change, a market-closed
#     status write, or a no-change cycle's finding) must not bounce the app: a restart re-seeds every
#     forecast/σ sensor cold and flattens the book, which destroys the very ADR-0116 evaluation window
#     a no-change cycle exists to protect (ADR-0142).
#     Returns 0 either way — a deploy result is logged, never a cycle-killing status.
deploy_if_code_changed() {
  local before="$1" after="$2" changed deploy_started
  changed=$(git diff --name-only "$before" "$after" | grep -Ev "$NON_BINARY_PATHS" || true)
  if [ -z "$changed" ]; then
    echo "no code change $before -> $after — no rebuild/restart" >> "$LOG"
    return 0
  fi
  echo "code changed:" >> "$LOG"; printf '%s\n' "$changed" >> "$LOG"
  if [ "$DEPLOY_CMD" = "none" ]; then
    echo "JETHRO_DEPLOY_CMD=none — change is committed+pushed but the app was NOT rebuilt/restarted." \
         "The next cycle will score this commit against a binary that does NOT contain it." >> "$LOG"
    return 0
  fi
  deploy_started=$(date +%s)
  run_deploy "$DEPLOY_CMD"
  if wait_for_restart "$deploy_started"; then
    echo "deploy VERIFIED — app is serving on a process that started after the deploy began" >> "$LOG"
  elif [ "$DEPLOY_CMD" != "$DEPLOY_FALLBACK_CMD" ]; then
    echo "deploy NOT verified (no restarted process answering /api/ops/jvm) — falling back to the repo's own restart" >> "$LOG"
    deploy_started=$(date +%s)
    run_deploy "$DEPLOY_FALLBACK_CMD"
    if wait_for_restart "$deploy_started"; then
      echo "deploy VERIFIED via fallback" >> "$LOG"
    else
      echo "deploy FAILED — app did NOT turn over after the fallback either. The next cycle would" \
           "score this commit against a binary that does NOT contain it; treat that verdict as void." >> "$LOG"
    fi
  else
    echo "deploy FAILED — app did NOT turn over. The next cycle would score this commit against a" \
         "binary that does NOT contain it; treat that verdict as void." >> "$LOG"
  fi
  return 0
}

# Single-flight: with a short interval, a slow gradle test could still be running when the next cron
# fires. Take a non-blocking lock and skip this fire rather than stacking overlapping cycles.
exec 9>"$REPO/.improve-loop.lock"
if ! flock -n 9; then
  echo "==== $(date -Is) skipped — previous cycle still running ====" >> "$LOG"
  exit 0
fi

echo "==== $(date -Is) cycle start ====" >> "$LOG"

# NOTE (ADR-0128): the OOS backtest now reads the DB `daily_close` SEED history — the ONE source of
# truth, Tiingo-seeded and refreshed daily by the app's own HistorySeeder — so there is no separate bars
# file to fetch here. The previous scripts/fetch_bars.py (Stooq) step was removed: Stooq 404'd every
# name and it was a second, redundant, unreliable history source. History currency is the app's job now,
# not the loop's.

# 0. Market-hours gate. On a LIVE feed outside the US session, the tape is frozen — there is nothing
#    to analyse, so spending an Opus cycle on it is pure waste (and the frozen book reads as false
#    "staleness"). Skip the ANALYSIS — no report, no Claude/Opus call — and write one distinct
#    "market closed" heartbeat so the Improve page shows why. SIM/REPLAY never skip (their tape runs
#    continuously). Cron still fires every time; this only gates the expensive work.
#    Override with JETHRO_LOOP_IGNORE_MARKET_HOURS=1.
#
#    A closed market skips the ANALYSIS, never the DEPLOY (ADR-0123). This branch still fast-forwards
#    to origin, so a code/config change pushed to the branch overnight lands in the working tree here
#    — and BEFORE must be taken BEFORE that fast-forward, exactly as §2 does, or the change is
#    swallowed: it is already in HEAD when the next open-market cycle takes its own BEFORE, so no
#    later `git diff BEFORE AFTER` can ever see it and the app runs the old jar forever. That is not
#    hypothetical — it stranded the owner's ADR-0122 exploration-mode directive (committed after the
#    close) through a full overnight and into the next session, leaving the book dormant at zero gross
#    against a config the repo said was live.
if ! python3 scripts/market-open.py >> "$LOG" 2>&1; then
  echo "market CLOSED — skipping report + analysis this cycle (no Claude call)" >> "$LOG"
  git fetch origin >> "$LOG" 2>&1 || true
  git checkout -B "$BRANCH" >> "$LOG" 2>&1
  BEFORE=$(git rev-parse HEAD)
  git merge --ff-only "origin/$BRANCH" >> "$LOG" 2>&1 || true
  # Deterministic heartbeat only (reads live PnL for the page; no model call). --market-closed labels it.
  python3 scripts/score-change.py status --market-closed 1 >> "$LOG" 2>&1 \
    || echo "status writer exited non-zero (see above)" >> "$LOG"
  AFTER=$(git rev-parse HEAD)
  if [ "$BEFORE" != "$AFTER" ]; then
    push_branch || true
    # A closed market is the CHEAPEST time to bounce the JVM: no tape to miss, no live measurement to
    # wipe, and hours of warm-up before the open. The heartbeat write alone is reports/-only and so
    # deploys nothing; only a real code/config change pulled in here does.
    deploy_if_code_changed "$BEFORE" "$AFTER"
  fi
  echo "==== $(date -Is) cycle end (market closed) ====" >> "$LOG"
  exit 0
fi

# 1. Snapshot the LIVE app (writes logs/report.md + jethro-report-*.zip). Do NOT restart first —
#    the runtime telemetry is in-memory and a restart would wipe it.
python3 scripts/system-report.py >> "$LOG" 2>&1 || {
  echo "report generation failed — skipping cycle" >> "$LOG"; exit 0; }

# 2. Work on the single branch. Fast-forward to any maintainer changes pushed to it since last cycle
#    (one branch, no second "upstream" to merge). A non-ff divergence is left alone — the loop's own
#    commits below get pushed and reconciled — so a maintainer push never wedges the cycle.
git fetch origin >> "$LOG" 2>&1 || true
git checkout -B "$BRANCH" >> "$LOG" 2>&1
# Capture BEFORE the fast-forward so maintainer commits pulled in here ALSO count as this cycle's change
# and are rebuilt+restarted by the deploy step (§4b). A code/config fix pushed to the branch then deploys
# itself on the next (open-market) cycle — no manual pull/restart. (Bug fixed 2026-07-28: BEFORE used to
# be taken AFTER the ff, so a pulled maintainer change was invisible to CODE_CHANGED and the app kept
# running the old jar until the loop happened to make its own code change.)
BEFORE=$(git rev-parse HEAD)
git merge --ff-only "origin/$BRANCH" >> "$LOG" 2>&1 && echo "fast-forwarded to origin/$BRANCH" >> "$LOG" \
  || echo "no fast-forward from origin/$BRANCH (local has un-pushed commits, or already current)" >> "$LOG"

# Was a prior change awaiting its score at cycle start? Drives the run-status "scored/reverted" state.
HAD_PENDING=0; [ -f reports/.pending-baseline.json ] && HAD_PENDING=1

# 2b. Score the PREVIOUS cycle's change — DETERMINISTICALLY, in code, never by the LLM (invariant 7 /
#     ADR-0016). Measures the live app as it runs now (still on last cycle's code), writes the ledger
#     row + an audited snapshot, and on a BAD verdict reverts the offending commit. Any commits it
#     makes fall inside BEFORE..AFTER below, so they get pushed and (if the revert changed code)
#     trigger the rebuild. All numbers come from /api/attribution + /api/risk, none from Claude.
python3 scripts/score-change.py score >> "$LOG" 2>&1 || echo "scorer exited non-zero (see above)" >> "$LOG"

# 2b-HOLD (ADR-0146): ONE change in flight, mechanically. If a pending baseline STILL exists after the
# scorer ran, its change is under evaluation (window not yet full) — so this cycle makes NO model call
# and NO new change: the prompt-level HOLD alone failed in production (35 changes in 4 trading days,
# week of 07-28, every window contaminated by the next change and 12 of 18 BAD reverts conflicting on
# the stacked commits). The scorer's `baseline` also refuses while pending — this skip is the outer
# belt. A distinct `holding` heartbeat records the state for the UI; evidence keeps accruing from the
# live book. The scorer just above still runs every cycle, so the moment the window fills the change is
# scored, pending clears, and the NEXT cycle analyses/changes again.
if [ -f reports/.pending-baseline.json ]; then
  echo "HOLD (ADR-0146) — pending change under evaluation; skipping analysis/change this cycle" >> "$LOG"
  # --scored 0: the pending SURVIVED the scorer (window not full), so nothing was scored this cycle —
  # a stale snapshot verdict must not surface as if fresh.
  python3 scripts/score-change.py status --scored 0 --holding 1 >> "$LOG" 2>&1 \
    || echo "status writer exited non-zero (see above)" >> "$LOG"
  AFTER=$(git rev-parse HEAD)
  if [ "$BEFORE" != "$AFTER" ]; then
    push_branch || true
    # A revert or a maintainer commit pulled in above is real code — deploy it; reports/-only skips.
    deploy_if_code_changed "$BEFORE" "$AFTER"
  fi
  echo "==== $(date -Is) cycle end (holding) ====" >> "$LOG"
  exit 0
fi

# 2c. Build THIS RUN'S prompt: the stable contract (ops/improve-prompt.md) followed by a generated
#     "THIS RUN'S LIVE CONTEXT" section that surfaces the freshest situation + memory (the ⚠ SITUATION
#     header, the latest objective flags, the last few scored ledger rows, recent findings) right in
#     the prompt, so the obvious money/risk state is never missed. It only QUOTES code-computed numbers
#     — it invents none (invariant 7). If it fails for any reason, fall back to the static contract so
#     the cycle still runs.
PROMPT_FILE="logs/improve-prompt.rendered.md"
python3 scripts/build-prompt.py >> "$LOG" 2>&1 && [ -s "$PROMPT_FILE" ] \
  || { echo "prompt build failed — falling back to the static contract" >> "$LOG"; PROMPT_FILE="ops/improve-prompt.md"; }

# 3. Claude Code (headless, on Max) reads the rendered prompt + logs/report.md, diagnoses against the
#    objective, and ONLY if warranted makes one change, runs the tests, and commits. It does NOT push
#    or restart — the wrapper owns those so build+restart only happen on a verified commit.
# 9>&- closes the single-flight lock fd for Claude and everything it spawns — otherwise a persistent
# child (notably the Gradle DAEMON that `./gradlew -Pci test` leaves running for hours) inherits the
# lock and holds it long after the cycle ends, wedging every later cycle into "skipped".
BRAIN_RAN=0
if ! command -v claude >/dev/null 2>&1; then
  echo "ERROR: 'claude' not found on PATH — analysis/change step SKIPPED (report + score + heartbeat" \
       "still ran). Install Claude Code for the cron user, or add its dir to PATH. PATH=$PATH" >> "$LOG"
else
  BRAIN_RAN=1
  claude -p "$(cat "$PROMPT_FILE")" \
    --allowedTools "Bash Read Edit Grep Glob Skill" \
    --permission-mode acceptEdits \
    >> "$LOG" 2>&1 9>&- || echo "claude run exited non-zero (see above)" >> "$LOG"
fi

# 3b. Per-cycle heartbeat for the UI (reports/run-status.json) — DETERMINISTIC, computed in code
#     (invariant 7): current PnL/exposure, % change vs the previous run, and this cycle's decision.
#     Runs EVERY cycle, including no-change ones, so the UI shows a line for each run. --changed = the
#     agent recorded a NEW baseline this cycle; --scored = a prior change was scored at cycle start.
CHANGED=0; [ -f reports/.pending-baseline.json ] && CHANGED=1
python3 scripts/score-change.py status --scored "$HAD_PENDING" --changed "$CHANGED" --brain-ran "$BRAIN_RAN" \
  >> "$LOG" 2>&1 || echo "status writer exited non-zero (see above)" >> "$LOG"

AFTER=$(git rev-parse HEAD)
if [ "$BEFORE" = "$AFTER" ]; then
  echo "nothing committed this cycle (even the heartbeat write?) — app left running as-is" >> "$LOG"
  echo "==== $(date -Is) cycle end ====" >> "$LOG"
  exit 0
fi

# 4. Something was committed (ledger score and/or a code change; the prompt requires green
#    `./gradlew -Pci test` before any code commit). Push so the ledger + any change persist.
echo "commit(s) this cycle $BEFORE -> $AFTER — pushing" >> "$LOG"
push_branch || true

# 4b/5. Rebuild+restart if this cycle changed code outside reports/ — the shared deploy defined at
#       the top of this script, verified against the app's own uptime (ADR-0110, ADR-0123).
deploy_if_code_changed "$BEFORE" "$AFTER"
echo "==== $(date -Is) cycle end ====" >> "$LOG"
