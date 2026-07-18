package io.jethro.messaging;

import org.apache.avro.Schema;

/**
 * Maps between an Avro writer schema and a durable global id (ADR-0030). Producers register
 * the schema they write and frame its id on the wire; consumers fetch the writer schema by id
 * and resolve it against their compiled reader schema, so backward-compatible evolution
 * (invariant 4) actually decodes across versions. Implementations cache both directions.
 */
public interface SchemaRegistryClient {

    /** Register (idempotently) {@code schema} under {@code subject} and return its global id. */
    int idFor(String subject, Schema schema);

    /** The writer schema for a previously issued id. Throws if the id is unknown. */
    Schema schemaById(int id);
}
