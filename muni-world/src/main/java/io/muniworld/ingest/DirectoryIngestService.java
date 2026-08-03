package io.muniworld.ingest;

import io.muniworld.extract.OfficialStatementExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Auto-loads Official Statement PDFs from a watched folder (ADR-0004/0015). Drop OS PDFs into the inbox
 * directory and the scheduler picks them up, extracts their terms, and indexes the bonds — no manual upload.
 * A processed file is moved to {@code <inbox>/processed/} so it isn't re-read. No extra rules: every {@code
 * .pdf} in the folder is loaded.
 *
 * <p>Config: {@code muni.os.inbox.dir} (default {@code os-inbox}, created on startup) and
 * {@code muni.os.inbox.scan-ms} (default 30s).
 */
@Service
public final class DirectoryIngestService {

    private static final Logger log = LoggerFactory.getLogger(DirectoryIngestService.class);

    private final OfficialStatementExtractor extractor;
    private final Path inbox;
    private final Path processed;

    public DirectoryIngestService(OfficialStatementExtractor extractor,
                                  @Value("${muni.os.inbox.dir:os-inbox}") String dir) {
        this.extractor = extractor;
        this.inbox = Path.of(dir);
        this.processed = inbox.resolve("processed");
        try {
            Files.createDirectories(processed);
            log.info("OS inbox watching {} (drop PDFs here; processed files move to {})",
                    inbox.toAbsolutePath(), processed);
        } catch (IOException e) {
            log.warn("could not create OS inbox {}: {}", inbox, e.toString());
        }
    }

    /** The scheduled sweep — loads every new PDF in the inbox. */
    @Scheduled(fixedDelayString = "${muni.os.inbox.scan-ms:30000}")
    public void scan() {
        scanNow();
    }

    /** Load every {@code .pdf} in the inbox now; returns one summary per file. Also the manual-trigger path. */
    public List<OfficialStatementExtractor.Summary> scanNow() {
        List<OfficialStatementExtractor.Summary> out = new ArrayList<>();
        if (!Files.isDirectory(inbox)) {
            return out;
        }
        try (Stream<Path> files = Files.list(inbox)) {
            List<Path> pdfs = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted()
                    .toList();
            for (Path pdf : pdfs) {
                out.add(loadOne(pdf));
            }
        } catch (IOException e) {
            log.warn("OS inbox scan failed: {}", e.toString());
        }
        return out;
    }

    private OfficialStatementExtractor.Summary loadOne(Path pdf) {
        String name = pdf.getFileName().toString();
        try {
            byte[] bytes = Files.readAllBytes(pdf);
            RawArtifact art = RawArtifact.of("file:" + name, name, "application/pdf", bytes);
            OfficialStatementExtractor.Summary s = extractor.extract(art, null, null, "");
            log.info("loaded {}: {} bonds indexed, {} quarantined (conf {})",
                    name, s.indexed(), s.quarantined(), s.confidence());
            move(pdf);   // done — get it out of the inbox so it isn't re-processed
            return s;
        } catch (IOException e) {
            log.warn("failed to load {}: {}", name, e.toString());
            return new OfficialStatementExtractor.Summary("file:" + name, null, 0, "read-failed",
                    0, 0, 0, 0, 0.0, false, e.getMessage());
        }
    }

    private void move(Path pdf) {
        try {
            Path dest = processed.resolve(pdf.getFileName());
            if (Files.exists(dest)) {
                dest = processed.resolve(System.currentTimeMillis() + "-" + pdf.getFileName());
            }
            Files.move(pdf, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("could not move processed file {}: {}", pdf, e.toString());
        }
    }
}
