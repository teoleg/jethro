-- Swaps become QUOTED and TRADEABLE (quant-engine phase 5, rates asset class).
-- The sim now publishes a live par rate for USD_IRS_5Y/10Y (standard annual-fixed par
-- formula on the SOFR curve), so swaps get real market data and can fill like any
-- instrument. This migration fixes the QUOTING CONVENTION that makes the generic
-- qty × (mark − cost) × multiplier ledger arithmetic produce correct first-order swap PnL:
--
--   price      = par rate in PERCENT (e.g. 4.04125)
--   1 lot      = $1,000,000 notional; BUY = pay fixed (profits when rates rise),
--                SELL = receive fixed
--   multiplier = $ PnL per 1.00 (one percentage point = 100bp) par move per lot
--              = DV01 × 100, held constant at inception values (first-order convention;
--                real DV01 drifts with the curve and rolls down — deliberately deferred,
--                exact revaluation lives in the Strata pricer on the Rates tab)
--
-- Sizing:  5Y annuity ≈ 4.4 at ~4.3% rates → DV01 ≈ $450/bp per $1M → 45,000/pt.
--         10Y annuity ≈ 8.0                → DV01 ≈ $800/bp per $1M → 80,000/pt.
--
-- Worked example (invariant: show the formula on PnL math):
--   BUY 1 USD_IRS_5Y @ 4.04 (pay fixed on $1M). Par moves to 4.14 (+10bp):
--   unrealized = 1 × (4.14 − 4.04) × 45,000 = $4,500 = 10bp × $450/bp. Sign correct:
--   pay-fixed gains when rates rise.

update instrument set contract_multiplier = 45000 where instrument_id = 'USD_IRS_5Y';
update instrument set contract_multiplier = 80000 where instrument_id = 'USD_IRS_10Y';

insert into instrument_attributes (instrument_id, name, value) values
    ('USD_IRS_5Y',  'quote_convention', 'par rate in percent; 1 lot = $1M notional; BUY = pay fixed'),
    ('USD_IRS_5Y',  'multiplier_basis', 'DV01 x 100 at inception (~$450/bp per $1M), held constant'),
    ('USD_IRS_10Y', 'quote_convention', 'par rate in percent; 1 lot = $1M notional; BUY = pay fixed'),
    ('USD_IRS_10Y', 'multiplier_basis', 'DV01 x 100 at inception (~$800/bp per $1M), held constant');
