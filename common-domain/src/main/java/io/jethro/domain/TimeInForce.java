package io.jethro.domain;

/**
 * How long an order stays working (ADR-0025). {@code GTC} is the default; {@code IOC}
 * cancels immediately if not marketable on arrival; {@code DAY} expires at the session
 * close — accepted in the model but rejected at submit until the session calendar exists
 * (ADR-0027), because silently treating DAY as GTC would misrepresent the order.
 */
public enum TimeInForce {
    GTC,
    IOC,
    DAY
}
