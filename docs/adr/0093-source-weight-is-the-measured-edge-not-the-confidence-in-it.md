# ADR-0093: Weight a fusion source by its measured edge, not by the confidence that the edge is positive

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, trading, signals, fusion

## Context

ADR-0067 replaced hit-rate source trust with expectancy, and ADR-0074 made the credibility term count
the sample the expectancy was actually measured over. Both were right. The statistic sitting between
them is not: the weight is `Φ(t_s)` — the evidence that a source's **true expectancy is positive**.
That is a *confidence*, and combining forecasts in proportion to confidence has two defects, both
binding on the live desk right now.

**Φ puts the null at ½, and the normaliser turns ½ into a full unit of weight.** `mean-reversion`
measures `+0.28 bps` against a `±8.0 bps` standard error — six resolved calls, `t = −0.02`, literally
no information — and carries weight `1.055`, i.e. **49% of the weight** of `reversion`, which measures
`t = +9.0` over 483 resolved calls and 24 emission cohorts. `social` (`t = +0.73`, `p = 0.24`) carries
`1.525`. A source that has shown nothing must be worth nothing, not half.

**Confidence saturates; edge does not.** `Φ(2.0) = 0.977` against `Φ(9.4) = 1.000`, so a source with an
overwhelming measured edge is allowed at most 2.3% more weight than one that barely clears a 2-sigma
hurdle. All discrimination *among the good sources* is squeezed out at the top of the scale — exactly
where it pays.

The two compound. Of the desk's five sources, the four that **fail its own significance test**
(`social` `+0.73`, `mean-reversion` `−0.02`, `momentum` `−3.17`, `trend` `−5.62`) carry `3.08` of the
`5.25` total weight — **59%** — against `41%` for the one source that passes. The combiner normalises
by `Σw`, so the desk's only proven edge is being diluted to under half strength by four sources that
have not shown they know anything. If we do nothing, every improvement to that edge arrives at the
book at 41% of its size.

## Decision

We will weight each fusion source by its **shrunk measured expectancy**, floored at zero, instead of by
`Φ` of its t-statistic:

```
µ̂_s     = avgReturnBps_s                  // the source's own measured edge, gross of cost
c_s     = n_s / (n_s + K)                 // Bühlmann credibility over resolved calls — unchanged (ADR-0074)
pool_s  = mean_{r≠s}(µ̂_r)                 // LEAVE-ONE-OUT prior: the rest of the desk
ê_s     = c_s·µ̂_s + (1 − c_s)·pool_s      // posterior mean of this source's edge
w_s     = clamp( max(0, ê_s) / mean_s(max(0, ê_r)), MIN, MAX )
```

Proportionality to expected return is the textbook combination rule (Grinold & Kahn, *Active Portfolio
Management*, ch. 11 — weight ∝ IC·volatility) and it has the two limits Φ lacks: no measured edge ⇒ no
weight, and twice the edge ⇒ twice the weight, with no ceiling arriving at `t = 2`. The prior is
**leave-one-out** because on an unbounded edge scale a self-inclusive pool lets a thin source with an
extreme reading drag the pool toward itself and then be shrunk toward its own luck; on the bounded Φ
scale that was harmless only because everything saturated. `MIN`, `MAX` and `K` are unchanged.

On the live cross-section this rotates `reversion` from **41% to 70%** of the weight mass and drops
`social` from `1.525` to `0.547`. It cannot scale the book: the combiner normalises by `Σw`, so only
ratios matter, and a more concentrated weight vector earns a *smaller* ADR-0076 diversification
multiplier — the one-way direction.

## Alternatives considered

**Keep Φ and raise the MIN/MAX band.** Widening the band lets Φ's ordering express itself more, but the
ordering is the problem, not its range: a null source is still at ½ of a perfect one before any
clamping, and saturation above `t ≈ 2.5` is unaffected. Rejected — it rescales a broken statistic.

**Remove the MIN floor so measured-negative sources drop out.** Tried on 2026-07-27 (`a70d880ac`),
scored ❌ BAD and auto-reverted: exposure grew with no PnL gain. Rejected on measured evidence, and
this ADR deliberately leaves the floor exactly where it is.

**Precision-weighted empirical Bayes** — shrink by `se²/(se² + τ²)` with `τ²` the cross-sectional
variance of the source means, instead of by `n/(n+K)`. Better statistics in principle, and it keeps the
standard error in the link. Deferred, not rejected: `τ²` is estimated from five sources here, which is
too few to be stable, and it would change the shrinkage and the link in one commit — untestable against
the ledger. Revive it once the desk carries enough sources for a cross-sectional variance to mean
something.

**Invert the significantly-negative sources.** `trend` measures `t = −5.6` and `momentum` `−3.2`; the
sim is plainly mean-reverting. Rejected: inverting a losing signal is the canonical overfit (Harvey,
Liu & Zhu, *RFS* 2016), and `trend` is already a near-mirror of `reversion`, so it would double the
desk's existing bet while presenting itself as a second one.

## Consequences

- **Positive:** the desk's proven edge stops being diluted by sources that have not earned a view; a
  null source now costs the floor rather than a full unit; discrimination survives at high `t`, so
  future work that *improves* a source's measured edge is rewarded proportionally instead of hitting
  Φ's ceiling.
- **Negative — the weight no longer reads the standard error directly.** Dispersion enters only through
  the sample count, so a high-variance source with a large mean is trusted more than Φ would have
  trusted it. Bounded by `MAX` and by the ADR-0064 edge gate, which still tests significance before any
  name may increase, but it is a real loss of information until the precision-weighted alternative above
  becomes estimable.
- **Negative — below zero the rule expresses no ordering.** The positive part is taken before any ratio
  is formed, so a source measured badly and one measured catastrophically both sit on the floor. When
  *every* source has negative shrunk expectancy the weights go equal — narrower than the degenerate case
  ADR-0067 removed (which fired whenever any source fell below a coin flip, profitable sources still on
  the desk), and in that state the ADR-0064 gate passes no source, so every name is reduce-only anyway.
- **Negative — near-winner-take-all when one source dominates.** At `MIN = 0.25` the live vector becomes
  `reversion 3.0` and everyone else at or near the floor. That is the correct reading of the evidence,
  but it makes the desk's book a bet on one measurement; `MIN`/`MAX` are the only things bounding it.
- **Follow-ups:** revisit `K`, `MIN` and `MAX` once the ledger has scored this — they were tuned against
  a saturating statistic and a non-saturating one may want a different band. Related: ADR-0055 (fusion),
  ADR-0064/0075 (edge gate), ADR-0067 (expectancy), ADR-0074 (credibility), ADR-0076 (diversification
  multiplier), ADR-0082 (horizon ladder).
