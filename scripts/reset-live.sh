#!/usr/bin/env bash
# Reset ONLY the LIVE (paper) book to zero — start the live epoch flat, leaving SIM/REPLAY untouched.
#
# Same mechanism as reset-sim.sh (invariant 8 / ADR-0029): sim/live/replay are separated by feed_mode,
# so this deletes exactly the feed_mode='LIVE' rows from every feed_mode-scoped table. There is NO
# positions table — positions are rebuilt from `fills` at boot — so once LIVE fills are gone and the app
# restarts, the live book is flat and total PnL is 0. SIM/REPLAY rows, the reference-data universe, and
# book masters are NOT touched. Everything here is PAPER (ADR-0061); no real broker is involved.
#
# Use this to start a clean live trading day after the book picked up positions you don't want to carry
# (e.g. after-hours opens before the ADR-0115 session gate was live).
#
#   scripts/reset-live.sh              # confirm, stop app, wipe LIVE rows, restart flat
#   scripts/reset-live.sh --yes        # no confirmation prompt
#   scripts/reset-live.sh --no-restart # leave the app stopped afterwards
#
# Stopping the app first triggers scripts/backup-db.sh (a full dump), so you have a restore point.
# Turn the improvement loop OFF first if you want to be sure nothing races the reset:
#   ops/loop-control.sh off   (re-enable after)
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

ASSUME_YES=0; RESTART=1
for a in "$@"; do case "$a" in
  --yes|-y) ASSUME_YES=1 ;;
  --no-restart) RESTART=0 ;;
  *) echo "usage: $0 [--yes] [--no-restart]"; exit 2 ;;
esac; done

# Book/trading/performance tables, all feed_mode-scoped. Deleting LIVE rows zeroes the live paper book
# and its analytics without affecting sim/replay. (Same set as reset-sim.sh.)
TABLES=(fills orders execution_quality swap_trades signal_observations hypothesis_record chat_audit firm_equity book_equity)

psql() { docker compose exec -T postgres psql -U jethro -d jethro "$@"; }

echo "==> Reset the LIVE (paper) book to zero (feed_mode='LIVE' only). SIM/REPLAY is NOT touched."
echo "    Tables: ${TABLES[*]}"
echo "    Also clears the loop's live heartbeat/baseline so the Improve page starts clean:"
echo "      reports/run-status.json, reports/.pending-baseline.json, reports/attribution/*"
echo "    NOT touched: reference-data universe, book masters, sim/replay rows, the improvement LEDGER + findings."

if ! psql -c 'select 1' >/dev/null 2>&1; then
  echo "ERROR: cannot reach Postgres via 'docker compose exec postgres'. Is the DB up? Aborting." >&2
  exit 1
fi

if [ "$ASSUME_YES" -eq 0 ]; then
  printf "\nType exactly 'reset-live' to proceed: "
  read -r reply
  [ "$reply" = "reset-live" ] || { echo "aborted — nothing deleted."; exit 0; }
fi

# 1. Stop the app so nothing writes mid-reset (svc.sh dumps the DB first).
echo "==> stopping app (a DB backup is taken first)"
scripts/svc.sh stop app || true

# 2. Show current LIVE row counts, then delete in one transaction.
echo "==> LIVE rows before:"
for t in "${TABLES[@]}"; do
  printf "  %-22s " "$t"
  psql -tA -c "select count(*) from $t where feed_mode='LIVE';" 2>/dev/null || echo "?"
done
echo "==> deleting LIVE rows..."
{
  echo "begin;"
  for t in "${TABLES[@]}"; do echo "delete from $t where feed_mode='LIVE';"; done
  echo "commit;"
} | psql -v ON_ERROR_STOP=1

# 3. Clear the loop's live-derived heartbeat/baseline so the Improve page + scorer start clean. The
#    durable improvement LEDGER (reports/improvement-ledger.md) and findings are preserved.
echo "==> clearing loop live heartbeat/baseline (ledger + findings kept)"
rm -f reports/.pending-baseline.json
rm -rf reports/attribution && mkdir -p reports/attribution
echo "[]" > reports/run-status.json

# 4. Restart so positions rebuild from the now-empty LIVE fills -> flat book, PnL 0. This also picks up
#    the current code (e.g. the ADR-0115 session gate), so no new exposure opens out-of-hours.
if [ "$RESTART" -eq 1 ]; then
  echo "==> restarting app (positions rebuild empty)"
  scripts/svc.sh start app
else
  echo "==> app left stopped (--no-restart). Start it with: scripts/svc.sh start app"
fi

echo "==> done. Live paper book reset to zero, flat for the next session."
