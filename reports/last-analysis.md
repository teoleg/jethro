Fixed the trend sensor's scale estimator: it was seeded from one observation, so every name opened at
maximum conviction — the sensor was asking for the biggest possible book at the moment it knew least.

Context first, because it explains the empty report: `scripts/reset-sim.sh` was run a few minutes before
this cycle. That wipes the SIM book and, by design, the loop's sim-derived records — so the pending
baseline for last cycle's trend sensor went with it and the scorer correctly reported nothing to score.
Last cycle's change is therefore deployed but unmeasured, not judged. The bundled report was captured ten
seconds after the restart and shows an idle desk for that reason alone; I read the live endpoints instead,
and they showed something quite different.

What they showed is a defect in my own last change. Every single name the trend source spoke about was
pinned at the extreme of the forecast scale, simultaneously — including the 2-year and 5-year Treasury
futures pinned at *opposite* extremes. Those two are the same rates factor; no real trend read puts them
in maximum opposition. That is the signature of a saturated forecast, not a directional view. The
mechanism is in the normaliser: the sensor divides each reading by an EWMA of its own typical magnitude,
and that EWMA was seeded from a *single* observation. One draw is not an estimate of "typical" — it is an
arbitrary anchor, and because the normalisation span is deliberately long, the EWMA needs most of a span
to walk it off. Any name whose first reading landed in a quiet patch then reported every ordinary move
afterwards as an extreme one. I reproduced it deterministically: on a stream built from incommensurate
sinusoids at production span proportions, five of the first ten published readings clipped at the cap,
peaking at six and a half times a typical trend.

This costs risk-adjusted PnL in both terms of the ratio. A clipped forecast carries no sizing
information, so the discrimination that justified ADR-0066 — a clean trend outranking a noisy one — is
destroyed exactly when it matters; and every saturated name asks for the maximum position the scale
allows, so exposure inflates with no conviction behind it. It also renders the conviction floor a no-op,
since nothing sits below a threshold when everything is at the ceiling.

The fix repairs the estimator and leaves the model alone: the first half-span of readings accumulate into
a running mean of the raw magnitude, the sensor publishes no view at all until it has them, and only then
does it switch to exponential updating. It stays silent longer and speaks calibrated when it does. I
deliberately did *not* speed up the EWMA — a fast normaliser would divide out the very trend strength the
score exists to report. The warm-up length is derived from the existing normalisation span rather than
added as a new dial, and it delays the first reading without sizing anything. Both the regression and the
long-run calibration are locked in by tests that I verified fail against the old seed, and the correction
is recorded in ADR-0066 itself, which is still Proposed.

I expect the sensor to be quiet for its warm-up and then to speak with real dispersion instead of a wall
of maximum readings. If the next ledger row says otherwise, the lever after this is the fusion weighting
rather than the sensor.
