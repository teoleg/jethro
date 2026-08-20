#!/usr/bin/env bash
# muni-world backup to S3 (muni ADR-0021 §3) — runs nightly (systemd timer, installed by
# bootstrap.sh) and before every deploy (remote-deploy.sh). Two halves, same as the local
# backup-muni.sh discipline, because neither covers the other:
#   * pg_dump of the WHOLE muni database (terms, filing detail, valuation history, curve, vol);
#   * the OS-PDF inbox — hand-fetched documents that exist nowhere else.
# The bucket is versioned + RETAINed by the stack, so even a teardown keeps every generation.
set -euo pipefail

REPO_DIR="${MUNI_REPO_DIR:-/opt/muni-world}"
cd "$REPO_DIR"
ENV_FILE="muni-world/deploy/.env"
[ -f "$ENV_FILE" ] || { echo "no $ENV_FILE yet (first deploy?) — nothing to back up"; exit 0; }

BUCKET="$(grep -E '^MUNI_BACKUP_BUCKET=' "$ENV_FILE" | cut -d= -f2-)"
PGUSER="$(grep -E '^POSTGRES_USER=' "$ENV_FILE" | cut -d= -f2-)"
[ -n "$BUCKET" ] || { echo "MUNI_BACKUP_BUCKET not set — is the stack's SSM param missing?"; exit 1; }

COMPOSE="docker compose -f muni-world/deploy/docker-compose.aws.yml --env-file $ENV_FILE"
TS="$(date -u +%Y%m%d-%H%M%S)"

echo "==> pg_dump muni -> s3://$BUCKET/db/muni-$TS.sql.gz"
if $COMPOSE exec -T postgres pg_isready -U "$PGUSER" -d muni >/dev/null 2>&1; then
  $COMPOSE exec -T postgres pg_dump -U "$PGUSER" -d muni | gzip \
    | aws s3 cp - "s3://$BUCKET/db/muni-$TS.sql.gz"
else
  echo "    Postgres not up — skipping the dump"
fi

echo "==> Syncing the OS-PDF inbox -> s3://$BUCKET/os-inbox/"
# sync (not cp) so re-runs are cheap; --delete is deliberately OMITTED — S3 keeps a PDF even if
# it is ever removed on the node (these files are the irreplaceable half).
$COMPOSE exec -T muni sh -c 'cd /data && tar -cf - os-inbox 2>/dev/null' \
  | aws s3 cp - "s3://$BUCKET/os-inbox/os-inbox-$TS.tar" \
  || echo "    inbox tar skipped (container down or inbox empty)"

echo "==> Backup complete"
