#!/usr/bin/env bash
#
# Wipe ALL local Jethro state — a clean slate. Removes:
#   • the running app (stopped via its pid file)
#   • Docker volumes: Postgres data + Redpanda topics
#     (orders, fills, marks, risk snapshots, ai decisions, reference data)
#   • LMDB local state (data/ — dedupe + warm-restart cache)
#   • app logs (logs/)
# With --all, also removes Gradle build outputs (*/build) for a from-scratch rebuild.
#
# After this, the next scripts/run-local.sh starts completely fresh: Flyway re-runs
# every migration and the sim rebuilds marks from zero.
#
# Usage:
#   ./scripts/clean-local.sh          # wipe data (asks for confirmation)
#   ./scripts/clean-local.sh -y       # wipe data, no prompt
#   ./scripts/clean-local.sh --all    # also remove build outputs
#
set -euo pipefail
cd "$(dirname "$0")/.."

ASSUME_YES=no
CLEAN_BUILD=no
for arg in "$@"; do
  case "$arg" in
    -y|--yes)  ASSUME_YES=yes ;;
    --all)     CLEAN_BUILD=yes ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)"; exit 2 ;;
  esac
done

echo "This will PERMANENTLY delete:"
echo "  • the running app (if any)"
echo "  • Docker volumes — Postgres data + Redpanda topics (all orders, fills, marks, reference data)"
echo "  • LMDB local state (data/)"
echo "  • logs (logs/)"
[ "$CLEAN_BUILD" = yes ] && echo "  • Gradle build outputs (*/build)"
echo

if [ "$ASSUME_YES" != yes ]; then
  read -r -p "Proceed? [y/N] " reply
  case "$reply" in
    y|Y|yes|YES) ;;
    *) echo "Aborted — nothing deleted."; exit 0 ;;
  esac
fi

# 1) Stop the background app.
PIDFILE="logs/jethro-app.pid"
if [ -f "$PIDFILE" ]; then
  PID="$(cat "$PIDFILE" 2>/dev/null || true)"
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    echo "==> Stopping app (pid $PID)…"
    kill "$PID" 2>/dev/null || true
    for _ in 1 2 3 4 5; do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$PID" 2>/dev/null || true
  fi
  rm -f "$PIDFILE"
fi

# 2) Snapshot the DB before wiping — a clean slate shouldn't mean an unrecoverable one.
./scripts/backup-db.sh || true

# 3) Docker containers + volumes (the Postgres + Redpanda data).
echo "==> Removing Docker containers and volumes…"
docker compose down --volumes --remove-orphans || true

# 3) LMDB local state (derived data — safe to drop; rebuilt on next start).
echo "==> Removing local LMDB state (data/)…"
rm -rf data/

# 4) Logs.
echo "==> Clearing logs (logs/)…"
rm -rf logs/

# 5) Optional: build outputs.
if [ "$CLEAN_BUILD" = yes ]; then
  echo "==> Removing Gradle build outputs (*/build)…"
  ./gradlew clean --console=plain >/dev/null 2>&1 || true
  find . -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
fi

echo "==> Clean slate. Next 'scripts/run-local.sh' starts fresh (Flyway re-runs all migrations)."
