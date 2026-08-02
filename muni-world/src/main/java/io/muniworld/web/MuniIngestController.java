package io.muniworld.web;

import io.muniworld.ingest.FieldMap;
import io.muniworld.ingest.HttpFetcher;
import io.muniworld.ingest.IngestService;
import io.muniworld.ingest.SocrataConnector;
import org.springframework.web.bind.annotation.PostMapping;
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

    public MuniIngestController(IngestService ingest, HttpFetcher http) {
        this.ingest = ingest;
        this.http = http;
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
}
