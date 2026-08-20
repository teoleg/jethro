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

# Bucket: explicit env wins; else the stack's SSM parameter (the canonical location).
BUCKET="${MUNI_BACKUP_BUCKET:-}"
if [ -z "$BUCKET" ]; then
  BUCKET="$(aws ssm get-parameter --name /muni/prod/MUNI_BACKUP_BUCKET \
            --query Parameter.Value --output text 2>/dev/null || true)"
fi
if [ -z "$BUCKET" ] || [ "$BUCKET" = "None" ]; then
  echo "ERROR: no bucket. Deploy the MuniWorld stack first (it writes /muni/prod/MUNI_BACKUP_BUCKET),"
  echo "       or pass MUNI_BACKUP_BUCKET=<name> explicitly."
  exit 1
fi

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
