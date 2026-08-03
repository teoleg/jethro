#!/usr/bin/env bash
# Gather EVERYTHING needed to build the muni PDF load + analysis against the REAL EMMA (I can't reach it from
# where I build). Run on the Pi, paste the WHOLE output.
#
#   bash scripts/muni-emma-probe.sh <CUSIP-9> [OS_PDF_URL]
#
# CUSIP-9   = any real muni CUSIP (from an EMMA search in your browser).
# OS_PDF_URL = optional; the "Official Statement" document link for that CUSIP. If given, the script pulls
#              the PDF and DUMPS ITS EXTRACTED TEXT (maturity schedule + redemption) — that's the key data
#              I need to write the parser to match the real format.
cd "$(dirname "$0")/.."
CUSIP="${1:?usage: muni-emma-probe.sh <CUSIP-9> [OS_PDF_URL]}"
OSURL="${2:-}"
UA="muni-world/0.1 (+municipal-data-collection)"
SEC="https://emma.msrb.org/Security/Details/$CUSIP"

echo "############### PART A — how do we FIND the OS on EMMA (discovery) ###############"
echo "=== A0. reachability ==="
curl -sS -A "$UA" -o /dev/null -w "robots.txt   : HTTP %{http_code}\n" https://emma.msrb.org/robots.txt || echo "robots fetch failed"
curl -sS -A "$UA" -o /tmp/emma-sec.html -w "security page : HTTP %{http_code}  type=%{content_type}  bytes=%{size_download}\n" "$SEC" || echo "security fetch failed"

echo; echo "=== A1. are OS/PDF links in the raw HTML? (if yes, the scraper works; if 0 + JS app, EMMA loads them via an API) ==="
grep -oiE 'href="[^"]*(\.pdf|/document|officialstatement)[^"]*"' /tmp/emma-sec.html | sort -u | head -20
echo "  matching links : $(grep -oicE 'href="[^"]*(\.pdf|/document|officialstatement)[^"]*"' /tmp/emma-sec.html 2>/dev/null)"
echo "  total links    : $(grep -oiE '(href|src)=\"[^\"]+\"' /tmp/emma-sec.html 2>/dev/null | wc -l)"
echo "  JS-app markers : $(grep -oiE 'ng-app|angular|<app-root|data-reactroot|__NEXT_DATA__' /tmp/emma-sec.html 2>/dev/null | sort -u | tr '\n' ' ')"
echo "  any api/xhr URLs referenced in the page:"
grep -oiE 'https?://[a-z0-9./_-]*(api|service|data)[a-z0-9./_-]*' /tmp/emma-sec.html | sort -u | head -15

if [ -z "$OSURL" ]; then
  echo; echo ">>> Re-run with the OS PDF URL as a 2nd arg to get PART B (the text I need for the parser)."
  echo "=== done — paste everything above ==="; exit 0
fi

echo; echo "############### PART B — the OS PDF itself (the analysis input) ###############"
echo "=== B0. fetch the OS PDF ==="
curl -sSL -A "$UA" -o /tmp/emma-os.pdf -w "OS document : HTTP %{http_code}  type=%{content_type}  bytes=%{size_download}\n" "$OSURL" || echo "OS fetch failed"
echo -n "  first 4 bytes (want %PDF): "; head -c 4 /tmp/emma-os.pdf; echo

echo; echo "=== B1. extract text with layout (installs poppler-utils if missing) ==="
command -v pdftotext >/dev/null || sudo apt-get install -y poppler-utils >/dev/null 2>&1
pdftotext -layout /tmp/emma-os.pdf /tmp/emma-os.txt 2>/dev/null && echo "  ok: $(wc -l < /tmp/emma-os.txt) lines, $(wc -c < /tmp/emma-os.txt) chars"

echo; echo "=== B2. the MATURITY SCHEDULE region (THIS is the format I build the parser to) ==="
grep -n -iE 'maturity|cusip|interest rate|yield|principal' /tmp/emma-os.txt | head -5
echo "----- lines around the first 'maturity' mention -----"
awk 'BEGIN{IGNORECASE=1} /maturity/{p=NR} p&&NR>=p&&NR<=p+40{print NR": "$0}' /tmp/emma-os.txt | head -45

echo; echo "=== B3. the REDEMPTION / optional-call paragraph ==="
awk 'BEGIN{IGNORECASE=1} /redemption|on or after|callable/{print NR": "$0}' /tmp/emma-os.txt | head -12

echo; echo "=== B4. what the current muni extractor makes of it (needs muni running on :8090) ==="
curl -sS -X POST "localhost:8090/api/muni/ingest/emma-os?base=${CUSIP:0:6}&url=$OSURL" || echo "extractor call failed"
echo
echo "=== done — paste everything above ==="
