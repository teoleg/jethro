#!/usr/bin/env bash
# Dump the Postgres DB to backups/ so a wipe is never fatal. Best-effort: if Postgres isn't up
# it just skips (never blocks a stop). Keeps the most recent 20 dumps.
#
#   scripts/backup-db.sh                 # write backups/jethro-<timestamp>.sql
# Restore one with:
#   docker compose exec -T postgres psql -U jethro -d jethro < backups/jethro-YYYYmmdd-HHMMSS.sql
set -euo pipefail
cd "$(dirname "$0")/.."

if ! docker compose exec -T postgres pg_isready -U jethro -d jethro >/dev/null 2>&1; then
  echo "==> Postgres not up — skipping DB backup."
  exit 0
fi

mkdir -p backups
OUT="backups/jethro-$(date +%Y%m%d-%H%M%S).sql"
echo "==> Backing up DB → $OUT"
if docker compose exec -T postgres pg_dump -U jethro -d jethro > "$OUT" 2>/dev/null && [ -s "$OUT" ]; then
  # keep the newest 20 dumps
  ls -1t backups/jethro-*.sql 2>/dev/null | tail -n +21 | xargs -r rm -f
  echo "==> Backup done ($(du -h "$OUT" | cut -f1))."
else
  echo "   WARN: pg_dump failed — no backup written."
  rm -f "$OUT"
fi
