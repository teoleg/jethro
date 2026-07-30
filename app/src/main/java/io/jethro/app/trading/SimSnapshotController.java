package io.jethro.app.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * Captures a real historical OHLCV snapshot for the history-anchored sim (ADR-0032): pulls each
 * instrument's daily series from Tiingo (dev/demo only, ADR-0023 — Yahoo history became
 * subscription-only) and writes the snapshot the {@code sim-engine=historical} engine reads — turning
 * the labelled synthetic seed into genuine dynamics. A user-triggered ops action (the fetch is blocking
 * and hits an external, rate-limited source), so it's a POST you run deliberately, not on a schedule.
 * Instruments come from the DYNAMIC refdata master and their proxy is derived by
 * {@link HistorySymbols#proxyFor} (SPY→ES etc.); names without a free proxy (Treasury futures, swaps)
 * are simply absent (invariant 2 — internal ids, never provider ones).
 */
@RestController
public final class SimSnapshotController {

    private static final Logger log = LoggerFactory.getLogger(SimSnapshotController.class);
    private static final String DEFAULT_FILE = "jethro-history.json";
    private static final String SOURCE = "tiingo";

    public record SnapshotStatus(String path, boolean exists, boolean engineActive, String hint) {
    }

    private final TradingCoreProperties properties;
    private final io.jethro.trading.riskpnl.InstrumentRefSource refs;
    private final String tiingoToken;
    private final int years;

    public SimSnapshotController(TradingCoreProperties properties,
                                 io.jethro.trading.riskpnl.InstrumentRefSource refs,
                                 @Value("${jethro.hedge.tiingo-token:}") String tiingoToken,
                                 @Value("${jethro.hedge.history-range-years:5}") int years) {
        this.properties = properties;
        this.refs = refs;
        this.tiingoToken = tiingoToken;
        this.years = years;
    }

    @GetMapping("/api/sim/snapshot")
    public SnapshotStatus status() {
        Path path = outputPath();
        boolean exists = Files.exists(path);
        boolean active = properties.historicalSimEngine();
        String hint = !exists
                ? "POST /api/sim/snapshot/fetch to capture real history from Tiingo (needs TIINGO_API_TOKEN)"
                : active ? "historical engine active on this snapshot"
                : "snapshot present — set jethro.trading.sim-engine=historical and sim-snapshot-path, then restart";
        return new SnapshotStatus(path.toAbsolutePath().toString(), exists, active, hint);
    }

    @PostMapping("/api/sim/snapshot/fetch")
    public SnapshotCapture.Result fetch() {
        // Snapshot the sim's own universe, restricted to names with a free Tiingo proxy (SPY→ES etc.).
        Map<String, String> symbols = proxySymbols();
        if (symbols.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "no history proxy for the universe (HistorySymbols.proxyFor) — nothing to snapshot");
        }
        var client = new TiingoHistoryClient(tiingoToken, Duration.ofSeconds(15), years);
        if (!client.configured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "no Tiingo token — set TIINGO_API_TOKEN (jethro.hedge.tiingo-token) before capturing a snapshot");
        }
        var capture = new SnapshotCapture(client::fetch, SOURCE);
        Path out = outputPath();
        log.warn("SNAPSHOT CAPTURE: pulling {}y daily history for {} instruments from Tiingo → {} "
                + "(dev/demo only, ADR-0023).", years, symbols.size(), out.toAbsolutePath());
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

    /** internal instrumentId → Tiingo proxy symbol for the sim universe (invariant 2), proxy-gated. */
    private Map<String, String> proxySymbols() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String id : refs.instrumentIds()) { // the DYNAMIC refdata master (invariant 9), not a list
            String assetClass = refs.find(id)
                    .map(io.jethro.trading.riskpnl.InstrumentRef::assetClass).orElse(null);
            String sym = HistorySymbols.proxyFor(id, assetClass);
            if (sym != null) {
                map.put(id, sym);
            }
        }
        return map;
    }
}
