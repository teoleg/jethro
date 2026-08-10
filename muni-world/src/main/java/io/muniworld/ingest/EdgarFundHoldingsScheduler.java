package io.muniworld.ingest;

import io.muniworld.bond.MuniBondService;
import io.muniworld.bond.SecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final SecurityRepository repo;
    private final boolean enabled;
    private final Map<String, String> lastResult = new LinkedHashMap<>();   // fund label → outcome line
    private volatile String lastRun = "";

    public EdgarFundHoldingsScheduler(EdgarFundCatalog catalog, EdgarNportConnector connector,
                                      MuniBondService bonds, SecurityRepository repo,
                                      @Value("${muni.funds.enabled:true}") boolean enabled) {
        this.catalog = catalog;
        this.connector = connector;
        this.bonds = bonds;
        this.repo = repo;
        this.enabled = enabled;
    }

    /** Boot pass after 30s (let the app settle), then daily. */
    @Scheduled(initialDelay = 30_000, fixedDelay = 86_400_000)
    public void runOnce() {
        if (!enabled) {
            return;
        }
        lastRun = Instant.now().toString();
        // Phase 1 — fetch + store terms per fund, failures isolated per fund and per bond. Successful
        // results are kept for phase 2, because the cross-fund detail (held-by-N-funds, total par, the
        // par-weighted valuation) only exists ACROSS funds and must be computed after all have landed.
        List<EdgarNportConnector.Result> results = new ArrayList<>();
        List<EdgarFundCatalog.Fund> resultFunds = new ArrayList<>();
        for (EdgarFundCatalog.Fund fund : catalog.enabled()) {
            try {
                EdgarNportConnector.Result r = connector.loadLatest(fund);
                // ONE bad bond must not cost the whole fund. This threw out of the loop and Franklin's
                // entire portfolio was lost to a single unroundable coupon — exactly the force-fit-or-
                // drop-everything failure ADR-0011 quarantine exists to prevent. Count and carry on.
                int stored = 0;
                int rejected = 0;
                String firstRejection = null;
                for (EdgarNportConnector.Holding h : r.holdings()) {
                    try {
                        bonds.index(h.bond());
                        stored++;
                    } catch (RuntimeException e) {
                        rejected++;
                        if (firstRejection == null) {
                            firstRejection = h.bond().cusip() + ": " + rootMessage(e);
                        }
                    }
                }
                results.add(r);
                resultFunds.add(fund);
                String note = rejected == 0 ? ""
                        : "; " + rejected + " REJECTED on write (first: " + firstRejection + ")";
                synchronized (lastResult) {
                    lastResult.put(fund.label(), "ok — " + stored + " muni bond(s) from "
                            + r.registrant() + " (" + r.filings() + " filing(s), newest "
                            + r.newestFilingDate() + "; " + r.skippedNonMuni() + " non-muni skipped, "
                            + r.quarantined() + " quarantined" + note + ")");
                }
            } catch (Exception e) {
                // Loud, named, and non-fatal: the next fund still runs, the next day retries.
                // Include the ROOT cause: the fetcher's own message is only "fetch failed: <url>" and the
                // HTTP status lives in its cause, so without this the page said nothing about WHY (a 404
                // from the XSL viewer path read identically to a network outage).
                synchronized (lastResult) {
                    lastResult.put(fund.label(), "FAILED — " + rootMessage(e));
                }
                log.warn("EDGAR fund ingest failed for {} (CIK {}): {}",
                        fund.label(), fund.cik(), e.toString());
            }
        }
        writeCrossFundDetail(results);
        // The current cycle is also a HISTORY point: write each fund's dated valuations so the series
        // keeps extending quarter by quarter after the one-time backfill.
        for (int i = 0; i < results.size(); i++) {
            repo.upsertValuations(historyRows(resultFunds.get(i).cik(), results.get(i).periodEnd(),
                    results.get(i).holdings()));
        }
        backfillHistory();
    }

    /**
     * The ONE-TIME history walk (ADR-0016 amendment 2): EDGAR still serves every N-PORT ever filed, so
     * each fund's full filing history is fetched once — years of quarterly, fund-attested valuations per
     * held CUSIP, the free lawful form of the "delayed historical price" vendors sell live. Marked done
     * per fund in muni.ingest_state so it never re-runs; ~30-60 filings per registrant at a polite 500ms
     * pace, so a full backfill is minutes, once.
     */
    private void backfillHistory() {
        for (EdgarFundCatalog.Fund fund : catalog.enabled()) {
            String stateKey = "nport-backfill:" + fund.cik();
            if (repo.state(stateKey).isPresent() || !repo.available()) {
                continue;   // done before, or no DB to record into — retry on a later pass
            }
            try {
                List<EdgarNportConnector.FilingRef> refs = connector.allNportFilings(fund);
                int rows = 0;
                for (EdgarNportConnector.FilingRef ref : refs) {
                    try {
                        EdgarNportConnector.Parsed parsed = connector.loadFiling(fund, ref);
                        rows += repo.upsertValuations(
                                historyRows(fund.cik(), parsed.periodEnd(), parsed.holdings()));
                    } catch (RuntimeException e) {
                        // one unreadable old filing is logged and skipped — history keeps its gaps honest
                        log.warn("history backfill: filing {} of {} unreadable: {}",
                                ref.accession(), fund.label(), e.toString());
                    }
                    Thread.sleep(500);
                }
                repo.setState(stateKey, "filings=" + refs.size() + " rows=" + rows
                        + " at=" + Instant.now());
                log.info("history backfill DONE for {}: {} filing(s), {} valuation row(s)",
                        fund.label(), refs.size(), rows);
                synchronized (lastResult) {
                    lastResult.merge(fund.label(), "", (cur, x) -> cur
                            + " · history backfilled: " + refs.size() + " filing(s)");
                }
            } catch (Exception e) {
                log.warn("history backfill failed for {} (will retry next pass): {}",
                        fund.label(), e.toString());
            }
        }
    }

    /** Filing holdings → history rows {cusip, asOf, cik, par, valUsd}. Empty when the period is unknown. */
    static List<Object[]> historyRows(String cik, java.time.LocalDate periodEnd,
                                      List<EdgarNportConnector.Holding> holdings) {
        List<Object[]> rows = new ArrayList<>();
        if (periodEnd == null) {
            return rows;    // an undated valuation is not a history point — omitted, never guessed
        }
        for (EdgarNportConnector.Holding h : holdings) {
            if (h.parHeld() != null && h.valUsd() != null && h.parHeld().signum() > 0) {
                rows.add(new Object[] {h.bond().cusip(), periodEnd, cik, h.parHeld(), h.valUsd()});
            }
        }
        return rows;
    }

    /**
     * Phase 2 — the detail one filing cannot state alone. Per CUSIP across every fund that reported this
     * pass: how many funds hold it, their total par, and the par-weighted filing valuation
     * {@code sum(valUSD) / sum(par) × 100} as-of the filings' period date. Worked example:
     * fund A holds 1,000,000 par valued $1,012,500; fund B holds 500,000 par valued $505,000 →
     * (1,012,500 + 505,000) / 1,500,000 × 100 = 101.166667. The credit flags OR across funds — one fund
     * attesting default is a fact about the issue. Reflects only funds that SUCCEEDED this pass.
     */
    private void writeCrossFundDetail(List<EdgarNportConnector.Result> results) {
        record Agg(java.util.Set<Integer> funds, java.math.BigDecimal[] parVal, boolean[] flags,
                   String[] kind, java.time.LocalDate[] asOf) {
        }
        Map<String, Agg> byCusip = new LinkedHashMap<>();
        for (int f = 0; f < results.size(); f++) {
            EdgarNportConnector.Result r = results.get(f);
            for (EdgarNportConnector.Holding h : r.holdings()) {
                Agg a = byCusip.computeIfAbsent(h.bond().cusip(), k -> new Agg(new java.util.HashSet<>(),
                        new java.math.BigDecimal[] {java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO},
                        new boolean[2], new String[1], new java.time.LocalDate[1]));
                a.funds().add(f);
                if (h.parHeld() != null && h.valUsd() != null && h.parHeld().signum() > 0) {
                    a.parVal()[0] = a.parVal()[0].add(h.parHeld());
                    a.parVal()[1] = a.parVal()[1].add(h.valUsd());
                }
                a.flags()[0] |= h.inDefault();
                a.flags()[1] |= h.intArrears();
                if (a.kind()[0] == null && !h.couponKind().isBlank()) {
                    a.kind()[0] = h.couponKind();
                }
                if (r.periodEnd() != null
                        && (a.asOf()[0] == null || r.periodEnd().isAfter(a.asOf()[0]))) {
                    a.asOf()[0] = r.periodEnd();
                }
            }
        }
        int written = 0;
        for (Map.Entry<String, Agg> e : byCusip.entrySet()) {
            Agg a = e.getValue();
            java.math.BigDecimal par = a.parVal()[0];
            java.math.BigDecimal valPer100 = valPer100(a.parVal()[1], par);
            boolean ok = repo.updateDetail(e.getKey(), new SecurityRepository.Detail(
                    a.kind()[0], a.flags()[0], a.flags()[1], a.funds().size(),
                    par.signum() > 0 ? par : null, valPer100, a.asOf()[0]));
            if (ok) {
                written++;
            }
        }
        log.info("cross-fund detail: {} of {} CUSIP(s) updated", written, byCusip.size());
    }

    /** {@code sum(valUSD)/sum(par) × 100} as an exact decimal at the schema's 6dp (invariant 1) — the
     *  par-weighted filing valuation. Null when no par was reported (never a division by zero). */
    static java.math.BigDecimal valPer100(java.math.BigDecimal sumVal, java.math.BigDecimal sumPar) {
        return sumPar == null || sumPar.signum() <= 0 || sumVal == null ? null
                : sumVal.multiply(new java.math.BigDecimal(100))
                        .divide(sumPar, 6, java.math.RoundingMode.HALF_UP);
    }

    /** The message plus its root cause, so an HTTP status reaches the page instead of dying in the chain. */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? String.valueOf(e.getMessage())
                : e.getMessage() + " (" + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
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
