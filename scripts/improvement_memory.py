#!/usr/bin/env python3
"""
RAG memory of the improvement loop's own history (ADR-0035 / ADR-0063).

Embeds every scored change (the audited attribution snapshots) and every finding (loop-findings.md)
into a local vector index, and retrieves the most SIMILAR past changes for the current situation — so
each cycle recalls "we tried something like this before and it went ✅/❌" instead of relearning. This
is the durable, semantic memory of all improvement changes.

Self-contained by design:
  - embeddings via the local Ollama model already pulled on the box (nomic-embed-text),
  - a JSONL vector index at reports/memory-index.jsonl (a derived cache — gitignored; rebuilt by
    `ingest` from the COMMITTED sources: attribution snapshots + findings),
  - pure-Python cosine (stdlib only — no numpy/pgvector). Fine for this corpus size; pgvector is the
    ADR-0035 scale path once it is large.

Subcommands:
  ingest   — embed any not-yet-indexed attribution snapshots + findings; append to the index. Idempotent.
  retrieve — build a query from the current situation (run-status + last-analysis), embed it, print the
             top-k most similar past changes+outcomes (for appending into logs/report.md).

Env: OLLAMA_URL (default http://localhost:11434), MEMORY_EMBED_MODEL (default nomic-embed-text),
     MEMORY_TOPK (default 5). Degrades gracefully: if Ollama/model is down, it no-ops with a note.
"""

import glob
import hashlib
import json
import math
import os
import sys
import urllib.request
from datetime import datetime, timezone

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INDEX = os.path.join(REPO, "reports", "memory-index.jsonl")
SNAP_DIR = os.path.join(REPO, "reports", "attribution")
FINDINGS = os.path.join(REPO, "docs", "loop-findings.md")
STATUS = os.path.join(REPO, "reports", "run-status.json")
ANALYSIS = os.path.join(REPO, "reports", "last-analysis.md")

OLLAMA = os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/")
EMBED_MODEL = os.environ.get("MEMORY_EMBED_MODEL", "nomic-embed-text")
TOPK = int(os.environ.get("MEMORY_TOPK", "5"))


def embed(text):
    """Return the embedding vector for text, or None if Ollama/model is unavailable."""
    body = json.dumps({"model": EMBED_MODEL, "prompt": text[:4000]}).encode("utf-8")
    req = urllib.request.Request(OLLAMA + "/api/embeddings", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            v = json.loads(r.read().decode("utf-8")).get("embedding")
            return v if v else None
    except Exception:
        return None


def cosine(a, b):
    if not a or not b or len(a) != len(b):
        return -1.0
    dot = s1 = s2 = 0.0
    for x, y in zip(a, b):
        dot += x * y
        s1 += x * x
        s2 += y * y
    if s1 == 0 or s2 == 0:
        return -1.0
    return dot / (math.sqrt(s1) * math.sqrt(s2))


def load_index():
    out = []
    if os.path.exists(INDEX):
        with open(INDEX, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        out.append(json.loads(line))
                    except Exception:
                        pass
    return out


def _sources():
    """Yield (id, kind, text) for every memory-worthy source: scored changes + findings."""
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
        yield ("snap:" + os.path.basename(path), "change", text)
    # Findings — the distilled lessons.
    if os.path.exists(FINDINGS):
        with open(FINDINGS, encoding="utf-8") as f:
            body = f.read()
        for block in body.split("\n### ")[1:]:
            block = "### " + block
            fid = "finding:" + hashlib.sha1(block[:200].encode("utf-8")).hexdigest()[:12]
            yield (fid, "finding", block[:1500])


def cmd_ingest():
    have = {e["id"] for e in load_index()}
    added = 0
    with open(INDEX, "a", encoding="utf-8") as f:
        for sid, kind, text in _sources():
            if sid in have:
                continue
            vec = embed(text)
            if vec is None:
                print("ingest: embeddings unavailable (Ollama/model down) — indexed 0 new this run")
                return 0
            f.write(json.dumps({"id": sid, "kind": kind, "text": text, "vector": vec,
                                "at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}) + "\n")
            have.add(sid)
            added += 1
    print(f"ingest: indexed {added} new (corpus now {len(have)})")
    return 0


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


def _keyword_score(query, text):
    """Ollama-free fallback: overlap of distinctive query terms with an entry (Claude-driven, no
    embedding model needed — correct for a small corpus)."""
    q = {w for w in "".join(c.lower() if c.isalnum() else " " for c in query).split() if len(w) > 3}
    if not q:
        return 0.0
    t = "".join(c.lower() if c.isalnum() else " " for c in text).split()
    tset = set(t)
    return sum(1 for w in q if w in tset) / len(q)


def cmd_retrieve(argv):
    """Retrieve the most relevant past changes/findings. CLAUDE drives this: it passes its own query
    (what it wants to recall); with no query we fall back to the current situation. Semantic ranking via
    Ollama embeddings when available, else a keyword overlap — either way the QUERY and the reasoning
    are Claude's; the embedding model is only a similarity calculator, never the thinker."""
    query = " ".join(argv).strip() or _situation_query()
    # Prefer the committed sources directly (always current) over the cached index, so a fresh box with
    # no index still retrieves from the real findings/ledger.
    entries = [{"kind": k, "text": tx} for _, k, tx in _sources()]
    print("\n## MEMORY — past changes & findings most relevant to the query (Claude-driven RAG)")
    print(f"query: {query.splitlines()[0][:160] if query else '(current situation)'}")
    if not entries:
        print("(no prior improvement memory yet — grows as the loop accumulates changes/findings.)")
        return 0
    qv = embed(query)  # None if Ollama/model unavailable — we degrade to keyword scoring
    if qv is not None:
        vecs = {e["id"]: e.get("vector") for e in load_index()}
        # match cached vectors back to sources by id; fall back to keyword where no vector exists
        by_id = {sid: (k, tx) for sid, k, tx in _sources()}
        scored = []
        for sid, (k, tx) in by_id.items():
            v = vecs.get(sid)
            s = cosine(qv, v) if v else _keyword_score(query, tx)
            scored.append((s, k, tx))
    else:
        print("(embeddings unavailable — keyword retrieval; still Claude-driven.)")
        scored = [(_keyword_score(query, tx), k, tx) for _, k, tx in _sources()]
    scored.sort(key=lambda t: t[0], reverse=True)
    print("Recall these before repeating a mistake or re-trying a reverted idea:")
    for s, k, tx in scored[:TOPK]:
        if s <= 0:
            continue
        print(f"- [{k}, score {s:.2f}] {' '.join(tx.split())[:280]}")
    return 0


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    if argv[0] == "ingest":
        return cmd_ingest()
    if argv[0] == "retrieve":
        return cmd_retrieve(argv[1:])
    print(f"unknown subcommand: {argv[0]}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
