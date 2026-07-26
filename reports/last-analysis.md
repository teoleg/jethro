Found why the desk has been stuck flat: both continuous sensors need more minutes of warm-up than the
process lives between redeploys, so they are permanently cold-starting — now they boot warm from the
durable mark history (ADR-0071).

## Situation (live endpoints, read first)

**Money — flat, not bleeding.** Total PnL is `-$867.78`, down `$0.38` since last run and down `$4.55`
across the last three — both inside the scorer's noise deadband. That $4.55 is the entire three-run
move; the big `-$843` from earlier cycles was the `fb9273505` round trip, long since scored ❌ BAD,
auto-reverted, and sunk. The flags read BLEEDING and UNDERWATER and we are nowhere near the
≥1%-per-3-iterations target — but the reason is not an active loss. It is that **the desk does not
trade**. You cannot grow PnL by 1% from a book that is forbidden to open a position.

**Risk — minimal and stable.** Gross `$785.55`, net `$56.36`, down `$0.41` on the window. Historical
VaR95 is `$6.87` against that gross; the firm drawdown breaker is not tripped and is nowhere close.
The residual book is two single-share stubs (AAPL, GOOG) plus an ON-TARGET hedge leg of 0.0015 ES.
The `$5.4k` of gross that vanished two cycles ago was ADR-0069's stranded-hedge unwind, correctly.

**Cause — last cycle's change did nothing measurable, exactly as predicted.** ADR-0070's `reversion`
sensor scored ⚠️ MIXED, "no material change (within noise band)". Attributing this window honestly:
the change opened, closed and resized **nothing** — there are no orders in the window at all — so
100% of the `-$0.38` PnL and the `-$0.41` gross is mark drift on positions it never touched. That is
**market, not change**, and far too small to read either way.

**Danger — no.** Not bleeding-and-adding; exposure is an order of magnitude below where it was and
the breaker is far away. So this cycle is free to fix a cause rather than cut risk.

## The cause — and it is a deployment defect, not a signal defect

The reversion sensor is not merely quiet. It is **structurally unable to speak**, and the arithmetic
is mechanical. Its warm-up is `rangeSpan + 1 + normSpan/2` = 241 prices at a 10s cadence ≈ **40
minutes** of continuous uptime before it publishes anything. The improvement loop redeploys the app
on every accepted change; the observed restart interval over the last five cycles is **14–55
minutes**, mostly under 25. Live JVM uptime at report time: **172 seconds**. All sensor state is heap
and dies with the process. So the source I added last cycle to give the desk a view in the CHOP
regime is, in deployment, dead code — it publishes nothing, appears in no fusion weight, and above
all accumulates **no telemetry about itself**, so the ADR-0064 edge gate can never judge it. That is
why it read as "no material change": there was no change to read.

The same defect quietly damages the trend sensor, which is worse. `trend` warms at 193 prices × 5s ≈
**16 minutes** — comparable to the process lifetime — so it speaks only in the last handful of
minutes of each process life, always from a scale estimate built on the minimum possible sample.
Every one of its 46 resolved observations was produced by a barely-warmed instrument, and it is those
observations, at a t-statistic of −6.9, that hold the gate reduce-only. The number keeping the desk
flat is measured on a permanently cold-starting sensor.

Meanwhile the exact price series the sensors consume live is **already durable and already survives a
restart**: the LMDB mark history behind the chart (ADR-0014/0017), 12h retention, ~1 Hz. I checked it
on the live box — 1787 points over the last 92 minutes for AAPL, largest gap 60s, i.e. continuous
across redeploys. The data needed to boot a calibrated sensor has been sitting on disk unused.

## What I changed

`SensorWarmup` (ADR-0071): on first sight of an instrument, each continuous sensor replays that
instrument's own stored recent prices through its ordinary update path before it consumes a live
mark. The seed is thinned to **one price per evaluation interval** (replaying every 1 Hz mark would
define the windows over a different horizon than live operation does), the sample count is read off
the sensor itself via `warmupSamples()` so it cannot drift from the sensor's real arithmetic, the
walk runs newest-first so the seed ends at the present, and it **stops at a hole** wider than 30
intervals — a redeploy blip is bridged, a real outage truncates the seed rather than fabricating a
price jump across it. Seed prices are deliberately **not** recorded in the signal telemetry: a
historical price is not a call the desk made, and counting it would fabricate track record for a
source the gate is about to judge. Because replaying the store into a sensor makes invariant 8
load-bearing where it previously was not, the mark-history store is now namespaced by feed mode.

Nothing about sizing, the edge gate, the conviction floor, the guardrail or the firm breaker moves.
This changes *when a sensor is calibrated*, not what it says or what the desk does with it — and
while the gate is reduce-only it cannot increase exposure at all. Six new tests cover the cadence
thinning, the newest-first cap, the gap stop, the no-history cold start, non-positive prices, and the
property that matters: a seeded sensor speaks on its first live mark where a cold one stays silent.

Honest expectation: the immediate PnL/exposure move should be nil, and against the ledger this may
well score as another "no material change". What it buys is that the reversion hypothesis finally
gets *measured* — the sensor now reaches the gate's 30-sample minimum in the ordinary course of a
cycle instead of never — and that trend's expectancy stops being an artifact of permanent cold
starts. That is the prerequisite for any legitimate path off a flat book; the next lever depends on
what those measurements say.
