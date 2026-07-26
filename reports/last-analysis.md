Cut the stranded hedge: an absolute $10k min-trade guard had frozen an ES leg at five times its own target, holding two thirds of firm gross exposure with no mechanism that could ever remove it.

## Situation (live endpoints, read first)

**Money — bleeding, but the bleed has essentially stopped.** Total PnL is `-$866.49`, down `$3.26`
since last run and down `$843.66` across the last three. Nowhere near the ≥1%-per-3-iterations target;
the flags read BLEEDING and UNDERWATER. The shape matters more than the level: realised is `-$869.25`
against unrealised of `+$2.77`, and today's fills carry `$130.90` of fees on 185 fills. That is not an
adverse market — it is the transaction cost of one round trip. Almost the entire `-$843` came from the
`fb9273505` change (already scored ❌ BAD and auto-reverted) opening a `$43k` book and then working it
back to nothing: the order log shows the unwind decaying by halves every 30 s — 78, 39, 20, 10, 5, 2, 1
— each clip crossing a spread. The money is spent and sunk; the current run rate is small.

**Risk — flat, and stuck.** Gross `$6,885.14`, net `$4,897.35`, both barely moving (`-$5.76` gross this
window). The firm drawdown breaker is not tripped and is not close; historical VaR95 is `$78.58` against
that gross. But the composition is wrong: `$5,791.23` of the `$6,885.14` gross — 84% — is a single ES
future in the HEDGE book, against an ALPHA book that is `-$893.88` net short and consists of five
single-share stubs. The hedge is more than six times the exposure it exists to neutralise, and it is
what makes firm *net* `+$4.9k` long.

**Cause — last cycle's change is not the culprit; the market isn't either.** `c12099fea` (expectancy-
weighted source trust) scored ⚠️ MIXED, "no material change", which is what I predicted: the edge gate is
reduce-only, so re-weighting rotates conviction without resizing the book. Attributing this window
honestly: the change opened, closed and resized **nothing** — the only order after it went in was a
single JNJ buy completing a stub unwind — so all of the `-$3.26`/`-$5.76` is mark drift on positions it
never touched. Market, not change, and too small to read either way. The real culprit is older and still
live, and it is the thing the report did not foreground.

**Danger — no, but there is a stranded risk position.** We are not bleeding-and-adding; exposure is flat
and the breaker is far away. So this cycle is free to fix a cause. The cause is that the ES hedge is
frozen: `/api/hedging` says *"held 0.021254 → target 0.004011 ES — largest delta under the $10,000.00 min
trade, holding"*. The advisor has correctly re-sized itself down and correctly computed the delta, and
then refused to trade it, because ADR-0039's churn guard is an absolute dollar amount compared against a
delta with no reference to the size of the hedge it guards. Once the hedged book shrank below `$10k`,
every delta a small hedge can produce — up to and including its own full unwind — fell under the guard,
so the position became untradable in both directions. That silently repeals ADR-0039's own promise that
"no underlying, no hedge"; the naked proxy leg lingers permanently.

## What I changed

The no-trade band is now the **smaller** of the absolute `$10k` guard and 25% of the hedge's own scale
(`max(|target|,|held|)` notional) — trade when the delta is material in absolute terms *or* material
relative to the hedge itself (ADR-0069, `HedgeAdvisor.noTradeBand`). Taking the minimum is the point: on
a large book `0.25 × scale` exceeds `$10k`, so ADR-0039 behaviour is bit-for-bit unchanged (the existing
`$8k`-trim-on-a-`$1.8M`-hedge regression test still holds its trade); on a small book the relative leg
governs and the hedge can be trimmed. Because the fraction is clamped at 1, a zero target gives
`|delta| = scale ≥ band`, so a full unwind is now always executable at any book size — the promise
becomes structural rather than true-only-above-`$10k`. The `0.25` is the 25% leg of the standard 5/25
band-rebalancing convention, flagged `PLACEHOLDER — Oleg to set`; sizing the band from measured ES/NQ
round-trip cost is the economically correct form and is in the deferred register, since the exchange
rate between residual exposure and cost is a risk-appetite decision, not a number I may invent. Three
new tests cover the stranded-trim case, the always-unwind property, and that a 1% drift on a small hedge
still trades nothing. Deliberately not touched: the strategy book's own ±1-share stubs are a different
mechanism (whole-unit truncation) already in the register, and the deterministic floor is untouched.

Honest expectation: this removes roughly `$4.7k` of exposure the hedging system itself says it does not
want, so gross and net should fall sharply while PnL moves only by the one small commission. Against the
ledger's ratio annotation that will *look* worse — PnL per $1 gross gets more negative when PnL is
negative and gross shrinks — but the vector is the right one: same money, far less money at risk, and a
whole class of stranded position closed. I also found `docs/adr/0068` and a set of firm drawdown/max-loss
property edits left uncommitted in the tree by an interrupted cycle; I reverted the property edits
unshipped and kept the ADR as a not-built record, because retuning the breaker is exactly the floor edit
that ADR forbids the loop from making — and an unscored second dial change would confound this cycle's
attribution.

Next lever if this lands: the standing problem is unchanged — no fusion source has positive measured
expectancy, so the reduce-only gate has nothing legitimate to open on. A source built for the CHOP regime
the detector actually reports, measured honestly before it sizes anything, is the next thing to build.
