# ADR-0006: Canonical Postgres schema for the municipal domain

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** schema, postgres, domain-model

## Context

The normalise stage (ADR-0004) needs a canonical target: a domain model that captures munis faithfully
enough to serve Kalotay-style analytics later (ADR-0002), across the messy realities of conduit financings,
serial bonds, and per-maturity call features. Postgres (the shared instance, `muni` schema) is the store;
Flyway migrations own it.

## Decision

Model the domain as these canonical tables in the `muni` schema (money always `NUMERIC`, never binary FP;
Flyway-versioned; every row carries `raw_artifact_id` provenance per ADR-0005):

- **`geography`** — FIPS-keyed hierarchy: state (2) → county (3) → place/municipality (5) → school
  district. The normalisation spine everything hangs geography off (ADR-0007).
- **`issuer`** — the legal issuing entity, anchored on **CUSIP-6**; typed (state / authority / county /
  city / township / school-district / special-district), linked to `geography`. Sits above obligors.
- **`obligor`** — the credit ultimately on the hook; **distinct from issuer** for conduit deals (a housing
  authority issues, a hospital repays). Many issues → one obligor; issuer≠obligor is first-class.
- **`issue`** — a bond sale/series: dated date, sale date, par, purpose, tax status (tax-exempt / AMT /
  taxable / BABs), first-issuer link. One issue → many securities (serial structure).
- **`security`** — a single CUSIP-9 (one per maturity/tranche): coupon, maturity, dated date, denomination,
  tax status, yield/price at issue. The unit OAS will price.
- **`call_feature`** — redemption provisions per security/issue (call dates, prices, type: optional /
  sinking-fund / extraordinary). **Required for OAS** — captured now even though nothing prices it yet.
- **`document`** — official statements, ACFR/CAFR, continuing/financial disclosures; typed, dated, linked
  to issuer/issue, pointing at the raw artifact + extraction (ADR-0010).
- **`financials`** — issuer/obligor annual financial facts (revenues, expenditures, fund balances, debt
  outstanding) as tidy `(entity, fiscal_year, metric, value, unit, source)` rows — accommodates Census,
  OSC, DCA and DCED shapes without a rigid columnar table.
- **`rating`** — agency ratings per security/issuer (agency, scale, value, watch, date) — public actions
  only (ADR-0008).
- **`trade`** *(optional, later)* — RTRS prints from EMMA (price/yield/par/side/time).
- Plus the ingestion tables: **`source`** (ADR-0003), **`raw_artifact`** (ADR-0005), and per-source
  **staging** tables.

## Consequences

- The issuer/obligor/issue/security/call_feature spine matches how munis actually trade and how Kalotay
  analytics needs them — no reshape when pricing arrives.
- The tidy `financials` shape absorbs heterogeneous state/Census extracts without a migration per source.
- FIPS + CUSIP keys make the model joinable to external reference data and keep entity resolution
  deterministic where possible (ADR-0007).
