package io.jethro.app.fusion;

import io.jethro.app.social.SocialSignal;
import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.algo.strategy.TradeSignal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single place every source's CURRENT forecast lives (ADR-0055 phase 4). Sources push their latest
 * per-instrument view here (raw signal → normalised {@link Forecast} via the phase-2 mappers, using the
 * configured scalars); the fusion layer reads a fresh cross-section each cycle. A view expires after a
 * freshness window so a stale call (a source that has gone quiet) drops out rather than lingering as a
 * phantom vote. Thread-safe; observational — holding a forecast here places no order (ADR-0016).
 */
public final class ForecastRegistry {

    /** Source scaling constants (ADR-0055 phase 2) — modelling dials, to be tuned from telemetry. */
    public record Params(double expectedAbsZ, double convictionStep, double socialPerChannel, double learnedScale) {
    }

    private record Entry(Forecast forecast, long atMillis) {
    }

    private final Map<String, Entry> latest = new ConcurrentHashMap<>(); // "source|instrument" → latest
    private final Params params;
    private final long freshnessMillis;
    /** ADR-0092: each continuous source's claimed scale, measured on its own stream of readings. */
    private final ForecastScalars scalars;

    public ForecastRegistry(Params params, long freshnessMillis) {
        this(params, freshnessMillis, new ForecastScalars(false, 30));
    }

    public ForecastRegistry(Params params, long freshnessMillis, ForecastScalars scalars) {
        this.params = params;
        this.freshnessMillis = Math.max(1_000, freshnessMillis);
        this.scalars = scalars == null ? new ForecastScalars(false, 30) : scalars;
    }

    /** Per-source measured scale and the scalar it implies (ADR-0092) — operator disclosure. */
    public Map<String, ForecastScalars.Measurement> scalarSnapshot() {
        return scalars.snapshot();
    }

    // ---- source push points (called where each source already records telemetry) ----

    public void submitStrategy(TradeSignal signal) {
        putScaled(signal.kind(), signal.instrumentId(),
                SourceForecasts.strategyClaim(signal, params.expectedAbsZ()));
    }

    public void submitHypothesis(String instrument, Side direction, Hypothesis.Conviction conviction) {
        put(SourceForecasts.fromHypothesis(instrument, direction, conviction, params.convictionStep()));
    }

    public void submitSocial(SocialSignal signal) {
        put(SourceForecasts.fromSocial(signal, params.socialPerChannel()));
    }

    /** Price-derived EWMAC trend reading (ADR-0066); {@code score} is the self-normalised forecast. */
    public void submitTrend(String instrument, double score) {
        putScaled(SourceForecasts.TREND, instrument,
                SourceForecasts.trendClaim(score, Forecast.TARGET_ABS));
    }

    /** Price-derived range-position reversion reading (ADR-0070); {@code score} is self-normalised. */
    public void submitReversion(String instrument, double score) {
        putScaled(SourceForecasts.REVERSION, instrument,
                SourceForecasts.reversionClaim(score, Forecast.TARGET_ABS));
    }

    public void submitLearned(String instrument, double pUp, double pDown, boolean ships) {
        putScaled(SourceForecasts.LEARNED, instrument,
                SourceForecasts.learnedClaim(pUp, pDown, ships, params.learnedScale()));
    }

    /**
     * Publish a CONTINUOUS source's uncapped claim, rescaled to that source's measured scale and then
     * capped (ADR-0092). The ordinal sources — hypothesis (a conviction category) and social (a count
     * of corroborating channels) — do not come through here: their magnitudes are a declared category
     * scale, not an estimated one, so there is no claimed E|reading| to hold them to.
     */
    private void putScaled(String source, String instrument, double claim) {
        put(Forecast.of(source, instrument, scalars.rescale(source, claim)));
    }

    private void put(Forecast forecast) {
        if (forecast == null || forecast.instrument() == null) {
            return;
        }
        latest.put(forecast.source() + "|" + forecast.instrument(),
                new Entry(forecast, System.currentTimeMillis()));
    }

    // ---- read side ----

    /** Fresh forecasts grouped by instrument; silent (no-view) and stale entries are excluded. */
    public Map<String, List<Forecast>> byInstrument(long now) {
        Map<String, List<Forecast>> out = new LinkedHashMap<>();
        for (Entry e : latest.values()) {
            if (now - e.atMillis() > freshnessMillis || !e.forecast().hasView()) {
                continue;
            }
            out.computeIfAbsent(e.forecast().instrument(), k -> new ArrayList<>()).add(e.forecast());
        }
        return out;
    }

    /** Flat list of fresh forecasts (for the UI). */
    public List<Forecast> fresh(long now) {
        List<Forecast> out = new ArrayList<>();
        for (Entry e : latest.values()) {
            if (now - e.atMillis() <= freshnessMillis && e.forecast().hasView()) {
                out.add(e.forecast());
            }
        }
        return out;
    }
}
