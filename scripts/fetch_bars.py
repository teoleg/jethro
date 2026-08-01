#!/usr/bin/env python3
"""Fetch real daily bars for the walk-forward replay (ADR-0027) from Stooq and write
data/historical-bars.json in the format HistoricalBars.java loads:
{"AAPL": [["2024-01-02", "185.64"], ...], ...}.

The instrument list is READ FROM THE LIVE UNIVERSE (GET /api/universe — the refdata master,
invariant 9), NOT a hardcoded ticker list. So a name added by ADR-0060 discovery promotion or a
seed migration gets its history fetched automatically, with no edit here — the failure mode this
avoids is a component silently missing every new name because its own list went stale (the
sim-instruments legacy trap). Only the Stooq SYMBOL is derived locally, from each name's asset
class, with a tiny override map for the one case a derivation cannot know (index-future proxies).

Run on a machine WITH network (the Pi), with the app reachable:  python3 scripts/fetch_bars.py
Env: JETHRO_URL (default http://localhost:8080). Stdlib only, no dependencies.
"""
import json
import os
import sys
import urllib.request
from datetime import date, timedelta

BASE = os.environ.get("JETHRO_URL", "http://localhost:8080").rstrip("/")
YEARS = 6

# The ONLY provider-specific choice a derivation cannot infer: free Stooq has no continuous-future
# history, so the index futures use their cash-index proxy. Everything else derives from asset class.
STOOQ_OVERRIDES = {"ES": "^spx", "NQ": "^ndx"}


def stooq_symbol(instrument_id: str, asset_class: str):
    """Stooq symbol for a name, derived from its asset class. US equities are <ticker>.us; FX pairs
    are the lowercase pair; index futures use the proxy override. Anything else (Treasury futures,
    swaps) has no free Stooq daily series and is skipped (returns None)."""
    if instrument_id in STOOQ_OVERRIDES:
        return STOOQ_OVERRIDES[instrument_id]
    if asset_class == "EQUITY":
        return instrument_id.lower() + ".us"
    if asset_class == "FX":
        return instrument_id.lower()
    return None


def universe() -> list:
    """The live tradable universe from the refdata master: [(instrumentId, assetClass), ...]."""
    req = urllib.request.Request(BASE + "/api/universe", headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        data = json.loads(r.read().decode("utf-8"))
    return [(row["instrumentId"], row.get("assetClass")) for row in data]


def fetch(symbol: str) -> list:
    start = (date.today() - timedelta(days=365 * YEARS)).strftime("%Y%m%d")
    url = (f"https://stooq.com/q/d/l/?s={symbol}&d1={start}"
           f"&d2={date.today().strftime('%Y%m%d')}&i=d")
    with urllib.request.urlopen(url, timeout=30) as response:
        text = response.read().decode()
    bars = []
    for line in text.splitlines()[1:]:  # skip header Date,Open,High,Low,Close,Volume
        parts = line.split(",")
        if len(parts) >= 5 and parts[4] not in ("", "N/A"):
            bars.append([parts[0], parts[4]])  # [date, close] — close kept as STRING (exact)
    return bars


def main() -> int:
    try:
        names = universe()
    except Exception as e:  # noqa: BLE001
        print(f"cannot read the live universe from {BASE}/api/universe ({e}); is the app up? "
              f"NOT fetching against a stale hardcoded list — keeping any existing history file",
              file=sys.stderr)
        return 1
    if not names:
        print("the universe endpoint returned no instruments — nothing to fetch", file=sys.stderr)
        return 1

    out = {}
    for instrument, asset_class in names:
        symbol = stooq_symbol(instrument, asset_class)
        if symbol is None:
            continue  # no free Stooq daily series for this asset class (rates futures, swaps)
        try:
            bars = fetch(symbol)
            if len(bars) < 250:
                print(f"WARN {instrument} ({symbol}): only {len(bars)} bars — skipped", file=sys.stderr)
                continue
            out[instrument] = bars
            print(f"{instrument}: {len(bars)} bars {bars[0][0]}..{bars[-1][0]}", file=sys.stderr)
        except Exception as e:  # noqa: BLE001 — report and continue, partial data is usable
            print(f"WARN {instrument} ({symbol}): {e}", file=sys.stderr)
    if not out:
        print("no data fetched — check network/Stooq availability", file=sys.stderr)
        return 1
    os.makedirs("data", exist_ok=True)
    with open("data/historical-bars.json", "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"wrote data/historical-bars.json ({len(out)} of {len(names)} universe instruments)",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
