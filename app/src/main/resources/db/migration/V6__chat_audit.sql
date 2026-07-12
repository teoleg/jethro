-- Operational chat audit (ADR-0021): every chat turn is persisted — question, the
-- understood intent + slots, the deterministic answer, the model that parsed it, and
-- latency. Compliance-grade record and the corpus for improving NL later.

create table chat_audit (
    id             varchar(64)  primary key,
    asked_at       timestamptz  not null,
    question       text         not null,
    intent         varchar(32)  not null,
    book           varchar(64),
    instrument     varchar(64),
    answer         text         not null,
    model          varchar(64),
    latency_millis bigint       not null
);

create index idx_chat_audit_time on chat_audit (asked_at desc);
