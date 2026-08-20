#!/usr/bin/env bash
# Runs ON the muni-world EC2 node, invoked by .github/workflows/muni-deploy.yml via SSM Run
# Command (muni ADR-0021). Pulls secrets from SSM Parameter Store (/muni/prod/*), logs in to
# ECR, BACKS UP FIRST, then brings up the stack from muni-world/deploy/docker-compose.aws.yml.
# Idempotent: safe to re-run.
#
#   Usage: remote-deploy.sh <MUNI_IMAGE> [COMPONENT]
#     MUNI_IMAGE  full ECR image ref, e.g. 123.dkr.ecr.us-east-1.../muni-world:<sha>
#     COMPONENT   all (default) | muni | postgres | caddy
set -euo pipefail

MUNI_IMAGE="${1:?usage: remote-deploy.sh <MUNI_IMAGE> [COMPONENT]}"
COMPONENT="${2:-all}"
REGION="${AWS_REGION:-$(curl -s http://169.254.169.254/latest/meta-data/placement/region)}"
SSM_PREFIX="${MUNI_SSM_PREFIX:-/muni/prod}"
REPO_DIR="${MUNI_REPO_DIR:-/opt/muni-world}"

cd "$REPO_DIR"
COMPOSE="docker compose -f muni-world/deploy/docker-compose.aws.yml --env-file muni-world/deploy/.env"

echo "==> Fetching secrets from SSM ($SSM_PREFIX/*) into muni-world/deploy/.env"
umask 077
{
  echo "MUNI_IMAGE=$MUNI_IMAGE"
  # POSTGRES_USER has a sane non-secret default; override with a param if ever wanted.
  echo "POSTGRES_USER=muni"
  aws ssm get-parameters-by-path --region "$REGION" --path "$SSM_PREFIX" --recursive \
      --with-decryption --query 'Parameters[].[Name,Value]' --output text \
    | while IFS=$'\t' read -r name value; do
        echo "$(basename "$name")=$value"
      done
} > muni-world/deploy/.env
echo "    wrote $(grep -c '=' muni-world/deploy/.env) settings"

# Backup BEFORE the deploy touches anything — same nothing-lost discipline as svc.sh locally.
# Best-effort on the very first deploy (no DB yet).
bash muni-world/deploy/backup-to-s3.sh || echo "WARN: pre-deploy backup skipped (first deploy?)"

echo "==> Logging in to ECR"
registry="${MUNI_IMAGE%%/*}"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$registry"

if [ "$COMPONENT" = "all" ]; then SERVICES=""; else SERVICES="$COMPONENT"; fi

echo "==> Pulling images ($COMPONENT)"
$COMPOSE pull $SERVICES

echo "==> Starting ($COMPONENT)"
$COMPOSE up -d --remove-orphans $SERVICES

echo "==> Waiting for muni-world to report healthy"
for i in $(seq 1 30); do
  status="$($COMPOSE ps --format '{{.Name}} {{.Health}}' muni 2>/dev/null | awk '{print $2}')"
  if [ "$status" = "healthy" ]; then
    echo "    muni-world healthy"; break
  fi
  if [ "$i" = "30" ]; then
    echo "ERROR: muni-world did not become healthy in time"; $COMPOSE logs --tail 80 muni; exit 1
  fi
  sleep 6
done

echo "==> Deploy complete ($COMPONENT @ $MUNI_IMAGE)"
$COMPOSE ps
