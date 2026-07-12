package io.jethro.app.order;

import io.jethro.app.kafka.KafkaEventPublisher;
import io.jethro.domain.Decimals;
import io.jethro.domain.Fill;
import io.jethro.domain.Order;
import io.jethro.messaging.EventMeta;
import io.jethro.messaging.FillEvent;
import io.jethro.messaging.OrderEvent;
import io.jethro.messaging.Topics;
import io.jethro.order.OrderEventPublisher;

import java.time.Instant;
import java.util.UUID;

/**
 * Binds the order module's outbound port to the broker (ADR-0015). Order events keyed
 * by orderId, fills keyed by instrumentId so a book/instrument's fills stay ordered
 * (average-cost PnL is order-sensitive). Fill's eventId == fillId for dedupe (invariant 6/8).
 */
public final class KafkaOrderEventPublisher implements OrderEventPublisher {

    private final KafkaEventPublisher publisher;

    public KafkaOrderEventPublisher(KafkaEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publishOrderEvent(Order order, String reason) {
        var event = OrderEvent.newBuilder()
                .setMeta(meta(UUID.randomUUID().toString()))
                .setOrderId(order.orderId())
                .setBookId(order.bookId().value())
                .setInstrumentId(order.instrumentId().value())
                .setStatus(io.jethro.messaging.OrderStatus.valueOf(order.status().name()))
                .setReason(reason)
                .build();
        publisher.publish(Topics.ORDERS_EVENTS, order.orderId(), event);
    }

    @Override
    public void publishFill(Fill fill) {
        var event = FillEvent.newBuilder()
                .setMeta(meta(fill.fillId())) // eventId = fillId → idempotent projection
                .setFillId(fill.fillId())
                .setOrderId(fill.orderId())
                .setBookId(fill.bookId().value())
                .setInstrumentId(fill.instrumentId().value())
                .setSide(io.jethro.messaging.Side.valueOf(fill.side().name()))
                // Avro decimal fields have a fixed scale — restate before encoding
                // (user-entered "100" is scale 0; the schema declares scale 6).
                .setQuantity(Decimals.atScale(fill.quantity(), Decimals.QTY_SCALE))
                .setPrice(Decimals.atScale(fill.price(), Decimals.PRICE_SCALE))
                .build();
        publisher.publish(Topics.FILLS, fill.instrumentId().value(), event);
    }

    private static EventMeta meta(String eventId) {
        Instant now = Instant.now();
        return EventMeta.newBuilder()
                .setEventId(eventId)
                .setProviderTimestamp(now)
                .setIngestTimestamp(now)
                .build();
    }
}
