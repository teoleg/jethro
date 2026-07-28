#!/usr/bin/env python3
"""
Decide whether the improvement loop should run its (expensive) analysis this cycle, or skip it because
the traded market is closed. Cron stays free and fires as usual; this only gates the Claude/Opus call
so no cycle is spent analysing a frozen, closed-market tape (ADR-0063).

Rule:
  - Only a LIVE feed has market hours. SIM / REPLAY tapes run continuously, so we NEVER skip them.
  - On a LIVE feed, "open" = the US equity REGULAR session in America/New_York: a weekday that is not a
    NYSE full-closure holiday, between 09:30 and 16:00 ET (13:00 on the disclosed early-close days).
    Outside that window -> closed -> skip analysis.
  - JETHRO_LOOP_IGNORE_MARKET_HOURS=1 forces "open" (never skip) — for testing.

Exit 0 = OPEN (run the analysis). Exit 1 = CLOSED (skip). Prints a one-line reason either way.

Anything uncertain (feed mode unknown, app unreachable, no tz database) fails SAFE = OPEN, so the loop
never wrongly skips a live-market cycle.

Provenance: NYSE published calendar — regular session 09:30–16:00 ET; the 2026 full-closure holidays
and early-close (13:00) days below are the NYSE 2026 schedule. This gates on US equities only (the
Alpaca feed); nearly-24h FX/futures venues are intentionally not gated here.
"""
import datetime
import json
import os
import sys
import urllib.request

BASE = os.environ.get("JETHRO_URL", "http://localhost:8080").rstrip("/")

# NYSE 2026 full-closure holidays (observance-shifted). Source: NYSE 2026 holiday calendar.
HOLIDAYS = {
    "2026-01-01",  # New Year's Day
    "2026-01-19",  # Martin Luther King, Jr. Day
    "2026-02-16",  # Washington's Birthday
    "2026-04-03",  # Good Friday
    "2026-05-25",  # Memorial Day
    "2026-06-19",  # Juneteenth
    "2026-07-03",  # Independence Day (observed — Jul 4 is a Saturday)
    "2026-09-07",  # Labor Day
    "2026-11-26",  # Thanksgiving Day
    "2026-12-25",  # Christmas Day
}
# NYSE 2026 early closes — 13:00 ET. Source: NYSE 2026 schedule.
EARLY_CLOSE = {
    "2026-11-27",  # day after Thanksgiving
    "2026-12-24",  # Christmas Eve
}


def feed_mode():
    """The running app's feed mode from /api/attribution, or None if it can't be read."""
    try:
        req = urllib.request.Request(BASE + "/api/attribution", headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return (json.loads(r.read().decode("utf-8")) or {}).get("feedMode")
    except Exception:
        return None


def main():
    if os.environ.get("JETHRO_LOOP_IGNORE_MARKET_HOURS") == "1":
        print("market-open: override JETHRO_LOOP_IGNORE_MARKET_HOURS=1 — treating as OPEN")
        return 0

    mode = feed_mode()
    if mode != "LIVE":
        print(f"market-open: feedMode={mode!r} is not LIVE — tape runs continuously, OPEN (never skip)")
        return 0

    try:
        from zoneinfo import ZoneInfo
        now = datetime.datetime.now(ZoneInfo("America/New_York"))
    except Exception as e:
        print(f"market-open: no tz database ({e}); cannot check hours — OPEN (fail safe)")
        return 0

    d = now.strftime("%Y-%m-%d")
    if now.weekday() >= 5:
        print(f"market-open: {now:%a %Y-%m-%d %H:%M} ET — weekend, CLOSED")
        return 1
    if d in HOLIDAYS:
        print(f"market-open: {d} is a NYSE holiday — CLOSED")
        return 1
    open_t = now.replace(hour=9, minute=30, second=0, microsecond=0)
    close_hour = 13 if d in EARLY_CLOSE else 16
    close_t = now.replace(hour=close_hour, minute=0, second=0, microsecond=0)
    if open_t <= now < close_t:
        print(f"market-open: {now:%a %H:%M} ET — US session OPEN (09:30–{close_hour}:00)")
        return 0
    print(f"market-open: {now:%a %H:%M} ET — outside 09:30–{close_hour}:00 ET, CLOSED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
