package io.jethro.app.fusion;

import io.jethro.app.social.SocialSignal;
import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.algo.strategy.TradeSignal;

/**
 * Maps each concrete signal type onto the common {@link Forecast} scale (ADR-0055 phase 2) — the
 * adapter layer between the sources and the fusion core. Each mapper is pure and carries the source's
 * scaling parameters explicitly, so the mapping is auditable and testable. The scalars (expected |z|,
 * conviction step, per-channel weight) are modelling constants to be estimated from the phase-1
 * telemetry; nothing here is a size or a price (ADR-0016 / invariant 7).
 */
public final class SourceForecasts {

    /** Source names for the price-derived sensors — shared so the registry and mappers cannot drift. */
    public static final String TREND = "trend";
    public static final String REVERSION = "reversion";
    public static final String LEARNED = "learned";

    private SourceForecasts() {
    }

    /**
     * The UNCAPPED signed forecast a continuous source claims, before {@link Forecast#CAP} is applied.
     * Each {@code from…} mapper below is defined as {@code Forecast.of(source, instrument, …Claim(…))},
     * so this is the single place that source's arithmetic lives.
     *
     * <p>The claim, not the capped forecast, is what {@link ForecastScalars} measures: a clipped
     * reading has already lost the magnitude information the measurement needs, and measuring the
     * output of the rescaling would feed the estimate back into itself (ADR-0092).
     */
    public static double strategyClaim(TradeSignal signal, double expectedAbsZ) {
        int sign = signal.side() == Side.BUY ? 1 : -1;
        return sign * ForecastScaler.claim(Math.abs(signal.zScore()), expectedAbsZ);
    }

    /**
     * Deterministic strategy (momentum or mean-reversion). The signal's SIDE already encodes the traded
     * direction (mean-reversion fades, so its side is the fade); its |z-score| is the strength, scaled
     * so a typical firing reads ≈ TARGET_ABS. {@code kind()} is the source name (keeps them separable).
     */
    public static Forecast fromStrategy(TradeSignal signal, double expectedAbsZ) {
        return Forecast.of(signal.kind(), signal.instrumentId(), strategyClaim(signal, expectedAbsZ));
    }

    /**
     * AI hypothesis: an ordinal conviction (LOW/MED/HIGH → level 1/2/3) in the model's direction,
     * mapped to evenly spaced magnitudes ({@code step}=5 → ±5/±10/±15 per ADR-0055). The model supplies
     * a category, never a number (ADR-0016); this turns the category into a bounded forecast.
     */
    public static Forecast fromHypothesis(String instrument, Side direction,
                                          Hypothesis.Conviction conviction, double step) {
        int sign = direction == Side.BUY ? 1 : -1;
        int level = switch (conviction) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
        return Forecast.of("hypothesis", instrument, ForecastScaler.stepped(sign * level, step));
    }

    /**
     * Corroborated social signal (ADR-0050). Strength grows with the number of distinct credible
     * channels behind it; a NEUTRAL direction or a suspected pump is no view (0). Untracked names have
     * no mark and are filtered upstream (telemetry), not here.
     */
    public static Forecast fromSocial(SocialSignal signal, double perChannel) {
        if (signal.manipulationSuspected()) {
            return Forecast.of("social", signal.instrumentId(), 0.0);
        }
        int sign = switch (signal.direction()) {
            case "BULLISH" -> 1;
            case "BEARISH" -> -1;
            default -> 0;
        };
        double strength = Math.max(1, signal.corroboratingChannels()) * perChannel;
        return Forecast.of("social", signal.instrumentId(), sign * strength);
    }

    /**
     * Price-derived trend sensor (ADR-0066). {@code score} is the EWMAC forecaster's self-normalised
     * reading, built so its expected absolute value on the running stream is ≈ 1 — "one typical trend".
     * Multiplying by {@code targetAbs} puts it on the desk's shared convention (a typical trend reads
     * as a typical conviction), so the sizing dials keep their meaning across sources. The forecaster
     * has already done every measurement; this mapper only changes units.
     */
    public static Forecast fromTrend(String instrument, double score, double targetAbs) {
        return Forecast.of(TREND, instrument, trendClaim(score, targetAbs));
    }

    /** Uncapped trend claim — see {@link #strategyClaim} for why the claim is what gets measured. */
    public static double trendClaim(double score, double targetAbs) {
        return Double.isFinite(score) ? score * targetAbs : 0.0;
    }

    /**
     * Price-derived mean-reversion sensor (ADR-0070). {@code score} is the range-position forecaster's
     * self-normalised reading, built so its expected absolute value on the running stream is ≈ 1 — "one
     * typical stretch". Multiplying by {@code targetAbs} puts it on the desk's shared convention exactly
     * as {@link #fromTrend} does, so the sizing dials keep their meaning across sources. The sign is
     * already the traded direction (the forecaster fades, so a stretch to the top of the range reads
     * negative); this mapper only changes units.
     */
    public static Forecast fromReversion(String instrument, double score, double targetAbs) {
        return Forecast.of(REVERSION, instrument, reversionClaim(score, targetAbs));
    }

    /** Uncapped reversion claim — see {@link #strategyClaim} for why the claim is what gets measured. */
    public static double reversionClaim(double score, double targetAbs) {
        return Double.isFinite(score) ? score * targetAbs : 0.0;
    }

    /**
     * Learned advisory label (ADR-0053) — contributes ONLY when its walk-forward gate says it ships;
     * otherwise it is silent (0), never a phantom edge on the order path. The directional edge
     * {@code P(up) − P(down)} in [−1,1] scales up to the cap.
     */
    public static Forecast fromLearned(String instrument, double pUp, double pDown, boolean ships, double scale) {
        return Forecast.of(LEARNED, instrument, learnedClaim(pUp, pDown, ships, scale));
    }

    /** Uncapped learned claim — see {@link #strategyClaim} for why the claim is what gets measured. */
    public static double learnedClaim(double pUp, double pDown, boolean ships, double scale) {
        return ships ? (pUp - pDown) * scale : 0.0;
    }
}
