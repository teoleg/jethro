package io.muniworld.web;

import io.muniworld.extract.OfficialStatementExtractor;
import io.muniworld.ingest.FieldMap;
import io.muniworld.ingest.HttpFetcher;
import io.muniworld.ingest.IngestService;
import io.muniworld.ingest.RawArtifact;
import io.muniworld.ingest.SocrataConnector;
import java.io.IOException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The securities ingest API. The compliant Official-Statement path is: a human downloads an OS PDF (it's a
 * public document) and drops it in the {@code os-inbox/} folder or uploads it here — the extractor pulls its
 * maturity schedule into the bonds table (ADR-0015). EMMA is never scraped (its Terms of Use prohibit it).
 *
 * <ul>
 *   <li>{@code POST /api/muni/ingest/scan} — load every OS PDF sitting in the inbox folder now.</li>
 *   <li>{@code POST /api/muni/ingest/os-pdf} — upload one OS PDF directly.</li>
 *   <li>{@code POST /api/muni/ingest/rows} — index rows already in hand (the network-free seam; tests).</li>
 *   <li>{@code POST /api/muni/ingest/socrata-bonds} — fetch a Socrata open-data dataset and index it.</li>
 * </ul>
 */
@RestController
public final class MuniIngestController {

    private final IngestService ingest;
    private final HttpFetcher http;
    private final OfficialStatementExtractor osExtractor;
    private final io.muniworld.ingest.DirectoryIngestService dirIngest;

    public MuniIngestController(IngestService ingest, HttpFetcher http,
                               OfficialStatementExtractor osExtractor,
                               io.muniworld.ingest.DirectoryIngestService dirIngest) {
        this.ingest = ingest;
        this.http = http;
        this.osExtractor = osExtractor;
        this.dirIngest = dirIngest;
    }

    /** Load every PDF sitting in the OS inbox folder now (also runs automatically on a schedule). */
    @PostMapping("/api/muni/ingest/scan")
    public List<OfficialStatementExtractor.Summary> scanInbox() {
        return dirIngest.scanNow();
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
     * Upload an Official Statement PDF directly and extract its terms (ADR-0015). Download the OS from EMMA in
     * a browser (it's a public document), upload it here, and its maturity schedule lands in the bonds table.
     * {@code issuer}/{@code geoFips}/{@code base} tag the OS; {@code base} defaults to the CUSIP-6 the parser
     * reads from the document.
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
}
