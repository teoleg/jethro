#!/usr/bin/env bash
# Quickest single-box run — NO ECR / CDK / GitHub Actions. Reuse any instance you can reach
# (needs ~16 GB for Ollama). Builds the app on the box and brings up the whole stack on
# :8080. There is no TLS or auth in this quick path (that's what Caddy in
# deploy/docker-compose.prod.yml adds) — so LOCK THE SECURITY GROUP to your own IP.
#
# On the box:  git clone <repo> && cd jethro && bash deploy/quickstart.sh
# Redeploy:    git pull && bash deploy/quickstart.sh
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

if ! command -v docker >/dev/null 2>&1; then
  echo "==> Installing Docker"
  curl -fsSL https://get.docker.com | sh
fi

export JETHRO_TRADING_PROVIDER="${JETHRO_TRADING_PROVIDER:-sim}"
export JETHRO_AI_MODEL="${JETHRO_AI_MODEL:-qwen2.5:1.5b}"
export JETHRO_RAG_MODEL="${JETHRO_RAG_MODEL:-nomic-embed-text}" # RAG embeddings (ADR-0035)

echo "==> Building the app and starting the stack (redpanda, postgres, ollama, app)"
docker compose -f docker-compose.yml -f deploy/quickstart.override.yml --profile app up -d --build

echo "==> Pulling the SLM ($JETHRO_AI_MODEL) so narration/hypothesis work (sim + UI run without it)"
docker compose exec -T ollama ollama pull "$JETHRO_AI_MODEL" || \
  echo "   (model pull will retry on first generation)"

IP="$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4 || echo '<box-public-ip>')"
echo
echo "==> Up. UI: http://$IP:8080   (make sure the security group allows 8080 from YOUR ip only)"
