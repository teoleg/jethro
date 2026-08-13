# ADR-0017 — Benchmark curve and rate volatility from free official sources

Status: Accepted (2026-08-12)

## Context

The readiness panel (ADR-0016) names two inputs that **no Official Statement can supply**, because they are
properties of the *market*, not of a bond:

- a **benchmark curve** to discount cashflows against, and
- a **short-rate volatility** for the lattice.

Both are sold. A AAA municipal curve (MMD from Refinitiv, BVAL from Bloomberg) is a paid licence in the
tens of thousands per year, and the swaption implied-vol surface that would pin σ is likewise vendor data —
Cboe's SRVIX was discontinued in 2022 and TYVIX wound down with it, so there is no longer a free published
rates-implied-vol index. Neither is available to this project, and neither may be redistributed.

The premise worth testing is whether *paid* and *usable* are the same thing here. They are not. A lattice
calibrated for buy-and-hold relative-value analysis needs a curve that is **accurate and reproducible**, not
one that is **live**. Nothing in the OAS calculation degrades because the curve is yesterday's close. That
reframes the problem from "buy a real-time feed" to "find the authoritative end-of-day series", and there
the public record is rich.

Three free, official, automatable sources cover it:

1. **Federal Reserve GSW curve** (`feds200628.csv`, Gürkaynak–Sack–Wright, FEDS 2006-28) — the **daily
   zero-coupon** Treasury curve as Nelson-Siegel-Svensson parameters, **1961 to the present**, published by
   the Board as a plain CSV with no key and no registration. This is the Fed's own staff curve and the
   standard academic benchmark.
2. **US Treasury daily par yield curve** — the official "as published" par yields (13 tenors, ~3:30pm NY
   from FRBNY indicative quotes), free XML/CSV feed, same-day.
3. **The project's own N-PORT valuation panel** (ADR-0016) — thousands of dated, fund-attested marks on
   *tax-exempt* bonds with known coupon and maturity, quarterly back to 2019.

The decisive point about (1): a lattice discounts cashflows, so it needs **zero rates / discount factors**,
not par yields. Source (2) would have to be bootstrapped first, from only 13 tenors with structural gaps.
Source (1) hands over a continuous zero curve evaluable in closed form at any maturity. It is both the freer
and the *technically correct* input; the earlier framing of the Treasury par curve as "the" free option was
the weaker of the two.

The decisive point about (3): **Treasury is not the muni curve.** Kalotay measures muni OAS against a
tax-exempt benchmark. Discounting a tax-exempt bond on a taxable curve misprices it by the entire
muni/Treasury yield ratio — a first-order error, not a refinement. That ratio is observable, and this
project already owns the observations needed to measure it.

## Decision

### 1. Two curve legs, one interface

A `CurveSource` SPI yields, for a given date and tenor, a **zero rate** as an exact decimal.

- **`GSW` (benchmark leg)** — ingest `feds200628.csv` in full, store per-date NSS parameters *and* the
  published `SVENYnn` zero yields. Daily, 1961→present, refreshed weekly.
- **`MUNI_RATIO` (tax-exempt leg)** — the GSW zero curve scaled by a **measured** muni/Treasury ratio per
  tenor bucket, estimated from the N-PORT panel (below). This is the curve OAS is quoted against.

The par curve (source 2) is ingested as a **cross-check only**: GSW's own par-yield reconstruction is
compared against Treasury's published par yields, and a divergence beyond a stated tolerance raises a data-
quality flag (ADR-0011). It never feeds the lattice.

### 2. The muni ratio is measured, never assumed

No muni/Treasury ratio is hard-coded. It is fitted from the project's own data, over the subset that can
carry the weight:

- fixed coupon (`couponKind = Fixed`), and
- callability **known** — an OS was read (`callState ∈ {CALLABLE, NON_CALLABLE}`) — and **non-callable**,
  because a callable bond's yield contains option value and would contaminate the benchmark, and
- not in default or interest arrears, and
- held by **≥ 2 funds** in the period, so the mark is corroborated rather than one filer's opinion.

For each qualifying bond in a period: price → yield → ratio against the GSW zero at the same maturity.
Bucket by tenor, take the median (robust to a single bad mark), and record the **observation count and
dispersion alongside it**. A bucket below a minimum sample count publishes **no ratio** — it is reported as
unmeasured, and OAS for bonds in that bucket is refused rather than computed on a guessed level.

This leg therefore **starts empty and earns its coverage as Official Statements are loaded** — the same
"earns its weight" discipline jethro applies to forecast sources. That is the designed behaviour, not a gap.

### 3. Volatility is a measured statistic plus a stated band — never a single hidden number

There is no free implied-vol surface, so σ is **not** taken from the market's option prices. It is estimated
as **realized volatility of the benchmark short rate**, computed from the GSW history that is already being
ingested — 60+ years of daily official data. Two parameterisations, because the lattice family decides which
one is meaningful:

- **normal / Hull-White:** `σ_bp = stdev(Δr_t) × √252`, r in basis points
- **lognormal / BDT / Black-Karasinski** (Kalotay's own model family — he co-authored BDT):
  `σ_ln = stdev(Δ ln r_t) × √252`

Both are *computed from published data*, not chosen. The only judgement is the **lookback window**, and that
is declared explicitly rather than buried.

**OAS is reported across a vol band, never at one σ.** The band is the **10th / 50th / 90th percentile of
rolling 1-year realized vol** over the available history — dispersion measured from the same official series,
not invented. Every OAS number carries the σ that produced it and the band around it. A bond whose OAS holds
across the band is a relative-value candidate; one whose OAS flips sign across it is a volatility bet
wearing a value bond's clothing, and the band is what makes that visible.

**Known bias, stated rather than silently absorbed:** tax-exempt rates are materially less volatile than
Treasuries (a slower, retail-dominated market). Using Treasury realized vol therefore **overstates** muni
option value, which **understates** OAS on callables and makes them screen *worse* than they are. Per the
repo convention on uncertain market conventions, this is the conservative direction — it cannot cause a
callable bond to look cheap when it is not — and it is adopted as the default with the bias named at the
call site. Once the `MUNI_RATIO` leg has enough quarterly history, vol is re-estimated on the muni curve
itself, which is the right underlying; the Treasury-vol default is a documented stand-in, not a finding.

### 4. Exactness boundary

Invariant 1 (no binary floating point for money) holds at every boundary: curve points, ratios and vol
estimates are `NUMERIC` in Postgres and `BigDecimal` in Java, at declared scales.

The NSS evaluation itself is transcendental — it is `exp()` — and cannot be exact in `BigDecimal`. The rule
applied is therefore: **the curve fit is floating-point mathematics; its result is rounded once, at a
declared scale, and everything downstream is exact decimal.** Zero rates are stored at 8dp (1e-8, i.e.
1/10000 bp — far finer than any quoted rate), ratios at 6dp, vols at 6dp, each with an explicit
`RoundingMode.HALF_UP`. No cashflow, price or PnL arithmetic ever touches a `double`.

### 5. Provenance on every number

Each stored curve point carries its `source` and the `as_of` date it was published for — never the fetch
date. Each vol estimate carries its window, its observation count, and the series it was computed from.
Nothing in this ADR introduces a self-chosen constant: every figure downstream traces to a Fed/Treasury
publication or to a measurement over the project's own filings.

## Consequences

- **The blocker clears with source (1) alone.** GSW is fetchable and parseable today, so lattice work can
  start against the Treasury zero curve immediately, with the muni ratio applied as it becomes measurable.
- **OAS is quoted as a range, always.** Any UI or report showing a single OAS without its vol band is a bug.
- **A refusal is a valid output.** Tenor buckets without enough corroborated observations return "no ratio
  measured" and OAS is withheld, per ADR-0011 — never force-fit.
- **The paid curves stay optional.** If MMD/BVAL is ever licensed it becomes another `CurveSource`; nothing
  above needs revisiting.
- The Fed publishes GSW weekly, so the tax-exempt leg's *level* can lag intraweek. For buy-and-hold
  relative value that is immaterial; it would not be for intraday trading, which this module does not do.
