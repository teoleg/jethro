#!/usr/bin/env bash
# Runs ON the EC2 node, invoked by the GitHub Actions deploy workflow via SSM Run Command
# (ADR-0013). Pulls secrets from SSM Parameter Store, logs in to ECR, and brings up the
# requested component(s) from deploy/docker-compose.prod.yml. Idempotent: safe to re-run.
#
#   Usage: remote-deploy.sh <APP_IMAGE> [COMPONENT]
#     APP_IMAGE  full ECR image ref for the app, e.g. 123.dkr.ecr.eu-west-1.../jethro-app:<sha>
#     COMPONENT  all (default) | app | ollama | redpanda | postgres | caddy
set -euo pipefail

APP_IMAGE="${1:?usage: remote-deploy.sh <APP_IMAGE> [COMPONENT]}"
COMPONENT="${2:-all}"
REGION="${AWS_REGION:-$(curl -s http://169.254.169.254/latest/meta-data/placement/region)}"
SSM_PREFIX="${JETHRO_SSM_PREFIX:-/jethro/prod}"
REPO_DIR="${JETHRO_REPO_DIR:-/opt/jethro}"

cd "$REPO_DIR"
COMPOSE="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env"

echo "==> Fetching secrets from SSM ($SSM_PREFIX/*) into deploy/.env"
# Every parameter under the prefix becomes NAME=value (SecureString auto-decrypted). Never
# printed. Written 0600 so only root/the deploy user can read it.
umask 077
{
  echo "APP_IMAGE=$APP_IMAGE"
  aws ssm get-parameters-by-path --region "$REGION" --path "$SSM_PREFIX" --recursive \
      --with-decryption --query 'Parameters[].[Name,Value]' --output text \
    | while IFS=$'\t' read -r name value; do
        echo "$(basename "$name")=$value"
      done
} > deploy/.env
echo "    wrote $(grep -c '=' deploy/.env) settings to deploy/.env"

echo "==> Logging in to ECR"
registry="${APP_IMAGE%%/*}"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$registry"

# Resolve which services to act on (compose service names == component names).
if [ "$COMPONENT" = "all" ]; then
  SERVICES=""            # empty = every service in the file
else
  SERVICES="$COMPONENT"
fi

echo "==> Pulling images ($COMPONENT)"
$COMPOSE pull $SERVICES

echo "==> Starting components ($COMPONENT)"
# --remove-orphans keeps the box tidy when a service is renamed/removed.
$COMPOSE up -d --remove-orphans $SERVICES

# The AI model is data in a volume, not baked into the ollama image — ensure it's present
# (first boot pulls it; later runs are a no-op). Only when touching ollama or the whole stack.
if [ "$COMPONENT" = "all" ] || [ "$COMPONENT" = "ollama" ]; then
  MODEL="$(grep -E '^JETHRO_AI_MODEL=' deploy/.env | cut -d= -f2-)"
  MODEL="${MODEL:-qwen2.5:1.5b}"
  echo "==> Ensuring Ollama model present: $MODEL"
  $COMPOSE exec -T ollama ollama pull "$MODEL" || echo "WARN: model pull failed; app will retry at generation time"
fi

echo "==> Waiting for the app to report healthy"
for i in $(seq 1 40); do
  status="$($COMPOSE ps --format '{{.Name}} {{.Health}}' app 2>/dev/null | awk '{print $2}')"
  if [ "$status" = "healthy" ]; then
    echo "    app healthy"; break
  fi
  if [ "$i" = "40" ]; then
    echo "ERROR: app did not become healthy in time"; $COMPOSE logs --tail 80 app; exit 1
  fi
  sleep 6
done

echo "==> Deploy complete ($COMPONENT @ $APP_IMAGE)"
$COMPOSE ps
