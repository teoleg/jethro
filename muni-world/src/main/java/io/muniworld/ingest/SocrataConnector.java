package io.muniworld.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * The first api-first connector (ADR-0004): NYC Open Data (and any Socrata portal, incl. data.ny.gov). Pulls
 * a dataset's rows as JSON over the Socrata resource API — the clean, permitted path ADR-0004 prefers over
 * scraping. Fetch lands the raw JSON (provenance via {@link RawArtifact}); {@link #parse} turns it into rows
 * for the normalise stage.
 *
 * <p>URL: {@code https://<domain>/resource/<datasetId>.json?$limit=<n>&$offset=<o>}. Paginate by offset for
 * large datasets. Domain examples: {@code data.cityofnewyork.us}, {@code data.ny.gov}.
 */
public final class SocrataConnector implements SourceConnector {

    private final String sourceId;
    private final String domain;
    private final String datasetId;   // the Socrata 4x4 resource id, resolved from the catalog (ADR seed)
    private final int limit;
    private final HttpFetcher http;

    public SocrataConnector(String sourceId, String domain, String datasetId, int limit, HttpFetcher http) {
        this.sourceId = sourceId;
        this.domain = domain;
        this.datasetId = datasetId;
        this.limit = limit;
        this.http = http;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    public String url() {
        return "https://" + domain + "/resource/" + datasetId + ".json?$limit=" + limit;
    }

    @Override
    public List<RawArtifact> fetch() {
        return List.of(http.fetch(sourceId, url()));
    }

    /** Parse a landed Socrata JSON artifact into a list of row maps (source-shaped; normalise maps onward). */
    public static List<Map<String, Object>> parse(RawArtifact artifact, ObjectMapper mapper) {
        try {
            return mapper.readValue(artifact.body(), new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new RuntimeException("Socrata parse failed for " + artifact.url(), e);
        }
    }
}
