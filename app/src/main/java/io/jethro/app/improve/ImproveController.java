package io.jethro.app.improve;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Read-only monitoring surface for the continuous-improvement loop (ADR-0063). Serves the per-cycle
 * heartbeat that {@code scripts/score-change.py} writes to {@code reports/run-status.json} — the feed
 * the "Improve" UI page polls to show one line per run (time, PnL, exposure, % change, decision).
 *
 * <p>Every number in that file is produced by the deterministic scorer, never by a model
 * (invariant 7 / ADR-0016). This controller only relays the file as-is; it computes nothing. The path
 * is resolved relative to the app's working directory (the repo root on the box), overridable via
 * {@code jethro.improve.status-path}.
 */
@RestController
public final class ImproveController {

    private final Path statusPath;

    public ImproveController(@Value("${jethro.improve.status-path:reports/run-status.json}") String path) {
        this.statusPath = Path.of(path);
    }

    /** The run-status feed as-is (JSON array, newest first). Empty array until the loop has run once. */
    @GetMapping(value = "/api/improve/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> status() {
        try {
            if (Files.isReadable(statusPath)) {
                return ResponseEntity.ok(Files.readString(statusPath, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // best-effort monitoring surface — fall through to an empty feed rather than erroring the UI
        }
        return ResponseEntity.ok("[]");
    }
}
