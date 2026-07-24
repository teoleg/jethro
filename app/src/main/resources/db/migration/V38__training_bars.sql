-- ADR-0053: real daily bars for the learned-advisory-signal TRAINING set. A DEDICATED, rebuildable
-- store — deliberately separate from daily_close, which mixes real seeded history with today's
-- live/sim close appended each session, so its tail is sim-contaminated and unfit for ground truth.
-- Populated by a direct Tiingo EOD pull (split/dividend-adjusted close → clean returns); rebuilt on
-- demand when empty, so a data wipe on restart is a non-issue. NEVER a number into live sizing/risk —
-- training data only (ADR-0016 / invariant 7). Tiingo free tier is INTERNAL USE ONLY (ADR-0023).
create table if not exists training_bars (
    instrument varchar(64)    not null,
    day        date           not null,
    adj_close  numeric(20, 8) not null check (adj_close > 0),
    volume     bigint         not null default 0,
    source     varchar(16)    not null default 'tiingo',
    primary key (instrument, day)
);
create index if not exists idx_training_bars_instrument on training_bars (instrument, day);
