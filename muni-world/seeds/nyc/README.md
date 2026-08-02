# Seed area: New York City

**NYC is *one* city — but a huge issuer complex with the best municipal data in the region.** The five
boroughs are five *counties* (New York/Manhattan, Kings/Brooklyn, Queens, Bronx, Richmond/Staten Island)
under **one consolidated city government** — so NYC is a single municipality, not "a lot of cities." As a
**bond issuer**, though, it is one of the largest in the entire market, and it issues through a **family of
authorities** — which is where the volume and depth are.

## The issuer complex (`issuers.csv`)

- **City of New York** — general-obligation (GO) bonds.
- **NYC Transitional Finance Authority (TFA)** — Future Tax Secured (FTS) + Building Aid (BARBs); finances a
  large share of the City's capital program.
- **NYC Municipal Water Finance Authority (NYW)** — water & sewer system revenue.
- **TSASC Inc.** — tobacco-settlement asset-backed.
- **Hudson Yards Infrastructure Corp (HYIC)**, **Sales Tax Asset Receivable Corp (STARC)**, **NYC Housing
  Development Corp (HDC)**, **NYC Health + Hospitals (HHC)**, **NYC IDA / Build NYC** (conduit — obligor ≠
  issuer, ADR-0006), **Educational Construction Fund (ECF)**.

This one "city" therefore yields **10+ distinct issuers** and thousands of CUSIPs — far more bond data than
the entire Bergen/Rockland/Pike corridor combined. Verified investor/disclosure pages are in `issuers.csv`
(`verified=partial` rows still need their exact investor/EMMA URL resolved on first crawl).

## Why NYC is the efficient starting point for VOLUME

- **Best-in-class open data.** **NYC Open Data** (`opendata.cityofnewyork.us`) is a Socrata portal with a
  clean **API** — exactly the api-first ingestion ADR-0004 prefers over scraping. Plus **Checkbook NYC**
  (spending/contracts) and **OMB** (budget/financial plan).
- **Central disclosure.** The **Comptroller's** "Invest in NYC Bonds" hub + **NYC Investor Relations** portal
  centralise official statements, the **ACFR**, and the annual **Capital Debt & Obligations** report for the
  City and its authorities — and **EMMA** carries every issuer's CUSIP-level disclosure.
- **One credit family.** GO / TFA-FTS / NYW / TSASC are related but distinct credits — a natural first set to
  exercise the issuer≠obligor and per-credit modelling (ADR-0006) before scaling to thousands of small towns.

## Sources (`sources.csv`) — 106 targets

**106 NYC sources** (target was ≥100), grouped: federal/market-wide (EMMA, Census, IRS, SEC, Treasury,
FRED) · the NYC **issuers** · NY State & regional **authorities** that finance NYC (MTA, PANYNJ, DASNY,
ESD, HFA/SONYMA, EFC, Thruway, NYPA, BPCA, OSC, ABO) · **Comptroller** report series + the 5 NYC pension
funds · **OMB**/Council/MMR budget publications · ~20 **NYC Open Data** finance datasets · other city
agencies (DOF, IBO, EDC, Water Board) · civic/analytic (CBC, IBO, Volcker, Empire Center, MMA) · rating
agencies (public actions only) · news.

**Verify legend:** `verified=yes` — confirmed this session; `pending` — real entity, exact URL to confirm
on first crawl; `resolve-id` — a real NYC Open Data dataset whose Socrata 4×4 id the connector resolves
from the catalog at ingest (the dev environment here blocks the catalog API, so IDs aren't hard-coded —
`data.cityofnewyork.us`/`api.us.socrata.com` are reachable from the Pi). Nothing is asserted final; the
connector validates every row under the polite-crawl policy (ADR-0008).

## Relationship to the tri-county corridor

NYC borders **Bergen (NJ)** across the Hudson and anchors the metro that contains **Rockland (NY)** — so
this seed and `../bergen-rockland-pike/` are the same region at two scales: NYC is the deep, high-volume
core; the tri-county corridor is the broad, many-small-issuers surround. Recommend **starting the pipeline
on NYC** (rich, API-first, well-documented) to prove landing→parse→normalise on real disclosure, then
sweeping the tri-county municipalities with the CMS-aware connectors.

All URLs are candidates until the connector validates them under the polite-crawl policy (ADR-0008); nothing
here is asserted as final.
