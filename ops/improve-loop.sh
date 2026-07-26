#!/usr/bin/env bash
# Jethro continuous-improvement loop (ADR-0063) — ONE full cycle, all local on the box.
#
#   report the live app -> Claude deep analysis -> (if warranted) one code change -> run tests
#   -> commit to a feature branch -> push -> rebuild + restart the app -> done till next run.
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

# Single-flight: with a short interval, a slow gradle test could still be running when the next cron
# fires. Take a non-blocking lock and skip this fire rather than stacking overlapping cycles.
exec 9>"$REPO/.improve-loop.lock"
if ! flock -n 9; then
  echo "==== $(date -Is) skipped — previous cycle still running ====" >> "$LOG"
  exit 0
fi

echo "==== $(date -Is) cycle start ====" >> "$LOG"

# 1. Snapshot the LIVE app (writes logs/report.md + jethro-report-*.zip). Do NOT restart first —
#    the runtime telemetry is in-memory and a restart would wipe it.
python3 scripts/system-report.py >> "$LOG" 2>&1 || {
  echo "report generation failed — skipping cycle" >> "$LOG"; exit 0; }

# 2. Work on the feature branch.
git fetch origin >> "$LOG" 2>&1 || true
git checkout -B "$BRANCH" >> "$LOG" 2>&1
BEFORE=$(git rev-parse HEAD)
# Was a prior change awaiting its score at cycle start? Drives the run-status "scored/reverted" state.
HAD_PENDING=0; [ -f reports/.pending-baseline.json ] && HAD_PENDING=1

# 2b. Score the PREVIOUS cycle's change — DETERMINISTICALLY, in code, never by the LLM (invariant 7 /
#     ADR-0016). Measures the live app as it runs now (still on last cycle's code), writes the ledger
#     row + an audited snapshot, and on a BAD verdict reverts the offending commit. Any commits it
#     makes fall inside BEFORE..AFTER below, so they get pushed and (if the revert changed code)
#     trigger the rebuild. All numbers come from /api/attribution + /api/risk, none from Claude.
python3 scripts/score-change.py score >> "$LOG" 2>&1 || echo "scorer exited non-zero (see above)" >> "$LOG"

# 3. Claude Code (headless, on Max) reads logs/report.md, diagnoses against the objective, and ONLY
#    if warranted makes one change, runs the tests, and commits. It does NOT push or restart — the
#    wrapper owns those so build+restart only happen on a verified commit.
# 9>&- closes the single-flight lock fd for Claude and everything it spawns — otherwise a persistent
# child (notably the Gradle DAEMON that `./gradlew -Pci test` leaves running for hours) inherits the
# lock and holds it long after the cycle ends, wedging every later cycle into "skipped".
BRAIN_RAN=0
if ! command -v claude >/dev/null 2>&1; then
  echo "ERROR: 'claude' not found on PATH — analysis/change step SKIPPED (report + score + heartbeat" \
       "still ran). Install Claude Code for the cron user, or add its dir to PATH. PATH=$PATH" >> "$LOG"
else
  BRAIN_RAN=1
  claude -p "$(cat ops/improve-prompt.md)" \
    --allowedTools "Bash Read Edit Grep Glob" \
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
for i in 1 2 3 4; do
  git push -u origin "$BRANCH" >> "$LOG" 2>&1 && break || { echo "push retry $i" >> "$LOG"; sleep $((2 ** i)); }
done

# 4b. Rebuild+restart ONLY if code outside reports/ changed. A ledger-only commit (scoring the
#     previous change) must not bounce the app.
CODE_CHANGED=$(git diff --name-only "$BEFORE" "$AFTER" | grep -v '^reports/' || true)
if [ -z "$CODE_CHANGED" ]; then
  echo "ledger-only update — pushed, no rebuild/restart" >> "$LOG"
  echo "==== $(date -Is) cycle end ====" >> "$LOG"
  exit 0
fi
echo "code changed:" >> "$LOG"; printf '%s\n' "$CODE_CHANGED" >> "$LOG"

# 5. Rebuild the binary + restart the app. This is YOUR command (how you build/run Jethro) — set
#    JETHRO_DEPLOY_CMD in the crontab or environment, e.g.
#      JETHRO_DEPLOY_CMD='./gradlew :app:bootJar -x test && sudo systemctl restart jethro'
if [ -n "${JETHRO_DEPLOY_CMD:-}" ]; then
  echo "deploy: $JETHRO_DEPLOY_CMD" >> "$LOG"
  # 9>&- as above: the deploy starts the long-lived app (and may spawn Gradle); neither must inherit
  # the single-flight lock, or it stays held for the life of the app and every later cycle skips.
  bash -c "$JETHRO_DEPLOY_CMD" >> "$LOG" 2>&1 9>&- || echo "deploy command FAILED — app NOT restarted" >> "$LOG"
else
  echo "JETHRO_DEPLOY_CMD not set — change is committed+pushed but app was NOT rebuilt/restarted" >> "$LOG"
fi
echo "==== $(date -Is) cycle end ====" >> "$LOG"
