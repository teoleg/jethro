-- ADR-0134: the ORIGINATION trigger of an order — why the desk wanted the trade.
--
-- `orders.reason` is a STATUS-TRANSITION reason: it records why a status CHANGED, so it is written
-- only on the branches that have a change worth explaining (REJECTED by the guardrail, CANCELLED by a
-- fusion re-plan, an unmarketable IOC). The happy path NEW → ROUTED → FILLED has nothing to explain
-- and writes null, so every order that actually TRADED carried a null reason and the order-level
-- post-mortem had no evidence in it.
--
-- These are two different concepts, so this is a second column rather than an overload: an order can
-- both have been wanted for a trigger AND been refused for a reason, and a REJECTED row is strictly
-- more useful carrying both. Nullable because rows written before this migration have no origin, and
-- because a null here must never block an order.
alter table orders add column origin_reason varchar(256);

comment on column orders.origin_reason is
    'ADR-0134: why the desk wanted this trade (the originating trigger), set at insert and never '
    'overwritten by a later status transition. Distinct from `reason`, which is why the STATUS changed.';
