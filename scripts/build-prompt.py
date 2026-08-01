#!/usr/bin/env python3
"""
Per-run prompt assembler for the Jethro continuous-improvement loop (ADR-0063).

The loop's instructions come in two layers, kept separate on purpose:

  1. ops/improve-prompt.md      the STABLE operator contract — persona, objective, invariants, hard
                                limits. Human-owned; it does NOT change run to run.
  2. this script                assembles, EACH run, `logs/improve-prompt.rendered.md` = that contract
                                followed by a generated **"THIS RUN'S LIVE CONTEXT"** section that
                                surfaces the freshest situation and memory right in the prompt, so the
                                obvious money/risk state and the last few lessons can never be missed
                                (the failure that started all this: an analysis that didn't even notice
                                the book was bleeding).

What goes in the generated section is COPIED VERBATIM from artifacts already produced by deterministic
code this cycle — the ⚠ SITUATION header from logs/report.md, the latest run-status flags, the top of
the scored ledger, and the most recent findings. This script invents NO numbers and makes NO verdicts
(invariant 7 / ADR-0016): it only quotes what code already computed. If any input is missing it is
skipped with a note; the script never raises and never blocks the cycle (the wrapper falls back to the
static contract if this fails).

Dependency-free: Python 3 standard library only.

Usage:  python3 scripts/build-prompt.py            # writes logs/improve-prompt.rendered.md
"""
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONTRACT = os.path.join(REPO, "ops", "improve-prompt.md")
REPORT = os.path.join(REPO, "logs", "report.md")
STATUS = os.path.join(REPO, "reports", "run-status.json")
LEDGER = os.path.join(REPO, "reports", "improvement-ledger.md")
FINDINGS = os.path.join(REPO, "docs", "loop-findings.md")
MUSTFIX = os.path.join(REPO, "reports", "must-fix.md")
OUT = os.path.join(REPO, "logs", "improve-prompt.rendered.md")

# How much of each memory to surface. Enough to carry the recent thread; not so much it buries the
# contract. The full artifacts are still on disk for the model to read in depth.
LEDGER_ROWS = 5
FINDINGS_TAIL_LINES = 40
# The must-fix register is the carried-forward backlog Step 0 verifies against — surface its OPEN block in
# full (capped) so the run cannot skip verification or lose track of what it committed to fix.
MUSTFIX_HEAD_LINES = 60


def read_text(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return f.read()
    except Exception:
        return ""


def situation_from_report():
    """Copy the ⚠ SITUATION block verbatim out of logs/report.md (system-report.py already computed it
    from the live risk endpoint). Returns "" if the report or the block is absent."""
    text = read_text(REPORT)
    if not text:
        return ""
    lines = text.splitlines()
    start = None
    for i, ln in enumerate(lines):
        if ln.lstrip().startswith("## ") and "SITUATION" in ln.upper():
            start = i
            break
    if start is None:
        return ""
    out = [lines[start]]
    for ln in lines[start + 1:]:
        if ln.lstrip().startswith("## "):  # next top-level section ends the block
            break
        out.append(ln)
    return "\n".join(out).strip()


def status_flags():
    """The latest heartbeat's target/danger flags — the numbers are already computed by
    score-change.py; we only quote them. run-status.json is newest-first."""
    try:
        with open(STATUS, "r", encoding="utf-8") as f:
            entries = json.load(f)
    except Exception:
        return ""
    if not entries:
        return ""
    e = entries[0]

    def g(k):
        return e.get(k)

    parts = [
        "- Latest heartbeat `%s` (commit `%s`): action **%s**." % (g("ts"), g("commit"), g("action")),
        "- total PnL **%s** (%s%% vs last run), gross **%s** (%s%% vs last run)."
        % (g("total_pnl"), g("pnl_pct"), g("gross"), g("gross_pct")),
        "- Owner target: PnL growth **%s%%** over the last %s iterations vs target **%s%%** — "
        "on_track=**%s**, stale=**%s**, underwater=**%s**."
        % (g("pnl_growth_pct"), g("pnl_growth_window"), g("pnl_target_pct"),
           g("on_track"), g("stale"), g("underwater")),
    ]
    prev = g("last_verdict")
    if prev:
        parts.append("- Previous scored change verdict: **%s**." % prev)
    return "\n".join(parts)


def ledger_head(n):
    """The top n scored rows of the ledger (most recent changes + verdicts). The ledger is a Markdown
    table with newest rows at the top, just under the `|---|` separator."""
    text = read_text(LEDGER)
    if not text:
        return ""
    lines = text.splitlines()
    sep = None
    for i, ln in enumerate(lines):
        if ln.lstrip().startswith("|---") or ln.lstrip().startswith("| ---"):
            sep = i
            break
    if sep is None:
        return ""
    header = lines[max(0, sep - 1)] if sep > 0 else ""
    rows = [ln for ln in lines[sep + 1:] if ln.strip().startswith("|")][:n]
    if not rows:
        return ""
    out = []
    if header.strip().startswith("|"):
        out.append(header)
        out.append(lines[sep])
    out.extend(rows)
    return "\n".join(out)


def mustfix_head(n):
    """The head of the MUST-FIX register (OPEN items live near the top, most-costly first). Surfaced so
    Step 0's verify-the-previous-run and the 'change targets #1' rule cannot be skipped. Copied verbatim —
    no numbers invented (invariant 7)."""
    text = read_text(MUSTFIX)
    if not text:
        return ""
    lines = text.splitlines()
    head = lines[:n]
    return "\n".join(head).strip()


def findings_tail(n):
    """The most recent lines of the append-only findings memory (newest content is at the END of the
    file — it is appended to)."""
    text = read_text(FINDINGS)
    if not text:
        return ""
    lines = [ln for ln in text.splitlines()]
    tail = lines[-n:]
    return "\n".join(tail).strip()


def build():
    contract = read_text(CONTRACT)
    if not contract:
        # Nothing to build from — signal failure so the wrapper uses its fallback.
        return None

    blocks = []
    mf = mustfix_head(MUSTFIX_HEAD_LINES)
    if mf:
        blocks.append(("⛳ MUST-FIX register — STEP 0: verify each open item against THIS run's telemetry "
                       "(✅/⚠️/🔴) BEFORE any new idea; your one change targets item #1", mf))
    sit = situation_from_report()
    blocks.append(("Live situation (from this run's report — read FIRST)", sit or
                   "_(report.md had no SITUATION block this run — read logs/report.md directly.)_"))
    flags = status_flags()
    if flags:
        blocks.append(("Objective tracking (latest deterministic heartbeat)", flags))
    lh = ledger_head(LEDGER_ROWS)
    if lh:
        blocks.append(("Last %d scored changes (what worked / what was reverted — do NOT repeat a reverted idea)"
                       % LEDGER_ROWS, lh))
    ft = findings_tail(FINDINGS_TAIL_LINES)
    if ft:
        blocks.append(("Most recent findings (compounding memory — apply, don't relearn)", ft))

    ctx = ["", "---", "",
           "# THIS RUN'S LIVE CONTEXT  *(generated deterministically — quotes code-computed artifacts,",
           "no invented numbers). This is the freshest slice of the inputs the contract tells you to read;",
           "the full artifacts (`logs/report.md`, `reports/improvement-ledger.md`, `docs/loop-findings.md`,",
           "`reports/run-status.json`) are on disk for depth. START HERE, then diagnose per the contract.*",
           ""]
    for title, body in blocks:
        ctx.append("## %s" % title)
        ctx.append("")
        ctx.append(body)
        ctx.append("")

    return contract.rstrip() + "\n" + "\n".join(ctx) + "\n"


def main():
    rendered = build()
    if rendered is None:
        print("build-prompt: contract missing — NOT writing a rendered prompt (wrapper will fall back)")
        return 1
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(rendered)
    print("build-prompt: wrote %s (%d chars)" % (os.path.relpath(OUT, REPO), len(rendered)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
