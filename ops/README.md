# Continuous-improvement loop — runbook (ADR-0063, Proposed)

This is the **implementation of ADR-0063**. Nothing runs until you install the crontab below.
It runs the improvement loop on your **Claude Max subscription** — no per-token API charge.

## What it does, per cycle (every 2 hours)
1. `system-report.py` snapshots the **live** app → `logs/report.md` (+ the full `jethro-report-*.zip`).
2. Claude Code (headless, on Max) reads the report, diagnoses against the objective
   (risk-adjusted PnL — PnL up per unit exposure, on strategy alpha), and **only if warranted**
   makes one change, runs the tests, and pushes to branch **`claude/auto-improve`**.
3. A separate deploy cron pulls that branch and restarts the app.

Most cycles end with **no commit** (flat is often optimal). That is by design and keeps cost near zero.

## Billing — Max, not API
- Claude Max covers Claude Code usage **when Claude Code is logged in with your Max account**. It is
  **not** the same as API credits, and it does **not** cover `ANTHROPIC_API_KEY` usage (that bills the
  pay-as-you-go API account separately).
- `improve-loop.sh` runs `unset ANTHROPIC_API_KEY` so every cycle stays on Max. **Do not** put an API
  key in the cron environment — a set key overrides the subscription and starts a separate bill.
- Cost on Max is measured in **plan usage, not dollars**. The loop shares that allowance with your own
  interactive Claude usage; if you hit the plan cap, the loop **pauses until reset** (no runaway bill).
  Every-2-hours (~12 runs/day), mostly cheap early-exits, is modest — but keep the cadence slow.

## One-time setup (on the box)
```sh
# 1. Node 18+ (Pi/ARM64 works), then Claude Code:
npm install -g @anthropic-ai/claude-code

# 2. Log Claude Code into your Max account (interactive, once):
claude            # then run:  /login   → pick your Max subscription
#    Verify no API key is shadowing it:  echo "$ANTHROPIC_API_KEY"  (should be empty)

# 3. Git push credentials for the claude/auto-improve branch must be configured for the cron user.
```

## Install the crontab
```cron
# Analysis loop — every 2 hours, on Max. Adjust the repo path if needed.
0 */2 * * *  cd $HOME/kernel-code/jethro && JETHRO_REPO=$HOME/kernel-code/jethro ops/improve-loop.sh

# Deploy side — pull the auto-improve branch and restart if it moved (every 15 min).
# Replace the restart command with however you run the app (systemd unit shown as an example).
*/15 * * * *  cd $HOME/kernel-code/jethro && git fetch origin claude/auto-improve -q && \
  [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/claude/auto-improve)" ] && \
  git merge --ff-only origin/claude/auto-improve && sudo systemctl restart jethro
```

> The **deploy cadence is the gate**: point it at `claude/auto-improve` for hands-off autonomy, or
> comment the deploy cron out and fast-forward the branch yourself when you want to review each change
> before it runs. Same analysis loop either way.

## Turning it off
Comment out (or `crontab -e` and delete) the two lines. Nothing else runs on its own.

## Files
- `ops/improve-loop.sh` — the wrapper each analysis cycle runs.
- `ops/improve-prompt.md` — the instructions Claude follows (objective, procedure, hard limits).
- `scripts/system-report.py` — the report collector (now also writes the compact `report.md` + logs).
