#!/usr/bin/env bash
# Stop the background app started by run-local.sh and the Docker infrastructure.
# Data now persists in named volumes (pg-data, redpanda-data, ollama-models), so a plain stop
# KEEPS your DB + model across restarts. Add --volumes to wipe them (or use clean-local.sh).
# To bounce only part of the stack (e.g. restart the app, leave LLM + DB up), use scripts/svc.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

PIDFILE="logs/jethro-app.pid"
if [ -f "$PIDFILE" ]; then
  PID="$(cat "$PIDFILE" 2>/dev/null || true)"
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    echo "==> Stopping app (pid $PID)…"
    kill "$PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$PID" 2>/dev/null || true
  fi
  rm -f "$PIDFILE"
else
  echo "==> No app pid file; skipping app stop (was it started with run-local.sh?)"
fi

if [ "${1:-}" = "--volumes" ]; then
  echo "==> Stopping infra and removing volumes (Postgres + Redpanda data wiped)…"
  docker compose down --volumes
else
  echo "==> Stopping infra (data volumes kept)…"
  docker compose down
fi
./gradlew --stop >/dev/null 2>&1 || true
echo "==> Stopped."
