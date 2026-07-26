#!/usr/bin/env python3
"""
Memory of the improvement loop's own history (ADR-0063).

Recalls the most relevant PAST changes + findings for the current situation, so each cycle remembers
"we tried something like this before and it went ✅/❌" instead of relearning. This is the loop's durable
memory of every improvement change.

Deliberately self-contained and dependency-free — NO embedding service, NO external model. Ollama on this
box serves the social-media feed and is off-limits to this tool; retrieval here is a plain lexical overlap
over the COMMITTED sources (the audited attribution snapshots + docs/loop-findings.md). Claude drives it:
Claude passes the query (what it wants to recall) and does all the reasoning — this script is only a
similarity calculator over text the loop already wrote. Small corpus, so keyword overlap is the right tool;
if it ever grows large, ADR-0035's pgvector path is the scale option (a separate, approved decision).

Subcommands:
  retrieve [query...] — print the top-k past changes/findings most similar to the query (Claude's own
                        query; with none, falls back to the current situation from run-status/last-analysis).
                        Output is appended into logs/report.md so the cycle opens with relevant history.

(There is intentionally no `ingest` step and no vector index: sources are read live from the committed
files every call, so a fresh box with no cache still recalls the real findings/ledger.)

Env: MEMORY_TOPK (default 5).
"""

import glob
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SNAP_DIR = os.path.join(REPO, "reports", "attribution")
FINDINGS = os.path.join(REPO, "docs", "loop-findings.md")
STATUS = os.path.join(REPO, "reports", "run-status.json")
ANALYSIS = os.path.join(REPO, "reports", "last-analysis.md")

TOPK = int(os.environ.get("MEMORY_TOPK", "5"))


def _sources():
    """Yield (kind, text) for every memory-worthy source: scored changes + findings."""
    # Scored changes — the audited snapshots (structured: commit, verdict, summary, deltas).
    for path in sorted(glob.glob(os.path.join(SNAP_DIR, "*.json"))):
        try:
            with open(path, encoding="utf-8") as f:
                s = json.load(f)
        except Exception:
            continue
        commit = s.get("commit", os.path.basename(path))
        d = s.get("delta", {})
        text = ("CHANGE {sha}: {summary}\nVERDICT {verdict}. {note}\n"
                "ΔPnL {dp} Δgross {dg}").format(
            sha=commit[:9], summary=s.get("summary", ""), verdict=s.get("verdict", ""),
            note=s.get("note", ""), dp=d.get("total_pnl", d.get("alpha_pnl", "?")), dg=d.get("gross", "?"))
        yield ("change", text)
    # Findings — the distilled lessons.
    if os.path.exists(FINDINGS):
        with open(FINDINGS, encoding="utf-8") as f:
            body = f.read()
        for block in body.split("\n### ")[1:]:
            yield ("finding", ("### " + block)[:1500])


def _situation_query():
    parts = []
    try:
        with open(STATUS, encoding="utf-8") as f:
            e = json.load(f)[0]
        parts.append("Now: PnL {p}, gross {g}, action {a}. {d}".format(
            p=e.get("total_pnl"), g=e.get("gross"), a=e.get("action"), d=e.get("decision", "")))
    except Exception:
        pass
    if os.path.exists(ANALYSIS):
        try:
            with open(ANALYSIS, encoding="utf-8") as f:
                parts.append(f.read()[:1500])
        except Exception:
            pass
    return "\n".join(parts) or "current trading situation"


def _score(query, text):
    """Lexical overlap of the query's distinctive terms with an entry. Claude-driven: the QUERY and all
    reasoning are Claude's; this is just an overlap count, no model, no embedding service."""
    q = {w for w in "".join(c.lower() if c.isalnum() else " " for c in query).split() if len(w) > 3}
    if not q:
        return 0.0
    tset = set("".join(c.lower() if c.isalnum() else " " for c in text).split())
    return sum(1 for w in q if w in tset) / len(q)


def cmd_retrieve(argv):
    query = " ".join(argv).strip() or _situation_query()
    entries = list(_sources())
    print("\n## MEMORY — past changes & findings most relevant to the query (Claude-driven, lexical)")
    print(f"query: {query.splitlines()[0][:160] if query else '(current situation)'}")
    if not entries:
        print("(no prior improvement memory yet — grows as the loop accumulates changes/findings.)")
        return 0
    scored = sorted(((_score(query, tx), k, tx) for k, tx in entries), key=lambda t: t[0], reverse=True)
    print("Recall these before repeating a mistake or re-trying a reverted idea:")
    any_hit = False
    for s, k, tx in scored[:TOPK]:
        if s <= 0:
            continue
        any_hit = True
        print(f"- [{k}, score {s:.2f}] {' '.join(tx.split())[:280]}")
    if not any_hit:
        print("(no past entry overlaps this query — nothing directly comparable yet.)")
    return 0


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    if argv[0] == "retrieve":
        return cmd_retrieve(argv[1:])
    if argv[0] == "ingest":
        # Back-compat no-op: the loop wrapper still calls `ingest`; there is no index to build anymore.
        print("ingest: no-op (memory reads committed sources live; no embedding/index).")
        return 0
    print(f"unknown subcommand: {argv[0]}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
