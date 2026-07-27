ADR-0113 changed the unit the sensors count in — prints, not cycles — and the warm-start seed was left counting in the old one, so no name whose tape prints slower than we poll can ever warm; ADR-0114 re-denominates the seed into each name's own print cadence.

*Every figure below is quoted from the live endpoints, `logs/report.md` or the ledger; none is authored
here (invariant 7 / ADR-0016 — the scorer owns every number that gates money).*

## Situation, in plain numbers

**Money.** Total PnL **$5,665.94**, up **$11.81** on the run and **$11.72** across the last three. Not
bleeding — but the growth flag reads **stale**: −1.27% over three iterations against the +1% target, and
it reads stale for the obvious reason that the desk is inert.

**Risk.** Gross **$0.00**, net **$0.00**. Every position row is flat, unrealized is zero, the drawdown
breaker is nowhere in sight. Exposure is not rising. The problem this cycle is the opposite of danger.

**Cause.** Last cycle's ADR-0113 scored **⚠️ MIXED** and did reach the JVM (boot 22:26Z against a 22:25Z
commit; `PrintClock` gating both sensors). It behaved exactly as predicted — it narrows the observed
universe and lowers exposure by design. No culprit to name.

**Danger.** No. Not bleeding, zero exposure. The live danger state does not apply.

**Order-level post-mortem.** The last fill of any kind was 19:54Z, over three hours ago. Every ALPHA fill
in the window was AAPL or JPM, each shadowed within 10–40 s by a HEDGE ES clip of 0.003–0.069 contracts.
No trigger to blame for a loss, because no trigger fired at all after the cash close.

**Attribution, honestly.** The $11.81 is on a book holding nothing — neither market nor change. ADR-0113
earns credit and blame for nothing this window.

## Diagnosis

`/api/fusion/targets` publishes `instruments: 0`. `reversion` carries fusion weight 2.90 and is the only
source with measured edge (t = 3.55); it publishes nothing, so the cross-section is empty. The WARN log
names the mechanism on every name: *"reversion sensor still cold for ES after seeding 1 of 241 stored
prices"* — ES, GBPUSD, NQ, and every equity.

Reading `/api/history` per name gives the reason. The median inter-print gap is **19.9 s** on the Treasury
curve, **90 s** on NQ, **778 s** on GBPUSD and **1,199 s** on ES, against a 10 s reversion cadence. The
ADR-0071 seed derives two quantities from the *poll* cadence — how far back to read history
(`interval × samples × 2` = 80 min) and how large a break stops the walk (`interval × 30` = 300 s). Both
assume the tape prints at least as often as we poll. That is exactly the assumption ADR-0113 removed from
the live path, and it was left standing in the seed. So for ES every **ordinary** 20-minute print interval
exceeds the 300 s tolerance and reads as an outage: the walk breaks at the first one, seed = 1 of 241. The
Treasury curve fails the other way — its gaps clear the tolerance, but 241 prints at ~20 s need 80 minutes
of history against a lookback asking for exactly 80, hence the observed 147 of 241.

Because the live path now accumulates at that same print rate, none of these names can ever warm — the
ADR-0071 failure ("a sensor whose warm-up exceeds the process lifetime never speaks at all") re-entered
through ADR-0113's own door.

## What I changed

ADR-0114 (Proposed, same commit): the seed's lookback and hole tolerance are counted in the step at which
the **live sensor actually consumes that name** — `max(poll interval, that name's median inter-print
gap)`, measured from its own stored series. The `×2` and `×30` constants are unchanged; only their unit
moves, so no dial is added and nothing is configured. This is deliberately **not** the alternative
ADR-0113 rejected ("widen the tolerance so the seed bridges the halt"): the tolerance is a multiple of the
name's *own* typical interval, so a 12 s equity tape that stops for three hours at the cash close is still
a hole and still truncates — pinned as a test, alongside one proving a fast tape is sampled bit-for-bit as
before.

Honest cost: this **raises** exposure by admitting names the desk currently cannot see, so if the
reversion edge does not survive contact with them the loss is larger, not smaller. Expect NQ and the rates
curve to reach a full seed and ES/GBPUSD/AUDUSD to seed from as much series as the 12 h retention holds.
