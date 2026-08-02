package io.muniworld.extract;

import io.muniworld.ingest.IngestService;
import io.muniworld.ingest.RawArtifact;
import io.muniworld.pdf.PdfText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wires the ADR-0015 Phase-1 pipeline end to end on a <b>landed</b> Official Statement PDF: extract text
 * ({@link PdfText}), parse the maturity schedule + call clause into term rows
 * ({@link OfficialStatementParser}), and normalise→index them via {@link IngestService} into the bonds table.
 * Bonds land <b>terms-only</b> (blank market economics until a current-price source exists).
 *
 * <p>The PDF bytes come from a landed {@link RawArtifact} (ADR-0005 provenance), so the same immutable
 * artifact is re-runnable as the parser improves. No network here — the fetch/land step is upstream.
 */
@Component
public final class OfficialStatementExtractor {

    private static final Logger log = LoggerFactory.getLogger(OfficialStatementExtractor.class);

    private final IngestService ingest;

    public OfficialStatementExtractor(IngestService ingest) {
        this.ingest = ingest;
    }

    /** The observable extraction record: what was read, indexed, quarantined, and how confident the parse. */
    public record Summary(String sourceId, String sha256, int pages, int rowsParsed, int indexed,
                          int quarantined, double confidence, boolean ok, String error) {
    }

    /** Extract a landed OS PDF into terms and index them. {@code issuer}/{@code geoFips}/{@code base} tag the OS. */
    public Summary extract(RawArtifact osPdf, String issuer, String geoFips, String fallbackBase) {
        try {
            int pages = PdfText.pageCount(osPdf.body());
            String text = PdfText.extract(osPdf.body());
            OfficialStatementParser.Result res =
                    OfficialStatementParser.parse(text, issuer, geoFips, fallbackBase);
            IngestService.Summary ing = ingest.indexRows(res.rows(), OfficialStatementParser.FIELD_MAP);
            int quarantined = res.quarantined() + ing.skipped();
            log.info("OS extract {}: {} pages, {} rows parsed, {} indexed, {} quarantined (conf {})",
                    osPdf.sourceId(), pages, res.rows().size(), ing.indexed(), quarantined, res.confidence());
            return new Summary(osPdf.sourceId(), osPdf.sha256(), pages, res.rows().size(),
                    ing.indexed(), quarantined, res.confidence(), true, null);
        } catch (Exception e) {
            return new Summary(osPdf.sourceId(), osPdf.sha256(), 0, 0, 0, 0, 0.0, false, e.getMessage());
        }
    }
}
