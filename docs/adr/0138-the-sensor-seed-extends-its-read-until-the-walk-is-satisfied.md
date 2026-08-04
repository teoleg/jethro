# ADR-0138: The sensor seed extends its read until the walk is satisfied, and names why it stopped

- **Status:** Implemented
- **Date:** 2026-08-04
- **Deciders:** Oleg
- **Tags:** backend, trading-core, signals, warm-restart

## Context

ADR-0071 boots the continuous sensors from durable mark history so a redeploy does not restart their
warm-up from zero; ADR-0114 fixed the units, denominating the read window and the hole test in the step
the sensor actually consumes a name at rather than in our poll cadence. Both leave one thing estimated
**before** the walk that can only be known **during** it: how far back to read. The window is
`LOOKBACK_MULTIPLE × samples × step`, with `step` the median inter-print gap.

The median is the wrong statistic for that particular job, and the class's own documentation says why:
print gaps are heavy-tailed. The median describes the typical gap; the window has to cover the **sum** of
`samples` gaps, which the tail dominates. So the estimate is systematically short, by an amount that
varies with whatever the tail did in that particular window — and the existing single widening cannot
rescue it, because when the median already equals the poll cadence there is nothing for it to widen to.
The walk then runs off the oldest point it *read*, while the stored series continues below it (retention
is 12h against seeds spanning tens of minutes), and returns a short seed as though the history had ended.

The consequence is not academic and it is not confined to the sensor. A name whose sensor stays cold
contributes no forecast; the combined view falls to one effective source; the live rule reads an
unestimable view as flat and plans the name flat — worked in full, not buffered. The loop has now
reproduced this on five consecutive boots and priced it from the app's own `fills` ⋈ `orders` join: fills
carrying an `origin_reason` with `sources ≤ 1` account for **10.04%** of the firm's entire LIVE fee bill,
around **69%** of them landing in the ten minutes after a restart. The register's tightest instance is a
JPM round trip opened on a three-source view and closed 27 seconds later on a one-source view, with
nothing between the two but the sensor losing the ability to see.

A second, separate cost: when a seed does come up short today, the log says only "seeded n of needed".
That cannot distinguish a window that cut the walk off from a series that genuinely ended, and those two
call for opposite responses — so every past cycle had to re-derive the answer with a replication script.

## Decision

We will make the seed's read window a **consequence of the walk rather than a guess ahead of it**: when
the backward walk exhausts the points it read while still short of `samples`, the read is **extended**
(the window doubles) and the walk repeats, until the seed fills, a genuine hole truncates it, or the
stored series demonstrably ends. The **shallowest** window that fills the seed is the one used, so the
replayed horizon stays as close as the store permits to what the live sensor would have consumed.

"The series ends here" is inferred from the read itself, with no new store API and no extra scan: if the
walk stops at an oldest point more than one gap tolerance newer than the `since` the store was asked for,
then nothing was held in `[since, oldest)`, so the next older point — if any exists — is further away than
the tolerance permits and a deeper read could only have broken there anyway.

Two supporting rules. The consumption step is **monotone non-decreasing** across extensions: a deeper read
is stride-downsampled more coarsely by the store, and adopting a *narrower* step would turn the finer
series' normal print gaps into fabricated outages. And every seed now reports its **terminator**
(`FULL` / `GAP_BREAK` / `HISTORY_EXHAUSTED` / `NO_HISTORY`), the wall-clock span it covered, the step it
walked at and how many reads it took — logged by all four sensors whenever a seed comes up short.

Nothing here is fitted, and nothing is per-name: the loop has measured the same name seeding full on one
boot and short on the next (BAC 193 → 192; NEE 193 → 181 → 171), so seed depth is a property of the boot,
not of the instrument, and only re-reading against the series itself can answer it. This class produces no
money, risk or exposure number — it replays prices into a sensor that publishes a conviction, with the
fusion layer, the edge gate, the conviction floor and the pre-trade floor all still between it and a fill.

## Alternatives considered

**Raise `LOOKBACK_MULTIPLE` from 2 to some larger constant.** The cheapest edit and the wrong one: it
replaces a too-small guess with a bigger guess, still fixed, still blind to the tail that defeated the
first one. It also over-reads on every name that did not need it, and because the store stride-downsamples
a wider read, over-reading actively degrades the seed's resolution. Rejected — a shortfall that varies by
boot cannot be closed by a constant chosen once.

**Size the window on a high quantile of the gap distribution instead of the median.** Better statistics on
the same guess. It still fails whenever the realized tail exceeds the quantile, and picking the quantile
is exactly the fitted number this repo forbids without provenance. Rejected in favour of asking the series
directly, which needs no quantile at all.

**Let the routing rule hold the position when the view becomes unestimable.** Already tried and already
graded ❌ BAD (ADR-0135), and rightly: it treats "the desk cannot see" as "the desk is confident", leaving
risk on for a reason unrelated to conviction. Rejected — the defect is a blind sensor, and the fix belongs
where the blindness is.

**Persist the sensors' internal state across the restart rather than replaying prices.** Strictly more
faithful, and the honest long-term answer. It is also a much larger change — every sensor's state becomes
a serialized format to version and evolve, against ADR-0014's rule that durable local state is derived
data only. Deferred, not rejected: revive it if seeds keep terminating on `GAP_BREAK` for structural
reasons (the cash close) that no amount of reading can repair.

## Consequences

- **Positive:** a sensor cold-starts only when the stored history genuinely cannot warm it, not because a
  window was estimated short — which removes the mechanism behind the post-restart liquidations the
  register has priced at 10.04% of the fee bill.
- **Positive:** a short seed is now self-diagnosing. The next cycle grades this from the app's own log —
  terminator, span, step, read count — instead of re-deriving it with a replication script.
- **Negative:** first sight of a name can now cost several LMDB range scans instead of one or two. It is
  bounded (the window doubles, capped at 12 attempts), it happens once per name per process, and the store
  is retention-bounded — but boot does more I/O than it did.
- **Negative:** a deeper read is stride-downsampled more coarsely, so a seed that needed extending replays
  a slightly coarser series than the live sensor would have consumed. Taking the shallowest window that
  fills the seed keeps this to the minimum the store permits, but it is a real distortion of the seeded
  horizon, and it is the price of a warm sensor over a blind one.
- **Follow-ups:** the register's item #2 (restart wipes the desk's *intent*, and the buffer then forbids
  re-entry) sits behind this one and becomes actionable once boots stop planning names flat. If seeds keep
  terminating on `GAP_BREAK` across the equity close, that is the trigger for the deferred state-persistence
  alternative above.
