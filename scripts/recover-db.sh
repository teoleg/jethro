#!/usr/bin/env bash
# Recover a Postgres data directory that got orphaned when the pg volume changed.
# Postgres always kept its data in a Docker volume; earlier down/up cycles left the old
# clusters behind as orphaned volumes. This finds them, shows you the choices, and — only
# after you confirm — copies the one you pick into the current named volume.
#
#   scripts/recover-db.sh                 # auto-pick the NEWEST cluster, confirm, restore
#   scripts/recover-db.sh <source_volume> # restore a specific volume you chose from the list
set -euo pipefail
cd "$(dirname "$0")/.."

SRC_ARG="${1:-}"

echo "==> Scanning Docker volumes for Postgres clusters (this spins up tiny alpine containers)…"
CANDIDATES=()
while read -r v; do
  [ -z "$v" ] && continue
  ver=$(docker run --rm -v "$v":/v alpine sh -c 'cat /v/PG_VERSION 2>/dev/null' 2>/dev/null || true)
  [ -z "$ver" ] && continue
  ts=$(docker run --rm -v "$v":/v alpine sh -c 'stat -c %Y /v/PG_VERSION 2>/dev/null' 2>/dev/null || echo 0)
  CANDIDATES+=("$ts|$v|$ver")
done < <(docker volume ls -q)

if [ ${#CANDIDATES[@]} -eq 0 ]; then
  echo "!! No Postgres clusters found in any volume — nothing to recover."; exit 1
fi

# newest first
mapfile -t SORTED < <(printf '%s\n' "${CANDIDATES[@]}" | sort -t'|' -k1,1 -rn)

echo
echo "Postgres clusters found (newest first):"
for c in "${SORTED[@]}"; do
  ts="${c%%|*}"; rest="${c#*|}"; vol="${rest%%|*}"; ver="${rest##*|}"
  when=$(date -d "@$ts" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$ts")
  printf "   %-64s  PG=%s  modified %s\n" "$vol" "$ver" "$when"
done
echo

# find the live target (…pg-data) first, so auto-pick can skip it.
TARGET=$(docker volume ls -q | grep -E 'pg-data$' | head -1 || true)
if [ -z "$TARGET" ]; then
  echo "!! Couldn't find the current pg-data volume. Start the stack once so it's created, then re-run."; exit 1
fi

# choose source: the arg if given, else the NEWEST cluster that ISN'T the current target
# (the target is usually the newest — it's the live/empty DB — so we skip past it).
if [ -n "$SRC_ARG" ]; then
  SRC="$SRC_ARG"
else
  SRC=""
  for c in "${SORTED[@]}"; do
    rest="${c#*|}"; vol="${rest%%|*}"
    if [ "$vol" != "$TARGET" ]; then SRC="$vol"; break; fi
  done
fi
if [ -z "$SRC" ]; then
  echo "!! No recoverable cluster other than the current one ($TARGET)."; exit 1
fi
if [ "$SRC" = "$TARGET" ]; then
  echo "!! Source and target are the same volume ($SRC) — nothing to do."; exit 1
fi

echo "About to restore:"
echo "   FROM  $SRC"
echo "   INTO  $TARGET   (this OVERWRITES the current DB in $TARGET)"
read -r -p "Proceed? [y/N] " ans
[ "$ans" = "y" ] || [ "$ans" = "Y" ] || { echo "Aborted — nothing changed."; exit 0; }

echo "==> Stopping Postgres so the target isn't in use…"
docker compose stop postgres >/dev/null 2>&1 || true

echo "==> Copying $SRC → $TARGET (preserving ownership)…"
docker run --rm -v "$SRC":/from -v "$TARGET":/to alpine \
  sh -c 'find /to -mindepth 1 -delete 2>/dev/null; cp -a /from/. /to/'

echo "==> Starting Postgres…"
docker compose up -d postgres
echo "==> Restored from $SRC. Verify your data, then start the app (scripts/svc.sh start app)."
