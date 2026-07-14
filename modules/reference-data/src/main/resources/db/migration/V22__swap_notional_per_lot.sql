-- Swap exposure convention (ADR-0020 follow-up): a swap position's EXPOSURE is its gross
-- notional (1 lot = $1,000,000 per V9), not qty x par-rate x multiplier (which mixed a rate
-- with a money multiplier and understated a $1M lot as ~$190k). P&L valuation is unchanged
-- (live DV01 x delta-par); only the exposure/concentration measure moves to the standard
-- gross-notional convention. Risk limits on swap-carrying books were recalibrated with it.
insert into instrument_attributes (instrument_id, name, value) values
    ('USD_IRS_5Y',  'notional_per_lot', '1000000'),
    ('USD_IRS_10Y', 'notional_per_lot', '1000000');
