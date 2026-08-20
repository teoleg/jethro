#!/usr/bin/env bash
# Restore a SPECIFIC archive from the backup bucket onto THIS node (muni ADR-0021) — the receiving
# end of scripts/muni-migrate-to-aws.sh (the Pi's data), and the disaster-recovery path. Distinct
# from restore-latest.sh (the boot-time seeder, which never overwrites): THIS script REPLACES the
# schema deliberately, which is why it requires --yes and takes a safety dump first.
#
#   restore-from-s3.sh <s3-key> --yes        # e.g. migrate/muni-20260813-101500.tar.gz
#   restore-from-s3.sh latest-migrate --yes  # newest object under migrate/
set -euo pipefail
cd /opt/muni-world

KEY="${1:?usage: restore-from-s3.sh <s3-key|latest-migrate> --yes}"
CONFIRM="${2:-}"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="muni-world-backups-$ACCOUNT"
COMPOSE="docker compose -f muni-world/deploy/docker-compose.aws.yml"

if [ "$KEY" = "latest-migrate" ]; then
  KEY="$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix migrate/ \
         --query 'sort_by(Contents,&LastModified)[-1].Key' --output text)"
  [ -n "$KEY" ] && [ "$KEY" != "None" ] || { echo "ERROR: nothing under migrate/ in $BUCKET"; exit 1; }
fi
echo "==> Restore source: s3://$BUCKET/$KEY"

if [ "$CONFIRM" != "--yes" ]; then
  echo "DRY RUN (no --yes): this WOULD drop schema muni and replace it with the archive above,"
  echo "plus overlay its OS PDFs. Re-run with --yes."
  exit 0
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
aws s3 cp "s3://$BUCKET/$KEY" "$STAGE/archive.tar.gz"
tar -xzf "$STAGE/archive.tar.gz" -C "$STAGE"
[ -s "$STAGE/muni-schema.sql" ] || { echo "ERROR: archive has no muni-schema.sql"; exit 1; }

echo "==> Safety dump of the CURRENT state -> s3://$BUCKET/pre-restore/"
$COMPOSE exec -T postgres pg_dump -U muni -d muni 2>/dev/null | gzip \
  | aws s3 cp - "s3://$BUCKET/pre-restore/muni-$(date -u +%Y%m%d-%H%M%S).sql.gz" \
  || echo "    (no current state to dump)"

echo "==> Stopping muni-world (schema is about to be replaced under it)"
$COMPOSE stop muni

echo "==> Ensuring the archive's owner roles exist (a Pi dump says OWNER TO jethro)"
grep -oE 'OWNER TO [a-zA-Z0-9_]+' "$STAGE/muni-schema.sql" | awk '{print $3}' | sort -u \
  | while read -r role; do
      $COMPOSE exec -T postgres psql -U muni -d muni -tAc \
        "SELECT 1 FROM pg_roles WHERE rolname='$role'" | grep -q 1 \
        || $COMPOSE exec -T postgres psql -U muni -d muni -c "CREATE ROLE \"$role\" NOLOGIN"
    done

echo "==> Dropping + restoring schema muni"
$COMPOSE exec -T postgres psql -U muni -d muni -v ON_ERROR_STOP=1 -c 'DROP SCHEMA IF EXISTS muni CASCADE'
$COMPOSE exec -T postgres psql -U muni -d muni -v ON_ERROR_STOP=1 -q < "$STAGE/muni-schema.sql"
ROWS="$($COMPOSE exec -T postgres psql -U muni -d muni -tAc 'SELECT count(*) FROM muni.security')"
echo "    restored: $ROWS row(s) in muni.security"

if [ -d "$STAGE/os-inbox" ]; then
  echo "==> Overlaying OS PDFs into the muni data volume (flattened into processed/)"
  find "$STAGE/os-inbox" -name '*.pdf' -print0 | tar -cf "$STAGE/pdfs.tar" --null -T - \
    --transform 's|.*/|os-inbox/processed/|'
  docker run --rm -v muni-world_muni-data:/data -v "$STAGE/pdfs.tar:/restore.tar:ro" alpine \
    sh -c 'mkdir -p /data/os-inbox/processed && tar -xf /restore.tar -C /data'
  echo "    $(find "$STAGE/os-inbox" -name '*.pdf' | wc -l) PDF(s) placed"
fi

echo "==> Restarting muni-world"
$COMPOSE up -d muni
echo "==> Restore complete. Verify /api/muni/status and the Bonds page."
