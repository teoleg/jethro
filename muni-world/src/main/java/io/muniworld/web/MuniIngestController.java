package io.muniworld.web;

import io.muniworld.crawl.EmmaDiscovery;
import io.muniworld.extract.OfficialStatementExtractor;
import io.muniworld.ingest.FieldMap;
import io.muniworld.ingest.HttpFetcher;
import io.muniworld.ingest.IngestService;
import io.muniworld.ingest.RawArtifact;
import io.muniworld.ingest.SocrataConnector;
import java.io.IOException;
import java.util.LinkedHashMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The securities ingest API (ADR-0004 pipeline end to end). Two entry points:
 *
 * <ul>
 *   <li>{@code POST /api/muni/ingest/rows} — normalise + index rows already in hand (posted directly, or the
 *       output of the ADR-0010 document-extraction stage). Body carries the source rows and the
 *       {@link FieldMap} that names their columns. This is the offline-testable seam: no network needed.</li>
 *   <li>{@code POST /api/muni/ingest/socrata-bonds} — fetch a Socrata dataset (land → parse → normalise →
 *       index) and report the {@link IngestService.Summary}. Live on a networked host (the Pi); the sandbox
 *       blocks the Socrata origins, which surfaces as {@code ok:false} with the block reason, not a guess.</li>
 * </ul>
 */
@RestController
public final class MuniIngestController {

    private final IngestService ingest;
    private final HttpFetcher http;
    private final OfficialStatementExtractor osExtractor;
    private final EmmaDiscovery discovery;
    private final io.muniworld.ingest.DirectoryIngestService dirIngest;
    private final io.muniworld.crawl.EmmaAutoFetcher emmaAutoFetcher;

    public MuniIngestController(IngestService ingest, HttpFetcher http,
                               OfficialStatementExtractor osExtractor, EmmaDiscovery discovery,
                               io.muniworld.ingest.DirectoryIngestService dirIngest,
                               io.muniworld.crawl.EmmaAutoFetcher emmaAutoFetcher) {
        this.ingest = ingest;
        this.http = http;
        this.osExtractor = osExtractor;
        this.discovery = discovery;
        this.dirIngest = dirIngest;
        this.emmaAutoFetcher = emmaAutoFetcher;
    }

    /** Load every PDF sitting in the OS inbox folder now (also runs automatically on a schedule). */
    @PostMapping("/api/muni/ingest/scan")
    public List<OfficialStatementExtractor.Summary> scanInbox() {
        return dirIngest.scanNow();
    }

    /**
     * Fully automatic: render a CUSIP's EMMA page with a headless browser, download its OS PDFs into the
     * inbox (the folder loader then extracts them). No manual download. Needs Chromium on the host + a
     * running muni. Reports what it found so EMMA's markup can be tuned from a real run.
     */
    @PostMapping("/api/muni/ingest/emma-auto")
    public io.muniworld.crawl.EmmaAutoFetcher.Result emmaAuto(@RequestParam String cusip) {
        return emmaAutoFetcher.fetchToInbox(cusip);
    }

    /** Load the LATEST official statements from EMMA — no CUSIP needed. Renders the recent-OS feed and pulls
     *  the newest {@code count} OS PDFs into the inbox (then auto-extracted). Needs Chromium on the host. */
    @PostMapping("/api/muni/ingest/emma-latest")
    public io.muniworld.crawl.EmmaAutoFetcher.Result emmaLatest(
            @RequestParam(defaultValue = "10") int count) {
        return emmaAutoFetcher.fetchLatest(count);
    }

    /** Debug: dump the links EMMA's recent-OS page renders — so the OS-link pattern can be matched to reality. */
    @org.springframework.web.bind.annotation.GetMapping("/api/muni/debug/emma-links")
    public List<String> emmaLinks() {
        return emmaAutoFetcher.recentLinks();
    }

    /** Debug: EMMA loads its grid via AJAX — surface the data-endpoint / postback candidates to hit directly. */
    @org.springframework.web.bind.annotation.GetMapping("/api/muni/debug/emma-probe")
    public java.util.Map<String, Object> emmaProbe() {
        return emmaAutoFetcher.debugProbe();
    }

    /** Debug: capture EMMA's actual network requests — reveals the AJAX data endpoint the grid calls. */
    @org.springframework.web.bind.annotation.GetMapping("/api/muni/debug/emma-network")
    public List<String> emmaNetwork() {
        return emmaAutoFetcher.dataRequests();
    }

    /** Request body for direct row ingest: the source rows plus the map naming their columns. */
    public record RowsRequest(List<Map<String, Object>> rows, FieldMap map) {
    }

    /** Normalise + index rows already in hand — the network-free path (extraction output, manual load, tests). */
    @PostMapping("/api/muni/ingest/rows")
    public IngestService.Summary ingestRows(@RequestBody RowsRequest req) {
        return ingest.indexRows(req.rows(), req.map());
    }

    /** Fetch a Socrata dataset and run it through the full pipeline into the bonds index. */
    @PostMapping("/api/muni/ingest/socrata-bonds")
    public IngestService.Summary ingestSocrataBonds(
            @RequestParam(defaultValue = "data.cityofnewyork.us") String domain,
            @RequestParam String dataset,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestBody FieldMap map) {
        SocrataConnector connector = new SocrataConnector("socrata:" + dataset, domain, dataset, limit, http);
        return ingest.ingest(connector, map);
    }

    /**
     * Fetch an EMMA Official Statement PDF, land it, and extract its terms into the bonds table (ADR-0015).
     * The flagship public-data path — live on a networked host (the Pi). Bonds land terms-only. {@code issuer}
     * /{@code geoFips}/{@code base} tag the OS being ingested.
     */
    @PostMapping("/api/muni/ingest/emma-os")
    public OfficialStatementExtractor.Summary ingestEmmaOs(
            @RequestParam String url,
            @RequestParam(required = false) String issuer,
            @RequestParam(required = false) String geoFips,
            @RequestParam(name = "base", required = false) String fallbackBase) {
        String base = fallbackBase != null ? fallbackBase : "";
        RawArtifact pdf;
        try {
            pdf = http.fetch("emma-os:" + url, url);           // land the PDF (ADR-0005 provenance)
        } catch (RuntimeException e) {
            // a bad/unreachable URL must not 500 — report it as a failed ingest, like any other
            return new OfficialStatementExtractor.Summary("emma-os:" + url, null, 0, "fetch-failed",
                    0, 0, 0, 0, 0.0, false, "fetch failed: " + e.getMessage());
        }
        return osExtractor.extract(pdf, issuer, geoFips, base);
    }

    /**
     * Upload an Official Statement PDF directly and extract its terms (ADR-0015). The public-data path that
     * needs no EMMA scraping: download the OS from EMMA in a browser (it's a public document), upload it
     * here, and its maturity schedule lands in the bonds table. {@code issuer}/{@code geoFips}/{@code base}
     * tag the OS; {@code base} defaults to the CUSIP-6 the parser reads from the document.
     */
    @PostMapping("/api/muni/ingest/os-pdf")
    public OfficialStatementExtractor.Summary ingestOsPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String issuer,
            @RequestParam(required = false) String geoFips,
            @RequestParam(name = "base", required = false) String fallbackBase) throws IOException {
        RawArtifact pdf = RawArtifact.of("upload:" + file.getOriginalFilename(),
                file.getOriginalFilename(), file.getContentType(), file.getBytes());  // land the upload (ADR-0005)
        return osExtractor.extract(pdf, issuer, geoFips, fallbackBase == null ? "" : fallbackBase);
    }

    /**
     * Discover a CUSIP's Official Statement on EMMA (politely — robots + rate limit, ADR-0008), land the
     * first candidate PDF, and extract its terms (ADR-0015 Phase 2). No hand-fed URL. Live on the Pi.
     */
    @PostMapping("/api/muni/ingest/emma-discover")
    public Map<String, Object> ingestEmmaDiscover(
            @RequestParam String cusip,
            @RequestParam(required = false) String issuer,
            @RequestParam(required = false) String geoFips) {
        Map<String, Object> out = new LinkedHashMap<>();
        EmmaDiscovery.Result disc = discovery.discover(cusip);
        out.put("securityUrl", disc.securityUrl());
        out.put("candidates", disc.candidates());
        if (disc.candidates().isEmpty()) {
            out.put("ok", false);
            out.put("error", "no Official Statement link found on the EMMA page — tune muni.emma.os-link-pattern");
            return out;
        }
        String osUrl = disc.candidates().get(0);
        out.put("chosen", osUrl);
        RawArtifact pdf = discovery.fetchDocument("emma-os:" + cusip, osUrl);
        String base = cusip.length() >= 6 ? cusip.substring(0, 6) : cusip;   // CUSIP-6 issuer prefix
        out.put("extract", osExtractor.extract(pdf, issuer, geoFips, base));
        return out;
    }
}
