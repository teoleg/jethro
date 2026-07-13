-- Executed AI hypotheses (ADR-0022): every hypothesis the bounded-autonomy envelope
-- acted on — i.e. that led to an order — is persisted here. Compliance-grade record of
-- what the model proposed and what the deterministic quant layer sized/executed, and the
-- source of the sticky "executed" list the UI shows (survives restarts). Live per-cycle
-- proposals stay transient; only decisions that moved an order are durable.

create table hypothesis_record (
    id                 varchar(64)   primary key,   -- the order id (one executed order = one record)
    created_at         timestamptz   not null,
    instrument         varchar(64)   not null,
    direction          varchar(8)    not null,      -- BUY / SELL
    horizon            varchar(16),
    conviction         varchar(16),
    thesis             text          not null,
    book               varchar(64),
    quantity           numeric(38,10),              -- exact decimal (invariant 1), never a float
    backtest_supported boolean,
    order_id           varchar(64),
    order_status       varchar(24)
);

create index idx_hypothesis_record_time on hypothesis_record (created_at desc);
