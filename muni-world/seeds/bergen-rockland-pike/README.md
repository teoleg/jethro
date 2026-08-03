# Seed area: Bergen (NJ) · Rockland (NY) · Pike (PA)

The first picked area for the muni-world data build (ADR-0002 staging, ADR-0003 sources). A contiguous
tri-state corridor along the NY/NJ line and the Delaware:

- **Bergen County, NJ** — 70 municipalities (the densest muni county in NJ; the anchor).
- **Rockland County, NY** — 5 towns + 18 villages (borders Bergen to the north).
- **Pike County, PA** — 2 boroughs + 11 townships (across the Delaware from NJ).

`municipalities.csv` is the **target universe** (106 rows). Collection starts with **cities**, then widens.

## Start with cities — the statutory reality (cities by county)

"City" is a specific legal class, and it is sparse here:

| County | Cities | Notes |
|--------|--------|-------|
| **Bergen, NJ** | **3** — Hackensack, Englewood, Garfield | everything else is a borough / township / village |
| **Rockland, NY** | **0** | NY: all towns & villages (principal centers: Clarkstown, Spring Valley, Suffern, Nyack) |
| **Pike, PA** | **0** | PA: boroughs & townships (principal: Milford borough) |

So the "cities" starting slice is the **3 Bergen cities** (`cities.csv`, all verified). After proving the
pipeline on them we widen to the principal municipalities of each county, then the full 106.

## What we found — official sites and feeds to grab (verified)

Each city runs a **different CMS**, and the CMS dictates the scrape strategy — the single most useful
finding for the connector (ADR-0004):

- **Hackensack — `hackensack.org` (WordPress).** Finance at `/finance/`; budget PDFs live under
  `/wp-content/uploads/<yyyy>/<mm>/*.pdf`. Grab: finance page + the uploads media library.
- **Englewood — `cityofenglewood.org` (CivicPlus / CivicEngage).** Finance at `/1174/Finance`; documents
  are ID-addressed via `/DocumentCenter/View/<id>` and `/Archive.aspx?ADID=<id>` (e.g. the 2024 Annual
  Financial Statement is DocumentCenter item 5612). CivicPlus exposes a **Document Center index** — the
  cleanest of the three to crawl.
- **Garfield — `garfieldnj.org` (custom).** Finance at `/Departments/finance-department`; documents follow
  a predictable `/_Content/pdf/budgets/<year>-<AFS|Audit|User-Friendly-Budget>.pdf`, plus legal `/Notices/`.

**Per-city feeds to grab:** finance/CFO page → **AFS** (Annual Financial Statement), **audit / ACFR**,
**user-friendly budget**, adopted budget, debt statement; plus **agendas/minutes** and **legal notices**
(bond ordinances/sales). Formats are PDF-heavy → document-extraction pipeline (ADR-0010).

## Central sources cover ALL of them (don't scrape 106 sites for financials)

The state and federal hubs already hold **per-municipality** data, so most financial coverage comes from a
handful of sources, not 106 town sites — the town sites add budgets, notices, agendas and official
statements the hubs lack:

- **NJ DCA, Division of Local Government Services** — fiscal reports / AUDs for every NJ municipality:
  `nj.gov/dca/divisions/dlgs/resources/fiscal_rpts.shtml`
- **NJ Dept. of Education** — ACFR search for every school district: `nj.gov/education/finance/fp/acfr/`
- **MSRB EMMA** — official statements, disclosures and trades for every issuer's CUSIPs.
- **U.S. Census** — Government Units + FIPS + government-finance survey (the denominator for coverage,
  ADR-0011).
- (NY → Office of the State Comptroller; PA → DCED Municipal Statistics — the equivalents for the other two
  counties.)

## CMS-aware connectors (implication for ADR-0004)

Because NJ municipal sites cluster on a few platforms (CivicPlus, WordPress, Revize, custom), the
`ScrapeConnector` should be **CMS-aware**: a CivicPlus adapter (Document Center API/index), a WordPress
adapter (media/uploads + REST), and a generic link-crawler fallback. One adapter then unlocks dozens of
towns at once — the efficient path to the full 106. Tracked as the next ADR/refinement.

## Legend / discipline

`verified=yes` rows have a confirmed official URL + a real document example. Everything is still fetched
under the polite-crawl policy (robots, rate limits, conditional GETs — ADR-0008) and lands immutably with
provenance (ADR-0005) before parsing. Sources are candidates until the connector validates them on first
crawl; nothing here is asserted as final.
