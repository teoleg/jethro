package io.jethro.app.strategy;

/**
 * A deterministic (momentum) strategy action, for the UI's "quant actions" view — so the
 * algo side is as visible as the AI hypothesis side (the two engines trade different books,
 * ADR-0019/0022). Every auto-executed entry and risk-reducing exit is recorded WITH its reason
 * (the momentum rationale, or the stop-loss/take-profit/de-risk trigger), so a SELL is never a
 * mystery. Live telemetry, in-memory only (the order itself is the durable record on the blotter).
 */
public record StrategyActivity(long timestampMillis, String kind, String instrumentId, String book,
                               String side, String quantity, String reason, String orderStatus) {

    /** kind values. */
    public static final String ENTRY = "ENTRY";
    public static final String EXIT = "EXIT";
}
