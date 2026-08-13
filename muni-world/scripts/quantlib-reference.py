#!/usr/bin/env python3
"""Generate QuantLib reference prices for cross-validating the muni-world lattice (ADR-0019).

Run offline (pip install QuantLib), commit the JSON it writes to
src/test/resources/quantlib-reference.json — QuantLibCrossValidationTest replays it against our engine.
QuantLib never becomes a runtime dependency; it is the independent oracle our numbers must agree with.

Convention matching (must mirror the Java engine EXACTLY, or the comparison tests conventions, not math):
  * flat zero curve, CONTINUOUS compounding, Thirty360(BondBasis) day count everywhere -> every
    semiannual period is exactly 0.5y and df(t) = exp(-y*t) at exact half-year t, like the Java side.
  * NullCalendar, Unadjusted, settlement 0 -> no business-day noise in either engine.
  * calls: Bermudan, CLEAN strike, on every coupon date from first call to the last before maturity —
    the same exercise set the Java lattice checks (min(continuation, K) at post-coupon nodes).
  * short-rate model: BlackKarasinski(a=1e-6, sigma). With a -> 0, d ln r = theta dt + sigma dW — the
    same continuous model as constant-sigma BDT; QL builds a trinomial tree, we build a binomial one,
    so callable prices converge to each other as steps grow (bullets must match to float precision).
"""
import json
import QuantLib as ql

TODAY = ql.Date(15, 6, 2026)
ql.Settings.instance().evaluationDate = TODAY
DC = ql.Thirty360(ql.Thirty360.BondBasis)
CAL = ql.NullCalendar()
QL_TREE_STEPS = 500        # converged reference for the callable cases
A_TINY = 1e-6              # BK mean reversion ~0 == constant-sigma BDT in the continuous limit


def curve(y_pct):
    return ql.YieldTermStructureHandle(
        ql.FlatForward(TODAY, y_pct / 100.0, DC, ql.Continuous))


def schedule(years):
    end = TODAY + ql.Period(int(years * 12), ql.Months)
    return ql.Schedule(TODAY, end, ql.Period(ql.Semiannual), CAL,
                       ql.Unadjusted, ql.Unadjusted, ql.DateGeneration.Backward, False)


def bullet_clean(y_pct, coupon_pct, years):
    bond = ql.FixedRateBond(0, 100.0, schedule(years), [coupon_pct / 100.0], DC)
    bond.setPricingEngine(ql.DiscountingBondEngine(curve(y_pct)))
    return bond.cleanPrice()


def callable_clean(y_pct, coupon_pct, years, call_years, strike, sigma):
    sched = schedule(years)
    calls = ql.CallabilitySchedule()
    for d in list(sched)[1:-1]:                       # coupon dates strictly inside (settle, maturity)
        if DC.yearFraction(TODAY, d) >= call_years - 1e-9:
            calls.append(ql.Callability(
                ql.BondPrice(strike, ql.BondPrice.Clean), ql.Callability.Call, d))
    bond = ql.CallableFixedRateBond(0, 100.0, sched, [coupon_pct / 100.0], DC,
                                    ql.Unadjusted, 100.0, TODAY, calls)
    model = ql.BlackKarasinski(curve(y_pct), A_TINY, sigma)
    bond.setPricingEngine(ql.TreeCallableFixedRateBondEngine(model, QL_TREE_STEPS))
    return bond.cleanPrice()


cases = []

# --- bullets: engine-exact on both sides -> float-precision agreement expected --------------------
for y in (3.0, 5.0):
    for c in (0.0, 4.0, 6.0):
        for t in (1, 10, 30):
            cases.append({"kind": "bullet", "flatYPct": y, "couponPct": c, "years": t,
                          "qlClean": bullet_clean(y, c, t)})

# --- callables: production-config accuracy measurement (our semiannual binomial vs QL's 500-step
#     trinomial) — the observed gap IS the discretization error we report ---------------------------
for y, c, sig in [(3.0, 4.0, 0.10), (3.0, 4.0, 0.15), (3.0, 4.0, 0.20),
                  (3.0, 6.0, 0.15), (5.0, 4.0, 0.15), (3.0, 4.0, 1e-4)]:
    cases.append({"kind": "callable", "flatYPct": y, "couponPct": c, "years": 10,
                  "callYears": 5, "strike": 100.0, "sigma": sig,
                  "qlClean": callable_clean(y, c, 10, 5, 100.0, sig)})

out = {"quantlib": ql.__version__, "evaluationDate": str(TODAY), "treeSteps": QL_TREE_STEPS,
       "blackKarasinskiA": A_TINY, "cases": cases}
print(json.dumps(out, indent=1))
