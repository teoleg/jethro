#!/usr/bin/env bash
# Turn the every-2-hours improvement loop ON or OFF (installs/removes one crontab line).
#
#   ops/loop-control.sh on       # enable — runs improve-loop.sh every 2 hours
#   ops/loop-control.sh off      # disable — removes the line, nothing runs on its own
#   ops/loop-control.sh status   # ON / OFF
#
# Set JETHRO_DEPLOY_CMD before enabling so a committed change actually rebuilds + restarts the app:
#   JETHRO_DEPLOY_CMD='./gradlew :app:bootJar -x test && sudo systemctl restart jethro' ops/loop-control.sh on
set -euo pipefail

REPO="${JETHRO_REPO:-$HOME/kernel-code/jethro}"
TAG="# jethro-improve-loop"
DEPLOY="${JETHRO_DEPLOY_CMD:-}"
# The cron line carries JETHRO_DEPLOY_CMD so it survives independent of your interactive shell.
LINE="0 */2 * * * cd $REPO && JETHRO_REPO=$REPO JETHRO_DEPLOY_CMD='$DEPLOY' ops/improve-loop.sh $TAG"

cmd="${1:-status}"
current="$(crontab -l 2>/dev/null || true)"

case "$cmd" in
  on)
    # Replace any existing loop line, then append the current one (picks up JETHRO_DEPLOY_CMD).
    { printf '%s\n' "$current" | grep -vF "$TAG"; printf '%s\n' "$LINE"; } | grep -v '^$' | crontab -
    echo "improvement loop ON — every 2 hours"
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
