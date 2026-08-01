# muni-world

An **independent subproject** inside the jethro repo — its own deployable jar, port, database schema,
LMDB env, Kafka topics, **ADR flow** (`docs/adr/`) and README. It *reuses* jethro's shared building
blocks (`common-domain` decimal money types, `common-messaging` Avro/serde) but is **not** part of the
jethro `app` assembly and is not depended on by it.

## Mission

Inspired by Andrew Kalotay's *Interest Rate Management of Municipal Bonds* — the eventual goal is deep,
option-adjusted analytics of the U.S. muni universe (callable-bond OAS, effective duration, refunding
efficiency). **Phase 1 is data**: collect, normalise and warehouse the muni universe from every available
source — MSRB/EMMA, Census, state disclosure hubs (NY OSC, NJ DCA, PA DCED), and thousands of local
governments (counties, cities, townships, school districts, authorities) — via APIs, bulk files and polite
web scraping. Geographic staging: **NY → NJ → PA** first, then outward. Postgres (its own `muni` schema) is
the system of record.

The data platform is specified across **[ADR-0002 … ADR-0012](docs/adr/README.md)** — mission (0002),
sources (0003), ingestion (0004), raw+provenance (0005), schema (0006), identity (0007), legal/polite
crawling (0008), scheduling (0009), document extraction (0010), quality/coverage (0011), and the
**Claude-assisted analysis layer + prompt library** (0012). Analytics is the deferred north star; the
schema is built to serve it. Today the module is scaffolding + a live status UI — the pipeline is the next
build.

**AI analysis (ADR-0012).** Analysis of the collected data is Claude-assisted, driven by a governed,
versioned **prompt library** in [`prompts/`](prompts/README.md) — grounded in muni-world's own data,
cited to source, number-guardrailed and audited (a model output never silently becomes a canonical
money/valuation figure — jethro's discipline, inherited). Local SLM (Ollama) handles cheap triage; Claude
does the deep reasoning, cost-gated.

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
