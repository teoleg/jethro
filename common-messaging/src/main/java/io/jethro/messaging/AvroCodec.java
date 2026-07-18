package io.jethro.messaging;

import org.apache.avro.Schema;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecordBase;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Avro binary serde for topic payloads through the schema registry (ADR-0030). Wire format is
 * the Confluent framing: magic byte {@code 0x00} + 4-byte big-endian schema id + Avro binary.
 * Decode fetches the writer schema by id and resolves it against the compiled reader schema, so
 * records written with an older schema still decode (defaulted fields apply) — invariant 4 made
 * real. Supersedes the v0 reader==writer codec that went silently dark on version-mixed topics.
 *
 * <p>Configure the registry once at startup via {@link #configure}. Unconfigured, it uses an
 * in-JVM registry (fine for single-process tests, unsafe for persisted topics — see
 * {@link InMemorySchemaRegistry}).
 */
public final class AvroCodec {

    private static final byte MAGIC = 0x0;

    private static volatile SchemaRegistryClient registry = new InMemorySchemaRegistry();

    private AvroCodec() {
    }

    /** Point the codec at the durable registry (app startup, before any encode/decode). */
    public static void configure(SchemaRegistryClient client) {
        registry = client;
    }

    /** Encode for {@code topic} (subject {@code <topic>-value}, TopicNameStrategy). */
    public static byte[] encode(String topic, SpecificRecordBase record) {
        Schema writer = record.getSchema();
        int id = registry.idFor(topic + "-value", writer);
        try {
            var out = new ByteArrayOutputStream(256);
            out.write(MAGIC);
            out.write((id >>> 24) & 0xFF);
            out.write((id >>> 16) & 0xFF);
            out.write((id >>> 8) & 0xFF);
            out.write(id & 0xFF);
            var encoder = EncoderFactory.get().binaryEncoder(out, null);
            new SpecificDatumWriter<SpecificRecordBase>(writer).write(record, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("avro encode failed for " + writer.getFullName(), e);
        }
    }

    public static <T extends SpecificRecordBase> T decode(byte[] bytes, Class<T> type) {
        if (bytes == null || bytes.length < 5 || bytes[0] != MAGIC) {
            // Pre-ADR-0030 records (plain Avro, no framing) or corruption: not resolvable.
            throw new IllegalArgumentException("not a registry-framed Avro record for "
                    + type.getSimpleName() + " (pre-ADR-0030 topic data? wipe/rebuild the topic)");
        }
        int id = ((bytes[1] & 0xFF) << 24) | ((bytes[2] & 0xFF) << 16)
                | ((bytes[3] & 0xFF) << 8) | (bytes[4] & 0xFF);
        Schema writer = registry.schemaById(id);
        Schema reader = SpecificData.get().getSchema(type);
        try {
            var decoder = DecoderFactory.get().binaryDecoder(bytes, 5, bytes.length - 5, null);
            return new SpecificDatumReader<T>(writer, reader).read(null, decoder);
        } catch (IOException e) {
            throw new UncheckedIOException("avro decode failed for " + type.getSimpleName(), e);
        }
    }
}
