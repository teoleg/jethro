package io.jethro.messaging;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvroCodecTest {

    @Test
    void encodeDecodeRoundTripsExactly() {
        var event = MarkEvent.newBuilder()
                .setMeta(EventMeta.newBuilder()
                        .setEventId("e-1")
                        .setProviderTimestamp(Instant.ofEpochMilli(1_700_000_000_000L))
                        .setIngestTimestamp(Instant.ofEpochMilli(1_700_000_000_001L))
                        .build())
                .setInstrumentId("AAPL")
                .setPrice(new BigDecimal("101.230000"))
                .setSource("sim")
                .build();

        byte[] bytes = AvroCodec.encode(event);
        assertEquals(event, AvroCodec.decode(bytes, MarkEvent.class));
    }
}
