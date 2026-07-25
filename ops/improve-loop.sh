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

unset ANTHROPIC_API_KEY || true   # bill to Max, never the API account

mkdir -p logs
LOG="logs/improve-$(date +%F).log"
echo "==== $(date -Is) cycle start ====" >> "$LOG"

# 1. Snapshot the LIVE app (writes logs/report.md + jethro-report-*.zip). Do NOT restart first —
#    the runtime telemetry is in-memory and a restart would wipe it.
python3 scripts/system-report.py >> "$LOG" 2>&1 || {
  echo "report generation failed — skipping cycle" >> "$LOG"; exit 0; }

# 2. Work on the feature branch.
git fetch origin >> "$LOG" 2>&1 || true
git checkout -B "$BRANCH" >> "$LOG" 2>&1
BEFORE=$(git rev-parse HEAD)

# 2b. Score the PREVIOUS cycle's change — DETERMINISTICALLY, in code, never by the LLM (invariant 7 /
#     ADR-0016). Measures the live app as it runs now (still on last cycle's code), writes the ledger
#     row + an audited snapshot, and on a BAD verdict reverts the offending commit. Any commits it
#     makes fall inside BEFORE..AFTER below, so they get pushed and (if the revert changed code)
#     trigger the rebuild. All numbers come from /api/attribution + /api/risk, none from Claude.
python3 scripts/score-change.py score >> "$LOG" 2>&1 || echo "scorer exited non-zero (see above)" >> "$LOG"

# 3. Claude Code (headless, on Max) reads logs/report.md, diagnoses against the objective, and ONLY
#    if warranted makes one change, runs the tests, and commits. It does NOT push or restart — the
#    wrapper owns those so build+restart only happen on a verified commit.
claude -p "$(cat ops/improve-prompt.md)" \
  --allowedTools "Bash Read Edit Grep Glob" \
  --permission-mode acceptEdits \
  >> "$LOG" 2>&1 || echo "claude run exited non-zero (see above)" >> "$LOG"

AFTER=$(git rev-parse HEAD)
if [ "$BEFORE" = "$AFTER" ]; then
  echo "no commit this cycle — app left running as-is (common, expected case)" >> "$LOG"
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
  bash -c "$JETHRO_DEPLOY_CMD" >> "$LOG" 2>&1 || echo "deploy command FAILED — app NOT restarted" >> "$LOG"
else
  echo "JETHRO_DEPLOY_CMD not set — change is committed+pushed but app was NOT rebuilt/restarted" >> "$LOG"
fi
echo "==== $(date -Is) cycle end ====" >> "$LOG"
