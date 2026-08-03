#!/usr/bin/env bash
# One-time: install the headless-browser stack for automatic EMMA fetch (ADR-0015 Phase 2).
#   Chromium (system) + Node puppeteer-core (drives it, waits for network-idle).
set -euo pipefail
cd "$(dirname "$0")/../.."
echo "==> installing chromium + node"
sudo apt-get update -y
sudo apt-get install -y chromium-browser nodejs npm || sudo apt-get install -y chromium nodejs npm
echo "==> installing puppeteer-core"
npm --prefix muni-world/scripts install --no-audit --no-fund
BIN="$(command -v chromium-browser || command -v chromium || echo chromium-browser)"
echo "==> done. In local.env set:"
echo "    MUNI_EMMA_AUTO=true"
echo "    MUNI_BROWSER_BIN=$BIN"
echo "Then: ./scripts/svc.sh restart muni   (and click 'Load latest from EMMA')"
