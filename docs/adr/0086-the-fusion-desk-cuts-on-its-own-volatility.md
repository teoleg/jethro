# ADR-0086: The fusion desk cuts a position on its own measured volatility

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, risk, fusion, strategy

## Context

The fusion layer is a pure target-portfolio loop. A position exists exactly as long as the combined
forecast asks for it, and it is closed only when that forecast decays. Every control on the path asks
a question about *wanting* risk — the ADR-0064 edge gate asks whether risk may be put ON, the ADR-0059
conviction floor whether a view is strong enough to act on, ADR-0079/0083 how to split the budget,
ADR-0080 how fast to get there. **Nothing anywhere in that path asks whether a position the desk
already holds is losing.** The only control that reacts to a position going wrong is the firm drawdown
breaker, which is a whole-book backstop by design. The legacy strategy path has had a per-position stop
since ADR-0019 (`jethro.strategy.stop-loss-pct`); the fusion path — the sole order origin, and the one
carrying the desk's only statistically significant edge — has had none.

That gap is most expensive for the source the evidence currently favours. `reversion` is the only
source clearing the gate (measured +8.09 bps over 900s, 184 resolved calls, 8 cohorts, p = 0.0025), and
a mean-reversion view is short optionality: right often for a little, wrong rarely for a lot. Its
unmanaged payoff is a long run of small gains ended by one large loss. Cutting that tail is not a
refinement of such a book — it is what makes its expectancy survivable, and it is the owner's thesis in
one line: let winners run, cut losers fast.

Keying such a control on the desk's existing σ is not possible. Every volatility the desk prices risk
with — the ADR-0083 budget split, the ADR-0079 correlation control, the parametric VaR, the ADR-0038
hedge advisor — comes from the `daily_close` series, which an instrument only enters after enough
admissible consecutive sessions in the running feed mode (ADR-0073). On the live book that covers 3 of
the 23 planned names and **none of the names the desk actually holds**: the parametric VaR currently
reports its entire covered exposure as skipped. A risk sensor that is silent exactly where risk is held
is not a risk sensor. The mark stream has no such gap — it is the same series both forecast sensors
already consume, warm for every name in the universe.

## Decision

We will cut a fusion position to FLAT once its mark has retraced from the best level seen since that
position was opened by more than `k ×` the name's own measured σ over the desk's own holding horizon,
and hold the name reduce-only for one holding horizon before it may be re-entered.

- **σ from the mark stream** (`StreamVolatility`): EWMA of squared log returns sampled at the fusion
  cadence, `σ_h = σ_Δ · √(h/Δ)`. It runs as a running mean until it has absorbed a full span and
  reports nothing before that (ADR-0066's lesson), and it is seeded on first sight from the durable
  mark store (ADR-0071) because its warm-up exceeds the process lifetime.
- **From the peak, not from entry** (`TrailingRiskCut`): the running maximum for a long, minimum for a
  short — the chandelier exit (LeBeau; Kaufman, *Trading Systems and Methods*, ch. 23). An entry stop
  never protects a profit; trailing from the peak is what implements "let winners run", and at entry
  the peak *is* the entry, so it is strictly tighter than an entry stop, never looser.
- **The horizon is derived, not dialled** — the ADR-0082-selected rung at ADR-0080's identity. The
  position was opened to earn one horizon's return, so that is the period over which an adverse move is
  evidence the view is wrong, and the period to stand aside for before paying to express it again.
- **One dial:** `jethro.fusion.risk-cut.sigma-multiple = 3.0` — **PLACEHOLDER, Oleg to set.** Not
  derived from this desk's measurements; it is the conservative end of the conventional chandelier
  distance (2.5–3 × ATR), read against a horizon σ rather than an ATR. Conservative for a *cut* rule
  means reluctant to cut: a stop inside the ordinary noise of the holding period turns routine winners
  into realised losses and destroys the expectancy it protects.

Worked example, pinned as a test. σ_Δ = |ln(1.01)| = 0.00995033…, h = Δ so σ_h = σ_Δ, k = 3 ⇒ trigger
2.98510%. A 0.3 ES short whose trough is 5440: at 5600 the retrace is (5600−5440)/5440 = 2.94118% →
hold; at 5605 it is 3.03309% → cut, target 0, delta +0.300000 traded in full this cycle.

It runs **last**, after the edge gate, so a cut is the desk's final word on a name. It can only ever set
a target flat and clamp the delta to a reduction — it never raises a target, never flips a sign, and a
name with no measured σ is left exactly as planned (no measurement, no claim: ADR-0016 / invariant 7).
The deterministic floor is untouched.

## Alternatives considered

**A fixed percentage stop, as the legacy path uses.** `stop-loss-pct=0.008` means something completely
different for a 2-year note future and for TSLA, so a single number is simultaneously a hair trigger on
the quiet names and no protection at all on the loud ones. It is also a hardcoded price-move constant,
which is exactly what the feed-agnostic rule forbids. Rejected.

**Key the stop on the existing daily-close σ.** Consistent with the sizing path and needs no new
estimator — but it covers 3 of 23 planned names and none of the held ones, so the control would be
dead precisely where risk sits. Deferred, not rejected: if the daily estimate's coverage ever reaches
the traded set, the two σ should be reconciled rather than left to disagree.

**Do nothing and let the firm breaker handle it.** The breaker is a firm-wide halt, so by the time it
speaks the loss is already whole-book sized, and its response is to stop the desk rather than to exit
one bad name. A per-name cut and a firm halt are different instruments; having the second is not a
reason to lack the first. Rejected.

**Tighten the entry side instead (a higher conviction floor or hurdle).** Refusing more entries lowers
the *frequency* of the tail, not its size, and the last five cycles of gate work have already shown the
entry side is over-constrained rather than under-constrained. Rejected as an answer to this problem.

## Consequences

- **Positive:** the desk gains the one risk response its thesis is built on and has never had, on the
  book that carries its edge. The control is exposure-reducing by construction, so it can improve
  PnL-per-unit-exposure but can never lever the book up. It also gives the desk a per-name volatility
  measurement with full universe coverage, reusable by anything that today has to say "not covered".
- **Negative:** a stop converts an unrealised loss into a realised one, and on a mean-reversion book it
  will sometimes cut exactly the position that was about to work — that is the premium paid for
  removing the tail, and at `k = 3` it is paid rarely rather than never. It adds turnover (one crossing
  exit plus a possible later re-entry) at a desk whose measured round-trip cost is already the binding
  constraint on what it may trade. The √time scaling of σ assumes i.i.d. increments, which overstates σ
  for a mean-reverting stream and therefore makes the trigger *wider* than a true horizon σ — a
  conservative error for a cut rule, but an error.
- **Follow-ups:** the re-arm is purely time-based; a rule that re-arms on the forecast changing sign is
  the natural refinement once cuts have been observed. `sigma-multiple` needs Oleg's number or an
  out-of-sample calibration. If cut frequency turns out to dominate turnover cost, the exit could post
  rather than cross (ADR-0084's asymmetry deliberately does not apply to a cut).
