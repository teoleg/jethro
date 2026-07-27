#!/usr/bin/env bash
# One-shot setup + enable for the Jethro continuous-improvement loop (ADR-0063).
#
# Bundles every step into one run: pull latest -> preflight checks -> (optional) one dry-run cycle
# -> install the every-30-minutes cron. Safe to re-run; it just re-checks and re-installs the cron line.
#
# Usage:
#   JETHRO_DEPLOY_CMD='<your build+restart>' ops/enable-loop.sh            # check, then enable
#   JETHRO_DEPLOY_CMD='<your build+restart>' ops/enable-loop.sh --dry-run  # run ONE cycle, do NOT enable
#   ops/enable-loop.sh --skip-pull                                          # don't git pull first
#   ops/enable-loop.sh --yes                                                # no confirmation prompt
#
# Env:
#   JETHRO_DEPLOY_CMD   how YOU rebuild + restart Jethro after a verified commit. If empty, changes
#                       still commit+push but the app won't restart (review-before-live mode).
#                       Example: './gradlew :app:bootJar -x test && sudo systemctl restart jethro'
#   JETHRO_URL          where the live app answers (default http://localhost:8080). The scorer + report
#                       read /api/attribution and /api/risk here.
set -euo pipefail

# --- resolve the repo from THIS script's location (works wherever you cloned it) ---
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
export JETHRO_REPO="$REPO"
URL="${JETHRO_URL:-http://localhost:8080}"
export JETHRO_URL="$URL"

DRY_RUN=0; SKIP_PULL=0; ASSUME_YES=0
for arg in "$@"; do
  case "$arg" in
    --dry-run)   DRY_RUN=1 ;;
    --skip-pull) SKIP_PULL=1 ;;
    --yes|-y)    ASSUME_YES=1 ;;
    *) echo "unknown flag: $arg"; echo "usage: ops/enable-loop.sh [--dry-run] [--skip-pull] [--yes]"; exit 2 ;;
  esac
done

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mOK\033[0m   %s\n' "$*"; }
warn() { printf '  \033[33mWARN\033[0m %s\n' "$*"; }
die()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; exit 1; }

say "Repo: $REPO   App URL: $URL"

# --- 1. Pull latest ---
if [ "$SKIP_PULL" -eq 0 ]; then
  BR="$(git rev-parse --abbrev-ref HEAD)"
  say "Pulling latest on '$BR'"
  for i in 1 2 3 4; do
    git pull --ff-only origin "$BR" && break || { warn "pull retry $i"; sleep $((2 ** i)); }
  done
else
  say "Skipping git pull (--skip-pull)"
fi

# --- 2. Preflight checks ---
say "Preflight"

command -v python3 >/dev/null 2>&1 && ok "python3 present" || die "python3 not found (the scorer + report need it)"

if command -v claude >/dev/null 2>&1; then
  ok "claude CLI present ($(command -v claude))"
else
  die "claude CLI not found — install Claude Code and run 'claude' then /login with your Max account"
fi

if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
  warn "ANTHROPIC_API_KEY is SET — this would bill the API account, not Max. The loop unsets it per cycle,"
  warn "but make sure it's not exported in the cron user's environment."
else
  ok "ANTHROPIC_API_KEY empty (cycles bill to Max)"
fi

# App endpoints the scorer + report depend on.
check_endpoint() {
  local path="$1"
  if curl -fsS --max-time 8 "$URL$path" >/dev/null 2>&1; then
    ok "reachable: $path"
  else
    warn "cannot reach $URL$path — start the app (or set JETHRO_URL). The loop will run but can't"
    warn "  score/report until the app answers."
  fi
}
check_endpoint /api/attribution
check_endpoint /api/risk

# Deploy command sanity.
if [ -z "${JETHRO_DEPLOY_CMD:-}" ]; then
  warn "JETHRO_DEPLOY_CMD is empty — verified changes will commit+push but the app WON'T rebuild/restart"
  warn "  (review-before-live mode). Set it to your build+restart to run fully hands-off."
else
  ok "deploy command set: $JETHRO_DEPLOY_CMD"
fi

# --- 3. Dry run one cycle (optional) ---
if [ "$DRY_RUN" -eq 1 ]; then
  say "Dry run — executing ONE cycle now (cron NOT installed)"
  ops/improve-loop.sh || warn "cycle exited non-zero (see log)"
  LOG="logs/improve-$(date +%F).log"
  say "Last 40 lines of $LOG"
  tail -n 40 "$LOG" 2>/dev/null || warn "no log yet"
  say "Dry run complete. Re-run without --dry-run to install the every-30-minutes cron."
  exit 0
fi

# --- 4. Confirm + enable ---
if [ "$ASSUME_YES" -eq 0 ]; then
  printf '\n\033[1mInstall the every-30-minutes cron now? [y/N] \033[0m'
  read -r reply || reply=""
  case "$reply" in
    y|Y|yes|YES) ;;
    *) echo "Aborted — nothing installed. (You can dry-run first: ops/enable-loop.sh --dry-run)"; exit 0 ;;
  esac
fi

say "Enabling the loop"
ops/loop-control.sh on
say "Status"
ops/loop-control.sh status
say "Done. Read the record anytime: reports/improvement-ledger.md (+ reports/attribution/ snapshots)."
echo "     Turn it off with: ops/loop-control.sh off"
