#!/usr/bin/env bash
# Restore muni-world data from the S3 backup bucket ONTO THIS NODE (muni ADR-0021) — the receiving
# end of scripts/muni-migrate-to-aws.sh, also usable for disaster recovery from any nightly backup.
#
#   restore-from-s3.sh <s3-key> --yes        # e.g. migrate/muni-20260813-101500.tar.gz
#   restore-from-s3.sh latest-migrate --yes  # newest object under migrate/
#
# DESTRUCTIVE by design: it DROPS the muni schema and replaces it with the archive's contents
# (the archive carries its own flyway_schema_history, so the app boots consistent). That is why
# --yes is REQUIRED — without it the script prints what it WOULD do and exits. A safety dump of
# the current state goes to S3 first, so even a mistaken restore is reversible.
set -euo pipefail

KEY="${1:?usage: restore-from-s3.sh <s3-key|latest-migrate> --yes}"
CONFIRM="${2:-}"
REPO_DIR="${MUNI_REPO_DIR:-/opt/muni-world}"
cd "$REPO_DIR"
ENV_FILE="muni-world/deploy/.env"
[ -f "$ENV_FILE" ] || { echo "ERROR: no $ENV_FILE — run a deploy first"; exit 1; }
BUCKET="$(grep -E '^MUNI_BACKUP_BUCKET=' "$ENV_FILE" | cut -d= -f2-)"
PGUSER="$(grep -E '^POSTGRES_USER=' "$ENV_FILE" | cut -d= -f2-)"
COMPOSE="docker compose -f muni-world/deploy/docker-compose.aws.yml --env-file $ENV_FILE"

if [ "$KEY" = "latest-migrate" ]; then
  KEY="$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix migrate/ \
         --query 'sort_by(Contents,&LastModified)[-1].Key' --output text)"
  [ -n "$KEY" ] && [ "$KEY" != "None" ] || { echo "ERROR: nothing under migrate/ in $BUCKET"; exit 1; }
fi
echo "==> Restore source: s3://$BUCKET/$KEY"

if [ "$CONFIRM" != "--yes" ]; then
  echo "DRY RUN (no --yes): this WOULD drop schema muni in the node's database and replace it with"
  echo "the archive above, plus overlay its OS PDFs into /data/os-inbox. Re-run with --yes."
  exit 0
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
aws s3 cp "s3://$BUCKET/$KEY" "$STAGE/archive.tar.gz"
tar -xzf "$STAGE/archive.tar.gz" -C "$STAGE"
[ -s "$STAGE/muni-schema.sql" ] || { echo "ERROR: archive has no muni-schema.sql"; exit 1; }

echo "==> Safety dump of the CURRENT node state -> s3://$BUCKET/pre-restore/"
$COMPOSE exec -T postgres pg_dump -U "$PGUSER" -d muni 2>/dev/null | gzip \
  | aws s3 cp - "s3://$BUCKET/pre-restore/muni-$(date -u +%Y%m%d-%H%M%S).sql.gz" \
  || echo "    (no current state to dump — empty node)"

echo "==> Stopping muni-world (schema is about to be replaced under it)"
$COMPOSE stop muni

echo "==> Ensuring the archive's owner roles exist (a local dump says OWNER TO <local user>,"
echo "    and ON_ERROR_STOP would die on the first missing role)"
grep -oE 'OWNER TO [a-zA-Z0-9_]+' "$STAGE/muni-schema.sql" | awk '{print $3}' | sort -u \
  | while read -r role; do
      $COMPOSE exec -T postgres psql -U "$PGUSER" -d muni -tAc \
        "SELECT 1 FROM pg_roles WHERE rolname='$role'" | grep -q 1 \
        || $COMPOSE exec -T postgres psql -U "$PGUSER" -d muni -c "CREATE ROLE \"$role\" NOLOGIN"
    done

echo "==> Dropping + restoring schema muni"
$COMPOSE exec -T postgres psql -U "$PGUSER" -d muni -v ON_ERROR_STOP=1 \
  -c 'DROP SCHEMA IF EXISTS muni CASCADE'
$COMPOSE exec -T postgres psql -U "$PGUSER" -d muni -v ON_ERROR_STOP=1 -q < "$STAGE/muni-schema.sql"
ROWS="$($COMPOSE exec -T postgres psql -U "$PGUSER" -d muni -tAc 'SELECT count(*) FROM muni.security')"
echo "    restored: $ROWS row(s) in muni.security"

if [ -d "$STAGE/os-inbox" ]; then
  echo "==> Overlaying OS PDFs into the muni data volume"
  # The local backup nests paths (cp --parents); flatten every PDF into /data/os-inbox/processed
  # so the already-loaded documents don't re-ingest, they are just PRESENT and backed up.
  find "$STAGE/os-inbox" -name '*.pdf' -print0 | tar -cf "$STAGE/pdfs.tar" --null -T - \
    --transform 's|.*/|os-inbox/processed/|'
  $COMPOSE run --rm --no-deps -v "$STAGE/pdfs.tar:/restore/pdfs.tar:ro" --entrypoint sh muni \
    -c 'mkdir -p /data/os-inbox/processed && tar -xf /restore/pdfs.tar -C /data'
  echo "    $(find "$STAGE/os-inbox" -name '*.pdf' | wc -l) PDF(s) placed"
fi

echo "==> Restarting muni-world"
$COMPOSE up -d muni

echo "==> Restore complete. Verify at /api/muni/status and the Bonds page."
