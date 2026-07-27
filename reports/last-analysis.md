Four sources that fail the desk's own significance test were carrying 59% of the weight against the one that passes — weight is now proportional to the edge a source has MEASURED, not to the confidence that its edge is positive, so the desk's only proven signal stops arriving at the book at 41% strength (ADR-0093).

## Situation (read from the live endpoints; every number below is quoted, none computed here)

**Money.** Total PnL is **higher**, three runs running. The scorer put the last change at
`-$54.81 → $14.33 (+$69.14)`; live now `$11.63`, and `+$326.15` across the last three runs. The book is
not bleeding.

**Risk.** Gross exposure is **falling** — `-$7,107` since the last run and `-$8,405` over three — to
`$22,261` against net `$4,111`. VaR95 `$187.23` / ES95 `$270.22` on `$23,317` covered; the drawdown
breaker is not tripped and is nowhere near. No danger state, so no de-risk override: this cycle was free
to go at a mechanism.

**Cause.** Last cycle's change (`e49586f92`, the measured forecast scalar, ADR-0092) scored **✅ GOOD**.
Attributing it honestly: the exposure half is the change's own doing — it rescaled over-large forecasts
down, so the planned book shrank and the held book with it — while the PnL half is `$69` against a `$50`
deadband, barely outside noise, and I credit it only weakly.

**The open question from last cycle is closed.** The previous finding named the ADR-0089 stream
covariance as the next suspect because the boot log showed it cold. It is not cold: the live target book
reports `covarianceBasis = mark-stream`, `covarianceCoveredNames = 23`, `streamVolMeasuredNames = 23`,
`portfolioRiskMultiplier = 0.358`. The WARNs are a one-shot seed-time message; both sensors warm within a
cycle of boot. That lever is not available, so I went looking elsewhere.

**Order-level post-mortem** found nothing new to fix. Per-name PnL still sorts by execution cost exactly
as the memory records — the winners (`AAPL`, `MSFT`, `JPM`, `JNJ`) are the `0.5–1.4 bps` round trips and
the losers (`GOOGL` `20.1 bps`, `SAP` `8.2 bps`, `GOOG` `3.1 bps`) the expensive ones — but the
ADR-0072/0075 per-name cost gate has already flattened `GOOGL` and `SAP` and holds them reduce-only. That
trigger is fixed; re-fixing it would be chasing a symptom that no longer fires.

## Diagnosis

The binding constraint is that **the desk's one proven edge reaches the book at under half strength.**
Source weights come from `Φ(t)` — the evidence that a source's true expectancy is *positive*. That is a
confidence, not an edge, and it fails in two ways at once.

`Φ` puts the null at exactly ½, and the normaliser turns ½ into a full unit of weight. So
`mean-reversion`, measuring `+0.28 bps` against a `±8.0 bps` standard error over six calls — literally no
information — carries `1.055`, **49% of the weight** of `reversion`, which measures `t = +9.0` over 483
resolved calls and 24 emission cohorts. `social` (`t = +0.73`, `p = 0.24`) carries `1.525`. And `Φ`
saturates: `Φ(2.0) = 0.977` against `Φ(9.4) = 1.000`, so an overwhelming edge buys at most 2.3% more
weight than one barely clearing a 2-sigma hurdle — all discrimination is squeezed out of the top of the
scale, exactly where it pays. Between them, the four sources that **fail the desk's own significance
test** (`social` `+0.73`, `mean-reversion` `−0.02`, `momentum` `−3.17`, `trend` `−5.62`) carry `3.08` of
the `5.25` weight mass; since the combiner normalises by `Σw`, `reversion` is diluted to 41%.

## Change

Weight is now the **shrunk measured edge**: `w ∝ max(0, c·µ̂ + (1−c)·pool₋ᵢ)` over each source's own
`avgReturnBps`, with the ADR-0074 credibility term and `K`, `MIN`, `MAX` all untouched — a link-function
swap and nothing else. Proportionality to expected return is the textbook combination rule (Grinold &
Kahn ch. 11) and has the two limits `Φ` lacks: no measured edge ⇒ no weight, twice the edge ⇒ twice the
weight. The prior is **leave-one-out**, because on an unbounded edge scale a self-inclusive pool lets a
thin source with an extreme reading be shrunk toward its own luck — pinned as a test: `+80 bps` on three
calls now lands at `0.938`, below neutral.

It stays floored at zero, so a measured-bad source is down-weighted to `MIN`, never inverted into a
contrarian bet — the ADR-0087 removal of that floor stays reverted and untouched, and I did not
re-attempt it. It cannot scale the book: only ratios survive normalisation, and a more concentrated
vector earns a *smaller* ADR-0076 diversification multiplier. Live effect is a rotation of `reversion`
from 41% to 70% of the weight mass, `social` `1.525 → 0.547`. Proposed ADR-0093 in the same commit;
807 tests green.
