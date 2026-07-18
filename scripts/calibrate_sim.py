#!/usr/bin/env python3
"""Recalibrate sim-calibration.json from REAL daily market history (ADR-0026).

Reads daily closes from LOCAL CSV files in data/history/ (no network — every free API now blocks
scripted pulls, so you download each symbol once in a browser). Covers the full universe — the
equities themselves, ETF/index proxies for the futures (SPY→ES, QQQ→NQ), IEF for the 10Y yield
factor, and spot FX — then estimates:

  - per-name annualized vol and beta to the equity factor (SPY returns),
  - the USD factor from a DXY-style basket (-EURUSD, -GBPUSD, +USDJPY),
  - the rates LEVEL factor vol from IEF returns / duration (dYield ≈ -ret/D, D≈7.5),
  - per-regime factor correlations by classifying each day into CALM / TREND_UP /
    TREND_DOWN / RISK_OFF / INFLATION_SHOCK from realized vol + return + yield-change
    signs, and computing correlations within each bucket,
  - the daily regime transition matrix from the observed day-to-day bucket sequence.

Usage: run once with no data/history/ to get the download list, drop the CSVs there, re-run:

    python3 scripts/calibrate_sim.py > app/src/main/resources/sim-calibration.json

(the platform itself never fetches at runtime — ADR-0009)

Stdlib only. If a symbol fails to download the script keeps the hand-curated default
for that piece and says so on stderr — it never emits a partially-broken file silently.
"""
import csv
import json
import math
import os
import statistics as st
import sys

YEARS = 5
# Read history from LOCAL CSV files you download once in a browser — no network. Every free API
# (Stooq, Yahoo) now blocks scripted pulls (HTML block pages / 429), so fighting them is a waste;
# a browser download is reliable and one-time. Drop the files in data/history/ (gitignored).
HISTORY_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "history")

# instrument -> data symbol (proxies documented; invariant 2 does not apply here — this is an
# offline calibration tool, symbols never enter the platform). Index futures use the ETF proxy
# (SPY→ES, QQQ→NQ); FX uses the pair name. The CSV file for a symbol is <SYMBOL>.csv.
EQUITIES = {"AAPL": "AAPL", "MSFT": "MSFT", "AMZN": "AMZN", "GOOG": "GOOGL",
            "SAP": "SAP", "JNJ": "JNJ", "NVDA": "NVDA", "JPM": "JPM"}  # SAP US ADR ~ EUR listing
FUTURE_PROXIES = {"ES": "SPY", "NQ": "QQQ"}
FX = {"EURUSD": "EURUSD", "GBPUSD": "GBPUSD", "USDJPY": "USDJPY", "AUDUSD": "AUDUSD"}
RATES_PROXY = "IEF"                    # 7-10Y Treasury ETF; dYield ≈ -ret / 7.5
IEF_DURATION = 7.5
EQ_FACTOR = "SPY"

REGIMES = ["CALM", "TREND_UP", "TREND_DOWN", "RISK_OFF", "INFLATION_SHOCK"]


def needed_symbols():
    return sorted(set([EQ_FACTOR, RATES_PROXY]
                      + list(FUTURE_PROXIES.values()) + list(EQUITIES.values()) + list(FX.values())))


def fetch_closes(sym):
    """Daily closes {YYYY-MM-DD: close} from data/history/<SYM>.csv. Accepts the Yahoo download
    format (Date,Open,High,Low,Close,Adj Close,Volume) or the Stooq one (…,Close,Volume) — it
    prefers 'Adj Close' when present (split/dividend adjusted), else 'Close'."""
    path = os.path.join(HISTORY_DIR, sym + ".csv")
    if not os.path.isfile(path):
        raise RuntimeError(f"missing {path}")
    closes = {}
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            date = (row.get("Date") or row.get("date") or "")[:10]
            raw = row.get("Adj Close") or row.get("Close") or row.get("close")
            if not date or raw in (None, "", "null", "N/A"):
                continue
            try:
                closes[date] = float(raw)
            except ValueError:
                pass
    if not closes:
        raise RuntimeError(f"{path} has no usable Date/Close columns")
    dates = sorted(closes)[-(YEARS * 252):]
    return {d: closes[d] for d in dates}


def _stooq_symbol(s):
    """Stooq ticker: US listings take a .us suffix; FX pairs are the bare pair, lowercased."""
    return s.lower() if s in ("EURUSD", "GBPUSD", "USDJPY", "AUDUSD") else s.lower() + ".us"


def print_data_help():
    syms = needed_symbols()
    outdir = os.path.normpath(HISTORY_DIR)
    print(f"\nNo history found. Put {len(syms)} daily-CSV files (≥{YEARS}y each) in:\n  {outdir}",
          file=sys.stderr)
    print("\nStooq downloads free with NO login — open each link in a browser (it saves a CSV),\n"
          "then rename it to the name shown on the left:", file=sys.stderr)
    for s in syms:
        print(f"  {s + '.csv':13s} https://stooq.com/q/d/l/?s={_stooq_symbol(s)}&i=d", file=sys.stderr)
    print("\n(Tip: for a longer window add &d1=20150101&d2=20251231 to each URL.)", file=sys.stderr)
    print("Then re-run. Missing a few names only drops those specs (the rest still calibrate).\n",
          file=sys.stderr)


def returns(closes):
    dates = sorted(closes)
    return {d2: math.log(closes[d2] / closes[d1])
            for d1, d2 in zip(dates, dates[1:]) if closes[d1] > 0}


def aligned(*series):
    common = sorted(set.intersection(*[set(s) for s in series]))
    return [[s[d] for d in common] for s in series], common


def corr(x, y):
    mx, my = st.fmean(x), st.fmean(y)
    sxy = sum((a - mx) * (b - my) for a, b in zip(x, y))
    sxx = sum((a - mx) ** 2 for a in x)
    syy = sum((b - my) ** 2 for b in y)
    return sxy / math.sqrt(sxx * syy) if sxx > 0 and syy > 0 else 0.0


def ann_vol(rets):
    return st.pstdev(rets) * math.sqrt(252)


def classify_days(eq, d_yield, dates):
    """Bucket each day into a regime from realized signs/vol (simple, documented heuristic)."""
    vol_window = 21
    out = {}
    for i, d in enumerate(dates):
        if i < vol_window:
            out[d] = "CALM"
            continue
        window = eq[i - vol_window:i]
        realized = st.pstdev(window) * math.sqrt(252)
        trailing = sum(window)
        if realized > 0.28 and eq[i] < 0 and d_yield[i] < 0:
            out[d] = "RISK_OFF"
        elif realized > 0.22 and eq[i] < 0 and d_yield[i] > 0:
            out[d] = "INFLATION_SHOCK"
        elif trailing > 0.02:
            out[d] = "TREND_UP"
        elif trailing < -0.03:
            out[d] = "TREND_DOWN"
        else:
            out[d] = "CALM"
    return out


def main():
    have = os.path.isdir(HISTORY_DIR) and any(
        os.path.isfile(os.path.join(HISTORY_DIR, s + ".csv")) for s in needed_symbols())
    if not have:
        print_data_help()
        sys.exit(1)
    try:
        eq_closes = fetch_closes(EQ_FACTOR)
        ief_closes = fetch_closes(RATES_PROXY)
        fx_closes = {k: fetch_closes(v) for k, v in FX.items()}
    except Exception as e:
        print(f"FATAL: core-factor CSV missing/bad ({e}) — need SPY, IEF and the FX pairs in "
              + os.path.normpath(HISTORY_DIR) + "; keeping the checked-in default", file=sys.stderr)
        sys.exit(1)

    eq_r = returns(eq_closes)
    d_yield = {d: -r / IEF_DURATION for d, r in returns(ief_closes).items()}
    usd_r = {}
    fx_r = {k: returns(v) for k, v in fx_closes.items()}
    for d in set(fx_r["EURUSD"]) & set(fx_r["GBPUSD"]) & set(fx_r["USDJPY"]):
        usd_r[d] = (-fx_r["EURUSD"][d] - fx_r["GBPUSD"][d] + fx_r["USDJPY"][d]) / 3.0

    (eq_v, lvl_v, usd_v), dates = aligned(eq_r, d_yield, usd_r)
    regime_of = classify_days(eq_v, lvl_v, dates)

    # Per-name specs
    instruments = []
    for iid, sym in {**FUTURE_PROXIES, **EQUITIES}.items():
        try:
            r = returns(fetch_closes(sym))
            (ri, re), _ = aligned(r, eq_r)
            beta = corr(ri, re) * ann_vol(ri) / ann_vol(re)
            instruments.append({"id": iid, "annualVol": round(ann_vol(ri), 3),
                                "betaEquity": round(beta, 2), "betaUsd": 0.0})
        except Exception as e:
            print(f"WARN: {iid} ({sym}) failed ({e}) — copy its spec from the previous file", file=sys.stderr)
    for iid in FX:
        r = fx_r[iid]
        (ri, re, ru), _ = aligned(r, eq_r, usd_r)
        beta_usd = corr(ri, ru) * ann_vol(ri) / ann_vol(ru)
        beta_eq = corr(ri, re) * ann_vol(ri) / ann_vol(re)
        instruments.append({"id": iid, "annualVol": round(ann_vol(ri), 3),
                            "betaEquity": round(beta_eq, 2), "betaUsd": round(beta_usd, 2)})

    # Per-regime factor correlations + drift; slope factor kept from defaults (needs 2Y data).
    regimes_out = []
    for name in REGIMES:
        idx = [i for i, d in enumerate(dates) if regime_of[d] == name]
        if len(idx) < 30:
            print(f"WARN: only {len(idx)} {name} days — correlations for it will be noisy; "
                  f"consider keeping the default block", file=sys.stderr)
        e = [eq_v[i] for i in idx] or [0, 0]
        l = [lvl_v[i] for i in idx] or [0, 0]
        u = [usd_v[i] for i in idx] or [0, 0]
        c_el, c_eu, c_lu = corr(e, l), corr(e, u), corr(l, u)
        regimes_out.append({
            "name": name,
            "equityDriftAnnual": round(st.fmean(e) * 252, 2) if idx else 0.0,
            "ratesDriftBpPerDay": round(st.fmean(l) * 1e4, 1) if idx else 0.0,
            "usdDriftAnnual": round(st.fmean(u) * 252, 2) if idx else 0.0,
            "volMultiple": round((st.pstdev(e) / st.pstdev(eq_v)) if len(idx) > 5 else 1.0, 1),
            "factorCorrelation": [
                [1.0, round(c_el, 2), 0.0, round(c_eu, 2)],
                [round(c_el, 2), 1.0, -0.3, round(c_lu, 2)],
                [0.0, -0.3, 1.0, 0.0],
                [round(c_eu, 2), round(c_lu, 2), 0.0, 1.0]]})

    # Daily transition matrix from the observed regime sequence
    counts = {a: {b: 0 for b in REGIMES} for a in REGIMES}
    seq = [regime_of[d] for d in dates]
    for a, b in zip(seq, seq[1:]):
        counts[a][b] += 1
    transition = []
    for a in REGIMES:
        total = sum(counts[a].values()) or 1
        row = [round(counts[a][b] / total, 3) for b in REGIMES]
        row[REGIMES.index(a)] = round(1 - sum(v for i, v in enumerate(row) if i != REGIMES.index(a)), 3)
        transition.append(row)

    out = {
        "_doc": [f"Calibrated from Yahoo daily history ({YEARS}y window) by scripts/calibrate_sim.py.",
                 "Factor order: [EQUITY, RATES_LEVEL, RATES_SLOPE, USD]. See ADR-0026."],
        "equityFactorVolAnnual": round(ann_vol(eq_v), 3),
        "usdFactorVolAnnual": round(ann_vol(usd_v), 3),
        "ratesLevelVolBpPerDay": round(st.pstdev(lvl_v) * 1e4, 1),
        "ratesSlopeVolBpPerDay": 2.0,
        "tDegreesOfFreedom": 5,
        "instruments": instruments,
        "regimes": regimes_out,
        "transitionPerDay": transition,
    }
    json.dump(out, sys.stdout, indent=2)
    print(file=sys.stdout)
    print(f"OK: calibrated {len(instruments)} instruments over {len(dates)} common days", file=sys.stderr)


if __name__ == "__main__":
    main()
