#!/usr/bin/env bash
# Turn the every-30-minutes improvement loop ON or OFF (installs/removes one crontab line).
#
#   ops/loop-control.sh on       # enable — runs improve-loop.sh on the schedule (default every 30 min)
#   ops/loop-control.sh off      # disable — removes the line, nothing runs on its own
#   ops/loop-control.sh status   # ON / OFF
#
# The deploy (how a committed change reaches the running app) defaults to the repo's own
# `scripts/svc.sh restart app`. Set JETHRO_DEPLOY_CMD only if you deploy some other way — and test it
# first: improve-loop.sh verifies the app actually restarted and falls back to the repo command if it
# didn't, but an unverified deploy command is how a change gets scored against a binary that never ran
# it (ADR-0110). JETHRO_DEPLOY_CMD=none = commit+push, never restart (review-before-live).
#
# Change the interval with JETHRO_LOOP_CRON (a 5-field cron expression). Default is every 30 minutes.
#   JETHRO_LOOP_CRON='0 */2 * * *' ops/loop-control.sh on      # every 2 hours (slower)
# Overlapping fires are safe — improve-loop.sh takes a lock and skips if a cycle is still running, so a
# cycle whose build+test runs longer than 30 min simply defers the next fire rather than stacking.
set -euo pipefail

REPO="${JETHRO_REPO:-$HOME/kernel-code/jethro}"
TAG="# jethro-improve-loop"
DEPLOY="${JETHRO_DEPLOY_CMD:-}"
SCHED="${JETHRO_LOOP_CRON:-*/30 * * * *}"
# The cron line carries JETHRO_DEPLOY_CMD (and JETHRO_URL if set) so it survives independent of your
# interactive shell.
# Bake the current (interactive) PATH into the cron line so `claude`, gradle, docker, psql etc. are
# found — cron's default PATH is bare and would otherwise drop them. improve-loop.sh also re-adds the
# usual dirs as a fallback.
# Only carry JETHRO_DEPLOY_CMD when it is actually set: baking an EMPTY one into the cron line would
# override improve-loop.sh's default (the repo's own restart) with "deploy nothing".
# (`if` rather than `[ … ] && …`: under `set -e` a false test at the end of an AND-list aborts the
# script, so the cron line would never be installed.)
ENVP="PATH='$PATH' JETHRO_REPO=$REPO"
if [ -n "$DEPLOY" ];              then ENVP="$ENVP JETHRO_DEPLOY_CMD='$DEPLOY'"; fi
if [ -n "${JETHRO_URL:-}" ];      then ENVP="$ENVP JETHRO_URL=$JETHRO_URL"; fi
LINE="$SCHED cd $REPO && $ENVP ops/improve-loop.sh $TAG"

cmd="${1:-status}"
current="$(crontab -l 2>/dev/null || true)"

case "$cmd" in
  on)
    # Replace any existing loop line, then append the current one (picks up JETHRO_DEPLOY_CMD).
    { printf '%s\n' "$current" | grep -vF "$TAG"; printf '%s\n' "$LINE"; } | grep -v '^$' | crontab -
    echo "improvement loop ON — schedule: $SCHED"
    echo "deploy: ${DEPLOY:-scripts/svc.sh restart app (repo default)}"
    ;;
  off)
    printf '%s\n' "$current" | grep -vF "$TAG" | grep -v '^$' | crontab - || true
    echo "improvement loop OFF"
    ;;
  status)
    if printf '%s\n' "$current" | grep -qF "$TAG"; then echo "ON"; else echo "OFF"; fi
    ;;
  *)
    echo "usage: $0 {on|off|status}"; exit 2 ;;
esac
