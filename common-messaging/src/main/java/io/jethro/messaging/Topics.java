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

    /**
     * The mode-namespaced topic for this run (ADR-0029): {@code sim.md.marks}, {@code live.md.marks},
     * etc. Sim, live and replay never share a topic, so their events can't aggregate in the log or
     * any projection. Both producers and consumers resolve through here against the same
     * {@link Provenance#mode()}, so they always meet on the same stream.
     */
    public static String resolved(String baseTopic) {
        return Provenance.mode().name().toLowerCase() + "." + baseTopic;
    }

    private Topics() {
    }
}
