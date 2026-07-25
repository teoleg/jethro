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

# 3. Claude Code (headless, on Max) reads logs/report.md, diagnoses against the objective, and ONLY
#    if warranted makes one change, runs the tests, and commits. It does NOT push or restart — the
#    wrapper owns those so build+restart only happen on a verified commit.
claude -p "$(cat ops/improve-prompt.md)" \
  --allowedTools "Bash Read Edit Grep Glob" \
  --permission-mode acceptEdits \
  >> "$LOG" 2>&1 || echo "claude run exited non-zero (see above)" >> "$LOG"

AFTER=$(git rev-parse HEAD)
if [ "$BEFORE" = "$AFTER" ]; then
  echo "no change this cycle — app left running as-is (this is the common, expected case)" >> "$LOG"
  echo "==== $(date -Is) cycle end ====" >> "$LOG"
  exit 0
fi

# 4. A change was committed (the prompt requires green `./gradlew -Pci test` before committing).
echo "change committed $BEFORE -> $AFTER — pushing + deploying" >> "$LOG"
for i in 1 2 3 4; do
  git push -u origin "$BRANCH" >> "$LOG" 2>&1 && break || { echo "push retry $i" >> "$LOG"; sleep $((2 ** i)); }
done

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
