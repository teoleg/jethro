#!/usr/bin/env bash
# Push the LOCAL muni-world data (Postgres muni schema + the OS PDFs) to the AWS backup bucket,
# ready for restore-from-s3.sh on the muni node (muni ADR-0021). Run this ON THE MACHINE THAT HAS
# THE DATA (the Pi), with AWS credentials configured (the same ones used for `cdk deploy`).
#
#   scripts/muni-migrate-to-aws.sh            # fresh backup -> s3://<bucket>/migrate/
#   MUNI_BACKUP_BUCKET=... scripts/muni-migrate-to-aws.sh   # explicit bucket override
#
# The transfer medium is S3, NOT git: a dump + PDFs committed to a branch would live in the repo
# history forever and can exceed GitHub's file limits; the bucket is versioned, private, and both
# ends already have credentials for it.
set -euo pipefail
cd "$(dirname "$0")/.."

# Bucket by convention (muni ADR-0021): muni-world-backups-<account-id>, created by the deploy
# workflow. Explicit env override wins.
BUCKET="${MUNI_BACKUP_BUCKET:-}"
if [ -z "$BUCKET" ]; then
  ACCOUNT="$(aws sts get-caller-identity --query Account --output text 2>/dev/null || true)"
  [ -n "$ACCOUNT" ] || { echo "ERROR: aws credentials not configured on this machine"; exit 1; }
  BUCKET="muni-world-backups-$ACCOUNT"
fi
aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null || {
  echo "ERROR: bucket $BUCKET not found — run the Muni Deploy workflow once first (it creates it)."
  exit 1
}

echo "==> Taking a fresh local muni backup"
./scripts/backup-muni.sh

NEWEST="$(ls -1t backups/muni-*.tar.gz 2>/dev/null | head -1 || true)"
[ -n "$NEWEST" ] || { echo "ERROR: no backups/muni-*.tar.gz produced — is local Postgres up?"; exit 1; }

KEY="migrate/$(basename "$NEWEST")"
echo "==> Uploading $NEWEST ($(du -h "$NEWEST" | cut -f1)) -> s3://$BUCKET/$KEY"
aws s3 cp "$NEWEST" "s3://$BUCKET/$KEY"

echo "==> Done. Restore it on the AWS node with the 'Muni Restore' GitHub Actions workflow"
echo "    (s3_key: $KEY), or over SSM:"
echo "      bash muni-world/deploy/restore-from-s3.sh '$KEY' --yes"
