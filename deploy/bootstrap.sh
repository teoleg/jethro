#!/usr/bin/env bash
# One-time node bootstrap — pass as EC2 user-data (or run once over SSM) on a fresh
# Ubuntu 24.04 instance (ADR-0013). Installs Docker + the AWS CLI, clones the repo, and
# installs a systemd unit so the compose stack comes back up after a reboot (the
# stop-when-idle schedule stops/starts the instance; the stack must survive that). The
# actual deploys are done by the GitHub Actions workflow via SSM afterwards.
set -euo pipefail

REPO_URL="${JETHRO_REPO_URL:-https://github.com/teoleg/jethro.git}"
REPO_REF="${JETHRO_REPO_REF:-claude/new-session-smb8v6}"
REPO_DIR="${JETHRO_REPO_DIR:-/opt/jethro}"

echo "==> Installing Docker Engine + compose plugin + git + AWS CLI"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl git unzip
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker

# AWS CLI v2 (the SSM agent is preinstalled on the Ubuntu AWS AMI).
if ! command -v aws >/dev/null 2>&1; then
  curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o /tmp/awscliv2.zip
  unzip -q /tmp/awscliv2.zip -d /tmp && /tmp/aws/install
fi

echo "==> Cloning $REPO_URL@$REPO_REF into $REPO_DIR"
if [ ! -d "$REPO_DIR/.git" ]; then
  git clone "$REPO_URL" "$REPO_DIR"
fi
git -C "$REPO_DIR" fetch origin "$REPO_REF"
git -C "$REPO_DIR" checkout "$REPO_REF"
git -C "$REPO_DIR" reset --hard "origin/$REPO_REF"

echo "==> Installing the jethro systemd unit (restores the stack on reboot)"
cat >/etc/systemd/system/jethro.service <<UNIT
[Unit]
Description=Jethro trading platform (docker compose)
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=$REPO_DIR
# Only bring the stack UP on boot; deploys (pull + up with a new image) go through SSM.
# APP_IMAGE + secrets live in deploy/.env, written by remote-deploy.sh on the first deploy.
ExecStart=/usr/bin/docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d
ExecStop=/usr/bin/docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env down
TimeoutStartSec=0

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable jethro.service

echo "==> Bootstrap complete. Trigger the GitHub Actions 'Deploy' workflow to roll out the"
echo "    first image + secrets; the systemd unit will then restore the stack on reboots."
