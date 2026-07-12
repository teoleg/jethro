package io.jethro.order;

import io.jethro.domain.Fill;
import io.jethro.domain.Order;

/**
 * Outbound port (ADR-0015): the order module emits lifecycle + fill events without
 * knowing the transport. The app assembly binds this to the Kafka publisher
 * (orders.events, fills). Keeps the module free of broker code and extractable.
 */
public interface OrderEventPublisher {

    void publishOrderEvent(Order order, String reason);

    void publishFill(Fill fill);
}
