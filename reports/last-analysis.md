# ADR-0145 shipped with an exemption that admitted the worst case it was written to stop — a decayed forecast liquidating a whole position at market, wearing a chandelier cut's clothes.

## Situation (live, read from this run's report — never authored)

1. **Money.** Total PnL **-$1,207.61**, **+$6.93** since last run, **-$5.07** across the last three.
   Essentially flat and UNDERWATER. Realized **-$1,216.05** against unrealized **+$8.44** — the loss is
   almost entirely *round trips*, not held positions.
2. **Risk.** Gross **$45,018.69** = **3.0%** of the $1,500,000 firm cap, **$1,454,981** of headroom; net
   **-$4,582.44** = **0.5%** of the $1,000,000 net cap. Gross **+$8,134.46** this window. Not danger —
   this is the DORMANT-side failure (an unused budget), and gross rising is the ADR-0132 intent.
3. **Cause.** `19d924e69` (ADR-0148) was scored **❌ BAD** this cycle and reverted by the scorer
   (`6937cea`). That revert landed at 18:00:08Z, **seven seconds after this report's stamp** and well
   after the running JVM started (uptime **3577** at a stamp of **18:00:01Z** ⇒ start **17:00:24Z**), so
   the binary that produced this window is still ADR-0148. Nothing in this window is creditable to the
   revert; the revert is graded next cycle.
4. **Danger.** No. Bleeding slowly at 3.0% of the gross cap with the breaker cold.
5. **Change vs market.** No logic deployed during this window. The move is market and pre-existing
   logic — none of it is creditable or blameable on a change.

## Step 0 — ADR-0145 (kept ⚠️ INCONCLUSIVE): ⚠️ **STILL-BROKEN**, and I found why

ADR-0145 made the conviction floor symmetric so a decayed forecast could not unwind a position it was
never strong enough to open. `recent_orders` says it did not: `PFE BUY 240 — fusion exit — target
decayed to flat [forecast=-0.0, sources=1]` at 17:49:19. A source is still speaking, no control fired,
the forecast is a signed zero, and 240 shares go back at market. WMT, NVDA and BAC carried the same
reason the prior window.

The mechanism is `ConvictionHold`'s own exemption. It returns the delta untouched when the *controlled*
target is a literal zero, on the reasoning that every control meaning "get out" plans the name FLAT
(ADR-0086/0065/0027). Sound about controls, wrong about its converse: `TargetPlanner.targetQuantity` is
**linear** in the combined forecast and returns `BigDecimal.ZERO` the instant that forecast reaches
zero — so a view that merely finished decaying arrives at the exemption indistinguishable from a
chandelier cut. ADR-0107's javadoc states the principle ("an EXIT is what a control ORDERED, not what
the arithmetic happens to read") and then reads `target == 0` as the control.

It is not a rounding detail, because two paths key on the same zero: `nextAim` **snaps** the aim to flat
rather than stepping it at the ADR-0080 rate, and `bufferedDelta` works a flat target **in full,
unbuffered and unrated**. Both the rate limit and the ADR-0094 band are bypassed, so the cycle with the
*least* conviction available produces the *largest* order the desk can place — the whole position, at
market — where that position was accumulated one rated step at a time. Buy slowly, sell instantly,
repeat. That is a ratchet, and it explains a realized-only loss on a book whose measured hit rates sit
at **0.523 / 0.483 / 0.486** (trend / xsreversion / reversion at 225s, n = 5,170 / 5,082 / 4,714) with
`totalFees` **$335.22** against `firmTotal` **-$1,207.61**.

## The change — ADR-0149

Attribute a flat target to its **author**, from facts already at the call site, never from magnitude. A
flat controlled target routes in full when a risk control planned the name flat *this cycle* (the
ADR-0086 cut set, already built for the ADR-0134 reason string, now computed before the buffer and
passed in — plus a name the planner could not value), or when `sources == 0` (the ADR-0065 orphan), or
when the planner's own target was non-zero before the controls ran. Everything else — live sources, no
control, planner target itself flat — was authored by the forecast, and the existing ADR-0145
arithmetic already yields zero at `p = 0`, so the position is held whole and the aim is re-seeded to
where the desk actually is.

No number is introduced: the threshold is still `jethro.fusion.min-forecast-to-route`. Unwired is
byte-identical. The deterministic floor is untouched, and four independent exits remain — conviction
returning either way, the ADR-0086 chandelier stop, the ADR-0118 trapped-exit path, and the ADR-0065
unwind. Deliberately **not** ADR-0133 (a wider band, scored ❌ BAD) and deliberately not a change to
`nextAim`/`bufferedDelta`, whose flat-target behaviour is correct *for a control-ordered exit*.

**How it gets graded next run**, from live telemetry only: in `recent_orders`, orders whose reason is
`fusion exit — target decayed to flat` with `sources ≥ 1` must be **zero**; the same reason at
`sources = 0` may still appear and is correct. Secondary: `totalFees` as a share of `firmTotal` falls,
`grossExposure` does not. Falsified if gross rises while `firmTotal` deteriorates on the retained
names — i.e. the decayed positions were worth exiting, which is the ADR-0086 trailing cut's job and it
is exempt here. `./gradlew -Pci test` green.
