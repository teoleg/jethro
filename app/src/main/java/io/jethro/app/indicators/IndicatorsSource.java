package io.jethro.app.indicators;

import java.util.List;

/**
 * The market-indicators strip's data source. Two implementations: {@link IndicatorsService}
 * polls real (delayed) index levels from Yahoo for live-feed runs; {@link SimIndicatorsSource}
 * derives the strip from the sim tape itself — pure sim runs are fully self-contained
 * (local-dev rule: never depend on the network for a sim feature).
 */
public interface IndicatorsSource {

    List<IndicatorsService.Indicator> latest();
}
