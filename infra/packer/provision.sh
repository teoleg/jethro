#!/usr/bin/env bash
# Runs INSIDE the Packer build box to bake the Jethro stack into the AMI. Installs Docker,
# unpacks the uploaded source, builds the app image, pre-pulls the base images and the Ollama
# model, and installs a systemd unit so an instance launched from the AMI boots straight into
# the running stack. Everything is captured in the EBS snapshot, so runtime needs no network
# for images/model — fast, self-contained boots.
set -euo pipefail

AI_MODEL="${AI_MODEL:-qwen2.5:1.5b}"
export DEBIAN_FRONTEND=noninteractive

echo "==> Installing Docker"
sudo apt-get update -y
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable docker

echo "==> Unpacking source to /opt/jethro"
sudo mkdir -p /opt/jethro
sudo tar -xzf /tmp/jethro-src.tgz -C /opt/jethro
cd /opt/jethro

COMPOSE="sudo docker compose -f docker-compose.yml -f deploy/quickstart.override.yml"

echo "==> Building the app image and pulling base images"
$COMPOSE --profile app build
$COMPOSE pull redpanda postgres ollama

echo "==> Baking the Ollama model ($AI_MODEL) into the image"
sudo docker compose up -d ollama
for i in $(seq 1 20); do
  if sudo docker compose exec -T ollama ollama list >/dev/null 2>&1; then break; fi
  sleep 3
done
sudo docker compose exec -T ollama ollama pull "$AI_MODEL"
sudo docker compose down

echo "==> Installing the boot-time systemd unit"
sudo tee /etc/systemd/system/jethro.service >/dev/null <<'UNIT'
[Unit]
Description=Jethro trading platform (baked AMI)
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/jethro
ExecStart=/usr/bin/docker compose -f docker-compose.yml -f deploy/quickstart.override.yml --profile app up -d
ExecStop=/usr/bin/docker compose -f docker-compose.yml -f deploy/quickstart.override.yml --profile app down
TimeoutStartSec=0

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable jethro.service

echo "==> Trimming build cache (keep the baked images + model)"
sudo docker builder prune -f || true

echo "==> Bake complete. An instance from this AMI boots the full stack on :8080."
