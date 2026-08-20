#!/usr/bin/env bash
# One-time muni-world node bootstrap (muni ADR-0021) — passed as EC2 user-data by MuniWorldStack
# (or run once over SSM) on a fresh Ubuntu 24.04 ARM64 instance. Installs Docker + AWS CLI, clones
# the repo, installs the systemd unit that restores the stack on reboot, and the nightly backup
# timer. Actual deploys happen via the muni-deploy GitHub Actions workflow over SSM.
#
# Deliberately parallel to deploy/bootstrap.sh (the trading node) but touching NOTHING shared:
# its own /opt/muni-world checkout, its own systemd units, its own compose project.
set -euo pipefail

REPO_URL="${MUNI_REPO_URL:-https://github.com/teoleg/jethro.git}"
REPO_REF="${MUNI_REPO_REF:-master}"
REPO_DIR="${MUNI_REPO_DIR:-/opt/muni-world}"

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
git -C "$REPO_DIR" reset --hard "origin/$REPO_REF" 2>/dev/null || true

echo "==> Installing the muni-world systemd unit (restores the stack on reboot)"
cat >/etc/systemd/system/muni-world.service <<UNIT
[Unit]
Description=muni-world (docker compose)
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=$REPO_DIR
# Only bring the stack UP on boot; deploys (pull + up with a new image) go through SSM.
# MUNI_IMAGE + secrets live in muni-world/deploy/.env, written by remote-deploy.sh.
ExecStart=/usr/bin/docker compose -f muni-world/deploy/docker-compose.aws.yml --env-file muni-world/deploy/.env up -d
ExecStop=/usr/bin/docker compose -f muni-world/deploy/docker-compose.aws.yml --env-file muni-world/deploy/.env down
TimeoutStartSec=0

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable muni-world.service

echo "==> Installing the nightly backup timer (pg_dump + OS PDFs -> S3, muni ADR-0021 §3)"
cat >/etc/systemd/system/muni-backup.service <<UNIT
[Unit]
Description=muni-world nightly backup to S3

[Service]
Type=oneshot
WorkingDirectory=$REPO_DIR
ExecStart=/usr/bin/bash muni-world/deploy/backup-to-s3.sh
UNIT
cat >/etc/systemd/system/muni-backup.timer <<UNIT
[Unit]
Description=Nightly muni-world backup

[Timer]
# 07:30 UTC — after the daily ingest pass, before the US session. Not a money/risk dial.
OnCalendar=*-*-* 07:30:00
Persistent=true

[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now muni-backup.timer

echo "==> Bootstrap complete. Trigger the 'Muni Deploy' GitHub Actions workflow to roll out the"
echo "    first image + secrets; the systemd unit then restores the stack on reboots."
