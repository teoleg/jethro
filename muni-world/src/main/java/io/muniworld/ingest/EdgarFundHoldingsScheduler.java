package io.muniworld.ingest;

import io.muniworld.bond.MuniBondService;
import io.muniworld.domain.Bond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drives the ADR-0016 fund-holdings ingest: every enabled fund in the EDGAR registry, once at boot and
 * daily after (the data is quarterly; the daily pass is a cheap two-request check that picks a new filing
 * up within a day of EDGAR publishing it). Upserts are idempotent, so re-reading the same filing is a
 * no-op in effect.
 *
 * <p>Always a bean so the status page can report the feature even when disabled; the flag only gates the
 * fetch. One fund's failure never blocks another's — each failure is caught, kept, and shown.
 */
@Component
public final class EdgarFundHoldingsScheduler {

    private static final Logger log = LoggerFactory.getLogger(EdgarFundHoldingsScheduler.class);

    private final EdgarFundCatalog catalog;
    private final EdgarNportConnector connector;
    private final MuniBondService bonds;
    private final boolean enabled;
    private final Map<String, String> lastResult = new LinkedHashMap<>();   // fund label → outcome line
    private volatile String lastRun = "";

    public EdgarFundHoldingsScheduler(EdgarFundCatalog catalog, EdgarNportConnector connector,
                                      MuniBondService bonds,
                                      @Value("${muni.funds.enabled:true}") boolean enabled) {
        this.catalog = catalog;
        this.connector = connector;
        this.bonds = bonds;
        this.enabled = enabled;
    }

    /** Boot pass after 30s (let the app settle), then daily. */
    @Scheduled(initialDelay = 30_000, fixedDelay = 86_400_000)
    public void runOnce() {
        if (!enabled) {
            return;
        }
        lastRun = Instant.now().toString();
        for (EdgarFundCatalog.Fund fund : catalog.enabled()) {
            try {
                EdgarNportConnector.Result r = connector.loadLatest(fund);
                for (Bond b : r.bonds()) {
                    bonds.index(b);
                }
                synchronized (lastResult) {
                    lastResult.put(fund.label(), "ok — " + r.bonds().size() + " muni bond(s) from "
                            + r.registrant() + " (" + r.filings() + " filing(s), newest "
                            + r.newestFilingDate() + "; " + r.skippedNonMuni() + " non-muni skipped, "
                            + r.quarantined() + " quarantined)");
                }
            } catch (Exception e) {
                // Loud, named, and non-fatal: the next fund still runs, the next day retries.
                synchronized (lastResult) {
                    lastResult.put(fund.label(), "FAILED — " + e.getMessage());
                }
                log.warn("EDGAR fund ingest failed for {} (CIK {}): {}",
                        fund.label(), fund.cik(), e.toString());
            }
        }
    }

    /** For the status endpoint: the flag, the last run time, and each fund's outcome verbatim. */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("funds", catalog.enabled().size());
        out.put("lastRun", lastRun.isBlank() ? "never" : lastRun);
        synchronized (lastResult) {
            out.put("results", new LinkedHashMap<>(lastResult));
        }
        return out;
    }
}
