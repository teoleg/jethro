#!/usr/bin/env python3
"""Fetch real daily bars for the walk-forward replay (ADR-0027) from Stooq — the same free
source scripts/calibrate_sim.py uses — and write data/historical-bars.json in the format
HistoricalBars.java loads: {"AAPL": [["2024-01-02", "185.64"], ...], ...}.

Run on a machine WITH network (the Pi):  python3 scripts/fetch_bars.py
Then either keep data/historical-bars.json on the host or point
jethro.backtest.bars-path somewhere else. Stdlib only, no dependencies.
"""
import json
import sys
import urllib.request
from datetime import date, timedelta

# instrumentId -> Stooq symbol (same mapping family as calibrate_sim.py). Stooq US equities
# are <sym>.us; ES/NQ futures use the continuous ^spx/^ndx indices as PROXIES (stated in the
# output notes); FX pairs are direct.
SYMBOLS = {
    "AAPL": "aapl.us",
    "MSFT": "msft.us",
    "AMZN": "amzn.us",
    "GOOG": "goog.us",
    "SAP": "sap.us",       # NYSE-listed ADR (USD), matching the platform's USD SAP line
    "ES": "^spx",          # proxy: S&P 500 index (continuous-future history isn't free)
    "NQ": "^ndx",          # proxy: Nasdaq-100 index
    "EURUSD": "eurusd",
    "GBPUSD": "gbpusd",
}

YEARS = 6


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
    out = {}
    for instrument, symbol in SYMBOLS.items():
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
    with open("data/historical-bars.json", "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"wrote data/historical-bars.json ({len(out)} instruments)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
