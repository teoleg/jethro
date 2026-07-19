-- Order auto-slicer (ADR-0025 follow-up): a risk-ADDING order that exceeds the ADV
-- participation cap is split into child slices instead of being rejected outright; each
-- child links back to the (CANCELLED) parent order here. Risk-REDUCING exits are never
-- capped, so never sliced. Nullable — a normal, unsliced order has no parent.
alter table orders add column parent_order_id varchar(64);
create index idx_orders_parent on orders (parent_order_id);
