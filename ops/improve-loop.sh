#!/usr/bin/env bash
# Jethro continuous-improvement loop (ADR-0063) — one cycle, driven by Claude Code on Max.
#
# Runs ON THE BOX next to the live app (system-report.py needs localhost:8080 + compose Postgres).
# Uses the Claude MAX subscription — NOT an API key. The key line is `unset ANTHROPIC_API_KEY`:
# if that env var is set, Claude Code bills the pay-as-you-go API account even while you're logged
# into Max. Unsetting it keeps every run on the plan you already pay for (no per-token charge).
#
# One-time setup (see ops/README.md): install Node + `@anthropic-ai/claude-code`, then `claude`
# and `/login` with your Max account. This script assumes that login is already stored.
set -euo pipefail

REPO="${JETHRO_REPO:-$HOME/kernel-code/jethro}"
BRANCH="claude/auto-improve"
cd "$REPO"

# --- bill to Max, never to the API account ---
unset ANTHROPIC_API_KEY || true

mkdir -p logs
LOG="logs/improve-$(date +%F).log"
echo "==== $(date -Is) cycle start ====" >> "$LOG"

# 1. Generate the report bundle from the LIVE app (writes logs/report.md + jethro-report-*.zip).
#    Do NOT restart the app first — the runtime telemetry is in-memory.
python3 scripts/system-report.py >> "$LOG" 2>&1 || {
  echo "report generation failed — skipping this cycle" >> "$LOG"; exit 0; }

# 2. Make sure the auto-improve branch exists and is based on the branch the box runs.
git fetch origin >> "$LOG" 2>&1 || true
git checkout -B "$BRANCH" >> "$LOG" 2>&1

# 3. Hand the whole thing to Claude Code (headless). It reads logs/report.md, diagnoses against the
#    objective, and — only if warranted — makes ONE change, runs the tests, commits, and pushes.
#    Tools are pre-granted because cron has no human to approve prompts.
claude -p "$(cat ops/improve-prompt.md)" \
  --allowedTools "Bash Read Edit Grep Glob" \
  --permission-mode acceptEdits \
  >> "$LOG" 2>&1 || echo "claude run exited non-zero (see above)" >> "$LOG"

echo "==== $(date -Is) cycle end ====" >> "$LOG"
