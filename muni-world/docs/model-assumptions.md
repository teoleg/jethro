# Model constants & assumptions registry — every hardcoded number, classified

The repo rule (CLAUDE.md): *never present a self-chosen default as an established rule; when a number is
mine and arbitrary, say so.* This page is the complete audit of every constant in the analytics path
(`curve/*`, `OasService`, `ModelWorkbenchController`, the readiness UI), in three tiers:

- **Tier M — mathematics/solver mechanics.** Not assumptions: changing them either breaks the math or
  changes nothing observable. Listed so nothing is exempt from the audit.
- **Tier C — cited market conventions.** Sourced to a named convention or publication; changing one means
  changing which convention the module follows, and the output labels it.
- **Tier J — judgement calls (mine).** Chosen by the implementer, with the reasoning and the impact of the
  choice stated. **None of these gates money, risk or exposure — no order, position or capital decision
  reads them** (muni-world is analytics-only today); if that ever changes, the affected constant must be
  promoted to an owner decision first. Oleg can overrule any of them; the table says what moves if he does.

## Tier M — mathematics / solver mechanics

| Constant | Where | Why it is not an assumption |
|---|---|---|
| Bisection: 200 iterations | `BdtLattice`, `LatticeBondPricer` | Converges to the double-precision floor after ~50; the rest are free. Fewer than ~60 would start to matter; 200 is headroom, not a tuning. |
| Bisection bracket low = 1e-9 | `BdtLattice` | "Rate just above zero" — any tiny positive works. |
| ½ / ½ branch probabilities | `BdtLattice`, `LatticeBondPricer` | The BDT model definition (risk-neutral measure), not a choice. |
| `NEAR_ZERO_YEARS = 1e-9` | `NelsonSiegelSvensson` | Guards the n→0 limit of the loadings; the limit values are exact mathematics. |
| `MIN_OPTION_VALUE = 1e-4` points | `ModelAnalytics` | 0/0 guard on refunding efficiency: below a hundredth of a cent per 100 the ratio is noise. Any tiny threshold gives the same behaviour: "absent", never a number. |
| Rounding scales: rates 8dp, DF 12dp, vol 6dp, OAS 0.01bp, `dt` 6dp | throughout | The single-rounding boundary of ADR-0017 §4. All finer than any quoted precision — they bound *representation*, not accuracy. |
| `tau2 → 1` when the Fed leaves it blank | `GswCsvParser` | Mathematically inert: blank BETA3 = 0 and the fourth term is `beta3 × f(tau2)` = 0 whatever tau2 is. Pinned by the `zeroBeta3ReproducesNelsonSiegelExactly` test. |
| Fed file header found by NAME, dates in ISO or US format | `GswCsvParser` | Parsing robustness, no numeric content. |

## Tier C — cited market conventions (each labeled in the output that uses it)

| Constant | Where | Source |
|---|---|---|
| 252 trading days/year annualisation | `RealizedVol.TRADING_DAYS` | Standard convention for business-day series; stated in the class Javadoc and the vol table. |
| Semiannual fixed coupons | `OasService`, workbench | US municipal bond convention. |
| Par (100) when an OS states a call date but no price | `OasService`, workbench | Modern-muni convention (post-2000s issues overwhelmingly par-call). Every result carrying it sets `assumedParCall: true` — it is a flag, never silent. |
| ±25bp parallel bump for effective duration/convexity | `ModelAnalytics.BUMP` | Common market definition of "effective" risk measures; stated in the conventions list of every response. |
| Refund when efficiency ≳ 90% | workbench UI text only | Kalotay's own rule of thumb, attributed to him where shown. Not enforced anywhere — it renders as *his* threshold, with the raw efficiency beside it. |
| 30/360 accrued in the display-only `BondMath` | `BondMath` | US muni day-count convention. Display path only; not an OAS input. |

## Tier J — judgement calls: mine, stated, and what moves if changed

| Constant | Where | My reasoning | Impact of changing it |
|---|---|---|---|
| `LN_FLOOR_BP = 1.0` — rates ≤ 1bp excluded from the **lognormal** vol estimate | `RealizedVol` | `ln r` explodes as r→0; the 1-year zero sat at a few bp in 2020-21. The floor level itself (0.5bp? 2bp?) is arbitrary within an order of magnitude. | Excluded observations are **counted and shipped** with every estimate, so the thinning is visible. Raising the floor excludes more ZIRP-era data → *lower* measured lognormal σ → higher OAS on callables. The normal estimate uses every observation and is unaffected — one reason to watch both. |
| OAS solve range ±1,000bp (`MAX_SPREAD`) | `LatticeBondPricer` | Wide enough for any performing muni; a mark needing more is distressed or mis-stated. | A price outside returns **"unsolvable", never a clamped number**. Widening the range only changes which marks get an answer instead of a refusal. |
| σ measured on the **GSW 1Y zero** (`VOL_TENOR`) | `GswCurveIngest` | The lattice wants short-rate vol; 1Y is the shortest tenor the Fed fits (GSW starts at one year). A constrained pick, but still a pick. | A longer proxy tenor would lower σ (vol falls with tenor) → higher OAS on callables. The series name ships inside every vol row (`GSW:1Y`), so the choice is never invisible. |
| Vol lookback 252 days (`muni.curve.vol.window-days`) | config, env-overridable | One year of observations — long enough to be stable (~4.5% sampling error), short enough to track regimes. Declared in `application.properties` with this provenance. | Shorter = noisier and more regime-sensitive σ. The p10/p50/p90 band across rolling windows is the mitigation either way. |
| Steps = `max(2, round(2 × years))`, `Δt = T/n` | `OasService`, workbench | Aligns steps with the semiannual coupon convention and lands exactly on maturity. The floor of 2 keeps a near-matured bond on a real lattice. | Finer steps sharpen the American-call approximation (real calls are continuous after first call; semiannual checking *understates* option value by single-digit bp of OAS). A finer grid near the call window is on the accuracy roadmap. |
| Year fraction ACT/365.25 | `OasService`, workbench | Simplest defensible choice for lattice *geometry* (not cash-flow day counts). True muni schedules are 30/360 on real coupon dates — the known gap, top of the accuracy roadmap. | Low single-digit bp of OAS. Stated in every response's conventions list. |
| Readiness verdict "≥ 20 fully-specified callables" | `index.html` readiness panel | A **working figure** for "enough bonds to validate a lattice against" — tens, not thousands. It is not a statistical result. | Purely a UI verdict; computes nothing. The panel now labels it as a working figure. |
| Materialised tenor grid (1…30y), lattice tree shows 8 steps, curve ingest 45s boot delay / daily refresh | `GswCurveIngest`, workbench UI | Display/ops choices. The stored **fit** is the source of truth — any tenor is evaluable on demand, so the grid loses nothing. | Cosmetic / scheduling only. |

## The two assumptions that dwarf every number above

Worth restating so this page can't mislead by precision: the **benchmark basis** (taxable Treasury until the
muni-ratio leg is measured — ADR-0017 §2) and **price quality** (quarterly fund fair-value marks, clean/dirty
unstated in N-PORT — ADR-0018 §3) move OAS by more than every Tier J row combined. Both are labeled on every
result; neither is a constant anyone can tune away.

## Book references — attached by the owner as he reads

The assistant does not have the book text and will not fabricate page numbers. Each mechanism below
follows a Kalotay concept; Oleg fills the page/table as he verifies it against
*Interest Rate Risk Management of Municipal Bonds*:

| Concept | Where implemented | Book page/table |
|---|---|---|
| BDT lattice (lognormal short rate, calibrated to the benchmark curve) | `BdtLattice` | ___ |
| callable = straight − option value decomposition | `ModelAnalytics` | ___ |
| Refunding efficiency and the ~90% rule of thumb | `ModelAnalytics`, workbench UI | ___ |
| Curve validation before valuation (reprice constituents; no negative forwards) | `GswCurveIngest.validate`, ADR-0020 | ___ |
| Benchmark curve must be option-free (muni quotes embed the 5%-callable convention) | ADR-0017 §2 — ratio leg measured from confirmed NON-callable bonds only | ___ |
| Effective duration/convexity of callables (negative convexity near the call) | `ModelAnalytics` | ___ |
| OAS as the valuation spread on the lattice | `LatticeBondPricer.solveOas` | ___ |

## Added by ADR-0020 (curve validation + assumption ledger)

| Constant | Tier | Where | Notes |
|---|---|---|---|
| SVENY repricing tolerance 1bp | M | `GswCurveIngest.validate` | Parse-error detector: genuine agreement is ~1e-6bp (the file publishes ~6 decimals); 1bp is pure daylight, distinguishing "same number up to print rounding" from "different number". |
| Plausibility band [−2%, +35%] at 1y/10y/30y | J | `CurveSanity` | Reasoned from the published record itself (~17% worst high in 1981; marginally negative bills): roughly double the historical extremes, so it rejects corruption, never history. Widening admits more of a corrupt file; it never changes a passing value. |
| Curve staleness limit, default 14 days | **PLACEHOLDER — Oleg to set** | `muni.curve.max-staleness-days` | 14 = one missed weekly Fed publication + a long weekend. The refusal message names the property. |
| Lattice repricing residual 1e-9 | M | `BdtLattice.calibrate` | Solver-exactness guard: bisection converges to ~1e-15; 1e-9 fails only genuinely unreachable steps (negative/huge implied forwards). |
