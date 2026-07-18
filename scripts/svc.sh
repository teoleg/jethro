#!/usr/bin/env bash
# Start/stop/restart individual parts of the local stack — so you can bounce the app without
# tearing down Ollama + Postgres (model stays warm, DB stays intact). `docker compose stop` keeps
# the container AND its volume, so a restart is fast: no re-pull, no data loss.
#
#   scripts/svc.sh restart app        # rebuild + restart just the app; LLM + DB keep running
#   scripts/svc.sh stop app           # stop the app, leave everything else up
#   scripts/svc.sh stop ollama        # stop just Ollama (model volume preserved)
#   scripts/svc.sh start postgres     # start just Postgres
#   scripts/svc.sh status             # what's up
#
# Targets: app | ollama | postgres | redpanda | infra (the 3 containers) | all   (default: all)
set -euo pipefail
cd "$(dirname "$0")/.."

ACTION="${1:-status}"
TARGET="${2:-all}"
INFRA="redpanda postgres ollama"
PIDFILE="logs/jethro-app.pid"

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

case "$ACTION:$TARGET" in
  status:*)
    docker compose ps || true
    app_running && echo "app: RUNNING (pid $(cat "$PIDFILE"))" || echo "app: stopped" ;;

  stop:app)      app_stop ;;
  start:app)     app_start ;;
  restart:app)   app_stop; app_start ;;

  stop:infra)    docker compose stop $INFRA ;;
  start:infra)   docker compose up -d $INFRA ;;
  restart:infra) docker compose restart $INFRA ;;

  stop:all)      app_stop; docker compose stop $INFRA ;;
  start:all)     app_start ;;                                  # run-local brings up infra + app
  restart:all)   app_stop; docker compose restart $INFRA; app_start ;;

  stop:ollama|stop:postgres|stop:redpanda)          docker compose stop "$TARGET" ;;
  start:ollama|start:postgres|start:redpanda)       docker compose up -d "$TARGET" ;;
  restart:ollama|restart:postgres|restart:redpanda) docker compose restart "$TARGET" ;;

  *) echo "usage: scripts/svc.sh <start|stop|restart|status> [app|ollama|postgres|redpanda|infra|all]"; exit 1 ;;
esac
echo "==> done."
