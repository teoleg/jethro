# ADR-0034: News-driven sim shocks — news moves the tape

- **Status:** Proposed
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** market-data, sim, ai, news

## Context

The narrative feed (news) and the price/volume sim are independent: news is text handed to the LLM,
and it moves nothing. Real markets move *because of* news and the flow it brings — a headline reprices
the name in a jump (bp momentum) and spikes turnover (volume surge), correlated. Without that
coupling the platform's news→price→volume relationships are a coincidence: a volume-confirmed signal
(gap-register G3/G4) or the hypothesis layer has no real signal to find, and the "prices feel
arbitrary vs the news" complaint stands even on a realistic price engine (ADR-0032).

Constraints: this must be **SIM-only** — a mechanism that let news move a *live* tape would be a
serious bug (the AI/news layer must never move real prices; invariant 7 keeps AI off the trading
path). It must reuse the existing controllable surface (SimControl, ADR-0031) rather than a second
way to perturb the tape. And an untouched, news-free run must still reproduce the seeded tape
(ADR-0009). Doing nothing leaves news decorative.

## Decision

In SIM mode the sim **generates its own news** and each event applies a **correlated shock** to the
emitting instrument, so news is the *cause* of the move, not a coincidence:

- A seedable **`SimNewsEngine`**, driven by the sim's own tick loop, occasionally fires a news event
  for an instrument (a sentiment sign + a magnitude) and records a templated headline.
- The event applies, through **`SimControl`** (ADR-0031), a **repricing jump** (one-shot price nudge
  in the news direction), a short **momentum drift**, and a **volume surge**, all **decaying over a
  news horizon** in the sim's own ticks. Folded into the existing per-instrument drift/volume dials,
  so both the correlated (ADR-0026) and historical (ADR-0032) engines pick it up unchanged.
- The **same event is surfaced to the narrative feed**, so the model reads the headline that actually
  moved the tape.

Hard-gated to `feedMode == SIM`; deterministic (a fixed seed ⇒ the same news, shocks and tape); an
untouched, news-disabled run is bit-for-bit the old seeded tape.

## Alternatives considered

**Bridge the real narrative feed to shocks.** Drive shocks from whatever news the feed carries. But
live news must NEVER move the tape, and sim news is our own fixtures anyway — generating in-sim keeps
causality *and* determinism, and keeps the live guard trivial. Rejected.

**Inject marks on the topic directly on a news event.** Bypasses the sim engine, so the injected move
wouldn't carry the factor-model correlations/regimes (ADR-0026) and would need the same SIM guard.
Rejected — same reasoning as ADR-0031's mark-injection alternative.

**Leave news and price independent (status quo).** Zero work, but it's exactly the gap: the model and
any volume signal train on a coincidence. Rejected.

## Consequences

- **Positive:** the sim becomes a *causal* lab — a headline reprices the name and spikes its volume,
  and you can watch the model react to news it can actually see move; real news→momentum→volume
  correlation for G3/G4 and the hypothesis layer to find.
- **Negative:** a new sim-only mechanism to keep strictly `feedMode==SIM`-gated (a tested invariant);
  shocks can stack into unrealistic moves (bounded magnitudes + a per-instrument cap); generated
  headlines are **templated/synthetic** and must read as sim, never mistaken for real news; news
  timing is seedable but adds a second stochastic source to reason about in a tape.
- **Follow-ups:** richer headline templates; once G3/G4 land, volume-confirmed signals consume the
  surge (a manual "fire a news shock" control on `/sim.html` shipped — see below). Depends on
  ADR-0031 (SimControl), ADR-0026/0032 (engines), ADR-0029 (`feedMode` gate).

## Implementation status (2026-07-18) — built

- **Tape coupling.** `SimControl.fireNewsShock` (repricing jump via the nudge queue + decaying
  momentum drift + decaying volume surge, aged by `onTick()`), folded into the existing drift/volume
  dials so both engines get it unchanged; `SimNewsEngine` (seedable) generates events from the
  adapter tick loop. Gated to the pure-sim provider (`simNewsEnabled`) — never a live tape. Config:
  `sim-news-per-day` (default 4), `sim-news-horizon-seconds` (default 20).
- **Model sees the news.** `SimEngineNarrativeFeed` surfaces the generated headlines (with display
  names + sentiment) to the hypothesis layer in pure-sim mode, so the model reads the very headline
  that moved the tape — news→price→volume is now causal and the model closes the loop.
- **Manual "fire a news shock" button (follow-up shipped).** `SimNewsEngine.fireManual` +
  `TradingCoreLifecycle.fireSimNews` route a hand-triggered event through the same coupling (jump +
  momentum + volume surge) and record the headline so the model reads it too — never a bare price
  nudge. Exposed as `POST /api/sim/news` (`{instrumentId, direction: BULL/BEAR, magnitude?}`, default
  1.5%), sharing the SIM-mode 403 gate of every other dial; a control on `/sim.html` fires it.
- Untouched (news-free) runs are bit-for-bit the old seeded tape; every path is tested.
