#!/usr/bin/env bash
#
# Build (no tests), bring up Docker infra, and start the app — one command.
# Designed for constrained hardware (e.g. Raspberry Pi): builds the boot jar without
# the test suite, runs the app as a plain JVM (no resident Gradle daemon), and by
# default leaves the local SLM (Ollama) OFF so the market path + UI come up light.
#
# Usage:
#   ./scripts/run-local.sh                 # app + Redpanda + Postgres, AI off
#   AI=on ./scripts/run-local.sh           # also start Ollama and enable commentary
#   PROFILE=default HEAP=1g ./scripts/run-local.sh
#
# Env knobs: PROFILE (default: pi), HEAP (default: 512m), AI (off|on, default: off),
#            MODEL (default: qwen2.5:0.5b)
#
set -euo pipefail
cd "$(dirname "$0")/.."

PROFILE="${PROFILE:-pi}"
HEAP="${HEAP:-512m}"
AI="${AI:-off}"
MODEL="${MODEL:-qwen2.5:0.5b}"

wait_for() {  # name, timeout_seconds, command...
  local name="$1" timeout="$2"; shift 2
  local deadline=$(( $(date +%s) + timeout ))
  printf '==> Waiting for %s' "$name"
  until "$@" >/dev/null 2>&1; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo " — TIMEOUT after ${timeout}s"; return 1
    fi
    printf '.'; sleep 2
  done
  echo " ready"
}

echo "==> Building app jar (tests skipped — CI is the test gate)…"
./gradlew :app:bootJar --console=plain
./gradlew --stop >/dev/null 2>&1 || true   # free the build daemon before running the app
JAR="$(ls -t app/build/libs/*.jar 2>/dev/null | grep -v -- '-plain' | head -1)"
if [ -z "$JAR" ]; then echo "!! no boot jar found in app/build/libs"; exit 1; fi
echo "==> Built: $JAR"

echo "==> Starting infrastructure…"
if [ "$AI" = "on" ]; then
  docker compose up -d redpanda postgres ollama
else
  docker compose up -d redpanda postgres
fi

wait_for "Postgres"  120 docker compose exec -T postgres pg_isready -U jethro -d jethro
wait_for "Redpanda"  120 docker compose exec -T redpanda rpk cluster health --exit-when-healthy

AI_ARGS=(--jethro.ai.enabled=false)
if [ "$AI" = "on" ]; then
  echo "==> Pulling model $MODEL (first run downloads it)…"
  docker compose exec -T ollama ollama pull "$MODEL"
  AI_ARGS=(--jethro.ai.model="$MODEL")
fi

echo "==> Starting app: profile=$PROFILE heap=$HEAP ai=$AI"
echo "==> UI at http://localhost:8080  (Ctrl+C to stop the app; 'scripts/stop-local.sh' stops Docker)"
exec java -Xmx"$HEAP" -XX:+UseZGC \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -jar "$JAR" \
  --spring.profiles.active="$PROFILE" "${AI_ARGS[@]}"
