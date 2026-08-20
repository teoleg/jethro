#!/usr/bin/env bash
# muni-world backup to S3 (muni ADR-0021) — nightly (muni-backup.timer, baked into the AMI) and
# before every instance replacement (the deploy workflow triggers it via SSM). Two halves, same as
# the local backup-muni.sh discipline: the whole muni database, and the OS-PDF inbox (hand-fetched
# documents that exist nowhere else). Bucket by convention: muni-world-backups-<account-id>,
# versioned, created idempotently by the deploy workflow.
set -euo pipefail
cd /opt/muni-world

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="muni-world-backups-$ACCOUNT"
COMPOSE="docker compose -f muni-world/deploy/docker-compose.aws.yml"
TS="$(date -u +%Y%m%d-%H%M%S)"

if $COMPOSE exec -T postgres pg_isready -U muni -d muni >/dev/null 2>&1; then
  echo "==> pg_dump muni -> s3://$BUCKET/db/muni-$TS.sql.gz"
  $COMPOSE exec -T postgres pg_dump -U muni -d muni | gzip \
    | aws s3 cp - "s3://$BUCKET/db/muni-$TS.sql.gz"
else
  echo "==> Postgres not up — skipping the dump"
fi

echo "==> OS-PDF inbox -> s3://$BUCKET/os-inbox/os-inbox-$TS.tar"
docker run --rm -v muni-world_muni-data:/data alpine sh -c \
    'cd /data && [ -d os-inbox ] && tar -cf - os-inbox || true' \
  | aws s3 cp - "s3://$BUCKET/os-inbox/os-inbox-$TS.tar" \
  || echo "    inbox tar skipped (empty)"

echo "==> Backup complete"
