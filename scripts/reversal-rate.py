#!/usr/bin/env python3
"""ALPHA same-name direction-reversal rate — the ADR-0145 proof metric.

The improvement loop (ADR-0063) must never hand-author a number that describes the book
(invariant 7 / ADR-0016). This computes the one metric `reports/must-fix.md` item #1 is
verified by, from the `recent_orders` table the report already carries.

    per instrument:  order its LIVE, FILLED orders in the named book chronologically and
                     count consecutive pairs whose side flips
    pooled rate   =  sum(reversals) / sum(pairs)  over every report in the window

It is a PROPORTION measured inside the window, not a rate of activity across windows, which
is why it survived the drift test the churn-rate candidates failed (see the 2026-08-07 19:00Z
block in `reports/must-fix.md`: CV 0.29 at the 6-cycle horizon against 0.42-0.53 for every
rate, measured over no-deploy windows).

Usage:
    scripts/reversal-rate.py logs/jethro-report-*.zip        # pooled over the given reports
    scripts/reversal-rate.py --book ALPHA --window 6 logs/   # pooled over the newest 6 reports

Prints the pooled rate, the reversal and pair counts, and the per-report breakdown. Exits 2
when the pooled pair count is under the sample gate, which is NO VERDICT rather than a pass.
"""
from __future__ import annotations

import argparse
import glob
import io
import os
import re
import sys
import zipfile
from decimal import Decimal

# The register's sample gate, applied to the POOLED window so a change that thins each
# report's fill count cannot select its own sample.
DEFAULT_MIN_PAIRS = 150


def report_text(path: str) -> str:
    """The report markdown, from either a raw .md or the .zip the loop archives."""
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if name.endswith("report.md"):
                    return z.read(name).decode("utf-8", "replace")
        raise ValueError(f"{path}: no report.md inside")
    with io.open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def recent_orders(text: str) -> list[dict[str, str]]:
    """Rows of the `### recent_orders` markdown table, in file order (newest first)."""
    body = re.split(r"^### recent_orders\s*$", text, flags=re.M)
    if len(body) < 2:
        return []
    rows: list[dict[str, str]] = []
    header: list[str] | None = None
    for line in body[1].splitlines():
        line = line.strip()
        if line.startswith("###") or line.startswith("## "):
            break
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if header is None:
            header = cells
            continue
        if set("".join(cells)) <= set("- :"):
            continue  # the markdown separator row
        if len(cells) == len(header):
            rows.append(dict(zip(header, cells)))
    return rows


def reversals(rows: list[dict[str, str]], book: str) -> tuple[int, int]:
    """(reversals, pairs) over LIVE FILLED orders in `book`, per instrument, chronological."""
    by_name: dict[str, list[tuple[str, str]]] = {}
    for r in rows:
        if r.get("feed_mode") != "LIVE" or r.get("status") != "FILLED":
            continue
        if book and r.get("book") != book:
            continue
        side = r.get("side")
        if side not in ("BUY", "SELL"):
            continue
        by_name.setdefault(r.get("instrument", ""), []).append((r.get("created_at", ""), side))
    flips = 0
    pairs = 0
    for name, orders in by_name.items():
        orders.sort(key=lambda o: o[0])  # the table is newest-first; pair them in time order
        for prev, cur in zip(orders, orders[1:]):
            pairs += 1
            if prev[1] != cur[1]:
                flips += 1
    return flips, pairs


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+", help="report .md / .zip files, or a directory of them")
    ap.add_argument("--book", default="ALPHA", help="book to measure (default ALPHA; empty = all)")
    ap.add_argument("--window", type=int, default=0, help="use only the newest N reports")
    ap.add_argument("--min-pairs", type=int, default=DEFAULT_MIN_PAIRS,
                    help=f"pooled sample gate (default {DEFAULT_MIN_PAIRS}); under it = NO VERDICT")
    args = ap.parse_args()

    files: list[str] = []
    for p in args.paths:
        if os.path.isdir(p):
            files.extend(glob.glob(os.path.join(p, "jethro-report-*.zip")))
            files.extend(glob.glob(os.path.join(p, "*report*.md")))
        else:
            files.append(p)
    files = sorted(set(files))
    if args.window > 0:
        files = files[-args.window:]
    if not files:
        print("no reports matched", file=sys.stderr)
        return 1

    total_flips = 0
    total_pairs = 0
    print(f"book={args.book or '(all)'}  reports={len(files)}")
    for f in files:
        try:
            flips, pairs = reversals(recent_orders(report_text(f)), args.book)
        except Exception as e:  # a truncated archive must not silently vanish from the pool
            print(f"  {os.path.basename(f):<44} SKIPPED: {e}", file=sys.stderr)
            continue
        total_flips += flips
        total_pairs += pairs
        rate = f"{Decimal(flips) / Decimal(pairs):.4f}" if pairs else "—"
        print(f"  {os.path.basename(f):<44} {flips:>4} / {pairs:<4}  {rate}")

    if total_pairs == 0:
        print("\nNO VERDICT — no pairs in the window")
        return 2
    pooled = Decimal(total_flips) / Decimal(total_pairs)
    print(f"\npooled reversal rate = {total_flips} / {total_pairs} = {pooled:.4f}")
    if total_pairs < args.min_pairs:
        print(f"NO VERDICT — pooled pairs {total_pairs} < sample gate {args.min_pairs}")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
