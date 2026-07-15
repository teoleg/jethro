-- Fee as a SEPARATE cash line (ADR-0025): the fill price is the traded price; commission
-- is its own column. LIMIT fills can now carry fees (no limit-price violation), and TCA
-- can separate spread/delay cost (price-based slippage) from commission.
alter table fills add column fee numeric(24, 6) not null default 0;
alter table execution_quality add column fee numeric(24, 6) not null default 0;
