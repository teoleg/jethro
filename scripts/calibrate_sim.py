#!/usr/bin/env python3
"""Recalibrate sim-calibration.json from REAL daily market history (ADR-0026).

Pulls free daily closes from Yahoo's chart JSON (no API key) for the full universe — the equities
themselves, ETF/index proxies for the futures (SPY→ES, QQQ→NQ), IEF for the 10Y yield
factor, and spot FX — then estimates:

  - per-name annualized vol and beta to the equity factor (SPY returns),
  - the USD factor from a DXY-style basket (-EURUSD, -GBPUSD, +USDJPY),
  - the rates LEVEL factor vol from IEF returns / duration (dYield ≈ -ret/D, D≈7.5),
  - per-regime factor correlations by classifying each day into CALM / TREND_UP /
    TREND_DOWN / RISK_OFF / INFLATION_SHOCK from realized vol + return + yield-change
    signs, and computing correlations within each bucket,
  - the daily regime transition matrix from the observed day-to-day bucket sequence.

Usage (on a machine with internet — the platform itself never fetches, ADR-0009):

    python3 scripts/calibrate_sim.py > app/src/main/resources/sim-calibration.json

Stdlib only. If a symbol fails to download the script keeps the hand-curated default
for that piece and says so on stderr — it never emits a partially-broken file silently.
"""
import datetime
import json
import math
import statistics as st
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

YEARS = 5
# Yahoo chart JSON (keyless, the same host the live adapter uses). Stooq's CSV endpoint now
# serves an anti-bot HTML page to scripts, so it's no longer usable here.
YAHOO = "https://query1.finance.yahoo.com/v8/finance/chart/{sym}?interval=1d&range={yrs}y"

# instrument -> Yahoo symbol (proxies documented; invariant 2 does not apply here — this is an
# offline calibration tool, symbols never enter the platform). Equities are their own tickers;
# index futures use the ETF proxy (SPY→ES, QQQ→NQ); FX uses Yahoo's "=X" spot symbols.
EQUITIES = {"AAPL": "AAPL", "MSFT": "MSFT", "AMZN": "AMZN", "GOOG": "GOOGL",
            "SAP": "SAP", "JNJ": "JNJ", "NVDA": "NVDA", "JPM": "JPM"}  # SAP US ADR ~ EUR listing
FUTURE_PROXIES = {"ES": "SPY", "NQ": "QQQ"}
FX = {"EURUSD": "EURUSD=X", "GBPUSD": "GBPUSD=X", "USDJPY": "USDJPY=X", "AUDUSD": "AUDUSD=X"}
RATES_PROXY = "IEF"                    # 7-10Y Treasury ETF; dYield ≈ -ret / 7.5
IEF_DURATION = 7.5
EQ_FACTOR = "SPY"

REGIMES = ["CALM", "TREND_UP", "TREND_DOWN", "RISK_OFF", "INFLATION_SHOCK"]


# Yahoo serves the chart JSON to a normal browser UA (a bare urllib UA is sometimes blocked).
UA = ("Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")


def _get_json(url, tries=5):
    """GET with polite throttle + exponential backoff on Yahoo's 429 rate limiting."""
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < tries - 1:
                wait = 5 * (2 ** attempt)  # 5, 10, 20, 40s — Yahoo throttles bursts hard
                print(f"  … 429 rate-limited, backing off {wait}s", file=sys.stderr)
                time.sleep(wait)
                continue
            raise
    raise RuntimeError("unreachable")


def fetch_closes(sym):
    """Daily adjusted closes {YYYY-MM-DD: close} for a Yahoo symbol (last YEARS years)."""
    url = YAHOO.format(sym=urllib.parse.quote(sym), yrs=YEARS)
    time.sleep(1.5)  # trickle between symbols so we don't trip the rate limit in the first place
    data = _get_json(url)
    chart = data.get("chart") or {}
    if not chart.get("result"):
        err = (chart.get("error") or {}).get("description", "no data")
        raise RuntimeError(f"yahoo returned no data for {sym}: {err}")
    result = chart["result"][0]
    stamps = result.get("timestamp") or []
    ind = result.get("indicators", {})
    # Prefer adjusted close (splits/dividends) so returns are clean; fall back to raw close.
    series = None
    if ind.get("adjclose") and ind["adjclose"][0].get("adjclose"):
        series = ind["adjclose"][0]["adjclose"]
    elif ind.get("quote") and ind["quote"][0].get("close"):
        series = ind["quote"][0]["close"]
    if not stamps or not series:
        raise RuntimeError(f"yahoo payload for {sym} had no close series")
    out = {}
    for t, c in zip(stamps, series):
        if c is None:
            continue
        d = datetime.datetime.utcfromtimestamp(t).strftime("%Y-%m-%d")
        out[d] = float(c)
    if not out:
        raise RuntimeError(f"yahoo close series for {sym} was all null")
    dates = sorted(out)[-(YEARS * 252):]
    return {d: out[d] for d in dates}


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
    try:
        eq_closes = fetch_closes(EQ_FACTOR)
        ief_closes = fetch_closes(RATES_PROXY)
        fx_closes = {k: fetch_closes(v) for k, v in FX.items()}
    except Exception as e:
        print(f"FATAL: could not fetch core factors ({e}) — keep the checked-in default", file=sys.stderr)
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
