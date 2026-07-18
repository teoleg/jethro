package io.jethro.messaging;

import org.apache.avro.Schema;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AvroCodecTest {

    @BeforeEach
    void freshRegistry() {
        AvroCodec.configure(new InMemorySchemaRegistry());
    }

    private static MarkEvent mark() {
        return MarkEvent.newBuilder()
                .setMeta(EventMeta.newBuilder()
                        .setEventId("e-1")
                        .setProviderTimestamp(Instant.ofEpochMilli(1_700_000_000_000L))
                        .setIngestTimestamp(Instant.ofEpochMilli(1_700_000_000_001L))
                        .build())
                .setInstrumentId("AAPL")
                .setPrice(new BigDecimal("101.230000"))
                .setSource("sim")
                .build();
    }

    @Test
    void encodeDecodeRoundTripsExactly() {
        var event = mark();
        byte[] bytes = AvroCodec.encode("md.marks", event);
        assertEquals(0x0, bytes[0], "record must carry the magic byte + schema id framing");
        assertEquals(event, AvroCodec.decode(bytes, MarkEvent.class));
    }

    /**
     * The ADR-0030 guarantee: a record written with an OLDER schema (before bid/ask) decodes
     * against the current reader via writer-schema resolution, defaulting the added fields —
     * the exact case the v0 codec failed, taking md.marks dark.
     */
    @Test
    void decodesRecordWrittenWithAnOlderSchema() throws Exception {
        // The pre-bid/ask MarkEvent: the current schema minus the two added fields, reusing the
        // real field schemas so the EventMeta reference and the decimal type stay identical.
        Schema current = MarkEvent.getClassSchema();
        List<Schema.Field> oldFields = current.getFields().stream()
                .filter(f -> !f.name().equals("bid") && !f.name().equals("ask"))
                .map(f -> new Schema.Field(f.name(), f.schema(), f.doc(), f.defaultVal()))
                .toList();
        Schema oldSchema = Schema.createRecord(current.getName(), current.getDoc(),
                current.getNamespace(), false, oldFields);

        var registry = new InMemorySchemaRegistry();
        AvroCodec.configure(registry);
        int writerId = registry.idFor("md.marks-value", oldSchema);

        // Frame [magic][id][avro binary written with the OLD schema] — bid/ask are not written.
        var out = new ByteArrayOutputStream();
        out.write(0x0);
        out.write((writerId >>> 24) & 0xFF);
        out.write((writerId >>> 16) & 0xFF);
        out.write((writerId >>> 8) & 0xFF);
        out.write(writerId & 0xFF);
        var encoder = EncoderFactory.get().binaryEncoder(out, null);
        new SpecificDatumWriter<MarkEvent>(oldSchema).write(mark(), encoder);
        encoder.flush();

        MarkEvent decoded = AvroCodec.decode(out.toByteArray(), MarkEvent.class);
        assertEquals("AAPL", decoded.getInstrumentId());
        assertEquals(new BigDecimal("101.230000"), decoded.getPrice());
        assertNull(decoded.getBid(), "added field defaults to null under resolution");
        assertNull(decoded.getAsk());
    }

    @Test
    void rejectsUnframedLegacyBytes() {
        // A pre-ADR-0030 record has no magic byte; decode must fail loudly, not mis-read.
        byte[] legacy = {0x06, 0x41, 0x41, 0x50, 0x4C};
        assertThrows(IllegalArgumentException.class, () -> AvroCodec.decode(legacy, MarkEvent.class));
    }
}
