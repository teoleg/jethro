#!/usr/bin/env bash
# Probe what EMMA actually returns on the Pi, so I can make the OS fetch/extraction work against the REAL
# site (I can't reach EMMA from where I build/test). Paste the WHOLE output.
#
#   bash scripts/muni-emma-probe.sh <CUSIP-9> [OS_PDF_URL]
#
# CUSIP-9 = any real muni CUSIP you have (from an EMMA search in your browser).
# OS_PDF_URL (optional) = the "Official Statement" document link from that CUSIP's EMMA page.
cd "$(dirname "$0")/.."
CUSIP="${1:?usage: muni-emma-probe.sh <CUSIP-9> [OS_PDF_URL]}"
OSURL="${2:-}"
UA="muni-world/0.1 (+municipal-data-collection)"
SEC="https://emma.msrb.org/Security/Details/$CUSIP"

echo "=== 0. can the Pi reach EMMA? ==="
curl -sS -A "$UA" -o /dev/null -w "robots.txt      : HTTP %{http_code}\n" https://emma.msrb.org/robots.txt || echo "robots fetch failed"
curl -sS -A "$UA" -o /tmp/emma-sec.html -w "security page    : HTTP %{http_code}  type=%{content_type}  bytes=%{size_download}\n" "$SEC" || echo "security fetch failed"

echo; echo "=== 1. does the security page HTML contain OS/PDF links? (scrapable HTML, or a JS shell?) ==="
grep -oiE 'href="[^"]*(\.pdf|/document|officialstatement)[^"]*"' /tmp/emma-sec.html | sort -u | head -20
echo "  matching-link count : $(grep -oicE 'href="[^"]*(\.pdf|/document|officialstatement)[^"]*"' /tmp/emma-sec.html 2>/dev/null)"
echo "  total link count    : $(grep -oiE '(href|src)="[^"]+"' /tmp/emma-sec.html 2>/dev/null | wc -l)"

echo; echo "=== 2. is it a JS app that renders data client-side (i.e. no data in the raw HTML)? ==="
grep -oiE 'ng-app|angular|<app-root|data-reactroot|__NEXT_DATA__|window\.__' /tmp/emma-sec.html | sort -u | head

echo; echo "=== 3. first 25 lines of the raw HTML (so I can see the structure) ==="
head -25 /tmp/emma-sec.html

if [ -n "$OSURL" ]; then
  echo; echo "=== 4. fetch the OS PDF you gave ==="
  curl -sS -A "$UA" -o /tmp/emma-os.pdf -w "OS document      : HTTP %{http_code}  type=%{content_type}  bytes=%{size_download}\n" "$OSURL" || echo "OS fetch failed"
  echo -n "  first 4 bytes (should be %PDF): "; head -c 4 /tmp/emma-os.pdf; echo
  echo; echo "=== 5. run that OS through the muni extractor (needs muni running on :8090) ==="
  curl -sS -X POST "localhost:8090/api/muni/ingest/emma-os?base=${CUSIP:0:6}&url=$OSURL" || echo "extractor call failed"
  echo
fi
echo; echo "=== done — paste everything above ==="
