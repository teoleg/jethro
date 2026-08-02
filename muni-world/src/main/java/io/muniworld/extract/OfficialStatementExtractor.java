package io.muniworld.extract;

import io.muniworld.ingest.IngestService;
import io.muniworld.ingest.RawArtifact;
import io.muniworld.pdf.OcrText;
import io.muniworld.pdf.PdfText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wires the ADR-0015 pipeline on a landed OS PDF, all three phases: extract text (native → OCR fallback for
 * scanned PDFs, Phase 3), parse the maturity schedule deterministically (Phase 1), and — only when the
 * deterministic parse is empty/low-confidence and a model is configured — add <b>verified</b> assisted rows
 * (Phase 3, ADR-0012), where every model-proposed number must appear in the source text or it's rejected.
 * Bonds land terms-only (blank economics until a current-price source exists).
 */
@Component
public final class OfficialStatementExtractor {

    private static final Logger log = LoggerFactory.getLogger(OfficialStatementExtractor.class);

    private final IngestService ingest;
    private final VerifyingAssistedExtractor assisted;
    private final boolean ocrEnabled;
    private final OcrText ocr;
    private final double assistedThreshold;

    public OfficialStatementExtractor(
            IngestService ingest,
            VerifyingAssistedExtractor assisted,
            @Value("${muni.ocr.enabled:false}") boolean ocrEnabled,
            @Value("${muni.ocr.bin:tesseract}") String tesseractBin,
            @Value("${muni.ocr.dpi:300}") int dpi,
            @Value("${muni.extract.assisted-threshold:0.5}") double assistedThreshold) {
        this.ingest = ingest;
        this.assisted = assisted;
        this.ocrEnabled = ocrEnabled;
        this.ocr = new OcrText(tesseractBin, dpi);
        this.assistedThreshold = assistedThreshold;
    }

    /** The observable extraction record: what was read, how, indexed, quarantined, and parse confidence. */
    public record Summary(String sourceId, String sha256, int pages, String method, int rowsParsed,
                          int assisted, int indexed, int quarantined, double confidence,
                          boolean ok, String error) {
    }

    /** Extract a landed OS PDF into terms and index them. {@code issuer}/{@code geoFips}/{@code base} tag the OS. */
    public Summary extract(RawArtifact osPdf, String issuer, String geoFips, String fallbackBase) {
        try {
            int pages = PdfText.pageCount(osPdf.body());
            String text = PdfText.extract(osPdf.body());
            String method = "native";
            if (ocrEnabled && OcrText.isSparse(text, pages)) {
                text = ocr.extract(osPdf.body());     // scanned PDF → OCR (Phase 3)
                method = "ocr";
            }

            OfficialStatementParser.Result res =
                    OfficialStatementParser.parse(text, issuer, geoFips, fallbackBase);
            List<Map<String, Object>> rows = new ArrayList<>(res.rows());

            // Deterministic parse thin/uncertain AND a model is configured → add VERIFIED assisted rows.
            if ((rows.isEmpty() || res.confidence() < assistedThreshold) && assisted.available()) {
                List<Map<String, Object>> extra = assisted.extractVerified(text, issuer, geoFips);
                Set<String> have = new HashSet<>();
                rows.forEach(r -> have.add(String.valueOf(r.get("cusip"))));
                int added = 0;
                for (Map<String, Object> r : extra) {
                    if (have.add(String.valueOf(r.get("cusip")))) {
                        rows.add(r);
                        added++;
                    }
                }
                if (added > 0) {
                    method = method.equals("ocr") ? "ocr+assisted" : "assisted";
                }
            }
            int assistedCount = rows.size() - res.rows().size();

            IngestService.Summary ing = ingest.indexRows(rows, OfficialStatementParser.FIELD_MAP);
            int quarantined = res.quarantined() + ing.skipped();
            log.info("OS extract {} [{}]: {} pages, {} rows ({} assisted), {} indexed, {} quarantined (conf {})",
                    osPdf.sourceId(), method, rows.size(), assistedCount, ing.indexed(), quarantined, res.confidence());
            return new Summary(osPdf.sourceId(), osPdf.sha256(), pages, method, rows.size(),
                    assistedCount, ing.indexed(), quarantined, res.confidence(), true, null);
        } catch (Exception e) {
            return new Summary(osPdf.sourceId(), osPdf.sha256(), 0, "error", 0, 0, 0, 0, 0.0, false, e.getMessage());
        }
    }
}
