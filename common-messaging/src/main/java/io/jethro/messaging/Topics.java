package io.jethro.messaging;

/**
 * Topic names and partition-key conventions (ADR-0012/0014). Partition keys:
 * instrumentId for marks, bookId for risk, orderId for order flow.
 */
public final class Topics {

    public static final String MD_MARKS = "md.marks";
    public static final String ORDERS_NEW = "orders.new";
    public static final String ORDERS_EVENTS = "orders.events";
    public static final String FILLS = "fills";
    public static final String RISK_SNAPSHOTS = "risk.snapshots";
    public static final String AI_DECISIONS = "ai.decisions";
    public static final String COST_SNAPSHOTS = "cost.snapshots";

    private Topics() {
    }
}
