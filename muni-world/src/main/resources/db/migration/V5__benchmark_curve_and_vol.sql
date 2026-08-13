-- ADR-0017 — the two model inputs no Official Statement can supply: a BENCHMARK CURVE and a RATE
-- VOLATILITY. Both come from free, official, end-of-day sources; nothing here is a purchased feed and
-- nothing here is a self-chosen constant.
--
-- Exactness (invariant 1 / ADR-0017 §4): the curve FIT is transcendental and computed in floating point,
-- but its RESULT lands here as NUMERIC at a declared scale and every downstream cashflow, price and PnL
-- is exact decimal from this point on.

-- The daily curve fit: six Nelson-Siegel-Svensson parameters reconstruct the whole zero curve at any
-- maturity, so this table IS the curve — curve_point below is a materialised convenience, not the source.
-- Parameters are stored in PERCENT, verbatim as the Fed publishes them (no unit conversion on the way in,
-- so a row can always be diffed against the published file).
CREATE TABLE IF NOT EXISTS muni.curve_fit (
    source   TEXT        NOT NULL,          -- 'GSW' (Fed FEDS 2006-28), 'MUNI_RATIO', ...
    as_of    DATE        NOT NULL,          -- the date the curve was PUBLISHED for, never the fetch date
    beta0    NUMERIC(12,8) NOT NULL,        -- level, percent
    beta1    NUMERIC(12,8) NOT NULL,        -- slope, percent  (short rate = beta0 + beta1)
    beta2    NUMERIC(12,8) NOT NULL,        -- first hump, percent
    beta3    NUMERIC(12,8) NOT NULL,        -- second hump, percent; 0 == Fed fitted plain Nelson-Siegel
    tau1     NUMERIC(12,8) NOT NULL,        -- first decay, years
    tau2     NUMERIC(12,8) NOT NULL,        -- second decay, years (unused when beta3 = 0)
    PRIMARY KEY (source, as_of)
);

-- Materialised zero yields per tenor — what the UI and the lattice read without re-evaluating the fit.
-- zero_rate is a RATE, not a percent: 0.04250000 = 4.25%. Scale 8 = 1/10000 bp (ADR-0017 §4).
CREATE TABLE IF NOT EXISTS muni.curve_point (
    source      TEXT          NOT NULL,
    as_of       DATE          NOT NULL,
    tenor_years NUMERIC(6,3)  NOT NULL,     -- 0.25, 1, 2, ... 30
    zero_rate   NUMERIC(12,8) NOT NULL,     -- continuously compounded, as a rate
    PRIMARY KEY (source, as_of, tenor_years)
);
CREATE INDEX IF NOT EXISTS curve_point_asof ON muni.curve_point (source, as_of);

-- Realized-volatility estimates. There is no free implied-vol surface (Cboe discontinued SRVIX in 2022 and
-- TYVIX with it; MOVE is licensed), so sigma is MEASURED from the curve history above and carries its own
-- provenance: which series, which window, how many observations, how many the estimator had to exclude.
--
-- p10/p50/p90 are the band of rolling one-year realized vol across the available history. OAS is reported
-- at all three (ADR-0017 §3) — a single sigma with no band is a hidden assumption, and reporting one is a
-- bug, not a simplification.
CREATE TABLE IF NOT EXISTS muni.rate_vol (
    series       TEXT        NOT NULL,      -- e.g. 'GSW:1Y' — the rate the vol was measured on
    kind         TEXT        NOT NULL,      -- 'NORMAL' (bp/yr, Hull-White) | 'LOGNORMAL' (proportion, BDT)
    as_of        DATE        NOT NULL,      -- last observation date in the estimation sample
    window_days  INT         NOT NULL,      -- lookback in observations — the one judgement, stated
    sigma        NUMERIC(14,6) NOT NULL,    -- annualised (sqrt(252) convention)
    observations INT         NOT NULL,
    excluded     INT         NOT NULL DEFAULT 0,   -- lognormal: rates at/below the 1bp floor, counted
    p10          NUMERIC(14,6),             -- NULL when history is shorter than one window: a band that
    p50          NUMERIC(14,6),             -- cannot be measured is reported ABSENT, never approximated
    p90          NUMERIC(14,6),
    windows      INT,
    PRIMARY KEY (series, kind, as_of, window_days)
);
