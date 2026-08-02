package io.muniworld.bond;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The bond watchlist (ADR-0009) — the CUSIPs to keep in the table. Loaded from a host file
 * ({@code muni.watchlist.file}) or the committed classpath default. Pipe-delimited
 * ({@code cusip|issuer|geoFips|notes}); {@code #} lines are comments. This is the input the scheduled /
 * on-demand loader walks: for each CUSIP it discovers the OS on EMMA, extracts terms once, then price
 * refresh keeps the economics current. Read-only at runtime. Empty until real CUSIPs are added — nothing
 * fabricated.
 */
@Component
public final class WatchlistCatalog {

    private static final Logger log = LoggerFactory.getLogger(WatchlistCatalog.class);

    /** One tracked bond: its CUSIP and the tags to attach when its terms are extracted. */
    public record Entry(String cusip, String issuer, String geoFips) {
    }

    private final List<Entry> entries;

    public WatchlistCatalog(@Value("${muni.watchlist.file:}") String file) {
        this.entries = load(file);
        log.info("WatchlistCatalog loaded {} CUSIP(s) from {}",
                entries.size(), (file == null || file.isBlank()) ? "classpath default" : file);
    }

    public List<Entry> entries() {
        return entries;
    }

    private List<Entry> load(String file) {
        String csv = readHostFile(file);
        if (csv == null) {
            csv = readClasspath();
        }
        return csv == null ? List.of() : parse(csv);
    }

    private String readHostFile(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(file);
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            log.warn("could not read watchlist file {}: {}", file, e.toString());
            return null;
        }
    }

    private String readClasspath() {
        try (InputStream in = getClass().getResourceAsStream("/seeds/cusips.csv")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<Entry> parse(String csv) {
        List<Entry> out = new ArrayList<>();
        for (String raw : csv.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("cusip|")) {
                continue;
            }
            String[] f = line.split("\\|", 4);
            if (f.length >= 1 && !f[0].strip().isEmpty()) {
                out.add(new Entry(f[0].strip(),
                        f.length > 1 ? f[1].strip() : null,
                        f.length > 2 ? f[2].strip() : null));
            }
        }
        return out;
    }
}
