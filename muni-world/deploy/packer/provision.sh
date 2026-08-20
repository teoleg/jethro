#!/usr/bin/env bash
# Runs INSIDE the Packer build box to bake muni-world into the AMI (muni ADR-0021 — the same
# immutable path as infra/packer/provision.sh for jethro). Installs Docker + AWS CLI, unpacks the
# source, builds the muni image (multi-stage: gradle inside docker), pulls postgres, and installs:
#   * muni-world.service   — boots the compose stack, AFTER restore-latest (state survives replace)
#   * muni-backup.timer    — nightly pg_dump + OS-PDF inbox to the account's muni backup bucket
# Everything is in the EBS snapshot: an instance launched from this AMI needs no build, no registry.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

echo "==> Installing Docker + AWS CLI"
sudo apt-get update -y
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable docker
sudo apt-get install -y unzip
curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp && sudo /tmp/aws/install

echo "==> Unpacking source to /opt/muni-world"
sudo mkdir -p /opt/muni-world
sudo tar -xzf /tmp/muni-src.tgz -C /opt/muni-world
cd /opt/muni-world

echo "==> Building the muni-world image (gradle build runs inside docker)"
sudo docker build -f muni-world/deploy/Dockerfile -t muni-world:local .
sudo docker pull postgres:16-alpine

echo "==> Writing /opt/muni-world/muni.env (SEC contact from the bake variable — never in git)"
sudo tee /opt/muni-world/muni.env >/dev/null <<ENV
MUNI_CONTACT_EMAIL=${MUNI_CONTACT_EMAIL:-}
ENV
sudo chmod 600 /opt/muni-world/muni.env

echo "==> Installing systemd units"
sudo tee /etc/systemd/system/muni-world.service >/dev/null <<'UNIT'
[Unit]
Description=muni-world (baked AMI)
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/muni-world
EnvironmentFile=/opt/muni-world/muni.env
# State survives instance replacement: before first start, pull the newest DB dump + OS PDFs
# from the backup bucket (no-op on an already-restored volume or an empty bucket).
ExecStartPre=/usr/bin/bash muni-world/deploy/restore-latest.sh
ExecStart=/usr/bin/docker compose -f muni-world/deploy/docker-compose.aws.yml up -d
ExecStop=/usr/bin/docker compose -f muni-world/deploy/docker-compose.aws.yml down
TimeoutStartSec=900

[Install]
WantedBy=multi-user.target
UNIT

sudo tee /etc/systemd/system/muni-backup.service >/dev/null <<'UNIT'
[Unit]
Description=muni-world nightly backup to S3

[Service]
Type=oneshot
WorkingDirectory=/opt/muni-world
ExecStart=/usr/bin/bash muni-world/deploy/backup-to-s3.sh
UNIT

sudo tee /etc/systemd/system/muni-backup.timer >/dev/null <<'UNIT'
[Unit]
Description=Nightly muni-world backup

[Timer]
# 07:30 UTC — after the daily ingest pass. An ops schedule, not a money/risk dial.
OnCalendar=*-*-* 07:30:00
Persistent=true

[Install]
WantedBy=timers.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable muni-world.service muni-backup.timer

echo "==> Bake complete — instances from this AMI boot straight into the stack."
