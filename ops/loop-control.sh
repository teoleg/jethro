#!/usr/bin/env bash
# Turn the every-2-hours improvement loop ON or OFF (installs/removes one crontab line).
#
#   ops/loop-control.sh on       # enable — runs improve-loop.sh on the schedule (default every 2 hours)
#   ops/loop-control.sh off      # disable — removes the line, nothing runs on its own
#   ops/loop-control.sh status   # ON / OFF
#
# Set JETHRO_DEPLOY_CMD before enabling so a committed change actually rebuilds + restarts the app:
#   JETHRO_DEPLOY_CMD='./gradlew :app:bootJar -x test && sudo systemctl restart jethro' ops/loop-control.sh on
#
# Change the interval with JETHRO_LOOP_CRON (a 5-field cron expression). Default is every 2 hours.
#   JETHRO_LOOP_CRON='*/15 * * * *' ops/loop-control.sh on     # every 15 min (testing)
# Overlapping fires are safe — improve-loop.sh takes a lock and skips if a cycle is still running.
set -euo pipefail

REPO="${JETHRO_REPO:-$HOME/kernel-code/jethro}"
TAG="# jethro-improve-loop"
DEPLOY="${JETHRO_DEPLOY_CMD:-}"
SCHED="${JETHRO_LOOP_CRON:-0 */2 * * *}"
# The cron line carries JETHRO_DEPLOY_CMD (and JETHRO_URL if set) so it survives independent of your
# interactive shell.
# Bake the current (interactive) PATH into the cron line so `claude`, gradle, docker, psql etc. are
# found — cron's default PATH is bare and would otherwise drop them. improve-loop.sh also re-adds the
# usual dirs as a fallback.
ENVP="PATH='$PATH' JETHRO_REPO=$REPO JETHRO_DEPLOY_CMD='$DEPLOY'"
[ -n "${JETHRO_URL:-}" ] && ENVP="$ENVP JETHRO_URL=$JETHRO_URL"
LINE="$SCHED cd $REPO && $ENVP ops/improve-loop.sh $TAG"

cmd="${1:-status}"
current="$(crontab -l 2>/dev/null || true)"

case "$cmd" in
  on)
    # Replace any existing loop line, then append the current one (picks up JETHRO_DEPLOY_CMD).
    { printf '%s\n' "$current" | grep -vF "$TAG"; printf '%s\n' "$LINE"; } | grep -v '^$' | crontab -
    echo "improvement loop ON — schedule: $SCHED"
    [ -z "$DEPLOY" ] && echo "NOTE: JETHRO_DEPLOY_CMD is empty — changes will commit+push but the app won't restart. Re-run with it set."
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
