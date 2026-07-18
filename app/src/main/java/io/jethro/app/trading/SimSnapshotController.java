package io.jethro.app.trading;

import io.jethro.domain.Instrument;
import io.jethro.refdata.RefDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Captures a real historical OHLCV snapshot for the history-anchored sim (ADR-0032): pulls each
 * instrument's daily series from Yahoo (dev/demo only, ADR-0023) and writes the snapshot the
 * {@code sim-engine=historical} engine reads — turning the labelled synthetic seed into genuine
 * dynamics. A user-triggered ops action (the fetch is blocking and hits an external, ToS-limited
 * source), so it's a POST you run deliberately, not on a schedule.
 */
@RestController
public final class SimSnapshotController {

    private static final Logger log = LoggerFactory.getLogger(SimSnapshotController.class);
    private static final Set<String> PRICE_QUOTED = Set.of("EQUITY", "FUTURE", "FX");
    private static final String DEFAULT_FILE = "jethro-history.json";

    public record SnapshotStatus(String path, boolean exists, boolean engineActive, String hint) {
    }

    private final TradingCoreProperties properties;
    private final ObjectProvider<RefDataRepository> refData;

    public SimSnapshotController(TradingCoreProperties properties, ObjectProvider<RefDataRepository> refData) {
        this.properties = properties;
        this.refData = refData;
    }

    @GetMapping("/api/sim/snapshot")
    public SnapshotStatus status() {
        Path path = outputPath();
        boolean exists = Files.exists(path);
        boolean active = properties.historicalSimEngine();
        String hint = !exists
                ? "POST /api/sim/snapshot/fetch to capture real history from Yahoo"
                : active ? "historical engine active on this snapshot"
                : "snapshot present — set jethro.trading.sim-engine=historical and sim-snapshot-path, then restart";
        return new SnapshotStatus(path.toAbsolutePath().toString(), exists, active, hint);
    }

    @PostMapping("/api/sim/snapshot/fetch")
    public SnapshotCapture.Result fetch() {
        RefDataRepository rd = refData.getIfAvailable();
        if (rd == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "reference data is unavailable — cannot map instruments to Yahoo symbols");
        }
        Map<String, String> symbols = yahooSymbols(rd);
        if (symbols.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "no 'yahoo' symbology found for the price-quoted universe (invariant 2)");
        }
        var client = new YahooHistoryClient(Duration.ofSeconds(15), "5y");
        var capture = new SnapshotCapture(client::fetch);
        Path out = outputPath();
        log.warn("SNAPSHOT CAPTURE: pulling 5y daily history for {} instruments from Yahoo → {} "
                + "(dev/demo only, ADR-0023).", symbols.size(), out.toAbsolutePath());
        try {
            SnapshotCapture.Result result = capture.capture(symbols, out);
            log.warn("SNAPSHOT CAPTURE done: {} instruments × {} common days → {}. Set "
                            + "jethro.trading.sim-engine=historical and sim-snapshot-path={} then restart.",
                    result.instruments(), result.days(), result.path(), result.path());
            return result;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "snapshot capture failed: " + e.getMessage());
        }
    }

    private Path outputPath() {
        String configured = properties.simSnapshotPathOrNull();
        return Path.of(configured != null ? configured : DEFAULT_FILE);
    }

    /** internal instrumentId → Yahoo symbol for the price-quoted names (invariant 2). */
    private static Map<String, String> yahooSymbols(RefDataRepository rd) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Instrument i : rd.findAllInstruments()) {
            String symbol = i.symbology().get("yahoo");
            if (symbol != null && PRICE_QUOTED.contains(i.assetClass().name())) {
                map.put(i.id().value(), symbol);
            }
        }
        return map;
    }
}
