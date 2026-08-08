#!/usr/bin/env python3
"""
Deterministic change scorer for the Jethro continuous-improvement loop (ADR-0063).

CLAUDE.md invariant 7 / ADR-0016: numbers that gate money, risk, or exposure are computed by CODE,
never by an LLM. This script is the ONLY thing that computes the improvement ledger's vector, its
deltas, and its verdict. Claude proposes and writes CODE; this script does all the measuring and all
the arithmetic. Every value is exact-decimal (invariant 1 — no binary float on money) and every
verdict is recomputable from the snapshot this script commits, so nothing is taken on trust.

Subcommands
-----------
  score
      Score the change recorded in reports/.pending-baseline.json (if any). Fetches the current
      objective vector from the live app, computes deltas vs the recorded baseline, applies the
      deterministic verdict rule, prepends a ledger row, writes an audited JSON snapshot, and — on a
      BAD verdict — reverts the offending commit. Commits reports/ (and the revert). Clears the
      pending file. If the app can't be reached, leaves the pending file untouched and retries next
      run (never fabricates a number).

  baseline <sha> <summary...>
      Record the CURRENT vector as the baseline for the next `score`, tagged with the commit sha and
      a one-line human summary. The numbers come from the live app, never from an argument — Claude
      supplies only the sha (from git) and prose (the summary). Commits the pending file.

  status --scored <0|1> --changed <0|1>
      Append one per-cycle heartbeat to reports/run-status.json (the feed the UI shows): current
      PnL/exposure, % change vs the previous run, and this cycle's decision (changed / no-change /
      reverted). Every number is computed here; the wrapper passes only the two booleans.

Objective vector (ADR-0063 — the FIRM TOTAL: total money made, total money at risk, ALL books incl.
the hedge; this is exactly the Overview headline, /api/risk .total)
  pnl        = /api/risk .total.totalPnl        (total realized+unrealized, net of all costs incl. fees)
  gross,net  = /api/risk .total.grossExposure | .netExposure   (total exposure — all books)
  fees       = /api/attribution .totalFees      (cumulative fees paid — money spent trading; context)

Env: JETHRO_URL (default http://localhost:8080).
"""

import json
import math
import os
import statistics
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation

BASE = os.environ.get("JETHRO_URL", "http://localhost:8080").rstrip("/")
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PENDING = os.path.join(REPO, "reports", ".pending-baseline.json")
LEDGER = os.path.join(REPO, "reports", "improvement-ledger.md")
SNAP_DIR = os.path.join(REPO, "reports", "attribution")
STATUS = os.path.join(REPO, "reports", "run-status.json")  # per-cycle heartbeat the UI reads
STATUS_CAP = 300
ANALYSIS = os.path.join(REPO, "reports", "last-analysis.md")  # Claude's own reasoning, written each run

# --- Deadbands: below these a move is treated as market noise, not an effect of the change. They
# gate the GOOD/BAD/revert decision, so per CLAUDE.md they carry provenance and are NOT silent
# self-chosen rules:
#   PLACEHOLDER -- Oleg to set. $50 is a round, deliberately-conservative floor meant to swamp
#   per-run mark jitter on a paper book; it is NOT a calibrated figure. 1% is a matching floor on
#   gross-exposure drift. Override either via env without editing code.
PNL_DEADBAND = Decimal(os.environ.get("JETHRO_SCORE_PNL_DEADBAND_USD", "50"))
EXP_DEADBAND_FRAC = Decimal(os.environ.get("JETHRO_SCORE_EXPOSURE_DEADBAND_FRAC", "0.01"))

# --- Evidence-based scoring (ADR-0116). A single 30-minute PnL delta on a small book is almost all
# market noise, so the old one-cycle deadband verdict was MIXED on essentially everything and the loop
# never learned. Instead a change is HELD for an evaluation window and judged by the SIGN + STATISTICAL
# SIGNIFICANCE of its per-cycle risk-adjusted PnL over that window. These gate the verdict (and thus a
# revert), so per CLAUDE.md they carry provenance — but they are METHODOLOGY knobs, not market/money
# numbers, and are PLACEHOLDER — Oleg to tune:
#   MIN_CYCLES  how many OPEN-MARKET heartbeats must accrue with the change live before it is scored at
#               all (a change is left running, not re-tried, until then). ADR-0135: raised 6 → 12 —
#               ~one full US session at the 30-min cadence — after the 07-28 week's 6–7-cycle windows
#               produced 0 GOOD in 52 scored cycles. Closed-market heartbeats no longer count (frozen
#               tape is not evidence).
#   T_HURDLE    |t| of the mean per-cycle risk-adjusted return the window must clear to call GOOD/BAD;
#               below it the verdict is INCONCLUSIVE (kept, not reverted). 1.5 is deliberately lenient.
#   GROSS_FLOOR normalisation floor so a near-zero book can't turn a $1 mark wiggle into a huge "edge".
#   FEE_DEADBAND (ADR-0135) fee burn over the window above which a change with mean return <= 0 scores
#               BAD — paid to churn, earned nothing. $25/window is a round starting point.
MIN_CYCLES = int(os.environ.get("JETHRO_SCORE_MIN_CYCLES", "12"))
T_HURDLE = Decimal(os.environ.get("JETHRO_SCORE_T_HURDLE", "1.5"))
GROSS_FLOOR = Decimal(os.environ.get("JETHRO_SCORE_GROSS_FLOOR_USD", "1000"))
FEE_DEADBAND = Decimal(os.environ.get("JETHRO_SCORE_FEE_DEADBAND_USD", "25"))

# --- Owner-set PERFORMANCE TARGET (2026-07-26): total PnL must grow at least PNL_TARGET_PCT percent
# every PNL_TARGET_WINDOW iterations. This is a KPI the loop is measured against and must actively
# pursue — NOT a market/risk dial that gates a trade. Staleness (PnL flat/negative and not on track,
# especially with exposure still high) is a monitored FAILURE state, not an acceptable "flat". Both
# owner-set, env-overridable.
PNL_TARGET_PCT = Decimal(os.environ.get("JETHRO_LOOP_PNL_TARGET_PCT", "1.0"))
PNL_TARGET_WINDOW = int(os.environ.get("JETHRO_LOOP_PNL_TARGET_WINDOW", "3"))


def fetch_json(path):
    req = urllib.request.Request(BASE + path, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode("utf-8"))


def dec(x):
    """Parse a boundary string into an exact Decimal; raises on anything non-numeric."""
    return Decimal(str(x))


def current_vector():
    """The objective vector — the FIRM TOTAL: total money made and total money at risk, ALL books
    including the hedge (the hedge costs real money and carries real exposure, so it counts). This is
    exactly what the Overview headline shows (/api/risk .total). Returns (vector, raw) or raises.
    Attribution (strategy vs hedge split, fees) is fetched best-effort for context/diagnostics only —
    it never changes the totals."""
    risk = fetch_json("/api/risk")
    total = risk.get("total")
    if not total:
        raise ValueError("risk totals unavailable — cannot score")

    vec = {
        "pnl": dec(total["totalPnl"]),           # total realized+unrealized, net of all costs (fees inside)
        "gross": dec(total["grossExposure"]),    # total gross exposure — all books incl. hedge
        "net": dec(total["netExposure"]),        # total net exposure
        "fees": Decimal(0),                       # cumulative fees paid (money spent trading); filled below
    }
    raw = {
        "totalPnl": total["totalPnl"],
        "realizedPnl": total.get("realizedPnl"),
        "unrealizedPnl": total.get("unrealizedPnl"),
        "grossExposure": total["grossExposure"],
        "netExposure": total["netExposure"],
    }
    # Best-effort context: feed mode, cumulative fees, and the alpha/hedge split (diagnostic only).
    try:
        attr = fetch_json("/api/attribution")
        if attr.get("available"):
            vec["fees"] = dec(attr.get("totalFees", "0"))
            raw["feedMode"] = attr.get("feedMode")
            raw["totalFees"] = attr.get("totalFees")
            raw["strategyAlpha"] = attr.get("strategyAlpha")
            raw["hedgePnl"] = attr.get("hedgePnl")
    except Exception:
        pass
    return vec, raw


# ----------------------------- formatting (display only) -----------------------------

def money2(d):
    # 2dp with thousands separators and sign, from an exact Decimal
    d = d.quantize(Decimal("0.01"))
    return ("-$" if d < 0 else "$") + f"{abs(d):,.2f}"


def delta(d):
    d = d.quantize(Decimal("0.01"))
    return ("+" if d >= 0 else "-") + "$" + f"{abs(d):,.2f}"


def pair(before, after):
    return f"{money2(before)} → {money2(after)} ({delta(after - before)})"


# ----------------------------- deterministic verdict -----------------------------

def classify(before, after):
    """Pure function of the two vectors. Returns (verdict, revert:bool, note)."""
    d_pnl = after["pnl"] - before["pnl"]
    d_gross = after["gross"] - before["gross"]
    gross_band = abs(before["gross"]) * EXP_DEADBAND_FRAC

    pnl_up = d_pnl > PNL_DEADBAND
    gross_up = d_gross > gross_band
    gross_down = d_gross < -gross_band

    ra_before = (before["pnl"] / before["gross"]) if before["gross"] != 0 else None
    ra_after = (after["pnl"] / after["gross"]) if after["gross"] != 0 else None
    if ra_before is not None and ra_after is not None:
        ra_dir = "improved" if ra_after > ra_before else ("worsened" if ra_after < ra_before else "unchanged")
        ra_note = f"risk-adj (PnL/$1 gross) {ra_dir} {float(ra_before):.5f}→{float(ra_after):.5f}"
    else:
        ra_note = "risk-adj n/a (zero gross)"

    if pnl_up and not gross_up:
        return "✅ GOOD", False, f"PnL up, exposure not up; {ra_note}"
    if (not pnl_up) and gross_up:
        return "❌ BAD", True, f"exposure grew with no PnL gain — reverted; {ra_note}"
    if not pnl_up and not gross_up and not gross_down and d_pnl.copy_abs() <= PNL_DEADBAND:
        return "⚠️ MIXED", False, f"no material change (within noise band); {ra_note}"
    return "⚠️ MIXED", False, ra_note


# ----------------------------- evidence-based verdict (ADR-0116) -----------------------------

def evaluate_window(points, t_hurdle, exp_frac, floor, fee_deadband=None):
    """Score a change over its evaluation WINDOW rather than a single delta (ADR-0116, ADR-0135).

    `points` — the change's trajectory as chronological (pnl, gross[, fees]) tuples (Decimals), oldest
    first, starting at the baseline and ending at the current vector. The verdict rests on the SIGN and
    STATISTICAL SIGNIFICANCE of the per-cycle risk-adjusted PnL over the window, so a real effect can be
    told from mark noise even on a tiny book, and "no evidence yet" is honest rather than a false MIXED.

    Per-cycle return r_i = ΔPnL_i / scale, where scale = max(floor, median |gross|) — flooring stops a
    near-zero book amplifying a $1 wiggle into a huge apparent edge. t = mean·√n / stdev(r).

      BAD (revert)   t ≤ −hurdle (significantly losing risk-adjusted), OR exposure grew with a
                     non-positive mean return (bought risk, earned nothing), OR — ADR-0135 — the window
                     burned more than `fee_deadband` in fees with a non-positive mean return (paid to
                     churn, earned nothing; PnL is net of fees, so flat PnL + high burn = harm).
      GOOD           t ≥ +hurdle (significantly positive) AND exposure did not grow.
      INCONCLUSIVE   otherwise — not enough evidence; the change is KEPT, not reverted.

    Pure function — deterministic, no I/O, no model (invariant 7). Returns (verdict, revert, note, stats).
    """
    th = float(t_hurdle)
    if len(points) < 3:
        return "⚠️ INCONCLUSIVE", False, f"only {len(points)} observation(s) — not enough to test", {"n": 0}
    scale = max(float(floor), statistics.median(abs(float(p[1])) for p in points))
    rets = [float(points[i + 1][0] - points[i][0]) / scale for i in range(len(points) - 1)]
    n = len(rets)
    mean = statistics.fmean(rets)
    sd = statistics.pstdev(rets) if n > 1 else 0.0
    if sd > 0:
        t = mean * math.sqrt(n) / sd
    else:
        t = 0.0 if mean == 0 else math.copysign(float("inf"), mean)
    g0 = abs(float(points[0][1]))
    g1 = abs(float(points[-1][1]))
    grew = (g1 - g0) > exp_frac_float(exp_frac) * max(g0, float(floor))

    # ADR-0135 fee-churn test: cumulative fees across the window, when both ends carry them. Fees are
    # monotonically increasing (money spent), so end - start is the burn attributable to the window.
    fee_burn = None
    if fee_deadband is not None and len(points[0]) > 2 and len(points[-1]) > 2 \
            and points[0][2] is not None and points[-1][2] is not None:
        fee_burn = points[-1][2] - points[0][2]
    churned = fee_burn is not None and fee_burn > fee_deadband and mean <= 0

    note = (f"risk-adj return/cycle {mean:+.6f} over {n} cycles, t={t:+.2f} (hurdle {th:.1f}); "
            f"gross {g0:,.0f}→{g1:,.0f}{' [grew]' if grew else ''}"
            + (f"; fees +${float(fee_burn):,.2f}" if fee_burn is not None else ""))
    stats = {"n": n, "mean_ret_per_cycle": round(mean, 8), "t": round(t, 3) if math.isfinite(t) else t,
             "scale": round(scale, 2), "gross_start": g0, "gross_end": g1, "grew": grew,
             "fee_burn": (str(fee_burn) if fee_burn is not None else None)}
    if churned:
        return "❌ BAD", True, note + " — fee churn with nothing earned (ADR-0135)", stats
    if t <= -th or (grew and mean <= 0):
        return "❌ BAD", True, note, stats
    if t >= th and not grew:
        return "✅ GOOD", False, note, stats
    return "⚠️ INCONCLUSIVE", False, note, stats


def exp_frac_float(exp_frac):
    return float(exp_frac)


def window_since(base_ts, base_mode):
    """The change's evaluation window: heartbeats recorded strictly AFTER the baseline, same feed mode
    (invariant 8), OPEN-MARKET only (ADR-0135 — a closed market's tape is frozen, so its heartbeats are
    zero-delta filler, not evidence; counting them let a hold window "fill" overnight and clear at the
    open on a verdict made of nothing), with valid numbers — returned chronological (oldest first) as
    (pnl, gross, fees) Decimals (fees None when the entry predates fee recording)."""
    try:
        with open(STATUS, "r", encoding="utf-8") as f:
            entries = json.load(f)
    except Exception:
        return []
    out = []
    for e in entries:  # STATUS is newest-first
        ts = e.get("ts")
        if not ts or not base_ts or ts <= base_ts:
            continue
        if e.get("action") == "market-closed":
            continue  # frozen tape is not evidence (ADR-0135); open-market `holding` beats ARE the evidence
        if base_mode and e.get("feedMode") and e.get("feedMode") != base_mode:
            continue
        p, g = e.get("total_pnl"), e.get("gross")
        if p in (None, "") or g in (None, ""):
            continue
        try:
            f_ = e.get("fees")
            fees = Decimal(str(f_)) if f_ not in (None, "") else None
            out.append((Decimal(str(p)), Decimal(str(g)), fees))
        except (InvalidOperation, ValueError):
            continue
    out.reverse()
    return out


# ----------------------------- ledger + snapshot writers -----------------------------

def prepend_ledger_row(row):
    with open(LEDGER, "r", encoding="utf-8") as f:
        lines = f.readlines()
    # drop the placeholder row if present
    lines = [ln for ln in lines if "no changes scored yet" not in ln]
    out, inserted = [], False
    for ln in lines:
        out.append(ln)
        if not inserted and ln.lstrip().startswith("|---"):
            out.append(row if row.endswith("\n") else row + "\n")
            inserted = True
    if not inserted:  # no table found — append one defensively
        out.append("\n" + row + "\n")
    with open(LEDGER, "w", encoding="utf-8") as f:
        f.writelines(out)


def write_snapshot(name, payload):
    os.makedirs(SNAP_DIR, exist_ok=True)
    path = os.path.join(SNAP_DIR, name)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, sort_keys=True)
    return path


def git(*args, check=True):
    return subprocess.run(["git", "-C", REPO, *args], check=check,
                          capture_output=True, text=True)


# ----------------------------- subcommands -----------------------------

def cmd_score():
    if not os.path.exists(PENDING):
        print("score: no pending change to score — nothing to do")
        return 0
    with open(PENDING, "r", encoding="utf-8") as f:
        base = json.load(f)

    try:
        after, raw_after = current_vector()
    except Exception as e:
        # App unreachable or projection empty: do NOT fabricate. Leave pending, retry next run.
        print(f"score: cannot measure current vector ({e}); leaving pending baseline for next run")
        return 0

    # Invariant 8 (ADR-0029): sim / live / replay are NEVER aggregated across modes. A sim↔live switch
    # starts a new epoch, so a baseline recorded in one mode must not be scored against a vector measured
    # in another — the delta would be meaningless and could auto-revert a good commit on garbage. If the
    # feed mode changed since the baseline, discard it (unscored) rather than compare across the boundary.
    base_mode = (base.get("source") or {}).get("feedMode")
    cur_mode = raw_after.get("feedMode")
    if base_mode and cur_mode and base_mode != cur_mode:
        os.remove(PENDING)
        git("add", "reports/.pending-baseline.json", check=False)
        git("commit", "-m",
            f"chore(ledger): feed mode {base_mode}->{cur_mode} — prior baseline discarded, not scored "
            f"across modes (invariant 8)", check=False)
        print(f"score: feed mode changed {base_mode} -> {cur_mode}; discarded the {base_mode} baseline "
              f"for {base.get('commit', 'unknown')[:9]} unscored (invariant 8 — no cross-mode aggregation)")
        return 0

    before = {
        "pnl": dec(base.get("total_pnl", base.get("alpha_pnl", "0"))),  # fallback: score an old-format baseline
        "gross": dec(base["gross_exposure"]),
        "net": dec(base["net_exposure"]),
    }
    sha = base.get("commit", "unknown")
    short = sha[:9]

    # ADR-0116: hold the change over an evaluation window and judge it by the SIGNIFICANCE of its
    # per-cycle risk-adjusted PnL, not one noisy 30-min delta. The trajectory is the heartbeats recorded
    # since the baseline (same feed mode) plus the current vector. Until MIN_CYCLES cycles have accrued
    # the change is left running and NOT scored — the prompt tells the loop to HOLD rather than pile on a
    # new change, so the evidence is about THIS change and not the next one.
    window = window_since(base.get("ts"), base_mode)
    if len(window) < MIN_CYCLES:
        print(f"score: {short} still accumulating evidence ({len(window)}/{MIN_CYCLES} open-market "
              f"cycles) — held, not scored this run")
        return 0
    base_fees = None
    if base.get("fees") not in (None, ""):
        try:
            base_fees = Decimal(str(base["fees"]))
        except (InvalidOperation, ValueError):
            base_fees = None
    points = ([(before["pnl"], before["gross"], base_fees)] + window
              + [(after["pnl"], after["gross"], after["fees"])])
    verdict, revert, note, stats = evaluate_window(points, T_HURDLE, EXP_DEADBAND_FRAC, GROSS_FLOOR,
                                                   FEE_DEADBAND)

    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    summary = base.get("summary", "")

    # Attempt the revert FIRST, so the snapshot, ledger row and heartbeat record what ACTUALLY happened
    # rather than what the verdict asked for. A silent revert failure (git conflict) would otherwise leave
    # a BAD commit LIVE while every downstream reader — the ledger note, the Improve page, next run's
    # Step 0 — believes it was pulled.
    reverted_ok = None  # None = no revert intended; True = reverted; False = revert intended but FAILED
    revert_method = None
    if revert and sha != "unknown":
        r = git("revert", "--no-edit", sha, check=False)
        if r.returncode == 0:
            reverted_ok = True
            revert_method = "git-revert"
            print(f"score: BAD verdict — reverted {short}")
        else:
            git("revert", "--abort", check=False)
            # ADR-0135: `git revert` conflicts whenever later commits touched the same code — which the
            # week of 07-28 proved happens (12 of 18 BAD reverts failed, each leaving measured-BAD code
            # LIVE for the rest of the session). Fall back to a PATH RESTORE: put the change's own files
            # (never reports/) back to their pre-change state. A forced restore has no conflict path, so
            # a BAD change is always out of the code. Honest cost (stated in the ADR): an edit someone
            # else made to the SAME files inside the window is clobbered back too — restoring to
            # known-good beats leaving measured-BAD live, and the exclusive window makes overlap rare.
            files = [f for f in git("diff-tree", "--no-commit-id", "--name-only", "-r", sha,
                                    check=False).stdout.splitlines()
                     if f.strip() and not f.startswith("reports/")]
            restored = False
            if files:
                rc = git("checkout", f"{sha}^", "--", *files, check=False)
                if rc.returncode == 0:
                    git("add", "--", *files, check=False)
                    c = git("commit", "-m",
                            f"revert(loop): restore {len(files)} file(s) to pre-{short} state — BAD "
                            f"verdict, git revert conflicted (ADR-0135)\n\n" + "\n".join(files),
                            check=False)
                    restored = c.returncode == 0
            if restored:
                reverted_ok = True
                revert_method = "path-restore"
                print(f"score: BAD verdict — `git revert {short}` conflicted; restored the change's "
                      f"files to their pre-change state instead ({len(files)} file(s))")
            else:
                reverted_ok = False
                print(f"score: BAD verdict but `git revert {short}` conflicted AND the path restore "
                      f"failed — NOT reverted; still LIVE, needs a manual revert")

    # A revert that was intended but FAILED must never read as "reverted": mark the note that lands in both
    # the ledger and the snapshot, so the record states plainly that the commit is still live.
    if reverted_ok is False:
        note = note + (" — ⚠️ REVERT FAILED (git conflict + restore failure): the BAD commit is STILL "
                       "LIVE and needs a manual revert")
    elif revert_method == "path-restore":
        note = note + " — reverted via path restore (git revert conflicted; ADR-0135)"

    # Audited snapshot — every number a verdict rests on, recomputable by anyone. `revertApplied` records
    # the ACTUAL git outcome (True = reverted / False = intended but failed / None = none intended),
    # distinct from `revert` (what the verdict ASKED for) so a failure can never masquerade as a revert.
    snap_name = f"{ts.replace(':', '').replace('-', '')}-{short}.json"
    snap = {
        "scoredAt": ts, "commit": sha, "summary": summary,
        "verdict": verdict, "revert": revert, "revertApplied": reverted_ok,
        "revertMethod": revert_method, "note": note,
        "method": {"minCycles": MIN_CYCLES, "tHurdle": str(T_HURDLE),
                   "grossFloorUsd": str(GROSS_FLOOR), "exposureFrac": str(EXP_DEADBAND_FRAC),
                   "feeDeadbandUsd": str(FEE_DEADBAND)},
        "windowStats": stats,
        "before": {"total_pnl": base.get("total_pnl", base.get("alpha_pnl")),
                   "gross": base["gross_exposure"], "net": base["net_exposure"], "at": base.get("ts")},
        "after": {"total_pnl": str(after["pnl"]), "gross": str(after["gross"]), "net": str(after["net"]),
                  "fees": str(after["fees"]), "at": ts, "source": raw_after},
        "delta": {"total_pnl": str(after["pnl"] - before["pnl"]),
                  "gross": str(after["gross"] - before["gross"]),
                  "net": str(after["net"] - before["net"])},
    }
    snap_path = write_snapshot(snap_name, snap)

    row = "| {ts} | `{short}` | {what} | {pnl} | {gross} | {net} | {verdict} | {note} |".format(
        ts=ts, short=short, what=(summary or "—").replace("|", "/"),
        pnl=pair(before["pnl"], after["pnl"]),
        gross=pair(before["gross"], after["gross"]),
        net=pair(before["net"], after["net"]),
        verdict=verdict, note=note.replace("|", "/"))
    prepend_ledger_row(row)

    os.remove(PENDING)
    git("add", "reports/", check=False)
    msg = f"chore(ledger): score {short} — {verdict}\n\n{note}\n\nSnapshot: {os.path.relpath(snap_path, REPO)}"
    git("commit", "-m", msg, check=False)

    print(f"score: {verdict} for {short} | ΔPnL {delta(after['pnl'] - before['pnl'])} "
          f"| Δgross {delta(after['gross'] - before['gross'])} | {note}")
    if reverted_ok is False:
        return 3
    return 0


def cmd_baseline(argv):
    if len(argv) < 2:
        print("usage: score-change.py baseline <sha> <summary...>", file=sys.stderr)
        return 2
    # ADR-0135: ONE change in flight, mechanically. While a pending baseline exists its change is still
    # under measurement — a second baseline would both orphan the first (never scored) and contaminate
    # its window with a new treatment. The prompt-level HOLD alone did not stop this (35 changes in 4
    # days, week of 07-28), so the scorer refuses outright.
    if os.path.exists(PENDING):
        try:
            with open(PENDING, "r", encoding="utf-8") as f:
                pend = json.load(f)
            pshort = (pend.get("commit") or "unknown")[:9]
        except Exception:
            pshort = "unknown"
        print(f"baseline: REFUSED — change {pshort} is still under evaluation (ADR-0135: one change in "
              f"flight). HOLD: no new change until it is scored.", file=sys.stderr)
        return 3
    sha = argv[0]
    summary = " ".join(argv[1:]).strip()
    try:
        vec, raw = current_vector()
    except Exception as e:
        print(f"baseline: cannot read current vector ({e}) — NOT recording a baseline "
              f"(the change will be unscored rather than scored against a fake number)")
        return 1
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    payload = {
        "commit": sha, "ts": ts, "summary": summary,
        "total_pnl": str(vec["pnl"]),
        "gross_exposure": str(vec["gross"]),
        "net_exposure": str(vec["net"]),
        "fees": str(vec["fees"]),
        "source": raw,
    }
    os.makedirs(os.path.dirname(PENDING), exist_ok=True)
    with open(PENDING, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, sort_keys=True)
    git("add", "reports/.pending-baseline.json", check=False)
    git("commit", "-m", f"chore(ledger): baseline for {sha[:9]} — {summary}"[:200], check=False)
    print(f"baseline: recorded for {sha[:9]} | total_pnl {money2(vec['pnl'])} "
          f"| gross {money2(vec['gross'])} | net {money2(vec['net'])}")
    return 0


def newest_snapshot():
    if not os.path.isdir(SNAP_DIR):
        return None
    files = sorted(f for f in os.listdir(SNAP_DIR) if f.endswith(".json"))
    if not files:
        return None  # snapshot names are timestamp-prefixed, so lexical sort == chronological
    with open(os.path.join(SNAP_DIR, files[-1]), "r", encoding="utf-8") as f:
        return json.load(f)


def cmd_status(argv):
    """Append one deterministic per-cycle heartbeat entry to reports/run-status.json (the UI feed).

    Every number (current vector + % change vs the previous run) is computed here, in exact decimal —
    never by the model. The wrapper passes only two booleans about what happened this cycle:
      --scored 0|1     a pending change was scored this cycle (so a fresh snapshot verdict exists)
      --changed 0|1    the agent recorded a NEW change this cycle (a new pending baseline)
      --brain-ran 0|1  did the Claude analysis step actually run? (0 = it was skipped, e.g. claude
                       not found) — surfaced so a silent brain-down never masquerades as "no change"
    """
    scored = changed = market_closed = holding = False
    brain_ran = True  # default true for backward compat if the flag isn't passed
    it = iter(argv)
    for a in it:
        if a == "--scored":
            scored = next(it, "0") == "1"
        elif a == "--changed":
            changed = next(it, "0") == "1"
        elif a == "--brain-ran":
            brain_ran = next(it, "1") == "1"
        elif a == "--market-closed":
            market_closed = next(it, "0") == "1"
        elif a == "--holding":
            holding = next(it, "0") == "1"
    if market_closed:
        brain_ran = False  # the market-closed cycle skips the analysis entirely — no model call
    if holding:
        brain_ran = False  # ADR-0135: the hold cycle skips the model BY DESIGN — evidence is accruing

    available = True
    vec = raw = None
    err = ""
    try:
        vec, raw = current_vector()
    except Exception as e:
        available = False
        err = str(e)

    entries = []
    if os.path.exists(STATUS):
        try:
            with open(STATUS, "r", encoding="utf-8") as f:
                entries = json.load(f)
        except Exception:
            entries = []
    prev = entries[0] if entries else None
    cur_mode = raw.get("feedMode") if raw else None

    def comparable(entry):
        # Invariant 8 (ADR-0029): never compute a delta / growth across feed modes. If either side's
        # mode is unknown we can't tell, so we don't block; a KNOWN mismatch (e.g. this run is LIVE, the
        # entry we'd compare to is SIM) is not comparable and yields no number until same-mode history
        # accumulates.
        if not entry:
            return False
        em = entry.get("feedMode")
        if em is None or cur_mode is None:
            return True
        return em == cur_mode

    def pct(cur, *prev_keys):
        if not comparable(prev):
            return None
        pv = None
        for k in prev_keys:  # try new key first, fall back to any old-format key
            if prev and prev.get(k) not in (None, ""):
                pv = prev.get(k)
                break
        if pv is None:
            return None
        p = Decimal(str(pv))
        if p == 0:
            return None
        return round(float((cur - p) / abs(p) * 100), 2)

    # Owner target: total PnL up >= PNL_TARGET_PCT every PNL_TARGET_WINDOW iterations. Measured vs the
    # entry WINDOW iterations back (positive = improvement, even climbing out of a loss). Staleness — a
    # flat/negative PnL not on track — is a failure the loop must act on, surfaced here for the UI + prompt.
    def growth_over(window):
        if not available or len(entries) < window:
            return None
        if not comparable(entries[window - 1]):  # invariant 8: don't measure growth across a mode switch
            return None
        ov = entries[window - 1].get("total_pnl", entries[window - 1].get("alpha_pnl"))
        if ov in (None, ""):
            return None
        o = Decimal(str(ov))
        if o == 0:
            return None
        return round(float((vec["pnl"] - o) / abs(o) * 100), 2)

    pnl_growth = growth_over(PNL_TARGET_WINDOW)
    on_track = pnl_growth is not None and Decimal(str(pnl_growth)) >= PNL_TARGET_PCT
    underwater = available and vec["pnl"] <= 0
    # A closed-market cycle is expected to be flat — never flag it as a staleness FAILURE (ADR-0063).
    # A holding cycle is likewise not stale (ADR-0135): evidence is accruing by design, and staleness
    # pressure during a hold is exactly the every-cycle-change thrash the ADR removes.
    stale = (not market_closed) and (not holding) and available and pnl_growth is not None and not on_track

    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    head = git("rev-parse", "--short", "HEAD", check=False).stdout.strip()
    snap = newest_snapshot() if scored else None
    last_verdict = snap.get("verdict") if snap else None
    revert_intended = bool(snap and snap.get("revert"))
    # `revertApplied` is the ACTUAL git outcome (absent on pre-fix snapshots → assume it applied, the old
    # behaviour). A revert intended but not applied is a FAILURE the next run's Step 0 must see without
    # reading git — a BAD commit is still live.
    revert_applied = snap.get("revertApplied") if snap else None
    revert_failed = revert_intended and revert_applied is False
    reverted = revert_intended and not revert_failed

    # Claude's own reasoning for this cycle, if it wrote one (reports/last-analysis.md). Only trust it
    # when the brain actually ran this cycle — a stale file from a prior run must not look current.
    reasoning = ""
    if brain_ran and os.path.exists(ANALYSIS):
        try:
            with open(ANALYSIS, "r", encoding="utf-8") as f:
                reasoning = f.read().strip()
        except Exception:
            reasoning = ""
    analysis_line = reasoning.splitlines()[0].strip() if reasoning else ""

    if market_closed:
        action = "market-closed"
    elif holding:
        action = "holding"
    elif not brain_ran:
        action = "no-analysis"
    elif revert_failed:
        action = "revert-failed"
    else:
        action = "reverted" if reverted else ("changed" if changed else "no-change")

    if market_closed:
        decision = ("🌙 Market closed — analysis skipped this cycle (no Claude call). The US session is "
                    "closed, so the tape is frozen and there is nothing to analyse; the loop resumes at "
                    "the next open. Flat/unchanged here is expected, not a failure.")
    elif holding:
        # ADR-0135: a deployed book left alone while a change accrues evidence is the loop WORKING —
        # a first-class success state, never idleness or staleness. Show what's measured and how far.
        pshort, psumm, progress = "", "", ""
        if os.path.exists(PENDING):
            try:
                with open(PENDING, "r", encoding="utf-8") as f:
                    pend = json.load(f)
                pshort = (pend.get("commit") or "")[:9]
                psumm = pend.get("summary", "")
                n = len(window_since(pend.get("ts"), (pend.get("source") or {}).get("feedMode")))
                progress = f" ({n}/{MIN_CYCLES} open-market cycles)"
            except Exception:
                pass
        decision = (f"⏸ HOLD — change {pshort or 'pending'} under evaluation{progress}; no model call, "
                    f"no new change until it is scored (ADR-0135). "
                    + (f"Measuring: {psumm}" if psumm else ""))
    elif not brain_ran:
        decision = ("⚠️ ANALYSIS STEP DID NOT RUN this cycle — `claude` was not invoked (not found on "
                    "PATH?). No diagnosis was made; the heartbeat/score still ran. Fix the loop's PATH.")
    elif not available:
        decision = f"app unreachable — not measured ({err})"
    elif revert_failed:
        short = (snap.get("commit") or "")[:9]
        decision = (f"⚠️ {short} scored {last_verdict} but the auto-revert FAILED (git conflict) — the "
                    f"commit is STILL LIVE and needs a manual revert; NOT backed off")
    elif reverted:
        short = (snap.get("commit") or "")[:9]
        decision = f"backed off {short} ({last_verdict}) — next run tries a different lever"
    elif changed:
        summ = ""
        if os.path.exists(PENDING):
            try:
                with open(PENDING, "r", encoding="utf-8") as f:
                    summ = json.load(f).get("summary", "")
            except Exception:
                pass
        decision = f"made a change: {summ or analysis_line}" + (f" · prev {last_verdict}" if last_verdict else "")
    else:
        # no change — show Claude's actual stated reason, not a generic placeholder
        decision = (analysis_line or "no change this cycle") + (f" · prev {last_verdict}" if last_verdict else "")

    entry = {
        "ts": ts,
        "feedMode": (raw.get("feedMode") if raw else None),
        "available": available,
        "brain_ran": brain_ran,
        "total_pnl": (str(vec["pnl"]) if available else None),
        "gross": (str(vec["gross"]) if available else None),
        "net": (str(vec["net"]) if available else None),
        "fees": (str(vec["fees"]) if available else None),
        "pnl_pct": (pct(vec["pnl"], "total_pnl", "alpha_pnl") if available else None),
        "gross_pct": (pct(vec["gross"], "gross") if available else None),
        "pnl_growth_pct": pnl_growth,                 # % PnL change vs WINDOW iterations ago
        "pnl_growth_window": PNL_TARGET_WINDOW,
        "pnl_target_pct": float(PNL_TARGET_PCT),      # owner target: >= this every WINDOW iters
        "on_track": on_track,
        "stale": stale,
        "underwater": underwater,
        "action": action,
        "last_verdict": last_verdict,
        "revert_failed": revert_failed,   # a BAD verdict whose git revert conflicted — commit still live
        "decision": decision,
        "reasoning": reasoning,
        "commit": head,
    }
    entries.insert(0, entry)
    entries = entries[:STATUS_CAP]
    os.makedirs(os.path.dirname(STATUS), exist_ok=True)
    with open(STATUS, "w", encoding="utf-8") as f:
        json.dump(entries, f, indent=2)
    git("add", "reports/run-status.json", check=False)
    git("commit", "-m", f"chore(status): run {ts} — {action}", check=False)
    print(f"status: {action} | total_pnl {entry['total_pnl']} ({entry['pnl_pct']}%) "
          f"| gross {entry['gross']} ({entry['gross_pct']}%) | {decision}")
    return 0


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    cmd, rest = argv[0], argv[1:]
    if cmd == "score":
        return cmd_score()
    if cmd == "baseline":
        return cmd_baseline(rest)
    if cmd == "status":
        return cmd_status(rest)
    print(f"unknown subcommand: {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except (InvalidOperation, ValueError) as e:
        print(f"score-change: refusing to proceed on non-numeric/boundary data: {e}", file=sys.stderr)
        sys.exit(2)
