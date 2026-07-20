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
#   MODEL=qwen2.5:3b ./scripts/run-local.sh      # stronger text (needs 8GB+ headroom)
#   PROVIDER=yahoo ./scripts/run-local.sh        # real (delayed) prices from Yahoo (ADR-0023)
#   PROFILE=default HEAP=1g ./scripts/run-local.sh
#
# Env knobs: PROFILE (default: pi), HEAP (default: 512m), AI (off|on, default: on),
#            AUTOEXEC (off|on, default: on), MODEL (default: qwen2.5:1.5b),
#            PROVIDER (sim|yahoo|finnhub, default: yahoo), AUTONOMY (off|on, default: on),
#            RAG (off|on, default: on). Set them once in local.env — a plain KEY=value file
#            (copy local.env.example); command-line env still overrides it.
#
# AUTOEXEC  = the momentum STRATEGY auto-submits simulated orders (ADR-0019).
# AUTONOMY  = the LLM's HYPOTHESES auto-execute, but only within the deterministic risk
#             envelope (admissible + backtest-supported + conviction>=min + notional<=cap +
#             whitelist), ADR-0022. Off by default; simulated only, never a real broker.
#
set -euo pipefail
cd "$(dirname "$0")/.."

# Optional local config: copy local.env.example → local.env, a plain KEY=value file.
# Command-line env still wins (each key is applied only when it isn't already set).
if [ -f local.env ]; then
  while IFS='=' read -r k v; do
    k="${k//[[:space:]]/}"; case "$k" in ''|\#*) continue;; esac
    v="${v%$'\r'}"
    [ -z "${!k:-}" ] && export "$k=$v"
  done < local.env
fi

PROFILE="${PROFILE:-pi}"
# 512m was too tight: the hourly OOS strategy-selector backtest is a large TRANSIENT allocation
# spike, and on a 512m ZGC heap it drove allocation stalls that froze the whole JVM for the run's
# duration (~1h cadence). 768m gives that spike headroom while staying Pi-friendly alongside a 1.5b
# Ollama model. A pre-run heap guard (StrategySelector) is the backstop — it SKIPS the backtest when
# headroom is thin rather than freezing. On an 8GB+ box set HEAP=1g so the selector always refreshes.
HEAP="${HEAP:-768m}"
AI="${AI:-on}"
MODEL="${MODEL:-qwen2.5:1.5b}" # 1.5b fits a Pi (frees ~1.5GB + CPU vs 3b, so you stay out of swap).
                               # MODEL=qwen2.5:3b for better text on an 8GB+ box; :0.5b for very tight RAM.
RAG="${RAG:-on}"               # on = RAG retrieval (ADR-0035); needs the embedding model below.
EMBED_MODEL="${EMBED_MODEL:-nomic-embed-text}" # RAG embeddings (~275MB); the chat MODEL can't embed.
AUTOEXEC="${AUTOEXEC:-on}"   # on = strategy auto-submits SIMULATED orders (ADR-0019)
AUTONOMY="${AUTONOMY:-on}"   # on = LLM hypotheses auto-execute within the risk envelope (ADR-0022)
PROVIDER="${PROVIDER:-yahoo}"  # sim | yahoo (delayed, ADR-0023) | finnhub (real-time WS, ADR-0024)
FINNHUB="${FINNHUB:-}"         # Finnhub API token (free at finnhub.io); needed for PROVIDER=finnhub

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

AI_ARGS=(--jethro.ai.enabled=false --jethro.rag.enabled=false) # no Ollama ⇒ no RAG either
if [ "$AI" = "on" ]; then
  echo "==> Pulling model $MODEL (first run downloads it)…"
  docker compose exec -T ollama ollama pull "$MODEL"
  AI_ARGS=(--jethro.ai.model="$MODEL")
  if [ "$RAG" != "off" ] && [ -n "$EMBED_MODEL" ]; then
    echo "==> Pulling embedding model $EMBED_MODEL for RAG (ADR-0035; the chat model can't embed)…"
    docker compose exec -T ollama ollama pull "$EMBED_MODEL" \
      || echo "   WARN: embed pull failed — RAG degrades to the deterministic guard until it's present"
  else
    AI_ARGS+=(--jethro.rag.enabled=false)
  fi
fi

EXTRA_ARGS=()
if [ "$AUTOEXEC" = "on" ]; then
  echo "==> AUTO-EXECUTE ON: the strategy will auto-submit SIMULATED orders (ADR-0019)"
  EXTRA_ARGS+=(--jethro.strategy.auto-execute=true)
fi
if [ "$AUTONOMY" = "on" ]; then
  echo "==> BOUNDED AUTONOMY ON: LLM hypotheses inside the risk envelope auto-execute as SIMULATED orders (ADR-0022)"
  EXTRA_ARGS+=(--jethro.hypothesis.autonomy.enabled=true)
fi
EXTRA_ARGS+=(--jethro.trading.provider="$PROVIDER")
# Sim time compression: wall-seconds per simulated trading day. Unset = app default (23400 =
# real time, the steady watchable tape). SIM_DAY_SECONDS=120 fast-cycles days for EOD/VaR work.
if [ -n "${SIM_DAY_SECONDS:-}" ]; then
  EXTRA_ARGS+=(--jethro.trading.sim-seconds-per-day="$SIM_DAY_SECONDS")
  echo "==> SIM TIME: $SIM_DAY_SECONDS wall-seconds per trading day"
fi
if [ "$PROVIDER" = "yahoo" ]; then
  echo "==> MARKET DATA: Yahoo (real, ~15-min delayed, dev/demo only — ADR-0023). Needs internet."
fi
if [ "$PROVIDER" = "finnhub" ] && [ -z "$FINNHUB" ]; then
  echo "!! PROVIDER=finnhub needs a token: FINNHUB=your_key PROVIDER=finnhub ./scripts/run-local.sh"
  echo "   (free key at https://finnhub.io) — falling back to sim until set."
fi
# A token enables the real-time WS feed (PROVIDER=finnhub) AND — independent of the price
# provider — real news + a LIVE US Treasury yield curve (ADR-0024). All share one 60/min budget.
if [ -n "$FINNHUB" ]; then
  EXTRA_ARGS+=(--jethro.trading.finnhub-token="$FINNHUB")
  [ "$PROVIDER" = "finnhub" ] && echo "==> MARKET DATA: Finnhub (real-time WebSocket, dev/demo only — ADR-0024)."
  echo "==> Finnhub key set: real news + live Treasury curve enabled (bond data may be premium —"
  echo "    check the log line 'RATES CURVE:' to see if the live curve or the sim curve is active)."
fi
# Tiingo history seed (ADR-0038): grounds the hedger covariance in REAL daily history (even in sim).
# Auto-exported from local.env above, so the app reads jethro.hedge.tiingo-token=${TIINGO_API_TOKEN:}.
if [ -n "${TIINGO_API_TOKEN:-}" ]; then
  echo "==> HISTORY SEED: Tiingo token set — real daily history for the hedger covariance (dev/demo, ADR-0023)."
else
  echo "==> HISTORY SEED: no TIINGO_API_TOKEN — covariance warms from the live feed. Set it in local.env for real history."
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
