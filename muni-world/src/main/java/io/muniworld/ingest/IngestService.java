package io.muniworld.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.muniworld.bond.MuniBondService;
import io.muniworld.domain.Bond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Wires the ADR-0004 pipeline end to end for securities: a {@link SourceConnector} lands JSON, the rows are
 * normalised through a {@link FieldMap} into canonical {@link Bond}s, and each is indexed into the LMDB
 * search index (ADR-0013) so it shows in the bonds table. Also indexes already-in-hand rows directly. Every
 * run returns a {@link Summary} (bytes/hash landed, rows, indexed, skipped) — the observable ingest record.
 */
@Service
public final class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final ObjectMapper mapper;
    private final SecurityNormaliser normaliser;
    private final MuniBondService bonds;

    public IngestService(ObjectMapper mapper, SecurityNormaliser normaliser, MuniBondService bonds) {
        this.mapper = mapper;
        this.normaliser = normaliser;
        this.bonds = bonds;
    }

    public record Summary(String sourceId, String url, int bytes, String sha256,
                          int rows, int indexed, int skipped, boolean ok, String error) {
    }

    /** Fetch a JSON source (land), normalise its rows, index the bonds. */
    public Summary ingest(SourceConnector connector, FieldMap map) {
        try {
            RawArtifact art = connector.fetch().get(0); // ADR-0005 landing
            List<Map<String, Object>> rows = mapper.readValue(art.body(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            SecurityNormaliser.Result res = normaliser.normalise(rows, map);
            res.bonds().forEach(bonds::index);
            log.info("ingested {}: {} rows -> {} bonds ({} skipped)",
                    connector.sourceId(), rows.size(), res.bonds().size(), res.skipped());
            return new Summary(connector.sourceId(), art.url(), art.size(), art.sha256(),
                    rows.size(), res.bonds().size(), res.skipped(), true, null);
        } catch (RuntimeException | java.io.IOException e) {
            return new Summary(connector.sourceId(), null, 0, null, 0, 0, 0, false, e.getMessage());
        }
    }

    /** Normalise + index rows already in hand (e.g. posted directly, or parsed from a document). */
    public Summary indexRows(List<Map<String, Object>> rows, FieldMap map) {
        SecurityNormaliser.Result res = normaliser.normalise(rows, map);
        res.bonds().forEach(bonds::index);
        return new Summary("adhoc:rows", null, 0, null,
                rows.size(), res.bonds().size(), res.skipped(), true, null);
    }
}
