# Continuous-improvement loop — runbook (ADR-0063, Proposed)

Implementation of ADR-0063, run on your **Claude Max subscription** (no per-token API charge).
It is inert until you turn it on with `ops/loop-control.sh on`.

## One full cycle (every 2 hours), all local on the box
```
report the live app  ->  Claude deep analysis  ->  (only if warranted) one code change
   ->  run tests (./gradlew -Pci test)  ->  commit to claude/auto-improve  ->  push
   ->  rebuild + restart the app  ->  done till next run
```
- **Most cycles make no change** — flat is often the right answer (ADR-0062), so the app is left
  running untouched and the cycle is nearly free on your Max allowance. It only rebuilds/restarts
  when Claude actually committed something.
- Claude commits green (tests must pass first); the wrapper owns push + rebuild + restart, so those
  only happen on a verified commit.

## Billing — Max, not API
- Max covers Claude Code usage **when Claude Code is logged in with your Max account**. It is **not**
  API credit and does **not** cover `ANTHROPIC_API_KEY` usage (that bills the API account separately).
- `improve-loop.sh` runs `unset ANTHROPIC_API_KEY` so every cycle stays on Max. Don't put an API key
  in the cron environment.
- Cost on Max is **plan usage, not dollars**; if you hit the cap the loop pauses until reset — no
  runaway bill. Every-2-hours, mostly no-change cycles, is modest.

## One-time setup (on the Pi — needs a 64-bit OS)
```sh
# Claude Code:
curl -fsSL https://claude.ai/install.sh | bash      # native binary  (or: npm i -g @anthropic-ai/claude-code)
claude            # then:  /login   -> pick your Max subscription
echo "$ANTHROPIC_API_KEY"                             # must be EMPTY, or it bills the API account

# Git push credentials for the claude/auto-improve branch must be set for the cron user.
```

## Turn it on / off
```sh
# Tell it how to rebuild + restart YOUR app, then enable. Example (adjust to how you run Jethro):
JETHRO_DEPLOY_CMD='./gradlew :app:bootJar -x test && sudo systemctl restart jethro' \
  ops/loop-control.sh on

ops/loop-control.sh status     # ON / OFF
ops/loop-control.sh off        # disable — removes the cron line, nothing runs on its own
```
- `JETHRO_DEPLOY_CMD` is **your** build-and-restart command; the loop runs it only after a verified
  commit. If you leave it empty, changes still commit+push but the app won't restart (it warns you).
- `on`/`off` just add/remove one tagged crontab line, so it's safe to toggle anytime.

## The branch is the gate
The box works on **`claude/auto-improve`** and runs whatever is committed there. For hands-off
autonomy, leave it as-is. To review each change before it runs live, set `JETHRO_DEPLOY_CMD` empty
(so it commits+pushes but doesn't restart) and rebuild/restart yourself after you've looked.

## Files (all in git)
- `ops/loop-control.sh` — on/off/status switch for the cron.
- `ops/improve-loop.sh` — one full cycle (report -> Claude -> test -> commit -> push -> deploy).
- `ops/improve-prompt.md` — the agent's instructions (objective, procedure, hard limits).
- `scripts/system-report.py` — the report collector (also writes the compact `report.md` + logs).
