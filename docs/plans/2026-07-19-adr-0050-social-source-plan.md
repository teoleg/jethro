# Implementation plan — ADR-0050: social media as an adversarial source

Spine reused from ADR-0045 (source → queue → classify → sector fan-out → ADR-0049 gate). Social media
adds the adversarial pre-stage. Advisory-only throughout — it can never originate an order (ADR-0049).

## Dependencies from other ADRs
- **ADR-0049** — the hard gate: any social-derived `(instrument, direction)` that ever becomes a trade
  candidate must pass it. Phase 1 does not order at all (pure context), so it is respected by
  construction.
- **ADR-0016** — the SLM only classifies categorically, never a number. Phase 1 uses a *deterministic*
  cashtag/sentiment router (no SLM), so it is trivially compliant; the SLM classify slots in at Phase 3.
- **ADR-0040 / `hedge_group`** — the GICS sectoring used for sector fan-out (already in refdata).
- **ADR-0045** — the queue/classify spine; Phase 1 builds a minimal in-process queue, full merge at P3.
- **ADR-0034 / invariant 8** — sim emits synthetic posts (incl. spam + a pump); classify from post
  TEXT, never a sim-attached label; the pipeline runs identically sim/live/replay.

## Phase 1 — sim slice, visible TODAY (this change)
Deterministic, offline, advisory-only. Package `io.jethro.app.social`.
- `SocialPost` — id, source, channel, author, followers, verified, accountAgeDays, ts, text.
- `SocialChannels` — curated channel→tier registry (TRUSTED/STANDARD/UNTRUSTED) seeded in config.
- `SpamFilter` (pure, tested) — drop near-duplicate/copypasta, link/cashtag-spam, low-credibility;
  every drop counted with a reason.
- `Cashtags` (pure, tested) — `$TICKER` + id mentions → instruments in the universe.
- `CorroborationGate` (pure, tested) — an instrument is promoted only on ≥ k DISTINCT credible
  channels in the window; a burst that fails corroboration is flagged `manipulationSuspected` (the
  pump gets caught, not traded). Direction from a deterministic bullish/bearish lexicon (majority).
- `SimSocialFeed` — seedable generator: legit posts from trusted channels + a synthetic PUMP (many
  low-cred throwaway accounts, identical copypasta on one ticker) + generic spam.
- `SocialLifecycle` — sim cadence (`jethro.social.interval-seconds`, demo default 60; prod ~1800 per
  ADR-0045/0050), runs the pipeline, keeps a rolling window + cross-batch dup memory, surfaces a
  summary on the attention feed, exposes counters.
- `SocialController` `/api/social` + `social.html` panel (nav link from the overview) — recent posts
  (kept/dropped), corroborated signals (instrument/sector/direction/#channels), pump-caught flags,
  and the ingest counters.
- Tests: SpamFilter (burst/dup/low-cred shed), CorroborationGate (single post blocked, k-channel
  promoted, pump flagged), Cashtags.
- **Result today (sim):** a live social panel where spam is shed, a synthetic pump is caught and NOT
  promoted, and only multi-channel-corroborated subjects surface as advisory context — zero orders.

## Phase 2 — real free adapters (behind the sim source)
- **2a (done):** `SocialFeed` multi-source composite + `StockTwitsSocialFeed` (HTTP, honours the env
  proxy, fail-open, round-robins symbols/cycle as a rate-limit guard, parse fixture-tested). Source
  selection via `jethro.social.sources` (default `sim` — opt-in, no boot network dependency); a
  subject seen on two different sources corroborates. `SocialChannels` gained a `defaultTier` so a
  real feed (`default-tier=STANDARD`) judges organic accounts by the credibility floors — a pump
  throwaway still fails. The live HTTP call can't be verified offline; the mapping is what's tested.
- **2b (done):** `TelegramSocialFeed` (Bot API `getUpdates`, public channels the bot joins, token via
  env, update cursor, fail-open, parse fixture-tested). Telegram gives no follower signal, so a
  Telegram channel is credible only when curated TRUSTED. Enable with `sources=sim,telegram`.
- **Visibility (done):** every source reports `SocialSourceStatus` (healthy / last poll / count /
  detail); the composite flattens them. `/api/social` now returns per-source connection health + the
  active controls. A dedicated **Sources** page (`social-sources.html`) shows the site connections,
  progress counters, and the running controls — separate from the signals page.
- **2c (todo):** formal rate-limit/backoff per source; a `feedMode`-style live gate if a source should
  be LIVE-only; MTProto (read public channels without being added) if Bot API coverage is too thin.

## Phase 3 — SLM classify + queue merge (ADR-0045)
Replace/augment the deterministic router with the SLM sector-classify for posts without explicit
cashtags; merge into the shared ADR-0045 event queue + `ai.decisions` audit; route corroborated
`(instrument, direction)` through the ADR-0049 gate for advisory-conviction overlay.

## Phase 4 — ranking, discovery, X
Outcome-scored channel ranking (ADR-0027 machinery) to promote/demote channels by measured hit-rate;
channel discovery; a "manipulation suspected" attention signal as a defensive tell; X behind its paid
trigger.
