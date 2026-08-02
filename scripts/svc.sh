#!/usr/bin/env bash
# Start/stop/restart the local stack from ONE place — core jethro (app + infra) AND muni-world, plus the
# muni-world TV audio capture. Config lives in ONE file: local.env (copy from local.env.example),
# sourced below. `docker compose stop` keeps the container AND its volume, so restarts are fast.
#
#   scripts/svc.sh restart app        # rebuild + restart just the app; LLM + DB keep running
#   scripts/svc.sh stop app           # stop the app, leave everything else up
#   scripts/svc.sh start postgres     # start just Postgres
#   scripts/svc.sh start muni         # build + start the muni-world service (independent, :8090)
#   scripts/svc.sh restart muni       # rebuild + restart muni-world
#   scripts/svc.sh tv setup           # one-time: install whisper.cpp/ffmpeg, fill audio config (Pi)
#   scripts/svc.sh tv start           # turn TV audio capture ON and (re)start muni-world
#   scripts/svc.sh tv stop            # turn capture OFF (muni-world keeps running)
#   scripts/svc.sh tv status          # capture flags + recent leads
#   scripts/svc.sh status             # what's up
#
# Targets: app | muni | tv | ollama | postgres | redpanda | infra (the 3 containers) | all  (default: all)
# muni-world is INDEPENDENT (own jar/port); it joins `all` only when MUNI_AUTOSTART=true in local.env.
set -euo pipefail
cd "$(dirname "$0")/.."

# --- the single config source: local.env (the SAME file run-local.sh uses) — export it so the launched
# JVMs inherit it. Same safe line-parse as run-local.sh; command-line env still wins (applied only if unset).
ENV_FILE="local.env"
if [ -f "$ENV_FILE" ]; then
  while IFS='=' read -r k v; do
    k="${k//[[:space:]]/}"; case "$k" in ''|\#*) continue;; esac
    v="${v%$'\r'}"
    [ -z "${!k:-}" ] && export "$k=$v"
  done < "$ENV_FILE"
fi

ACTION="${1:-status}"
TARGET="${2:-all}"
INFRA="redpanda postgres ollama"
PIDFILE="logs/jethro-app.pid"
MUNI_PIDFILE="logs/muni-world.pid"
MUNI_JAR="muni-world/build/libs/muni-world.jar"
MUNI_PORT="${MUNI_PORT:-8090}"

# Set/replace KEY=VALUE in local.env (creating it from local.env.example if needed) — used by `tv start|stop`
# so a capture toggle is DURABLE across restarts, not just for one invocation.
ensure_env_file() {
  [ -f "$ENV_FILE" ] || { [ -f "local.env.example" ] && cp "local.env.example" "$ENV_FILE" \
    && echo "==> created $ENV_FILE from local.env.example"; }
}
set_env_kv() {
  ensure_env_file
  local k="$1" v="$2"
  if [ -f "$ENV_FILE" ] && grep -qE "^${k}=" "$ENV_FILE"; then
    sed -i.bak -E "s|^${k}=.*|${k}=${v}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
  else
    echo "${k}=${v}" >> "$ENV_FILE"
  fi
  echo "==> set ${k}=${v} in $ENV_FILE"
}

app_running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2>/dev/null || echo 0)" 2>/dev/null; }
app_stop() {
  if app_running; then
    local pid; pid="$(cat "$PIDFILE")"
    echo "==> stopping app (pid $pid)"
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 10); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$PIDFILE"
  else
    echo "==> app not running"
    rm -f "$PIDFILE"
  fi
}
app_start() { echo "==> starting app (infra left as-is: LLM + DB keep running)"; ./scripts/run-local.sh; }

# --- muni-world: an INDEPENDENT jar on its own port (:8090). Same start/stop discipline as the app:
# stop the running JVM BEFORE rebuilding the jar (run-local's caveat applies — Spring Boot loads classes
# lazily out of build/libs, so overwriting the jar under a live process corrupts its classloader).
muni_running() { [ -f "$MUNI_PIDFILE" ] && kill -0 "$(cat "$MUNI_PIDFILE" 2>/dev/null || echo 0)" 2>/dev/null; }
muni_stop() {
  if muni_running; then
    local pid; pid="$(cat "$MUNI_PIDFILE")"
    echo "==> stopping muni-world (pid $pid)"
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 10); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$MUNI_PIDFILE"
  else
    echo "==> muni-world not running"
    rm -f "$MUNI_PIDFILE"
  fi
}
muni_start() {
  if muni_running; then echo "==> muni-world already running (pid $(cat "$MUNI_PIDFILE"))"; return; fi
  echo "==> building muni-world jar"
  ./gradlew -q :muni-world:bootJar
  mkdir -p logs
  local cap="${MUNI_AUDIO_CAPTURE:-false}"
  echo "==> starting muni-world on :$MUNI_PORT (independent; boots offline — PG/Kafka opt-in; TV capture=$cap)"
  # lmdbjava (JNR) needs the NIO opens, same as the jethro app. The MUNI_*/audio env is inherited from
  # local.env (exported above), so the capture loop reads its config with no extra plumbing here.
  nohup java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    -jar "$MUNI_JAR" > logs/muni-world.log 2>&1 &
  echo $! > "$MUNI_PIDFILE"
  echo "==> muni-world pid $(cat "$MUNI_PIDFILE") — logs/muni-world.log — http://localhost:$MUNI_PORT/"
}

# --- TV audio capture (ADR-0014): the capture loop is a bean INSIDE muni-world, gated by MUNI_AUDIO_CAPTURE.
# So "TV control" = flip that flag durably in local.env, then bounce muni-world to pick it up.
tv_setup() {
  ensure_env_file
  echo "==> muni-world audio setup (installs whisper.cpp/ffmpeg, finds the loopback)"
  # pass the env file so the setup script WRITES MUNI_WHISPER_BIN/MODEL into it (no more empty vars)
  MUNI_ENV_FILE="$ENV_FILE" bash muni-world/scripts/setup-audio-pi.sh
  # make sure the registry pointer is set too
  grep -qE "^MUNI_AUDIO_SOURCES_FILE=" "$ENV_FILE" 2>/dev/null || set_env_kv MUNI_AUDIO_SOURCES_FILE muni-world/seeds/audio-sources.csv
  echo "==> whisper paths written to $ENV_FILE. Next: bind a device + enable a feed in"
  echo "    muni-world/seeds/audio-sources.csv (the registry), then: scripts/svc.sh tv start"
}
tv_start() {
  set_env_kv MUNI_AUDIO_CAPTURE true
  export MUNI_AUDIO_CAPTURE=true
  if [ -z "${MUNI_WHISPER_MODEL:-}" ]; then
    echo "!!  MUNI_WHISPER_MODEL is empty in $ENV_FILE — run 'scripts/svc.sh tv setup' and set it,"
    echo "!!  or capture will error every cycle (it fails loudly, never invents a transcript)."
  fi
  echo "==> TV capture ON — bouncing muni-world. Feeds come from the registry"
  echo "    (${MUNI_AUDIO_SOURCES_FILE:-classpath default}); enable + bind a device there, then 'tv status'."
  muni_stop; muni_start
}
tv_stop() {
  set_env_kv MUNI_AUDIO_CAPTURE false
  export MUNI_AUDIO_CAPTURE=false
  echo "==> TV capture OFF — bouncing muni-world (service stays up, just without the capture loop)"
  muni_stop; muni_start
}
tv_status() {
  muni_running && echo "muni-world: RUNNING (pid $(cat "$MUNI_PIDFILE"))" || echo "muni-world: stopped"
  echo "master: MUNI_AUDIO_CAPTURE=${MUNI_AUDIO_CAPTURE:-false} model=${MUNI_WHISPER_MODEL:-unset} registry=${MUNI_AUDIO_SOURCES_FILE:-classpath default}"
  if muni_running; then
    grep -q "audio capture ENABLED" logs/muni-world.log 2>/dev/null \
      && echo "capture loop: ENABLED in the running process" || echo "capture loop: not enabled in the running process"
    echo "feed registry:"; curl -fsS "http://localhost:${MUNI_PORT}/api/muni/audio/sources" 2>/dev/null || echo "  (unreachable)"; echo
    echo "recent leads:"; curl -fsS "http://localhost:${MUNI_PORT}/api/muni/audio/leads/recent?limit=5" 2>/dev/null || echo "  (none / unreachable)"; echo
  fi
}

# `tv` is a sub-namespace: `svc.sh tv <setup|start|stop|restart|status>` (also accepts `start tv`, etc.).
# Handled here so `tv status` isn't shadowed by the general `status:*` below.
if [ "$ACTION" = "tv" ]; then
  case "$TARGET" in
    setup)      tv_setup ;;
    start)      tv_start ;;
    stop)       tv_stop ;;
    restart)    tv_start ;;
    status|all) tv_status ;;   # bare `svc.sh tv` → status
    *) echo "usage: scripts/svc.sh tv <setup|start|stop|restart|status>"; exit 1 ;;
  esac
  echo "==> done."; exit 0
fi

case "$ACTION:$TARGET" in
  status:*)
    docker compose ps || true
    app_running && echo "app: RUNNING (pid $(cat "$PIDFILE"))" || echo "app: stopped"
    muni_running && echo "muni-world: RUNNING (pid $(cat "$MUNI_PIDFILE"))" || echo "muni-world: stopped"
    echo "TV capture flag: MUNI_AUDIO_CAPTURE=${MUNI_AUDIO_CAPTURE:-false} (see 'svc.sh tv status')" ;;

  stop:app)      ./scripts/backup-db.sh || true; app_stop ;;
  start:app)     app_start ;;
  restart:app)   app_stop; app_start ;;                         # DB stays up; no dump needed

  # muni-world — independent service (its own jar/port); stop before rebuild (see muni_start note).
  stop:muni)     muni_stop ;;
  start:muni)    muni_start ;;
  restart:muni)  muni_stop; muni_start ;;
  deploy:muni)   muni_stop; muni_start ;;

  # TV audio capture (ADR-0014) — drives the capture loop inside muni-world via local.env.
  setup:tv)      tv_setup ;;
  start:tv)      tv_start ;;
  stop:tv)       tv_stop ;;
  restart:tv)    tv_start ;;                                    # tv_start already bounces muni-world
  status:tv)     tv_status ;;

  # THE loop's deploy command (JETHRO_DEPLOY_CMD). Rebuild + restart SAFELY, in the only correct order:
  # STOP the running JVM first, THEN rebuild the jar (run-local.sh builds it), THEN start.
  # NEVER `gradlew :app:bootJar` against a live app: run-local runs the jar straight out of
  # app/build/libs and the Spring Boot loader loads classes LAZILY from it, so overwriting the jar
  # under the running JVM corrupts its classloader — every not-yet-loaded class then throws
  # ClassNotFoundException (the UI dies while trading-core limps on). app_stop kills the old JVM before
  # app_start (run-local) rebuilds, so the jar is never swapped under a live process.
  deploy:app)    app_stop; app_start ;;

  stop:infra)    ./scripts/backup-db.sh || true; docker compose stop $INFRA ;;
  start:infra)   docker compose up -d $INFRA ;;
  restart:infra) docker compose restart $INFRA ;;

  # `all` includes muni-world only when MUNI_AUTOSTART=true in local.env (TV follows its own capture flag).
  stop:all)      ./scripts/backup-db.sh || true; app_stop; muni_stop; docker compose stop $INFRA ;;
  start:all)     app_start; [ "${MUNI_AUTOSTART:-false}" = "true" ] && muni_start || true ;;
  restart:all)   app_stop; docker compose restart $INFRA; app_start
                 [ "${MUNI_AUTOSTART:-false}" = "true" ] && { muni_stop; muni_start; } || true ;;

  stop:postgres) ./scripts/backup-db.sh || true; docker compose stop postgres ;;
  stop:ollama|stop:redpanda)                        docker compose stop "$TARGET" ;;
  start:ollama|start:postgres|start:redpanda)       docker compose up -d "$TARGET" ;;
  restart:ollama|restart:postgres|restart:redpanda) docker compose restart "$TARGET" ;;

  *) echo "usage: scripts/svc.sh <start|stop|restart|deploy|setup|status> [app|muni|tv|ollama|postgres|redpanda|infra|all]"; exit 1 ;;
esac
echo "==> done."
