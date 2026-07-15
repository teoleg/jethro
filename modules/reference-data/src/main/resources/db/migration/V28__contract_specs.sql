-- Contract specifications as reference data (review GAP-4, part 2): the last per-instrument
-- data that was hardcoded in domain code — Treasury-future CME deliverable windows
-- (BondFutureDurations) and the reference-swap economics (SwapPricingService). These are
-- published contract mechanics, but they are still per-instrument refdata: a real or larger
-- universe must extend by inserting rows, not by editing code. Numbers are analytics inputs
-- (durations/curves), not ledger money.

-- CME deliverable maturity windows (years) per Treasury future — the basket range the
-- conversion-factor / CTD model keys off. Published CME contract specs.
insert into instrument_attributes (instrument_id, name, value) values
    ('ZT', 'deliverable_short_years', '1.75'), ('ZT', 'deliverable_long_years', '2.0'),
    ('ZF', 'deliverable_short_years', '4.17'), ('ZF', 'deliverable_long_years', '5.25'),
    ('ZN', 'deliverable_short_years', '6.5'),  ('ZN', 'deliverable_long_years', '10.0'),
    ('ZB', 'deliverable_short_years', '15.0'), ('ZB', 'deliverable_long_years', '25.0')
on conflict (instrument_id, name) do update set value = excluded.value;

-- Reference-swap fixed coupon (fraction) for the platform's defined swaps — the coupon the
-- reference PV/DV01 valuation (/api/swaps, and the ledger's live DV01 multiplier) prices at.
-- tenor_years is already carried (V27); notional is the $1M/lot V9 convention.
insert into instrument_attributes (instrument_id, name, value) values
    ('USD_IRS_5Y',  'reference_coupon', '0.0400'),
    ('USD_IRS_10Y', 'reference_coupon', '0.0410')
on conflict (instrument_id, name) do update set value = excluded.value;
