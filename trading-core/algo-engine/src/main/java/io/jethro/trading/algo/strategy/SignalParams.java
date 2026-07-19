package io.jethro.trading.algo.strategy;

import java.math.BigDecimal;

/**
 * Live-tunable signal parameters for the z-score detector (ADR-0052). When a {@link
 * MomentumStrategy} is given one, it reads these on every {@code evaluate} instead of its
 * construction-time constants, so an operator can sweep sensitivity at runtime and watch the
 * book react.
 *
 * <p><b>Insulation:</b> only the LIVE strategy instances are wired to a live source. The
 * out-of-sample backtest and the walk-forward harness always build detectors with fixed
 * constructor values (no {@code SignalParams}), so edge measurement stays seeded and
 * reproducible (ADR-0027) — a live dial can never move the ground truth the selector trusts.
 */
public interface SignalParams {

    /** Number of returns in the window (window = lookback+1 prices); clamped to ≥2 by the reader. */
    int lookback();

    /** Z-score at which a signal fires — the window move in units of the instrument's own vol. */
    double thresholdSigmas();

    /** Floor: minimum absolute move in bps for any signal (dust-trade guard). */
    BigDecimal minSignalBps();

    /** Minimum relativeVolume for a signal to fire (ADR-0033); ≤0 disables the gate. */
    double volumeConfirmMin();
}
