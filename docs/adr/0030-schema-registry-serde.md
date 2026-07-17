# ADR-0030: Schema-registry serde — writer-schema resolution on the wire

- **Status:** Proposed
- **Date:** 2026-07-17
- **Deciders:** Oleg
- **Tags:** messaging, data, contracts

## Context

`AvroCodec` (v0) decodes every record with the **current compiled schema as both writer and
reader** (`new SpecificDatumReader<>(type)`): no writer schema on the wire, no registry. Avro
binary is not self-describing, so this only works while a topic holds exactly one schema
version. Invariant 4 says schemas evolve backward-compatibly — but backward compatibility is a
property of *schema resolution* (decode old bytes with the writer schema, apply reader
defaults), and this codec never resolves. The failure is silent and total: when `MarkEvent`
gained `bid`/`ask` (nullable, defaulted — ADR-0025), every pre-change record on `md.marks`
became undecodable, and `ui-gateway` (which replays from earliest) skipped 100% of marks — the
market path went dark with only `avro decode failed` WARNs. ADR-0012 already chose Redpanda's
built-in schema registry; it was never wired. Doing nothing means every future field addition
re-breaks any topic that spans the change, and "backward-compatible" stays aspirational.

## Decision

We will route all topic payloads through Redpanda's **Confluent-compatible Schema Registry**.
The writer schema is registered per subject (`<topic>-value` — TopicNameStrategy) and its id is
framed on the wire (**magic byte `0x00` + 4-byte big-endian schema id + Avro binary**);
deserialization fetches the writer schema by id and resolves it against the compiled reader
schema (`SpecificDatumReader(writerSchema, readerSchema)`), so defaulted added fields decode
correctly. Registry **compatibility is set to `BACKWARD`**, making an incompatible edit fail at
registration (in CI/boot) rather than silently at read. We talk to the registry through a
**minimal in-house client** (register + get-by-id, cached) over its REST API — no third-party
Confluent client in the data-path module (keeps `common-messaging` lean; plain Java in data
paths). This realises ADR-0012 and supersedes the v0 embedded-schema shortcut.

## Alternatives considered

**Fingerprint + local schema table** (Avro single-object encoding, CRC-64-AVRO). Lighter, no
registry service — but writer schemas must be shipped out-of-band and no central authority
enforces compatibility; a producer can still register-by-omission an incompatible change. This
is the "bandaid" — it decodes history but doesn't *govern* evolution. Kept only as an offline
fallback if the registry is unreachable at boot; rejected as the primary.

**Full writer schema embedded per record** (JSON/binary header). Self-describing and
registry-free, but the schema dwarfs a small `MarkEvent` payload (10–20×) on a ~1Hz-per-
instrument topic, and still enforces no compatibility. Rejected on overhead.

**Confluent `KafkaAvroSerializer` / `SpecificAvroSerde`.** The battle-tested reference, but it
drags the `packages.confluent.io` repo and a transitive dependency tree into a data-path
module for a narrow need (register + get-by-id). Rejected in favour of the in-house client;
revisit if we need subject compatibility groups, references, or JSON/Protobuf schemas.

## Consequences

- **Positive:** invariant 4 becomes real — old and new records coexist on a topic and both
  decode; incompatible edits are caught at registration, not in production reads; wire format
  is the industry-standard magic-byte framing, so external tooling interops.
- **Negative:** producers/consumers now depend on the schema-registry service being up at
  startup (mitigate: cache ids, register-on-boot, fail-fast with a clear error). A poisoned
  registration can block a subject. **Migration is breaking for existing data:** pre-registry
  records (plain Avro, no magic byte) are unreadable by the new deserializer — local dev topics
  must be wiped (the sim regenerates everything; dev Postgres/Redpanda are disposable per
  ADR-0013), and the registry must be provisioned in the compose stack and the AMI.
- **Follow-ups:** stand up the registry in `docker-compose.yml` + bake it into the AMI; register
  all `common-messaging` schemas in CI with a BACKWARD-compat gate; close the deferred-register
  "codec does no schema resolution" item.
