package io.muniworld.curve;

import io.muniworld.ingest.HttpFetcher;
import io.muniworld.ingest.RawArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingests the Federal Reserve's GSW Treasury zero curve (ADR-0017) and measures short-rate volatility from
 * it — the two model inputs no Official Statement can supply.
 *
 * <p>One file, no key, no registration: {@code feds200628.csv} is the Board's own staff curve, daily from
 * 1961, in Nelson-Siegel-Svensson form. Because a lattice discounts cashflows it needs ZERO rates, and this
 * file provides them in closed form at any maturity — which is why it, and not the Treasury par curve, is
 * the benchmark leg. The full history arrives in the same download that gives today's curve, so the
 * volatility estimate comes free with the ingest rather than needing a second (paid) source.
 *
 * <p>Refreshed daily; the Fed republishes weekly, so most passes are a no-op upsert. Failure is loud and
 * non-fatal — the next pass retries, and nothing else in the app depends on this having succeeded.
 */
@Component
public final class GswCurveIngest {

    private static final Logger log = LoggerFactory.getLogger(GswCurveIngest.class);

    /** The source key stored on every row — the benchmark (taxable Treasury) leg. */
    public static final String SOURCE = "GSW";

    /**
     * The tenors materialised into {@code muni.curve_point}. The fit is stored too, so any other maturity
     * is evaluable on demand; this set is the standard grid a lattice and the UI actually read.
     */
    private static final List<BigDecimal> TENORS = List.of(
            new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"), new BigDecimal("5"),
            new BigDecimal("7"), new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("20"),
            new BigDecimal("30"));

    /** The vol is measured on the shortest published GSW tenor — the Fed does not fit below one year. */
    private static final BigDecimal VOL_TENOR = new BigDecimal("1");
    private static final String VOL_SERIES = SOURCE + ":1Y";

    private final HttpFetcher fetcher;
    private final CurveRepository repo;
    private final boolean enabled;
    private final String url;
    private final int volWindowDays;
    private volatile String lastResult = "never run";
    private volatile String lastRun = "";

    public GswCurveIngest(HttpFetcher fetcher, CurveRepository repo,
                          @Value("${muni.curve.enabled:true}") boolean enabled,
                          @Value("${muni.curve.gsw.url}") String url,
                          @Value("${muni.curve.vol.window-days:252}") int volWindowDays) {
        this.fetcher = fetcher;
        this.repo = repo;
        this.enabled = enabled;
        this.url = url;
        this.volWindowDays = volWindowDays;
    }

    /** Boot pass after 45s (behind the fund ingest), then daily. */
    @Scheduled(initialDelay = 45_000, fixedDelay = 86_400_000)
    public void run() {
        if (!enabled) {
            lastResult = "disabled (muni.curve.enabled=false)";
            return;
        }
        lastRun = Instant.now().toString();
        try {
            RawArtifact raw = fetcher.fetch("fed-gsw", url);
            List<GswCsvParser.Row> rows = GswCsvParser.parse(raw.body());
            if (rows.isEmpty()) {
                lastResult = "FAILED — file parsed but contained no usable curve rows";
                return;
            }
            int written = repo.upsertFits(SOURCE, rows, TENORS);
            LocalDate newest = rows.get(rows.size() - 1).date();
            String volNote = measureVol(newest);
            lastResult = "ok — " + rows.size() + " curve day(s) parsed, " + written + " stored, newest "
                    + newest + "; " + volNote;
            log.info("GSW curve ingest: {}", lastResult);
        } catch (Exception e) {
            lastResult = "FAILED — " + rootMessage(e);
            log.warn("GSW curve ingest failed (retries next pass): {}", e.toString());
        }
    }

    /**
     * Measure both parameterisations of realized vol on the stored 1-year zero, each with its rolling-window
     * band, and store them. Returns a one-line note for the status page.
     */
    private String measureVol(LocalDate asOf) {
        List<Double> bp = repo.rateHistoryBp(SOURCE, VOL_TENOR);
        if (bp.size() < 2) {
            return "vol not measured (" + bp.size() + " observation(s) stored)";
        }
        RealizedVol.Estimate normal = RealizedVol.normal(bp);
        RealizedVol.Estimate logn = RealizedVol.lognormal(bp);
        RealizedVol.Band normalBand = RealizedVol.band(bp, volWindowDays, RealizedVol::normal);
        RealizedVol.Band lognBand = RealizedVol.band(bp, volWindowDays, RealizedVol::lognormal);
        repo.upsertVol(VOL_SERIES, "NORMAL", asOf, volWindowDays, normal, normalBand);
        repo.upsertVol(VOL_SERIES, "LOGNORMAL", asOf, volWindowDays, logn, lognBand);
        return "vol measured on " + VOL_SERIES + ": normal " + normal.sigma() + " bp/yr, lognormal "
                + logn.sigma() + " (" + normal.observations() + " obs, " + logn.excluded()
                + " excluded at the 1bp floor)";
    }

    /** For the status endpoint. */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("source", SOURCE);
        out.put("url", url);
        out.put("volWindowDays", volWindowDays);
        out.put("lastRun", lastRun.isBlank() ? "never" : lastRun);
        out.put("lastResult", lastResult);
        out.put("curveDays", repo.fitCount(SOURCE));
        out.put("newestCurve", repo.latestAsOf(SOURCE).map(String::valueOf).orElse("none"));
        out.put("vols", repo.latestVols());
        return out;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? String.valueOf(e.getMessage())
                : e.getMessage() + " (" + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
    }
}
