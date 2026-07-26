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

## Easiest: one guided script (pull → check → enable)
`ops/enable-loop.sh` does the whole thing — pulls latest, runs preflight checks (python3, the `claude`
CLI, that `ANTHROPIC_API_KEY` is empty so it bills Max, that the app's `/api/attribution` + `/api/risk`
answer), then asks before installing the cron. It finds the repo from its own location, so it works
wherever you cloned it.
```sh
# Set your build+restart, then run. It confirms before enabling.
JETHRO_DEPLOY_CMD='./gradlew :app:bootJar -x test && sudo systemctl restart jethro' \
  ops/enable-loop.sh

ops/enable-loop.sh --dry-run    # run ONE cycle now and STOP (don't install the cron) — great first test
ops/enable-loop.sh --yes        # skip the confirmation prompt
# If the app isn't on localhost:8080, add:  JETHRO_URL='http://host:port'
```

## Or the low-level switch directly
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

**Auto-updating from the maintainer branch.** Each cycle the loop `git merge`s `$UPSTREAM`
(default `origin/claude/new-session-smb8v6`, override with `JETHRO_UPSTREAM_BRANCH`) into
`auto-improve` before it works — so maintainer fixes pushed there are picked up **automatically, no
manual merge**. The loop only ever commits its own experiments to `auto-improve`; the maintainer only
ever writes `$UPSTREAM`; so the two never fight over one branch. A rare merge conflict is aborted and
logged, not left half-applied. (One-time bootstrap: the box needs this version of `improve-loop.sh`
first, so do a single manual merge once; after that it is automatic.)

## Watch it in the UI — the Improve page
Every cycle (change or not) writes one deterministic heartbeat line to `reports/run-status.json`, which
the running app serves at `/api/improve/status` and the **Improve** tab renders: time, alpha PnL,
exposure, % change vs the previous run, the action (changed / no-change / reverted), and the previous
change's scored verdict. All numbers come from `scripts/score-change.py` (never the model). Open
`http://localhost:8080/improve.html`.

## The improvement ledger — your running record
`reports/improvement-ledger.md` is the file you read to track improvement over time. Every code change
the loop makes is scored **on the next run** by its measured delta on **PnL and exposure (strategy
alpha)**, with a verdict: ✅ GOOD (PnL up, exposure down), ❌ BAD (PnL flat/down, exposure up →
**auto-reverted**), ⚠️ MIXED (risk-adjusted read). It's committed each cycle, so you can read the whole
history on GitHub from anywhere. A ledger-only commit does not restart the app.

## Files (all in git)
- `ops/enable-loop.sh` — one-shot guided setup: pull + preflight checks + (optional dry-run) + enable.
- `ops/loop-control.sh` — on/off/status switch for the cron.
- `ops/improve-loop.sh` — one full cycle (report -> Claude -> test -> commit -> push -> deploy).
- `ops/improve-prompt.md` — the agent's instructions (ledger, objective, procedure, hard limits).
- `reports/improvement-ledger.md` — the scored history of PnL/exposure deltas per change.
- `scripts/system-report.py` — the report collector (also writes the compact `report.md` + logs).
