-- Hypothesis outcome scoring (ADR-0027): every executed hypothesis carries its entry mark
-- and horizon expiry; at expiry the platform closes the AI-sleeve position and scores the
-- call. outcome_pnl is mark-to-mark (directional call quality); execution costs live in the
-- AI book's ledger P&L. This is the data conviction calibration runs on — without it no
-- model can EARN wider autonomy.

alter table hypothesis_record add column entry_price       numeric(24, 6);
alter table hypothesis_record add column horizon_expires_at timestamptz;
alter table hypothesis_record add column outcome           varchar(8)
    check (outcome in ('WIN', 'LOSS', 'FLAT'));
alter table hypothesis_record add column outcome_pnl       numeric(38, 10);
alter table hypothesis_record add column exit_price        numeric(24, 6);
alter table hypothesis_record add column closed_at         timestamptz;

create index idx_hypothesis_open on hypothesis_record (horizon_expires_at)
    where outcome is null;
