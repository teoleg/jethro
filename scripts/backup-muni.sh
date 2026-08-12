#!/usr/bin/env bash
# Back up EVERYTHING muni-world has earned: the `muni` Postgres schema AND the Official Statement PDFs.
#
# Both halves matter and neither is covered by the other:
#   * the DB holds the bond terms, filing detail and quarterly valuation history;
#   * os-inbox/ holds the OS PDFs you hand-picked and downloaded — they are GITIGNORED, exist nowhere
#     else, and re-finding them on EMMA is the slowest part of this whole pipeline.
# The N-PORT universe is re-fetchable from EDGAR, but the multi-year history backfill costs hours, so
# restoring a dump beats re-deriving it.
#
#   scripts/backup-muni.sh          # write backups/muni-<timestamp>.tar.gz
#   scripts/backup-muni.sh --list   # show what is already backed up
#
# Restore (schema + data):
#   tar -xzf backups/muni-YYYYmmdd-HHMMSS.tar.gz -C /tmp
#   docker compose exec -T postgres psql -U jethro -d jethro < /tmp/muni-schema.sql
#   cp -r /tmp/os-inbox/* muni-world/os-inbox/
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "${1:-}" = "--list" ]; then
  ls -lh backups/muni-*.tar.gz 2>/dev/null || echo "no muni backups yet"
  exit 0
fi

if ! docker compose exec -T postgres pg_isready -U jethro -d jethro >/dev/null 2>&1; then
  echo "==> Postgres not up — skipping muni backup (nothing to dump)."
  exit 0
fi

TS="$(date +%Y%m%d-%H%M%S)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p backups

# --schema=muni keeps the dump independently restorable: it can go into a fresh database without
# dragging jethro's trading tables along.
if ! docker compose exec -T postgres pg_dump -U jethro -d jethro --schema=muni > "$STAGE/muni-schema.sql" 2>/dev/null \
   || [ ! -s "$STAGE/muni-schema.sql" ]; then
  echo "!!  pg_dump of the muni schema failed — no backup written."
  exit 1
fi

# The documents. processed/ and failed/ both count: a failed one is a parser gap we may yet fix, and
# re-downloading it means finding it on EMMA again.
if [ -d muni-world/os-inbox ]; then
  mkdir -p "$STAGE/os-inbox"
  find muni-world/os-inbox -maxdepth 2 -name '*.pdf' -exec cp --parents {} "$STAGE/os-inbox/" \; 2>/dev/null || true
fi

OUT="backups/muni-$TS.tar.gz"
tar -czf "$OUT" -C "$STAGE" .
PDFS=$(find "$STAGE" -name '*.pdf' 2>/dev/null | wc -l)
ROWS=$(grep -c '^INSERT\|^COPY' "$STAGE/muni-schema.sql" 2>/dev/null || echo "?")
echo "==> muni backup → $OUT ($(du -h "$OUT" | cut -f1); $PDFS OS PDF(s), $ROWS data statement(s))"

# Keep the newest 20, same discipline as the app-level dump.
ls -1t backups/muni-*.tar.gz 2>/dev/null | tail -n +21 | xargs -r rm -f

cat <<TIP
    NOTE: backups/ lives on THIS machine. An SD-card failure loses the backups with the data —
    copy one off the Pi periodically, e.g.
      scp $OUT you@other-host:~/muni-backups/
TIP
