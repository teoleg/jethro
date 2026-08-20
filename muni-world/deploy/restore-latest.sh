#!/usr/bin/env bash
# Boot-time restore (muni ADR-0021): before the stack's FIRST start on a fresh instance, pull the
# newest DB dump + OS-PDF archive from the account's muni backup bucket, so replacing the instance
# (the immutable-AMI deploy) never loses data. Ran by muni-world.service as ExecStartPre.
#
# No-ops when: already restored on this volume (marker), no bucket, or an empty bucket (first ever
# boot — Flyway then builds a fresh schema). NEVER destructive: it only seeds an EMPTY database.
set -euo pipefail
cd /opt/muni-world
MARKER=/opt/muni-world/.restored
[ -f "$MARKER" ] && exit 0

REGION="$(curl -s --max-time 5 http://169.254.169.254/latest/meta-data/placement/region || echo us-east-2)"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text 2>/dev/null || true)"
[ -n "$ACCOUNT" ] || { echo "restore-latest: no instance role — skipping (fresh start)"; exit 0; }
BUCKET="muni-world-backups-$ACCOUNT"
aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null || { echo "restore-latest: no bucket yet — fresh start"; touch "$MARKER"; exit 0; }

KEY="$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix db/ \
       --query 'sort_by(Contents,&LastModified)[-1].Key' --output text 2>/dev/null || true)"
COMPOSE="docker compose -f muni-world/deploy/docker-compose.aws.yml"

if [ -n "$KEY" ] && [ "$KEY" != "None" ]; then
  echo "restore-latest: seeding database from s3://$BUCKET/$KEY"
  $COMPOSE up -d postgres
  for i in $(seq 1 30); do
    $COMPOSE exec -T postgres pg_isready -U muni -d muni >/dev/null 2>&1 && break
    sleep 2
  done
  # Only seed an EMPTY database — if muni.security exists this volume already has data.
  if ! $COMPOSE exec -T postgres psql -U muni -d muni -tAc \
        "SELECT 1 FROM information_schema.tables WHERE table_schema='muni' AND table_name='security'" \
        | grep -q 1; then
    aws s3 cp "s3://$BUCKET/$KEY" - | gunzip \
      | $COMPOSE exec -T postgres psql -U muni -d muni -q
    echo "restore-latest: database seeded"
  else
    echo "restore-latest: database already has data — leaving it alone"
  fi
else
  echo "restore-latest: no db backup in bucket — fresh start"
fi

# OS PDFs: newest inbox archive into the muni data volume (idempotent overlay).
PKEY="$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix os-inbox/ \
        --query 'sort_by(Contents,&LastModified)[-1].Key' --output text 2>/dev/null || true)"
if [ -n "$PKEY" ] && [ "$PKEY" != "None" ]; then
  echo "restore-latest: restoring OS PDFs from s3://$BUCKET/$PKEY"
  aws s3 cp "s3://$BUCKET/$PKEY" /tmp/os-inbox.tar
  docker run --rm -v muni-world_muni-data:/data -v /tmp/os-inbox.tar:/restore.tar:ro alpine \
    sh -c 'mkdir -p /data && tar -xf /restore.tar -C /data' || echo "restore-latest: PDF overlay skipped"
  rm -f /tmp/os-inbox.tar
fi

touch "$MARKER"
echo "restore-latest: done"
