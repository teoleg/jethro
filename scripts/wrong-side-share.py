#!/usr/bin/env python3
"""Convicted-wrong-side share — the ADR-0147 proof metric.

The improvement loop (ADR-0063) must never hand-author a number that describes the book
(invariant 7 / ADR-0016). This computes the one metric `reports/must-fix.md` item #1 is
verified by, from the `fusion_targets` block the report already carries.

    a planned name is CONVICTED-WRONG-SIDE when
        targetQty != 0                        the desk has a view
        sgn(currentQty) == -sgn(targetQty)    the holding is on the other side of it
        |combinedForecast| >= floor           that view carries ADR-0059 conviction

    share = sum |currentQty x price| over convicted-wrong-side names
            ---------------------------------------------------------
            sum |currentQty x price| over every planned name with a holding

It is a PROPORTION measured inside one report, pooled across the window by summing the two
sides separately — not a rate of activity across windows. That is the same property that let
the ALPHA reversal rate survive the drift test every churn-rate candidate failed (see the
2026-08-07 19:00Z block in `reports/must-fix.md`), and it is why this metric is measured on
no-deploy data BEFORE being adopted rather than after it fails (Rule 489).

The report elides `fusion_targets` to the strongest-|forecast| names. That selection is the
report's, is identical before and after any change, and is exactly the population ADR-0147
acts on (a name below the floor is out of scope by construction), so the proportion is
comparable across windows. `--pooled-gate` is applied to the pooled held notional so a change
that thins the book cannot select its own sample; under it the script prints NO VERDICT and
exits 2.

Usage:
    scripts/wrong-side-share.py logs/jethro-report-*.zip
    scripts/wrong-side-share.py --window 6 --floor 5.0 logs/jethro-report-*.zip
    scripts/wrong-side-share.py --dispersion logs/jethro-report-*.zip   # per-report CV
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys
import zipfile
from decimal import Decimal, InvalidOperation

# The shipped jethro.fusion.min-forecast-to-route (ADR-0059), which ADR-0147 reuses verbatim.
DEFAULT_FLOOR = "5.0"

# Pooled held notional below which the window is NO VERDICT rather than a pass. Read from the
# baseline measurement, not chosen: it is the held notional of the THINNEST single report in the
# 2026-08-10 baseline window (jethro-report-20260810-113002.zip, 6,035.85), so a verdict window must
# carry at least as much book as the leanest report the baseline was computed from. It exists to
# reject a degenerate near-empty window, not to size anything.
DEFAULT_POOLED_GATE = "6035.85"


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


def fusion_targets(text: str) -> list[dict]:
    """The dict entries of the `### fusion_targets` block; elision markers are strings, dropped."""
    parts = re.split(r"^### fusion_targets\s*$", text, flags=re.M)
    if len(parts) < 2:
        return []
    body = parts[1].split("```json", 1)
    if len(body) < 2:
        return []
    blob = body[1].split("\n```", 1)[0]
    try:
        book = json.loads(blob)
    except json.JSONDecodeError:
        return []
    return [t for t in book.get("targets", []) if isinstance(t, dict)]


def dec(value) -> Decimal | None:
    """Exact decimal, or None for anything that is not a number (invariant 1 — no float on size)."""
    if value is None or isinstance(value, bool):
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def measure(targets: list[dict], floor: Decimal) -> tuple[Decimal, Decimal, int, int]:
    """(wrong-side notional, total held notional, wrong-side names, names with a holding)."""
    wrong = Decimal(0)
    total = Decimal(0)
    wrong_n = 0
    held_n = 0
    for t in targets:
        held = dec(t.get("currentQty"))
        target = dec(t.get("targetQty"))
        price = dec(t.get("price"))
        forecast = dec(t.get("combinedForecast"))
        if held is None or price is None or held == 0 or price <= 0:
            continue
        notional = abs(held * price)
        total += notional
        held_n += 1
        if target is None or forecast is None or target == 0:
            continue
        if (held > 0) == (target > 0):
            continue  # the holding is on the side its own view is on
        if abs(forecast) < floor:
            continue  # no conviction — ADR-0147 leaves this name at the ADR-0107 rate
        wrong += notional
        wrong_n += 1
    return wrong, total, wrong_n, held_n


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+", help="report .zip / .md files, or a directory of them")
    ap.add_argument("--window", type=int, default=0, help="use only the newest N reports")
    ap.add_argument("--floor", default=DEFAULT_FLOOR, help="ADR-0059 conviction floor")
    ap.add_argument("--pooled-gate", default=DEFAULT_POOLED_GATE,
                    help="pooled held notional below which the result is NO VERDICT")
    ap.add_argument("--dispersion", action="store_true",
                    help="also print the per-report mean/sd/CV of the share")
    args = ap.parse_args(argv)

    files: list[str] = []
    for p in args.paths:
        if os.path.isdir(p):
            files.extend(os.path.join(p, f) for f in os.listdir(p)
                         if f.endswith(".zip") or f.endswith(".md"))
        else:
            files.append(p)
    files = sorted(set(files), key=lambda f: os.path.getmtime(f), reverse=True)
    if args.window > 0:
        files = files[: args.window]
    files.reverse()  # oldest first, so the printed series reads forward in time
    if not files:
        print("no reports given", file=sys.stderr)
        return 2

    floor = Decimal(args.floor)
    pooled_wrong = Decimal(0)
    pooled_total = Decimal(0)
    shares: list[Decimal] = []
    print(f"floor=|f| >= {floor}   reports={len(files)}")
    for path in files:
        try:
            targets = fusion_targets(report_text(path))
        except (OSError, ValueError, zipfile.BadZipFile) as exc:
            print(f"  {os.path.basename(path):<48} skipped ({exc})")
            continue
        wrong, total, wrong_n, held_n = measure(targets, floor)
        pooled_wrong += wrong
        pooled_total += total
        share = wrong / total if total > 0 else None
        if share is not None:
            shares.append(share)
        shown = f"{share:.4f}" if share is not None else "—"
        print(f"  {os.path.basename(path):<48} {wrong_n:>2}/{held_n:<3} names  "
              f"{float(wrong):>12,.0f} / {float(total):>12,.0f}  {shown}")

    if pooled_total <= 0:
        print("\nNO VERDICT — no planned name carried a holding in this window")
        return 2
    pooled = pooled_wrong / pooled_total
    print(f"\npooled convicted-wrong-side share = {float(pooled_wrong):,.2f} / "
          f"{float(pooled_total):,.2f} = {pooled:.4f}")

    if args.dispersion and len(shares) > 1:
        n = len(shares)
        mean = sum(shares) / n
        var = sum((s - mean) ** 2 for s in shares) / (n - 1)
        sd = var.sqrt()
        cv = sd / mean if mean > 0 else Decimal(0)
        print(f"per-report dispersion over n={n}: mean {mean:.4f}  sd {sd:.4f}  CV {cv:.2f}  "
              f"range {min(shares):.4f}–{max(shares):.4f}")

    gate = Decimal(args.pooled_gate)
    if pooled_total < gate:
        print(f"NO VERDICT — pooled held notional {float(pooled_total):,.2f} < sample gate "
              f"{float(gate):,.2f}")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
