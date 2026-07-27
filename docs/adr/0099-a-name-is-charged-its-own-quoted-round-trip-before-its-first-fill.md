# ADR-0099: A name is charged its own quoted round trip before its first fill

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, execution-cost, risk

## Context

ADR-0075 established the right test: before the desk may put risk **on** in a name, some source's
measured expectancy must survive **that name's own** measured round-trip cost with significance. It
was written because the desk's measured TCA spans two orders of magnitude across the cross-section,
so one blended hurdle is wrong in both directions at once.

It left one hole, and the hole is where the money goes. A name only has a measured round trip **after
it has traded**. ADR-0075 charges an unfilled name the desk's *blended* measured cost — deliberately,
because at the time the alternative was to invent a number. So the desk enters every new name
believing it costs the average of the names it already trades, then discovers the real figure out of
its own PnL, and only then applies the veto. The veto works; it just arrives after the loss.

The live book shows the mechanism and its price. The desk's two worst individual names are both
positions it has since closed:

| name | measured round trip | realised PnL |
|---|---|---|
| GOOGL | 20.11 bps | −$161.84 |
| SAP | 8.16 bps | −$85.46 |

Together they are more than half of what the whole firm has made. They are also, by a wide margin,
the two most expensive names the desk has ever filled — against a source (`reversion`) whose measured
net edge at the selected rung is `+9.07` bps with `t = 9.32`. A 20 bps round trip against a 9 bps edge
loses 11 bps every time it is taken; no amount of signal quality repairs it. Both names were entered
while charged the desk blend of `1.48` bps, which made them look affordable.

This is not over: the same trigger is loaded and pointing at the rest of the cross-section. Six names
the desk has **never filled** — `BRK.B`, `NFLX`, `ORCL`, `GS`, `TSLA` and `GOOGL` again — currently
quote a `20.0` bps touch, and the fusion planner's current target book wants roughly `$120k` of gross
in them. Charged the blend, every one of them passes the gate exactly as GOOGL did.

The missing input was never missing. **The quoted touch is a measurement of that name, on the stream,
available before the first fill.** The desk already carries live bid/ask per instrument in the
`QuoteCache` (ADR-0025) and has simply never read it into the cost model. Checked against the desk's
own realised TCA on the names it *has* filled, the quoted spread predicts the realised round trip
well and errs conservatively where it errs:

| name | quoted spread | measured round trip | quoted / measured |
|---|---|---|---|
| GOOGL | 20.00 | 20.11 | 0.99 |
| SAP | 8.00 | 8.16 | 0.98 |
| GOOG | 3.50 | 2.09 | 1.67 |
| MSFT | 2.50 | 1.33 | 1.88 |
| JPM | 2.00 | 1.16 | 1.72 |
| AAPL | 2.00 | 0.96 | 2.08 |
| JNJ | 2.00 | 1.30 | 1.53 |
| ES | 0.46 | 0.41 | 1.12 |

(Provenance: the *measured* column is `/api/fusion/targets` `.edgeGate.roundTripBpsByInstrument`, the
desk's own TCA. The *quoted* column is the estimator below applied to the live `/api/marks` bid/ask —
i.e. the shipped `QuotedSpreadCost.roundTripBps`, whose arithmetic is pinned by hand-worked unit tests.
Neither column is authored here.)

The pattern is exactly what the execution path predicts. ADR-0084 routes risk-increasing entries as
passive limits at the arrival mark, so on a tight name the desk often does **not** cross and pays
about half the touch — the ratio sits near 2. On a wide name a passive limit is superseded before it
fills and the desk crosses, so the ratio collapses to 1. The quote is therefore a good estimate
precisely where it matters most and a conservative one elsewhere.

## Decision

**A name with no measured round trip of its own is charged the round trip its own live quote implies,
floored at the desk blend it is charged today.**

```
roundTripBps(n) = measured(n)                                   if n has filled in this feed mode
                = max(deskBlend, 20000·(ask−bid)/(ask+bid))     else, if n has a two-sided quote
                = deskBlend                                     otherwise
```

The estimator is the standard ex-ante transaction-cost proxy — one full quoted spread is one round
trip, because crossing to enter pays the half-spread and crossing to exit pays it again (Grinold &
Kahn, *Active Portfolio Management* 2e ch. 16; Almgren & Chriss 2000). It is written over `(ask+bid)`
so the `/2` in the midpoint cancels and there is one exact division rather than two.

Three deliberate properties:

- **Measured always beats quoted.** A quote states what a round trip *would* cost at the touch; the
  desk's own TCA states what its orders actually paid, which under ADR-0084 is often less. The first
  fill displaces the estimate permanently. This is only ever a bootstrap.
- **Strictly one-way.** The `max(deskBlend, …)` means no name's hurdle can fall below what it is
  today. Since `EdgeGate`'s test is monotone in cost, this change **can only ever remove a trade,
  never add one** — the same safety property ADR-0079, ADR-0083, ADR-0086, ADR-0092 and ADR-0098 are
  built on. On the live cross-section the floor binds only on names already comfortably clearing the
  hurdle, so it changes no outcome there; it is a pure tightening on the wide names.
- **The desk-wide verdict is provably untouched.** That verdict is taken at the cheapest round trip
  in the map-plus-blend (ADR-0075). No entry this adds is below the blend, so the minimum cannot move,
  and neither can `mayIncrease()`, the selected rung, or the source weights that read it.

No new dial and no new number: the value is either the live quote or the existing blend. Rate-quoted
names are excluded on the same rule the execution-cost model already uses (`"SWAP".equals(assetClass)`),
because a basis point of a swap *rate* is additive and not a fraction of notional — mixing the two
into one map would be a unit error, and the ADR-0075 TCA map excludes them for exactly that reason.

## Consequences

**Expected.** The six 20-bps names drop out of the increasable set, and `SAP` (8 bps) with them —
roughly `$120k` of planned gross the desk was ramping toward. Firm gross exposure falls or stops
growing; the recurring per-name bleed that cost `$247` in GOOGL and SAP does not recur in their
six queued successors. Names quoting inside the edge (`NVDA`, `AMZN`, `AAPL`, `MSFT`, `JPM`, `JNJ`,
`GOOG`, the futures and the FX crosses) are unaffected, so the source with the demonstrated edge keeps
its whole tradable cross-section. Positions already held in an excluded name are reduce-only, never
force-closed — the gate has always worked that way.

**Feed-agnostic (invariant 9).** The input is whatever bid/ask the running feed publishes: a venue's
top of book on a live feed, the synthesised touch on the sim. No level, class assumption or configured
spread enters. Worth stating plainly: in the sim the quoted touch and the charged cost agree *by
construction* (ADR-0025 synthesises both from one spec), which is why the validation ratio is ~1.0 on
the wide names. On a live feed the quoted spread is the conventional ex-ante proxy but realised cost
will differ from it — which is exactly why measured TCA takes precedence the moment it exists.

**Risks.** (1) A name whose quote is momentarily wide — a thin patch, a bad tick — is charged that
width for as long as it persists, and stays reduce-only. The cost of that error is a missed entry,
which is the cheap direction; the opposite error is what this ADR is about. (2) A name can now be
locked out before it ever fills, so it never accumulates TCA of its own. That is intended: the desk
does not need to pay 20 bps to learn what the screen is already showing it. (3) The quote is read once
per fusion cycle from the last-value cache, so a stale quote on a halted name carries its last width;
the mark-cache staleness machinery already governs whether that name is planned at all.

**Not addressed here.** Whether the desk should route *more* patiently in a wide name rather than not
at all — a genuine liquidity-seeking execution policy — is a separate decision and a much larger one.
This ADR only stops the desk from paying a cost it can already see.

## Alternatives considered

- **Leave it and let TCA accumulate.** The status quo. It works, at the price of one full discovery
  loss per name — `$247` so far, with six more names queued at the same width.
- **Charge every unfilled name a conservative constant.** A number without provenance, and the wrong
  shape: the cross-section's costs span 150× and no single constant is right for it (`CLAUDE.md`).
- **Use the configured per-class spread from the execution-cost model.** Available, but it is a
  *config* value rather than a measurement, and on a live feed it would be a stale assumption about a
  venue rather than that venue's actual quote — the opposite of feed-agnostic.
- **Replace measured TCA with the quote everywhere.** Rejected: it would discard the desk's own
  realised experience, which is strictly better evidence about its own orders, and it would raise
  hurdles on names where passive entry demonstrably pays less than the touch.
