# ADR-0056: Multi-source market-data coverage — declarative per-instrument source precedence

- **Status:** Accepted
- **Date:** 2026-07-21
- **Deciders:** Oleg
- **Tags:** market-data, data, trading-core, ops

## Context

The market path abstracts one adapter behind a port (ADR-0009); ADR-0023/0024 then composed a
*hybrid* — Finnhub's real-time WebSocket for a few US equities, Yahoo's delayed poll for the rest,
the sim curve for rates — as bespoke logic inside `buildFinnhubAdapter`. Two problems compound as
sources grow. (1) Each source has its own symbology (already per-provider in refdata, invariant 2)
**and** its own coverage/liveness profile: Finnhub is real-time but push-on-trade (it goes dark
off-hours) and free-tier only streams a handful of names; Yahoo is delayed but polls 24/7. (2) A
name with a **single** source goes dark whenever that source hiccups — observed live: a short in the
ALPHA book showed `mark 0 → 0 exposure → frozen PnL` because its lone source stopped marking it.
Phantom-flat risk on a real position is exactly the class of bug this platform treats as serious.

Adding a source today means writing another hand-rolled hybrid builder, and which source is
authoritative for which instrument (with fallback) is implicit in code. Worse, the mark cache applies
every incoming mark **last-writer-wins with no timestamp ordering** (`MarkCache.update`), so two
sources on one name can send the price *backwards in time*. Doing nothing multiplies bespoke
composition per source and lets the single-source blackout recur. This is orthogonal to the sim/live
*mode* switch (ADR-0029) — that separates modes; this composes sources **within** a mode.

## Decision

We will make coverage **declarative and multi-source**: each instrument carries an **ordered list of
`(source, symbol)`** — primary + fallbacks — derived from the per-provider symbology already in
refdata, and the runtime composes all configured live adapters behind the ADR-0009 port into one
merged stream. The mark cache gains a **freshness guard**: an update is rejected when its
**provider** timestamp is strictly older than the stored (live) mark's, so a delayed source never
regresses a fresher one — making overlapping sources safe. A name stays marked while **any** of its
sources is live, so a quiet WS or a single-source miss no longer blanks a book. Adding a source
becomes: one adapter + its symbology rows + a coverage priority — data, not surgery.

**Interim step (shipped with this ADR, within ADR-0024's shape):** (a) the freshness guard lands in
`MarkCache`; (b) the Finnhub-covered names **stay** on the Yahoo background as a fallback instead of
being removed — the actual cure for the blackout. Those names (incl. JPM/NVDA/JNJ, which already
carry a `finnhub` symbol from V34) were Finnhub-*only*: the old composition stripped every
Finnhub-covered name out of the Yahoo background, so each went dark whenever the WS was quiet
(off-hours) or dropped. The full declarative priority table replaces the hardcoded composition once
this ADR is Accepted.

**First real second source (Alpaca):** `PROVIDER=alpaca` adds Alpaca's genuinely-free (no credit card)
real-time US-equities WebSocket (IEX tier) as an alternative to Finnhub, composed the same way —
Alpaca-covered names on the Yahoo background as a delayed fallback, freshness-guarded so the live and
delayed marks never mix. It's the concrete second source this ADR was written to make cheap to add:
one adapter behind the ADR-0009 port + `alpaca` symbology rows (V44) + the coverage priority. (Alpaca's
v2 stream can emit msgpack; this adapter expects JSON text and fails a binary frame loudly rather than
silently — a msgpack decoder is the follow-up if a connection lands on that format.)

## Alternatives considered

- **Keep the bespoke per-provider hybrid (status quo).** Every source is surgery in `buildXAdapter`;
  precedence is implicit; the single-source blackout recurs. Rejected — it is the problem statement.
- **Strict one-source-per-instrument (no overlap).** Simplest, but no fallback: a quiet or missing
  source blanks the name, and it throws away Finnhub's real-time where a Yahoo fallback would still
  cover the gap. Rejected.
- **Merge sources, "freshest by ingest time wins."** Rejected: ingest time reflects *our* poll
  cadence, so a delayed source polled just now looks fresh and clobbers a real-time mark. The honest
  clock is the **provider** timestamp (invariant 5 already carries it) — hence the guard uses it.
- **External consolidated/normalised vendor feed.** The right answer at production scale, but cost
  and integration overkill for one dev box. Deferred; trigger = real-money or a paid consolidated feed.

## Consequences

- **Positive:** a position stays valued while any source lives (no phantom-flat risk); adding a
  source is data + one adapter, not a new hybrid; precedence is auditable in refdata; the freshness
  guard fixes a latent last-writer-wins regression independent of the rest.
- **Negative:** overlapping sources spend more request quota (Yahoo now also polls names Finnhub
  streams) — bounded by the small universe; two sources can disagree on price near the jump-guard
  threshold (mitigated: the freshness guard drops the laggard *before* the jump check, so cross-source
  noise can't false-quarantine); the declarative priority table is a refdata schema addition every
  source path must honour.
- **Follow-ups:** a refdata coverage guard test (every price-quoted name has ≥1 source's symbology —
  folds into the existing deferred attribute-coverage item, blocked on a Flyway/Testcontainers harness);
  extend `/api/feeds` per-instrument ("which source is marking this now"). Supersedes the *composition*
  half of ADR-0024 (its Finnhub feed stays).

## Implementation status (2026-07-21)

Accepted and implemented:
- **Freshness guard** — `MarkCache.update` rejects a mark whose *provider* timestamp is older than the
  stored live one (`supersededTicks` counted). This is what lets sources overlap without mixing.
- **Fallback coverage** — every real-time WS source keeps its covered names on the Yahoo delayed poll,
  so a quiet WS never blanks a position. Realized as the shared `TradingCoreLifecycle.liveBackground`
  helper: a new WS source is now its symbology map + a `MarketDataAdapter` (one line), **not** a bespoke
  hybrid builder — Alpaca (`PROVIDER=alpaca`, V44 symbology) is the first source added this way.

**Decision — no per-instrument priority *table*.** The ADR floated an ordered `(source, symbol, priority)`
table per instrument. In implementation this proved unnecessary: the **freshness guard already resolves
precedence** (freshest provider timestamp wins), and "primary" is simply the configured `provider` with
Yahoo as the universal delayed fallback. A priority column would only tie-break equal timestamps — not
worth a schema addition every source path must honour. Precedence stays: *live provider ▸ delayed Yahoo
▸ sim/curve*, arbitrated by timestamp. If a future source needs explicit ordering beyond freshness (e.g.
two real-time feeds on one name), revisit then.
