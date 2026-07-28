#!/usr/bin/env bash
# Reset ONLY simulator data — start the sim book from zero, leaving LIVE/REPLAY data untouched.
#
# Sim and live are separated by feed_mode (invariant 8 / ADR-0029), so this deletes exactly the
# feed_mode='SIM' rows from every feed_mode-scoped table. There is NO positions table — positions are
# rebuilt from `fills` at boot — so once SIM fills are gone and the app restarts, the book is flat and
# total PnL is 0. Live/replay rows, the reference-data universe, and book masters are NOT touched.
#
#   scripts/reset-sim.sh              # confirm, stop app, wipe SIM rows, reset loop records, restart
#   scripts/reset-sim.sh --yes        # no confirmation prompt
#   scripts/reset-sim.sh --no-restart # leave the app stopped afterwards
#   scripts/reset-sim.sh --with-universe   # ALSO clear sim discovery audit (universe_promotion)
#
# Stopping the app first triggers scripts/backup-db.sh (a full dump), so you have a restore point.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

ASSUME_YES=0; RESTART=1; WITH_UNIVERSE=0
for a in "$@"; do case "$a" in
  --yes|-y) ASSUME_YES=1 ;;
  --no-restart) RESTART=0 ;;
  --with-universe) WITH_UNIVERSE=1 ;;
  *) echo "usage: $0 [--yes] [--no-restart] [--with-universe]"; exit 2 ;;
esac; done

# Book/trading/performance tables, all feed_mode-scoped. Deleting SIM rows here zeroes the sim book
# and its analytics without affecting live/replay.
TABLES=(fills orders execution_quality swap_trades signal_observations hypothesis_record chat_audit firm_equity book_equity)
# universe_promotion is discovery-universe audit, not P&L — left intact unless --with-universe.
[ "$WITH_UNIVERSE" -eq 1 ] && TABLES+=(universe_promotion)

psql() { docker compose exec -T postgres psql -U jethro -d jethro "$@"; }

echo "==> Reset SIMULATOR data to zero (feed_mode='SIM' only). LIVE/REPLAY is NOT touched."
echo "    Tables: ${TABLES[*]}"
echo "    Also resets loop sim records: reports/run-status.json, .pending-baseline.json, reports/attribution/*"
echo "    NOT touched: reference-data universe, book masters, live/replay rows, mark_quarantine, strategy dials."

# Fail fast if we can't reach Postgres.
if ! psql -c 'select 1' >/dev/null 2>&1; then
  echo "ERROR: cannot reach Postgres via 'docker compose exec postgres'. Is the DB up? Aborting." >&2
  exit 1
fi

if [ "$ASSUME_YES" -eq 0 ]; then
  printf "\nType exactly 'reset-sim' to proceed: "
  read -r reply
  [ "$reply" = "reset-sim" ] || { echo "aborted — nothing deleted."; exit 0; }
fi

# 1. Stop the app so nothing writes mid-reset (svc.sh dumps the DB first).
echo "==> stopping app (a DB backup is taken first)"
scripts/svc.sh stop app || true

# 2. Show current SIM row counts, then delete in one transaction (psql echoes DELETE <n> per table).
echo "==> SIM rows before:"
for t in "${TABLES[@]}"; do
  printf "  %-22s " "$t"
  psql -tA -c "select count(*) from $t where feed_mode='SIM';" 2>/dev/null || echo "?"
done
echo "==> deleting SIM rows..."
{
  echo "begin;"
  for t in "${TABLES[@]}"; do echo "delete from $t where feed_mode='SIM';"; done
  echo "commit;"
} | psql -v ON_ERROR_STOP=1

# 3. Reset the loop's sim-derived performance records so the Improve page / growth target start clean.
echo "==> resetting loop sim records"
rm -f reports/.pending-baseline.json
rm -rf reports/attribution && mkdir -p reports/attribution
echo "[]" > reports/run-status.json

# 4. Restart so positions rebuild from the now-empty SIM fills -> flat book, PnL 0.
if [ "$RESTART" -eq 1 ]; then
  echo "==> restarting app (positions rebuild empty)"
  scripts/svc.sh start app
else
  echo "==> app left stopped (--no-restart). Start it with: scripts/svc.sh start app"
fi

echo "==> done. Sim book reset to zero. (Commit reports/ if you want the clean slate on GitHub.)"
