#!/usr/bin/env python3
"""
Jethro system-report collector — pulls the RUNNING app's live data into one bundle for analysis.

Run it ON THE BOX against the live process (do NOT restart first — runtime telemetry like JVM
threads/memory, feed staleness and tick counters is in-memory and a restart wipes it):

    python3 scripts/system-report.py

Produces  logs/jethro-report-<timestamp>.zip  containing:
  - diagnostics.xlsx      the app's own Ops export (risk / P&L / fills / TCA / hypotheses / strategy)
  - ops-telemetry.xlsx    operational endpoints (feeds, JVM, traffic, marks, signals, fusion, VaR, …)
  - db-aggregates.xlsx    durable Postgres aggregates (turnover & cost by name, equity curve, orders)

Dependency-free: Python 3 standard library only. Env: JETHRO_URL (default http://localhost:8080).
"""
import datetime, json, os, subprocess, sys, urllib.request, zipfile

BASE = os.environ.get("JETHRO_URL", "http://localhost:8080").rstrip("/")
OUT_DIR = os.environ.get("JETHRO_OUT", "logs")
TS = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")

# ---- endpoints to snapshot (operational / runtime; the risk half is in diagnostics.xlsx) ----
ENDPOINTS = {
    "ops_jvm": "/api/ops/jvm", "traffic": "/api/traffic", "feeds": "/api/feeds",
    "marks": "/api/marks", "quarantined": "/api/marks/quarantined",
    "signals_telemetry": "/api/signals/telemetry", "fusion_targets": "/api/fusion/targets",
    "var": "/api/var", "breaker": "/api/breaker", "regime": "/api/market/regime",
    "hedging": "/api/hedging", "hypotheses_stats": "/api/hypotheses/stats",
    "hypotheses_balance": "/api/hypotheses/balance", "discovery": "/api/discovery",
    "social": "/api/social", "tca": "/api/tca", "history_status": "/api/history/status",
    "strategy_selection": "/api/strategy/selection", "strategy_diag": "/api/strategy/diagnostics",
    "orders_day": "/api/orders/day", "risk": "/api/risk", "llm_runs": "/api/llm/runs",
    "attention": "/api/attention", "attribution": "/api/attribution",
}

# ---- durable DB aggregates (behaviour over the run; complements the diagnostics fills/tca sheets) ----
DB_QUERIES = {
    "turnover_cost_by_name": "select instrument_id instrument, count(*) fills, sum(abs(qty)) shares, "
        "round(sum(fee)::numeric,2) total_fee from fills group by instrument_id order by fills desc",
    "orders_by_status": "select status, count(*) n from orders group by status order by n desc",
    "fills_by_day": "select date(executed_at) d, count(*) fills, round(sum(fee)::numeric,2) fee "
        "from fills group by 1 order by 1",
    "firm_equity_curve": "select * from firm_equity order by 1",
    "book_equity_curve": "select * from book_equity order by 1",
    "signal_observations": "select source, feed_mode, count(*) n, count(*) filter (where resolved) resolved, "
        "round(avg(case when outcome='WIN' then 1.0 when outcome='LOSS' then 0.0 end),3) hit_rate "
        "from signal_observations group by source, feed_mode order by n desc",
    "daily_close_depth": "select count(distinct day) days, min(day) first, max(day) last, "
        "count(distinct instrument) instruments from daily_close",
    "mark_quarantine": "select * from mark_quarantine",
    "strategy_param_change": "select * from strategy_param_change order by changed_at desc limit 100",
}


def fetch_json(path):
    try:
        req = urllib.request.Request(BASE + path, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)
    except Exception as e:
        return {"_error": "%s: %s" % (type(e).__name__, e)}


def download(path, dest):
    try:
        with urllib.request.urlopen(BASE + path, timeout=60) as r, open(dest, "wb") as f:
            f.write(r.read())
        return True
    except Exception as e:
        print("  ! %s failed: %s" % (path, e))
        return False


def psql(sql):
    """Run a query via the compose Postgres, return (headers, rows). Never raises."""
    try:
        p = subprocess.run(
            ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "jethro", "-d", "jethro",
             "--csv", "-c", sql],
            capture_output=True, text=True, timeout=45)
        if p.returncode != 0:
            return ["error"], [[p.stderr.strip().splitlines()[-1] if p.stderr.strip() else "query failed"]]
        lines = p.stdout.strip().splitlines()
        if not lines:
            return ["(no rows)"], []
        rows = [_split_csv(ln) for ln in lines]
        return rows[0], rows[1:]
    except Exception as e:
        return ["error"], [["%s: %s" % (type(e).__name__, e)]]


def _split_csv(line):
    out, cur, q = [], "", False
    for ch in line:
        if ch == '"':
            q = not q
        elif ch == "," and not q:
            out.append(cur); cur = ""
        else:
            cur += ch
    out.append(cur)
    return out


def to_table(data):
    """Flatten arbitrary JSON into (headers, rows)."""
    if isinstance(data, dict) and "_error" in data and len(data) == 1:
        return ["error"], [[data["_error"]]]
    if isinstance(data, list):
        if data and all(isinstance(x, dict) for x in data):
            headers = list(dict.fromkeys(k for row in data for k in row))
            return headers, [[_flat(row.get(h)) for h in headers] for row in data]
        return ["value"], [[_flat(x)] for x in data]
    if isinstance(data, dict):
        # split nested lists-of-dicts to their own note; top level is key/value
        rows = [[k, _flat(v)] for k, v in data.items()]
        return ["key", "value"], rows
    return ["value"], [[_flat(data)]]


def _flat(v):
    if isinstance(v, (dict, list)):
        return json.dumps(v, separators=(",", ":"))[:2000]
    if v is None:
        return ""
    return v


# ---- dependency-free .xlsx writer (inline strings; no styles/sharedStrings needed) ----
def _esc(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;"))


def _col(n):
    s = ""
    n += 1
    while n:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def _is_num(v):
    if isinstance(v, bool):
        return False
    if isinstance(v, (int, float)):
        return True
    if not isinstance(v, str) or not v:
        return False
    s = v[1:] if v[0] == "-" else v
    if s.count(".") > 1 or not s.replace(".", "", 1).isdigit():
        return False
    # don't coerce ids/codes with a leading zero (e.g. "007"); allow "0" and "0.5"
    if len(s) > 1 and s[0] == "0" and s[1] != ".":
        return False
    return True


def _cell(ref, v):
    if _is_num(v):
        return '<c r="%s"><v>%s</v></c>' % (ref, v)
    return '<c r="%s" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c>' % (ref, _esc(v))


def _sheet_xml(headers, rows):
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
           '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>']
    all_rows = [headers] + rows
    for ri, row in enumerate(all_rows, start=1):
        out.append('<row r="%d">' % ri)
        for ci, val in enumerate(row):
            out.append(_cell("%s%d" % (_col(ci), ri), val))
        out.append("</row>")
    out.append("</sheetData></worksheet>")
    return "".join(out)


def _safe_name(name, used):
    n = "".join(c for c in name if c not in '[]:*?/\\')[:31] or "sheet"
    base, i = n, 1
    while n.lower() in used:
        suffix = "_%d" % i
        n = base[:31 - len(suffix)] + suffix
        i += 1
    used.add(n.lower())
    return n


def write_xlsx(path, sheets):
    """sheets: list of (name, headers, rows)."""
    used = set()
    named = [(_safe_name(n, used), h, r) for (n, h, r) in sheets] or [("empty", ["(none)"], [])]
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Default Extension="xml" ContentType="application/xml"/>'
                   '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                   + "".join('<Override PartName="/xl/worksheets/sheet%d.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' % (i + 1)
                             for i in range(len(named)))
                   + "</Types>")
        z.writestr("_rels/.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
                   '</Relationships>')
        z.writestr("xl/workbook.xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                   'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
                   + "".join('<sheet name="%s" sheetId="%d" r:id="rId%d"/>' % (_esc(n), i + 1, i + 1)
                             for i, (n, _, _) in enumerate(named))
                   + "</sheets></workbook>")
        z.writestr("xl/_rels/workbook.xml.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   + "".join('<Relationship Id="rId%d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet%d.xml"/>' % (i + 1, i + 1)
                             for i in range(len(named)))
                   + "</Relationships>")
        for i, (_, h, r) in enumerate(named):
            z.writestr("xl/worksheets/sheet%d.xml" % (i + 1), _sheet_xml(h, r))


def capture_logs(max_lines=400):
    """Best-effort recent WARN/ERROR/Exception + stack-frame lines from the compose stack.
    The code-level causes an auto-fix loop must act on often live only in a stack trace, not in
    the risk/P&L workbook (e.g. a NUMERIC->double ClassCastException surfaces as a blank sheet,
    never a number). Never raises — a missing 'docker compose' just yields a note."""
    try:
        p = subprocess.run(["docker", "compose", "logs", "--no-color", "--tail", "1500"],
                           capture_output=True, text=True, timeout=45)
        text = p.stdout or ""
    except Exception as e:
        return "log capture unavailable (%s: %s)" % (type(e).__name__, e)
    keep = [ln for ln in text.splitlines()
            if any(k in ln for k in ("WARN", "ERROR", "Exception", "Caused by")) or ln.strip().startswith("at ")]
    return "\n".join(keep[-max_lines:]) if keep else "(no WARN/ERROR/Exception lines in recent logs)"


def _esc_md(v):
    return str(v).replace("|", "\\|").replace("\n", " ")


def _md_table(headers, rows):
    if not rows:
        return "_(no rows)_\n"
    out = ["| " + " | ".join(_esc_md(h) for h in headers) + " |",
           "| " + " | ".join("---" for _ in headers) + " |"]
    for row in rows:
        out.append("| " + " | ".join(_esc_md(c) for c in row) + " |")
    return "\n".join(out) + "\n"


def situation_block(ops_raw):
    """A prioritised SITUATION header so the obvious money/risk state is never buried under the section
    dump. Current PnL + exposure from the live risk endpoint, deltas vs the recent run-status heartbeats,
    and explicit danger flags (bleeding + exposure rising). All from live data — nothing invented."""
    total = (ops_raw.get("risk") or {}).get("total") or {}
    try:
        pnl = float(total["totalPnl"]); gross = float(total["grossExposure"]); net = float(total["netExposure"])
    except (KeyError, TypeError, ValueError):
        return "## SITUATION\n(risk endpoint unavailable — could not read live PnL/exposure.)"
    hist = []
    try:
        with open("reports/run-status.json", encoding="utf-8") as f:
            hist = json.load(f)
    except Exception:
        hist = []

    def prev(i):
        if len(hist) > i:
            try:
                return float(hist[i]["total_pnl"]), float(hist[i]["gross"])
            except (KeyError, TypeError, ValueError):
                return None
        return None

    lines = ["## ⚠ SITUATION — read this before anything else",
             "Current (live): total PnL **$%.2f**, gross exposure **$%.2f**, net **$%.2f**." % (pnl, gross, net)]
    p1, p3 = prev(0), prev(2)
    dp1 = dg1 = 0.0
    if p1:
        dp1, dg1 = pnl - p1[0], gross - p1[1]
        lines.append("Since last run: PnL **%+.2f**, gross **%+.2f**." % (dp1, dg1))
    if p3:
        lines.append("Over the last 3 runs: PnL **%+.2f**, gross **%+.2f**." % (pnl - p3[0], gross - p3[1]))
    flags = []
    if dp1 < 0: flags.append("BLEEDING (PnL falling)")
    if dg1 > 0: flags.append("EXPOSURE RISING")
    if pnl < 0: flags.append("UNDERWATER")
    if dp1 < 0 and dg1 > 0:
        flags.append("**DANGER — bleeding AND adding exposure; de-risk / revert the culprit is the priority this cycle**")
    lines.append("Flags: " + ("; ".join(flags) if flags else "none (not bleeding, exposure not rising)") + ".")
    return "\n".join(lines)


def render_markdown(ops_raw, db_sheets, logs_text):
    """Compact, model-readable digest of the same data as the xlsx bundle — cheap to read every
    cycle (the .xlsx is binary and token-heavy). Row-level detail (positions, fills, TCA,
    hypotheses, strategy dials) stays in diagnostics.xlsx in the same zip for when it's needed."""
    L = ["# Jethro report %s" % TS, "",
         "Model-readable digest of the live run for automated analysis. The objective is risk-adjusted "
         "PnL on the **FIRM TOTAL** (all books incl. hedge) — total PnL up per unit of total exposure — "
         "plus the owner target of **+1% PnL every 3 iterations**. Row-level detail is in `diagnostics.xlsx`.", "",
         situation_block(ops_raw), "",
         "## Operational / runtime (live endpoints)"]
    for name, data in ops_raw.items():
        L.append("### %s" % name)
        L.append("```json")
        L.append(json.dumps(data, indent=1)[:6000])
        L.append("```")
    L.append("## Postgres aggregates (behaviour over the run)")
    for name, h, r in db_sheets:
        L.append("### %s" % name)
        L.append(_md_table(h, r[:60]))
    L.append("## Recent WARN/ERROR / stack traces")
    L.append("```")
    L.append(logs_text[:20000])
    L.append("```")
    return "\n".join(L)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    files = []

    print("==> collecting operational endpoints from", BASE)
    ops_sheets = []
    ops_raw = {}
    for name, path in ENDPOINTS.items():
        data = fetch_json(path)
        ops_raw[name] = data
        h, r = to_table(data)
        ops_sheets.append((name, h, r))
        print("   %-20s %d row(s)" % (name, len(r)))
    ops_path = os.path.join(OUT_DIR, "ops-telemetry.xlsx")
    write_xlsx(ops_path, ops_sheets); files.append(ops_path)

    print("==> collecting Postgres aggregates")
    db_sheets = []
    for name, sql in DB_QUERIES.items():
        h, r = psql(sql)
        db_sheets.append((name, h, r))
        print("   %-22s %d row(s)" % (name, len(r)))
    db_path = os.path.join(OUT_DIR, "db-aggregates.xlsx")
    write_xlsx(db_path, db_sheets); files.append(db_path)

    print("==> capturing recent WARN/ERROR logs + writing report.md")
    logs_text = capture_logs()
    md_path = os.path.join(OUT_DIR, "report.md")
    with open(md_path, "w") as f:
        f.write(render_markdown(ops_raw, db_sheets, logs_text))
    files.append(md_path)

    print("==> downloading the app's own diagnostics.xlsx")
    diag = os.path.join(OUT_DIR, "diagnostics.xlsx")
    if download("/api/export/diagnostics.xlsx", diag):
        files.append(diag)

    bundle = os.path.join(OUT_DIR, "jethro-report-%s.zip" % TS)
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as z:
        for f in files:
            z.write(f, os.path.basename(f))
    print("\n==> bundle ready: %s" % bundle)
    print("    send that .zip back for analysis.")


if __name__ == "__main__":
    sys.exit(main())
