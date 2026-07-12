package io.jethro.messaging;

import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Schemas are contracts: prove serde round-trips exactly, decimals included. */
class SerdeRoundTripTest {

    private static EventMeta meta(String id) {
        return EventMeta.newBuilder()
                .setEventId(id)
                .setProviderTimestamp(Instant.ofEpochMilli(1_700_000_000_000L))
                .setIngestTimestamp(Instant.ofEpochMilli(1_700_000_000_042L))
                .build();
    }

    @Test
    void markEventRoundTripsWithExactDecimal() throws Exception {
        var event = MarkEvent.newBuilder()
                .setMeta(meta("e-1"))
                .setInstrumentId("I1")
                .setPrice(new BigDecimal("123.456789")) // scale 6, exact
                .setSource("sim")
                .build();

        var decoded = roundTrip(event, MarkEvent.class);
        assertEquals(event, decoded);
        assertEquals(new BigDecimal("123.456789"), decoded.getPrice());
    }

    @Test
    void fillEventRoundTrips() throws Exception {
        var event = FillEvent.newBuilder()
                .setMeta(meta("e-2"))
                .setFillId("f-1")
                .setOrderId("o-1")
                .setBookId("B1")
                .setInstrumentId("I1")
                .setSide(Side.SELL)
                .setQuantity(new BigDecimal("120.000000"))
                .setPrice(new BigDecimal("12.000000"))
                .build();

        assertEquals(event, roundTrip(event, FillEvent.class));
    }

    @Test
    void aiDecisionRoundTrips() throws Exception {
        var event = AiDecision.newBuilder()
                .setMeta(meta("e-3"))
                .setDecisionId("d-1")
                .setModelId("claude-x")
                .setModelVersion("2026-01")
                .setContextHash("abc123")
                .setContextSnapshotJson("{\"marks\":{}}")
                .setProposedActionsJson("[]")
                .setLatencyMillis(1200)
                .setInputTokens(2000)
                .setOutputTokens(300)
                .build();

        assertEquals(event, roundTrip(event, AiDecision.class));
    }

    @SuppressWarnings("unchecked")
    private static <T extends org.apache.avro.specific.SpecificRecordBase> T roundTrip(T record, Class<T> type)
            throws Exception {
        var out = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(out, null);
        new SpecificDatumWriter<>(type).write(record, encoder);
        encoder.flush();
        var decoder = DecoderFactory.get().binaryDecoder(out.toByteArray(), null);
        return new SpecificDatumReader<>(type).read(null, decoder);
    }
}
