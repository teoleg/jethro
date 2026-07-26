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

    public ForecastRegistry(Params params, long freshnessMillis) {
        this.params = params;
        this.freshnessMillis = Math.max(1_000, freshnessMillis);
    }

    // ---- source push points (called where each source already records telemetry) ----

    public void submitStrategy(TradeSignal signal) {
        put(SourceForecasts.fromStrategy(signal, params.expectedAbsZ()));
    }

    public void submitHypothesis(String instrument, Side direction, Hypothesis.Conviction conviction) {
        put(SourceForecasts.fromHypothesis(instrument, direction, conviction, params.convictionStep()));
    }

    public void submitSocial(SocialSignal signal) {
        put(SourceForecasts.fromSocial(signal, params.socialPerChannel()));
    }

    /** Price-derived EWMAC trend reading (ADR-0066); {@code score} is the self-normalised forecast. */
    public void submitTrend(String instrument, double score) {
        put(SourceForecasts.fromTrend(instrument, score, Forecast.TARGET_ABS));
    }

    public void submitLearned(String instrument, double pUp, double pDown, boolean ships) {
        put(SourceForecasts.fromLearned(instrument, pUp, pDown, ships, params.learnedScale()));
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
