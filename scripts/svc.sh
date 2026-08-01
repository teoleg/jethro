#!/usr/bin/env bash
# Start/stop/restart individual parts of the local stack — so you can bounce the app without
# tearing down Ollama + Postgres (model stays warm, DB stays intact). `docker compose stop` keeps
# the container AND its volume, so a restart is fast: no re-pull, no data loss.
#
#   scripts/svc.sh restart app        # rebuild + restart just the app; LLM + DB keep running
#   scripts/svc.sh stop app           # stop the app, leave everything else up
#   scripts/svc.sh stop ollama        # stop just Ollama (model volume preserved)
#   scripts/svc.sh start postgres     # start just Postgres
#   scripts/svc.sh start muni         # build + start the muni-world service (independent, :8090)
#   scripts/svc.sh stop muni          # stop muni-world, leave everything else up
#   scripts/svc.sh status             # what's up
#
# Targets: app | muni | ollama | postgres | redpanda | infra (the 3 containers) | all   (default: all)
# muni-world is an INDEPENDENT service (its own jar/port) — its own target, not part of `all` start.
set -euo pipefail
cd "$(dirname "$0")/.."

ACTION="${1:-status}"
TARGET="${2:-all}"
INFRA="redpanda postgres ollama"
PIDFILE="logs/jethro-app.pid"
MUNI_PIDFILE="logs/muni-world.pid"
MUNI_JAR="muni-world/build/libs/muni-world.jar"
MUNI_PORT="${MUNI_PORT:-8090}"

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
  echo "==> starting muni-world on :$MUNI_PORT (independent service; boots offline — PG/Kafka opt-in)"
  # lmdbjava (JNR) needs the NIO opens, same as the jethro app.
  nohup java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    -jar "$MUNI_JAR" > logs/muni-world.log 2>&1 &
  echo $! > "$MUNI_PIDFILE"
  echo "==> muni-world pid $(cat "$MUNI_PIDFILE") — logs/muni-world.log — http://localhost:$MUNI_PORT/"
}

case "$ACTION:$TARGET" in
  status:*)
    docker compose ps || true
    app_running && echo "app: RUNNING (pid $(cat "$PIDFILE"))" || echo "app: stopped"
    muni_running && echo "muni-world: RUNNING (pid $(cat "$MUNI_PIDFILE"))" || echo "muni-world: stopped" ;;

  stop:app)      ./scripts/backup-db.sh || true; app_stop ;;
  start:app)     app_start ;;
  restart:app)   app_stop; app_start ;;                         # DB stays up; no dump needed

  # muni-world — independent service (its own jar/port); stop before rebuild (see muni_start note).
  stop:muni)     muni_stop ;;
  start:muni)    muni_start ;;
  restart:muni)  muni_stop; muni_start ;;
  deploy:muni)   muni_stop; muni_start ;;

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

  stop:all)      ./scripts/backup-db.sh || true; app_stop; muni_stop; docker compose stop $INFRA ;;
  start:all)     app_start ;;                                  # run-local brings up infra + app (muni is opt-in: `start muni`)
  restart:all)   app_stop; docker compose restart $INFRA; app_start ;;

  stop:postgres) ./scripts/backup-db.sh || true; docker compose stop postgres ;;
  stop:ollama|stop:redpanda)                        docker compose stop "$TARGET" ;;
  start:ollama|start:postgres|start:redpanda)       docker compose up -d "$TARGET" ;;
  restart:ollama|restart:postgres|restart:redpanda) docker compose restart "$TARGET" ;;

  *) echo "usage: scripts/svc.sh <start|stop|restart|deploy|status> [app|muni|ollama|postgres|redpanda|infra|all]"; exit 1 ;;
esac
echo "==> done."
