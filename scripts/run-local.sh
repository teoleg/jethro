#!/usr/bin/env bash
#
# Build (no tests), bring up Docker infra, and start the app in the background — one
# command. Designed for constrained hardware (e.g. Raspberry Pi): builds the boot jar
# without the test suite, runs the app as a plain detached JVM (nohup, no resident
# Gradle daemon). By default the local SLM (Ollama) commentary AND simulated
# auto-execution are ON so the whole system runs itself. The app keeps running after you
# close the terminal; logs go to logs/jethro-app.log. Stop it with scripts/stop-local.sh.
#
# Usage:
#   ./scripts/run-local.sh                       # everything on (AI + auto-execute)
#   AI=off AUTOEXEC=off ./scripts/run-local.sh   # market path + UI only, no AI, no trading
#   MODEL=qwen2.5:0.5b ./scripts/run-local.sh    # lighter model for tight RAM (weaker text)
#   PROFILE=default HEAP=1g ./scripts/run-local.sh
#
# Env knobs: PROFILE (default: pi), HEAP (default: 512m), AI (off|on, default: on),
#            AUTOEXEC (off|on, default: on), MODEL (default: qwen2.5:3b)
#
set -euo pipefail
cd "$(dirname "$0")/.."

PROFILE="${PROFILE:-pi}"
HEAP="${HEAP:-512m}"
AI="${AI:-on}"
MODEL="${MODEL:-qwen2.5:3b}"   # 3b = usable commentary; MODEL=qwen2.5:0.5b for tight RAM
AUTOEXEC="${AUTOEXEC:-on}"   # on = strategy auto-submits SIMULATED orders (ADR-0019)

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

# Postgres is a hard dependency: the app runs Flyway on startup and blocks without it.
if ! wait_for "Postgres" 120 docker compose exec -T postgres pg_isready -U jethro -d jethro; then
  echo "!! Postgres never became ready — the app needs it. Check: docker compose logs postgres"
  exit 1
fi

# Redpanda is NOT a hard dependency: the app degrades gracefully and the Kafka client
# reconnects on its own once the broker is up. So warn on timeout, don't abort.
if ! wait_for "Redpanda" 90 docker compose exec -T redpanda rpk cluster health --exit-when-healthy; then
  echo "!! Redpanda not reporting healthy yet — starting the app anyway; it will connect"
  echo "   when the broker is ready. If live marks never appear in the UI, inspect it with:"
  echo "     docker compose logs --tail 50 redpanda"
  echo "     docker compose exec redpanda rpk cluster health"
fi

AI_ARGS=(--jethro.ai.enabled=false)
if [ "$AI" = "on" ]; then
  echo "==> Pulling model $MODEL (first run downloads it)…"
  docker compose exec -T ollama ollama pull "$MODEL"
  AI_ARGS=(--jethro.ai.model="$MODEL")
fi

EXTRA_ARGS=()
if [ "$AUTOEXEC" = "on" ]; then
  echo "==> AUTO-EXECUTE ON: the strategy will auto-submit SIMULATED orders (ADR-0019)"
  EXTRA_ARGS+=(--jethro.strategy.auto-execute=true)
fi

mkdir -p logs
LOG="logs/jethro-app.log"
PIDFILE="logs/jethro-app.pid"

# Refuse to double-start: one background app at a time.
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null; then
  echo "!! app already running (pid $(cat "$PIDFILE")). Stop it first: scripts/stop-local.sh"
  exit 1
fi

echo "==> Starting app in background: profile=$PROFILE heap=$HEAP ai=$AI"
nohup java -Xmx"$HEAP" -XX:+UseZGC \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -jar "$JAR" \
  --spring.profiles.active="$PROFILE" "${AI_ARGS[@]}" "${EXTRA_ARGS[@]}" \
  >"$LOG" 2>&1 &
APP_PID=$!
echo "$APP_PID" >"$PIDFILE"
echo "==> App launched (pid $APP_PID), logs → $LOG"

# Give it a moment; if it died immediately (bad jar, port in use), say so.
sleep 2
if ! kill -0 "$APP_PID" 2>/dev/null; then
  echo "!! app exited during startup — last log lines:"; tail -n 20 "$LOG"; rm -f "$PIDFILE"; exit 1
fi

if command -v curl >/dev/null 2>&1; then
  wait_for "app UI on :8080" 90 curl -fs http://localhost:8080/api/marks || \
    echo "   (still starting — follow it with: tail -f $LOG)"
fi

echo "==> UI at http://localhost:8080"
echo "==> Follow logs:  tail -f $LOG"
echo "==> Stop app+infra: scripts/stop-local.sh   |   Wipe all data: scripts/clean-local.sh"
