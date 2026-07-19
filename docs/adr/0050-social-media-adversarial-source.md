# ADR-0050: Social media as an adversarial signal source — spam / credibility / corroboration controls

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** ai, market-data, risk, sim-parity

## Context

Modern moves are driven as much by social platforms (X, Telegram, StockTwits, Reddit) as by wire
news. ADR-0045 already defines the spine — source → dedup queue → SLM sector-classify → deterministic
fan-out → ADR-0049 gate — and social media is "just another source" into that queue. But social media
differs from a central-bank RSS or an SEC filing in one decisive way: it is an **adversarial** channel.
People post *specifically to move prices* — pump-and-dump, coordinated bot campaigns, copypasta spam
on top of a real headline. The free, accessible corners (Telegram pump groups, low-tier X accounts)
are exactly where manipulation concentrates. Naively feeding this to the SLM wastes the scarce local
model on garbage and, worse, lets a manipulation campaign reach the advisory layer.

Access is also uneven and mostly *not* free: X's API is paid and gutted (scraping violates ToS — the
Yahoo wall again); Telegram (Bot API / MTProto public channels) and StockTwits (finance-native API)
are the tractable free firehoses; Reddit is limited-free; Bluesky/Mastodon are open but low-signal.

## Decision

We will ingest social media as a **first-class but adversarial source** behind the ADR-0045 queue,
adding a deterministic defensive pre-stage *before* the SLM and *before* any advisory weight:

1. **Spam / bot pre-filter (deterministic, cheap).** Burst detection, near-duplicate/copypasta
   hashing, link-spam, and a low-credibility floor drop garbage at the queue edge, so the SLM only
   ever sees survivors. Every drop is counted + metered (never silent).
2. **Credibility tiers per channel.** A curated **channel registry** assigns each channel a tier
   (TRUSTED / STANDARD / UNTRUSTED) that sets the *advisory-weight ceiling* — kept **categorical**,
   never a number multiplying a position (ADR-0016). An untrusted channel can never outweigh a
   filing.
3. **Corroboration gate (the core control).** A single post can **never** raise advisory conviction.
   A subject is promoted only on independent corroboration: ≥ *k* distinct credible channels within a
   window, **or** a news source (ADR-0045) confirming, **or** the price already moving in-line. This
   is the deterministic defence against "one tweet moves the book."
4. **Channel selection = curated seed, then measured ranking.** Start from a hand-picked seed registry;
   score each channel's advisory outcomes over time (reusing the ADR-0027 outcome machinery) and
   promote/demote/prune by measured hit-rate. Discovery of new channels is a later follow-up — we do
   not guess forever, we measure.
5. **Same queue, classify, fan-out, gate.** Survivors carry `(subject → sector via hedge_group →
   instruments)` and every `(instrument, direction)` still passes the **ADR-0049 hard gate**. Social
   media is **advisory-only** and can never originate an order; worst case it surfaces context on a
   name the deterministic model already has edge on. The sim emits synthetic posts — including
   synthetic spam and a synthetic pump — through the *same* pipeline (ADR-0034 spirit); classify from
   post **text**, never a sim-attached label (invariant 8, the ADR-0044 loophole lesson).

## Alternatives considered

**Feed social posts straight to the SLM (no pre-stage).** Simplest wiring. Rejected: it burns the
scarce local model on spam and lets a coordinated pump reach the advisory layer — the adversarial
nature is the whole point; the pre-filter + corroboration is not optional polish.

**Trust a single high-signal account / channel.** Cheap alpha if it's real. Rejected: a single source
is the exact manipulation surface — one account (compromised or paid) moves the book. Corroboration
across independent channels is the control.

**Scrape X / build around its firehose now.** Highest signal. Rejected as the default: paid + ToS-
walled (the Yahoo lesson). Start with the free/accessible sources (Telegram public channels,
StockTwits), defer X behind a paid trigger.

**No social media, news + price only.** Safest and simplest. Rejected as the end state — it leaves the
channel that actually moves modern names on the table; the adversarial controls are what make
ingesting it responsible rather than reckless.

## Consequences

- Positive: access to the channel that drives modern moves, absorbed by the existing queue/gate spine;
  the SLM only sees de-spammed, corroborated subjects; social media can never move the book on its own
  (ADR-0049), so ingesting an adversarial source is *safe to explore*; runs offline in sim with
  synthetic spam/pump exercising the filters.
- Negative: social media is a live manipulation surface — the controls reduce but never eliminate the
  risk; the corroboration window trades latency for safety (a real burst waits for a second source);
  free coverage is uneven and X is deferred; credibility tiers and the *k* threshold are tuning knobs;
  classification/attribution quality is a new failure surface needing telemetry; trading on social
  data sits near market-manipulation regulation — the `ai.decisions` audit trail is part of the
  defence.
- Follow-ups: real Telegram/StockTwits adapters behind the sim source; the SLM sector-classify step
  (ADR-0045) once built; outcome-scored channel ranking + channel discovery; X behind its paid trigger;
  a "manipulation suspected" attention signal when a burst fails corroboration (a defensive tell, not a
  trade).
