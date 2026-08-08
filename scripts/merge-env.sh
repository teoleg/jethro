#!/usr/bin/env bash
# Merge NEW params from the template into your local.env — never touching values you already set.
#
#   scripts/merge-env.sh                  # merge local.env.example -> local.env
#   scripts/merge-env.sh <template> <target>
#
# Rules (deliberately simple):
#   - A key you already have (active `KEY=...` OR deliberately commented `#KEY=...`) is LEFT ALONE.
#   - A key that exists only in the template is APPENDED with the template's default, under a dated
#     marker, and printed so you can review/edit the defaults just added.
#   - Your file is backed up to local.env.bak.<timestamp> first. Comments stay on their own lines
#     (inline comments break the KEY=value parser — learned the hard way).
set -euo pipefail
# Defaults resolve against the repo root; explicit args resolve against YOUR cwd (no cd — relative
# paths you pass must mean what you typed).
REPO="$(cd "$(dirname "$0")/.." && pwd)"

TEMPLATE="${1:-$REPO/local.env.example}"
TARGET="${2:-$REPO/local.env}"
[ -f "$TEMPLATE" ] || { echo "template not found: $TEMPLATE" >&2; exit 1; }

# No local.env yet — the merge is just the template.
if [ ! -f "$TARGET" ]; then
  cp "$TEMPLATE" "$TARGET"
  echo "==> $TARGET did not exist — created it from $TEMPLATE (edit the placeholder values)"
  exit 0
fi

# A key counts as "present" whether active or commented out — a key you commented out on purpose
# must not be resurrected by a merge.
has_key() {
  grep -qE "^[[:space:]]*#?[[:space:]]*$1=" "$TARGET"
}

missing=()
while IFS= read -r line; do
  key="${line%%=*}"
  has_key "$key" || missing+=("$line")
done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$TEMPLATE")

if [ "${#missing[@]}" -eq 0 ]; then
  echo "==> $TARGET already has every key in $TEMPLATE — nothing to merge"
  exit 0
fi

cp "$TARGET" "$TARGET.bak.$(date +%Y%m%d%H%M%S)"
{
  echo ""
  echo "# ── added by scripts/merge-env.sh from $TEMPLATE on $(date -Is) — review these defaults ──"
  printf '%s\n' "${missing[@]}"
} >> "$TARGET"

echo "==> merged ${#missing[@]} new key(s) into $TARGET (backup written):"
printf '    %s\n' "${missing[@]}"