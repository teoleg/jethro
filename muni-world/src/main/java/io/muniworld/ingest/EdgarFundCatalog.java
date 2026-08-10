package io.muniworld.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The EDGAR fund registry (ADR-0016) — which funds' N-PORT holdings feed the CUSIP universe. Read-only
 * config shipped in the jar ({@code /seeds/edgar-funds.csv}, {@code cik|expect_name|label|enabled}), the
 * same model as the audio-source registry: edit the CSV and rebuild, no writable host copy.
 *
 * <p>{@code expectName} is the safety gate a hand-typed CIK needs: the connector matches it against the
 * registrant name EDGAR itself reports, and refuses the fund on a mismatch — so a wrong CIK fails loudly
 * instead of silently pouring another fund's holdings into the master.
 */
@Component
public final class EdgarFundCatalog {

    private static final Logger log = LoggerFactory.getLogger(EdgarFundCatalog.class);

    /** One registered fund: {@code cik} is digits only; {@code expectName} gates ingestion. */
    public record Fund(String cik, String expectName, String label, boolean enabled) {
    }

    private final List<Fund> funds;

    public EdgarFundCatalog() {
        this.funds = load();
        log.info("EdgarFundCatalog: {} fund(s), {} enabled", funds.size(), enabled().size());
    }

    public List<Fund> all() {
        return funds;
    }

    public List<Fund> enabled() {
        return funds.stream().filter(Fund::enabled).toList();
    }

    private static List<Fund> load() {
        try (InputStream in = EdgarFundCatalog.class.getResourceAsStream("/seeds/edgar-funds.csv")) {
            return in == null ? List.of() : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("could not read the shipped EDGAR fund registry: {}", e.toString());
            return List.of();
        }
    }

    static List<Fund> parse(String csv) {
        List<Fund> out = new ArrayList<>();
        for (String raw : csv.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("cik|")) {
                continue;
            }
            String[] f = line.split("\\|", 4);
            if (f.length < 4 || !f[0].strip().matches("\\d{1,10}")) {
                continue;   // a malformed row is dropped, not guessed at
            }
            out.add(new Fund(f[0].strip(), f[1].strip(), f[2].strip(),
                    "true".equalsIgnoreCase(f[3].strip())));
        }
        return out;
    }
}
