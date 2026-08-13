# muni-world

An **independent subproject** inside the jethro repo — its own deployable jar, port (:8090), database
schema, LMDB env, Kafka topics, **ADR flow** (`docs/adr/`) and README. It *reuses* jethro's shared building
blocks (`common-domain` decimal money types, `common-messaging` Avro/serde) but is **not** part of the
jethro `app` assembly and is not depended on by it.

## Mission

Inspired by Andrew Kalotay's *Interest Rate Risk Management of Municipal Bonds*: deep, option-adjusted
analytics of the U.S. muni universe — callable-bond OAS, option value, effective duration/convexity,
refunding efficiency. Geographic staging: **NY → NJ → PA** first, then outward. Postgres (its own `muni`
schema) is the system of record; every decision lives in **[ADR-0001 … ADR-0020](docs/adr/README.md)**.

**Status: the data platform is operational and the analytics engine is live.** The loop that grows value
now is simple: each Official Statement loaded moves a whole series of bonds from "call UNKNOWN — refused"
to "OAS computed", and fills the tenor buckets the tax-exempt curve leg needs.

## What runs today

| Piece | ADR | What it does |
|---|---|---|
| **Universe from SEC N-PORT** | 0016 | Registered funds' quarterly portfolio XML on EDGAR → thousands of real NY muni CUSIPs with issuer/coupon/maturity, held-par, fund-attested valuations. Registrant-name gate so a wrong CIK cannot pollute. |
| **Quarterly valuation history** | 0016 | EDGAR serves every N-PORT ever filed (mid-2019→) — a one-time backfill per fund gives dated, par-weighted marks per CUSIP; extended every cycle. |
| **Official Statement pipeline** | 0010/0015 | Drop OS PDFs into `os-inbox/` (or upload at the UI) → deterministic parser extracts per-CUSIP terms incl. **call schedules** across real-world layouts; failures quarantined, never guessed. EMMA is **never scraped** (its ToS prohibit automation — a human downloads the public PDF). The coverage plan ranks which issuer's OS to fetch next. |
| **Benchmark curve** | 0017 | The Fed's own GSW daily **zero-coupon** Treasury curve (`feds200628.csv`, 1961→present, free, no key): six Nelson-Siegel-Svensson parameters per day reconstruct the whole curve in closed form. Refreshed daily. |
| **Curve validation** | 0020 | Every curve day passes three mechanical checks before storage (reprices its own published SVENY zeros; strictly decreasing discount factors — no negative forwards; level plausibility band) — failures quarantined and counted. At read time: staleness gate (a price is discounted on the curve of *its* date) + re-checked sanity. The lattice itself refuses any step it cannot reprice. |
| **Rate volatility** | 0017 | **Measured, never assumed**: realized vol of the GSW 1Y zero over a declared window, in both normal (Hull-White) and lognormal (BDT) parameterisations, always shipped with the p10/p50/p90 band of rolling one-year vol. |
| **OAS engine** | 0018 | BDT lognormal lattice, calibrated *exactly* to the curve as-of each price's own date; OAS solved from the filing mark and reported at the measured σ **and** across the band. Option value, effective duration/convexity (±25bp recalibrated bumps), Kalotay refunding efficiency. **Refusal is a first-class output** — no price, floater, default, or call-UNKNOWN each refuse by name. |
| **Assumption ledger** | 0020 | Every OAS response lists the non-fact inputs it rests on (`assumptions[]`, `assumptionFree`); `?strict=true` refuses rather than assumes. Every constant in the engine is classified in [`docs/model-assumptions.md`](docs/model-assumptions.md). |
| **External validation** | 0019 | CI replays committed **QuantLib** (C++) reference prices against the engine: bullets agree to ~1e-13, callables within 0.03 points (≈½bp OAS) of QuantLib's 500-step tree. Plus hand-computed worked examples throughout the tests. |
| **Broadcast audio** | 0014 | Licensed-feed audio capture → local whisper transcription → *soft signals only* (a lead to verify, never a number). |

## UI (server-served static pages, no build step)

- **`/` Bonds** — the universe browser (documented-bonds-first sort; call state visible per row),
  master-detail with full stored terms, valuation history, and the OAS block (σ band, analytics,
  assumption ledger); readiness panel ("can the model run, and what's missing"); coverage plan;
  the curve & volatility calculations panel.
- **`/model.html` Model workbench** — the engine step by step: load a **real bond** (all collected data,
  same code path as the OAS block) or state inputs by hand (book examples / what-ifs). Shows the discount
  curve per step, the short-rate lattice as a tree, both value legs, risk, and the refunding verdict.
- **`/tv.html`** — audio capture control + recent transcript leads.

## Key API endpoints

```
GET /api/muni/bonds?q=&sort=call&page=0&size=50      # browse (call-knowledge sort is the default)
GET /api/muni/bonds/{cusip}                          # everything stored about one bond
GET /api/muni/bonds/{cusip}/valuations               # quarterly fund-attested marks
GET /api/muni/bonds/{cusip}/oas[?strict=true]        # OAS + analytics; strict = facts-only, refuses on assumptions
GET /api/muni/bonds/coverage                         # which OS to fetch next
GET /api/muni/bonds/readiness                        # model preconditions, counted from the data
GET /api/muni/curve                                  # curve/vol ingest status incl. quarantine counts
GET /api/muni/curve/grid | /zero?years=              # the evaluated curve
GET /api/muni/model/analyze?...                      # workbench, explicit inputs
GET /api/muni/model/bond?cusip=                      # workbench, a real bond end-to-end
```

## Run

```bash
scripts/svc.sh start muni       # build + start (independent; :8090)
scripts/svc.sh restart muni     # rebuild + restart (auto-backup at most once/hour)
scripts/svc.sh backup muni      # muni schema dump + the OS PDFs -> backups/
scripts/svc.sh stop trading     # focus mode: improvement loop + ollama OFF; app/postgres/muni untouched

# or directly:
./gradlew :muni-world:bootJar && java --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED -jar muni-world/build/libs/muni-world.jar
```

The jar **boots offline** (Hikari never probes at startup; Flyway/Kafka off by default; LMDB is a local
file). Point it at real services via `MUNI_*` env vars — see `application.properties`, where every dial
carries a provenance comment. `MUNI_FLYWAY_ENABLED=true` once Postgres is up.

## Backends (own namespaces)

| Backend  | How muni-world uses it | Default |
|----------|------------------------|---------|
| **Postgres** | The shared instance, muni-world's **own `muni` schema**, Flyway-managed (V1–V5: terms, filing detail, valuation history, curve/vol). | **flyway off** until a DB is up |
| **LMDB** | Embedded, memory-mapped, **derived-only** index — its own env under `build/muni-lmdb`. | on (local file) |
| **Kafka/Redpanda** | The shared broker; muni-world owns only `muni.*` topics. | **off** until wired |

## Docs map

- **[`docs/adr/`](docs/adr/README.md)** — all decisions, ADR-0001…0020, with a reading order.
- **[`docs/model-assumptions.md`](docs/model-assumptions.md)** — every hardcoded constant, classified
  (math / cited convention / judgement call), plus the book-reference table the owner fills while
  verifying against Kalotay's text.
- **[`docs/runbooks/audio-capture.md`](docs/runbooks/audio-capture.md)** — the TV/audio setup.
- **[`prompts/`](prompts/README.md)** — the governed Claude prompt library (ADR-0012).

## Conventions

Same disciplines as jethro: exact-decimal money (`NUMERIC`/`BigDecimal`, never binary FP — the one stated
exception: the transcendental curve/lattice fit runs in `double` and is rounded ONCE at a declared scale,
ADR-0017 §4); design-first ADRs; provenance on every number (an unsourced value is either a cited
convention, an owner-settable `PLACEHOLDER`, or refused); quarantine-never-guess (ADR-0011); and refusal
as a first-class analytic output.
