package io.jethro.messaging;

import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecordBase;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Avro binary serde for topic payloads. v0: plain Avro binary, one record type per
 * topic — schema registry integration (Redpanda's built-in, ADR-0012) is a follow-up;
 * schemas remain the contract either way (invariant: evolve backward-compatibly).
 */
public final class AvroCodec {

    private AvroCodec() {
    }

    public static byte[] encode(SpecificRecordBase record) {
        try {
            var out = new ByteArrayOutputStream(256);
            var encoder = EncoderFactory.get().binaryEncoder(out, null);
            new SpecificDatumWriter<SpecificRecordBase>(record.getSchema()).write(record, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("avro encode failed for " + record.getSchema().getFullName(), e);
        }
    }

    public static <T extends SpecificRecordBase> T decode(byte[] bytes, Class<T> type) {
        try {
            var decoder = DecoderFactory.get().binaryDecoder(bytes, null);
            return new SpecificDatumReader<>(type).read(null, decoder);
        } catch (IOException e) {
            throw new UncheckedIOException("avro decode failed for " + type.getSimpleName(), e);
        }
    }
}
