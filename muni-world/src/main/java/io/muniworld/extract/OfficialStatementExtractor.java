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
    private final String inboxDir;
    private final io.muniworld.bond.SecurityRepository securities;

    public OfficialStatementExtractor(
            IngestService ingest,
            VerifyingAssistedExtractor assisted,
            @Value("${muni.ocr.enabled:false}") boolean ocrEnabled,
            @Value("${muni.ocr.bin:tesseract}") String tesseractBin,
            @Value("${muni.ocr.dpi:300}") int dpi,
            @Value("${muni.extract.assisted-threshold:0.5}") double assistedThreshold,
            @Value("${muni.os.inbox.dir:os-inbox}") String inboxDir,
            io.muniworld.bond.SecurityRepository securities) {
        this.inboxDir = inboxDir;
        this.securities = securities;
        this.ingest = ingest;
        this.assisted = assisted;
        this.ocrEnabled = ocrEnabled;
        this.ocr = new OcrText(tesseractBin, dpi);
        this.assistedThreshold = assistedThreshold;
    }

    /** The observable extraction record: what was read, how, indexed, quarantined, and parse confidence. */
    public record Summary(String sourceId, String sha256, int pages, String method, int rowsParsed,
                          int assisted, int indexed, int quarantined, double confidence,
                          boolean ok, String error,
                          Map<String, Object> probe, String keptAt) {
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
            // Record WHICH document these bonds came from (ADR-0005 provenance). Beyond provenance this
            // is the signal the coverage plan needs: "this issuer's OS has been read" is a fact about the
            // document, and cannot be inferred from call dates — plenty of serial bonds are genuinely
            // non-callable and would otherwise look uncovered forever.
            if (ing.indexed() > 0) {
                List<String> landed = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    Object c = r.get("cusip");
                    if (c != null) {
                        landed.add(String.valueOf(c));
                    }
                }
                securities.markSourced(landed, osPdf.sourceId());
            }
            int quarantined = res.quarantined() + ing.skipped();
            // 8 placeholders, 8 args — `pages` used to be missing, which shifted every value one slot left
            // and printed a literal "(conf {})". A log that misreports the extraction is worse than no log:
            // a failed parse (0 rows, 1 quarantined) read as "1 indexed" (see 2026-08-08 upload).
            log.info("OS extract {} [{}]: {} pages, {} rows ({} assisted), {} indexed, {} quarantined (conf {})",
                    osPdf.sourceId(), method, pages, rows.size(), assistedCount, ing.indexed(), quarantined,
                    res.confidence());
            Map<String, Object> probe = null;
            String keptAt = null;
            if (ing.indexed() == 0) {
                // Nothing landed. A failed extraction must carry its own evidence — the upload's bytes may
                // live on ANOTHER machine (a browser upload), so telling the owner to re-POST the file for
                // a probe is a dead end. Probe NOW, return the evidence in the response, and KEEP the PDF
                // on this host so the parser gap can be fixed against the real document later.
                probe = OfficialStatementProbe.probe(text, pages, ocrEnabled);
                keptAt = keepFailed(osPdf);
                log.warn("OS extract {}: NO bonds indexed — {} chars over {} pages{}, {} quarantined. "
                                + "Probe evidence attached to the response; document kept at {}.",
                        osPdf.sourceId(), text.length(), pages,
                        OcrText.isSparse(text, pages) ? " (under 100 chars/page — likely image-only)" : "",
                        quarantined, keptAt == null ? "(could not save)" : keptAt);
            }
            return new Summary(osPdf.sourceId(), osPdf.sha256(), pages, method, rows.size(),
                    assistedCount, ing.indexed(), quarantined, res.confidence(), true, null, probe, keptAt);
        } catch (Exception e) {
            return new Summary(osPdf.sourceId(), osPdf.sha256(), 0, "error", 0, 0, 0, 0, 0.0, false,
                    e.getMessage(), null, null);
        }
    }

    /** Keep a zero-yield document under {@code os-inbox/failed/} for a later parser fix. Best-effort. */
    private String keepFailed(RawArtifact osPdf) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(inboxDir, "failed");
            java.nio.file.Files.createDirectories(dir);
            String name = java.nio.file.Path.of(
                    osPdf.sourceId().replaceFirst("^(upload:|file:)", "")).getFileName().toString();
            java.nio.file.Path out = dir.resolve(name.isBlank() ? osPdf.sha256() + ".pdf" : name);
            java.nio.file.Files.write(out, osPdf.body());
            return out.toString();
        } catch (Exception e) {
            log.warn("could not keep failed OS: {}", e.toString());
            return null;
        }
    }
}
