package io.jethro.domain;

import java.util.Set;

/** Order lifecycle per ADR-0008: NEW → ROUTED → PARTIALLY_FILLED → FILLED | CANCELLED | REJECTED. */
public enum OrderStatus {
    NEW,
    ROUTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED;

    private static final Set<OrderStatus> TERMINAL = Set.of(FILLED, CANCELLED, REJECTED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Legal transitions; anything else is a defect in the order module. */
    public boolean canTransitionTo(OrderStatus next) {
        if (isTerminal()) {
            return false;
        }
        return switch (this) {
            case NEW -> next == ROUTED || next == CANCELLED || next == REJECTED;
            case ROUTED, PARTIALLY_FILLED ->
                    next == PARTIALLY_FILLED || next == FILLED || next == CANCELLED || next == REJECTED;
            default -> false;
        };
    }
}
