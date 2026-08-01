# muni-world

An **independent subproject** inside the jethro repo — its own deployable jar, port, database schema,
LMDB env, Kafka topics, **ADR flow** (`docs/adr/`) and README. It *reuses* jethro's shared building
blocks (`common-domain` decimal money types, `common-messaging` Avro/serde) but is **not** part of the
jethro `app` assembly and is not depended on by it. Municipal-bond domain is the intended focus; today the
module is scaffolding + a live status UI.

## Run

```bash
# independent build + jar
./gradlew :muni-world:build           # compiles + runs the smoke test (with -Pci)
./gradlew :muni-world:bootJar         # -> muni-world/build/libs/muni-world.jar

# run it (serves its UI on :8090, alongside the jethro app on :8080)
./gradlew :muni-world:bootRun
# or: java --add-opens java.base/java.nio=ALL-UNNAMED \
#          --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
#          -jar muni-world/build/libs/muni-world.jar
```

Open `http://localhost:8090/` — or reach it from the **jethro landing page** via the *Muni World* tile.

## Backends (own namespaces)

| Backend  | How muni-world uses it | Default |
|----------|------------------------|---------|
| **LMDB** | Embedded, memory-mapped, **derived-only** store — its own env under `build/muni-lmdb`. | on (local file) |
| **Postgres** | The shared instance, muni-world's **own `muni` schema**, Flyway-managed (`db/migration`). | **flyway off** until a DB is up |
| **Kafka/Redpanda** | The shared broker; muni-world owns only `muni.*` topics. | **off** until wired |

The jar **boots offline** — Hikari never probes the DB at startup, Flyway/Kafka are off by default, and
LMDB opens a local file — so `bootRun` serves the UI with no external services. Point it at real services
via the `MUNI_*` env vars in `application.properties` (all creds are placeholders).

## Conventions

muni-world follows the same disciplines as jethro: exact-decimal money (`NUMERIC` / `BigDecimal`, never
binary FP), design-first ADRs for anything architecturally significant, and provenance on any number that
gates money/risk. Its decisions live in **[`docs/adr/`](docs/adr/README.md)** — start at ADR-0001.
