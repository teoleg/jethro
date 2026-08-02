# NYC issuer dossiers — structure, security & conditions

Per-issuer profiles for the NYC complex (ADR-0006 issuer/obligor/security/call_feature). This captures the
**stable, structural conditions** — security pledge, lien, flow of funds, covenants, **statutory caps set in
the enabling acts / bond resolutions**, tax status — which are durable public facts and are what this
dossier asserts.

> **Provenance discipline (no invented numbers).** Only durable, statute-/resolution-defined conditions are
> stated as fact below. **Point-in-time figures — current ratings, par outstanding, coverage actuals — are
> NOT asserted here**; they are marked as pipeline-populated and are filled, each cited to its
> `raw_artifact_id` (ADR-0005), from each issuer's **Official Statements (EMMA)** and financial filings
> (ADR-0004/0010). Ratings are public rating actions only (licensed feeds excluded — ADR-0008). Where a
> number below is statutory it is a real condition; nothing here is a self-chosen placeholder.

---

## 1. City of New York — General Obligation (GO)
- **Type / role:** the City itself; direct GO issuer.
- **Security:** **full faith and credit** pledge; **ad valorem tax on all taxable real property, without
  limit as to rate or amount**, to pay debt service.
- **Structure:** a **General Debt Service Fund** (Financial Emergency Act, 1978) — City real-estate tax is
  deposited **on receipt** and retained under a statutory formula ahead of debt service; fully funded at the
  start of each payment period since inception.
- **Ratings:** rated by Moody's / S&P / Fitch / KBRA (public reports on the Comptroller/IR sites). *Current
  level + par outstanding: pipeline-populated from the latest OS/rating action — not asserted here.*
- **Tax status:** tax-exempt (some taxable series).
- **Programs / series:** "The City of New York General Obligation Bonds, Fiscal <year> Series <A…>".
- **Disclosure:** Comptroller OS archive + `nyc.gov/investorrelations` GO downloads + EMMA (CUSIP-6).
- **Pipeline populates:** series list, per-CUSIP terms + **call features**, par outstanding, current ratings.

## 2. NYC Transitional Finance Authority (TFA) — Future Tax Secured (FTS)
- **Type / role:** public benefit corporation; finances a large share of the City capital program.
- **Security:** **Personal Income Tax (PIT)** revenues and, if needed, **Sales Tax** revenues — statutory
  dedicated taxes, **not** a City GO pledge (bankruptcy-remote from the City).
- **Lien:** **senior** and **subordinate** liens. Senior debt capped at **$12B** principal outstanding with a
  **max quarterly debt-service of $330M**; subordinate bonds (the bulk of new issuance) are on parity with
  other subordinate FTS bonds, junior to senior DS.
- **Statutory cap:** original $7.5B → raised to **$13.5B** (+$2.5B "Recovery Bonds"); post-2009, issuance
  **above** the cap counts against the **City's debt limit**.
- **Ratings:** rated by Moody's / S&P / Fitch (senior and subordinate liens). *Current levels:
  pipeline-populated — not asserted here.*
- **Also issues:** **Building Aid Revenue Bonds (BARBs)** — secured by State building-aid payments (distinct
  credit; profile as a sub-program).
- **Disclosure:** `nyc.gov/site/transitionalfinance/investing` + Comptroller + EMMA.

## 3. NYC Municipal Water Finance Authority (NYW) — Water & Sewer System Revenue
- **Type / role:** authority financing the NYC Water & Sewer System capital program.
- **Security:** **gross system revenues** of the water/sewer system. Three-party structure: the **Water
  Board** owns the revenues (lease with the City) and pledges them to the **Authority** (financing
  agreement), which pledges them **first to bondholders** — insulating them from City finances.
- **Flow of funds:** debt service funded **monthly** (≈1/5 of interest, 1/11 of principal) **before** O&M;
  if revenues fall short, bondholders may claim **all** system revenues until DS is met.
- **Covenants / ABT:** **First Resolution** ABT — net revenues ≥ **115%** of MADS (senior, next 5 yrs) + 100%
  of Second Resolution DS + O&M. **Second Resolution** ABT — revenues ≥ **110%** of aggregate (first+second)
  DS. **Rate covenant:** net revenues sum-sufficient for combined DS + O&M + City lease payments.
- **Liens:** First Resolution (senior, largely legacy) and **Second Resolution** (current issuance).
- **Ratings:** rated by Moody's / S&P / Fitch (reports on the NYW site). *Current levels: pipeline-populated
  — not asserted here.*
- **Disclosure:** `nyc.gov/site/nyw` (investing + EMMA notices) + the System ACFR.

## 4. TSASC, Inc. — Tobacco Settlement Asset-Backed
- **Type / role:** NY not-for-profit **local development corporation**; sold the City's rights to Master
  Settlement Agreement (MSA) tobacco revenues.
- **Security:** **Tobacco Settlement Revenues (TSR)** under the 46-state MSA — payments depend on cigarette
  shipment volumes (secular decline) and participating-manufacturer solvency; **turbo** redemption typical.
- **Key conditions:** no City pledge; consumption-risk credit; capital-appreciation/serial structures common.
- **Disclosure:** via NYC Investor Relations + EMMA. **Pipeline populates:** turbo schedule, TSR history.

## 5. Hudson Yards Infrastructure Corp (HYIC)
- **Type / role:** LDC financing the Hudson Yards redevelopment (7 subway extension, etc.).
- **Security:** Hudson-Yards-district revenues — **PILOTs**, TEP, district improvement bonuses; **City support
  agreement** for interest (not principal) as a backstop. Project/district-revenue credit.
- **Disclosure:** NYC IR + EMMA.

## 6. Sales Tax Asset Receivable Corp (STARC)
- **Type / role:** LDC that refinanced Municipal Assistance Corporation (MAC) debt.
- **Security:** **$170M/yr** State sales-tax-related payments assigned to the City → STARC; dedicated-revenue
  lien, bankruptcy-remote.
- **Disclosure:** NYC IR + EMMA.

## 7. NYC Housing Development Corp (HDC)
- **Type / role:** one of the largest **multifamily housing** issuers in the U.S.
- **Security:** **Open Resolution** — mortgage repayments + reserves cross-collateralised across a large pool;
  various indentures/programs. Revenue credit, not City GO.
- **Disclosure:** `nychdc.com` investor + EMMA. **Pipeline populates:** program/indenture map, series terms.

## 8. NYC Health + Hospitals (HHC)
- **Type / role:** public-benefit corporation; municipal health system.
- **Security:** **Health System Revenue Bonds** — system net revenues (patient/third-party). Enterprise credit.
- **Disclosure:** `nychealthandhospitals.org` + EMMA.

## 9. NYC IDA / Build NYC Resource Corp (via NYCEDC)
- **Type / role:** **conduit** issuers — issue on behalf of third-party **obligors** (non-profits, industry).
- **Security:** the **obligor's** revenues/collateral, **not** the City. **issuer ≠ obligor** — model each
  deal's obligor separately (ADR-0006/0007).
- **Disclosure:** `edc.nyc` + EMMA (per obligor).

## 10. NYC Educational Construction Fund (ECF)
- **Type / role:** authority building schools + mixed-use.
- **Security:** **lease/sublease** payments (City Board of Education lease) — appropriation-style lease-revenue.
- **Disclosure:** NYC IR + EMMA.

## 11. NYC School Construction Authority (SCA)
- **Type / role:** manages the DOE capital program (largely funded via TFA-BARBs/GO rather than own-name debt).
- **Coverage:** capital plan + procurement; profile mainly as a program, not a standalone bond credit.

## 12. NYC Housing Authority (NYCHA)
- **Type / role:** the public housing authority; capital/operating; limited own-name market debt.
- **Coverage:** capital/financial disclosure; some transactions (e.g., PACT/RAD) create obligor structures.

---

## What "everything" means, and how it fills in
The **conditions above are the durable frame**. "Everything" per issuer — every series, every CUSIP's
coupon/maturity/**call and sinking-fund schedule**, refunding history, current par, current ratings, and
audited financials/ACFR — is exactly the corpus the pipeline collects: **Official Statements + continuing
disclosure from EMMA** (ADR-0004/0010) into the `security` / `call_feature` / `financials` tables (ADR-0006),
each value cited to its `raw_artifact_id` (ADR-0005). This dossier is the seed the ingestion fills to full
depth — it is not asserted as complete.
