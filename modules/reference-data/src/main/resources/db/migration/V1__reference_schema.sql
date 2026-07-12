-- Reference data (ADR-0008): books form a tree; instruments carry exact-decimal
-- multipliers (NUMERIC per invariant 1) and a symbology table that only the
-- market-data module may use for translation (invariant 2).

create table book (
    book_id       varchar(64) primary key,
    name          varchar(256)   not null,
    base_currency varchar(16)    not null,
    parent_id     varchar(64) references book (book_id)
);

create table instrument (
    instrument_id       varchar(64) primary key,
    asset_class         varchar(16)    not null
        check (asset_class in ('EQUITY', 'FUTURE', 'OPTION', 'FX', 'BOND')),
    currency            varchar(16)    not null,
    contract_multiplier numeric(20, 8) not null check (contract_multiplier > 0)
);

create table instrument_symbology (
    instrument_id varchar(64)  not null references instrument (instrument_id),
    source        varchar(64)  not null,
    symbol        varchar(128) not null,
    primary key (instrument_id, source)
);

create index idx_book_parent on book (parent_id);
