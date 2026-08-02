package io.muniworld.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.muniworld.ingest.HttpFetcher;
import io.muniworld.ingest.RawArtifact;
import io.muniworld.ingest.SocrataConnector;
import io.muniworld.seed.SeedCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seed data + on-demand ingestion for the UI. {@code /api/muni/issuers} serves the seeded NYC issuer
 * complex (offline). {@code /api/muni/ingest/socrata} runs the ADR-0004 Socrata connector on demand and
 * reports the landing — useful to prove the pipeline against NYC Open Data / data.ny.gov from a host with
 * network (the sandbox blocks those; the Pi does not).
 */
@RestController
public final class MuniDataController {

    private final SeedCatalog seeds;
    private final HttpFetcher http;
    private final ObjectMapper mapper = new ObjectMapper();

    public MuniDataController(SeedCatalog seeds, HttpFetcher http) {
        this.seeds = seeds;
        this.http = http;
    }

    /** The seeded NYC issuer complex. */
    @GetMapping("/api/muni/issuers")
    public List<SeedCatalog.Issuer> issuers() {
        return seeds.issuers();
    }

    /** Fetch a Socrata dataset and report the landing (bytes + sha256 + row count). Live on a networked host. */
    @PostMapping("/api/muni/ingest/socrata")
    public Map<String, Object> ingestSocrata(@RequestParam(defaultValue = "data.cityofnewyork.us") String domain,
                                             @RequestParam String dataset,
                                             @RequestParam(defaultValue = "1000") int limit) {
        SocrataConnector connector = new SocrataConnector("adhoc:" + dataset, domain, dataset, limit, http);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", connector.url());
        try {
            RawArtifact art = connector.fetch().get(0);
            out.put("ok", true);
            out.put("bytes", art.size());
            out.put("sha256", art.sha256());
            out.put("contentType", art.contentType());
            out.put("rows", SocrataConnector.parse(art, mapper).size());
        } catch (RuntimeException e) {
            out.put("ok", false);
            out.put("error", e.getMessage()); // e.g. blocked network in the sandbox — works on the Pi
        }
        return out;
    }
}
