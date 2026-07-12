#!/usr/bin/env bash
# Stop the Docker infrastructure started by run-local.sh. The app itself runs in the
# foreground — stop it with Ctrl+C. Add --volumes to also wipe Postgres/Redpanda data.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "${1:-}" = "--volumes" ]; then
  echo "==> Stopping infra and removing volumes (Postgres + Redpanda data wiped)…"
  docker compose down --volumes
else
  echo "==> Stopping infra (data volumes kept)…"
  docker compose down
fi
./gradlew --stop >/dev/null 2>&1 || true
