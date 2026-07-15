package io.jethro.app.trading;

/**
 * Fetches a real US Treasury zero/par curve snapshot from a provider (ADR-0024). Kept a small
 * port so the provider (Finnhub today; the US Treasury direct feed is a possible fallback) is
 * swappable without touching the curve source or wiring. Off the tick path, rate-limited.
 */
public interface TreasuryCurveFetcher {

    /**
     * The latest curve as zero rates (fraction, e.g. 0.0421) aligned to
     * {@link io.jethro.trading.marketdata.sim.CurveMarkSource#TENORS} (1/2/5/10/30Y), or
     * {@code null} if the fetch failed, the endpoint is unavailable/gated, or the snapshot is
     * incomplete — the caller tries the next source in the chain (or keeps the last good curve
     * on refresh).
     */
    double[] fetchNodeZeros();

    /** Human name of the provider, for the "which curve am I looking at" log/label. */
    String source();
}
