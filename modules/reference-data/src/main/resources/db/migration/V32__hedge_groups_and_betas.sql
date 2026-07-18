-- Structural hedge refdata (ADR-0040): each equity carries a hedge group (GICS sector), a hedge
-- proxy (the tradeable index future to hedge with), and an ASSIGNED FUNDAMENTAL BETA — so the
-- structural hedge tier can size a beta-hedge to flat with NO return history (the covariance the
-- statistical ADR-0038 hedge needs is exactly what we can't reliably source). Additive only:
-- attribute upserts, no instrument row is touched.
--
-- PROVENANCE of hedge_beta: published ~5y-monthly betas vs the S&P 500 (the levels every retail/
-- vendor sheet shows — Apple/Microsoft slightly above 1, JNJ defensive ~0.55, NVDA high ~1.75).
-- They are APPROXIMATE and rounded — a labeled starting point, NOT a desk-blessed number.
-- PLACEHOLDER precision: Oleg to set these from the firm's beta source; the structural hedge is
-- only as good as the assigned beta, and a stale beta silently mis-hedges (ADR-0040 consequence).

-- hedge_group: GICS sector (drives the balance view; SAP is software → Information Technology).
insert into instrument_attributes (instrument_id, name, value) values
    ('AAPL', 'hedge_group', 'Information Technology'),
    ('MSFT', 'hedge_group', 'Information Technology'),
    ('NVDA', 'hedge_group', 'Information Technology'),
    ('SAP',  'hedge_group', 'Information Technology'),
    ('GOOG', 'hedge_group', 'Communication Services'),
    ('AMZN', 'hedge_group', 'Consumer Discretionary'),
    ('JNJ',  'hedge_group', 'Health Care'),
    ('JPM',  'hedge_group', 'Financials')
on conflict (instrument_id, name) do update set value = excluded.value;

-- hedge_proxy: the index future the structural tier hedges with. ES for all equities in v1;
-- per-sector ETF proxies (XLK/XLF/…) are the ADR-0040 follow-up once those instruments exist.
insert into instrument_attributes (instrument_id, name, value) values
    ('AAPL', 'hedge_proxy', 'ES'), ('MSFT', 'hedge_proxy', 'ES'),
    ('NVDA', 'hedge_proxy', 'ES'), ('SAP',  'hedge_proxy', 'ES'),
    ('GOOG', 'hedge_proxy', 'ES'), ('AMZN', 'hedge_proxy', 'ES'),
    ('JNJ',  'hedge_proxy', 'ES'), ('JPM',  'hedge_proxy', 'ES')
on conflict (instrument_id, name) do update set value = excluded.value;

-- hedge_beta: assigned fundamental beta vs the S&P 500 (see PROVENANCE above).
insert into instrument_attributes (instrument_id, name, value) values
    ('AAPL', 'hedge_beta', '1.25'),
    ('MSFT', 'hedge_beta', '1.10'),
    ('AMZN', 'hedge_beta', '1.20'),
    ('GOOG', 'hedge_beta', '1.05'),
    ('SAP',  'hedge_beta', '1.00'),
    ('JNJ',  'hedge_beta', '0.55'),
    ('NVDA', 'hedge_beta', '1.75'),
    ('JPM',  'hedge_beta', '1.10')
on conflict (instrument_id, name) do update set value = excluded.value;
