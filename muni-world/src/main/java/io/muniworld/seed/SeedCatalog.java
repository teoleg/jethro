package io.muniworld.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the committed seed data (packaged as classpath resources) so the app has real content — the NYC
 * issuer complex — before any live connector runs. This is the offline "bulk_file" load of the curated
 * seeds; the live connectors (ADR-0004) add to it. Read-only.
 */
@Component
public final class SeedCatalog {

    private static final Logger log = LoggerFactory.getLogger(SeedCatalog.class);

    /**
     * A NYC issuer from the seed (ADR-0006 issuer, seed subset). {@code verified} is the provenance status of
     * the {@code disclosureUrl}: {@code "yes"} = the official disclosure page was confirmed; anything else
     * ({@code "partial"}/{@code "no"}) = NOT corroborated, so the UI must not present its URL as a fact.
     */
    public record Issuer(String id, String name, String type, String security, String disclosureUrl,
                         String verified, String notes) {

        /** True only when the disclosure URL was actually confirmed — the UI links only these. */
        public boolean isVerified() {
            return "yes".equalsIgnoreCase(verified);
        }
    }

    private final List<Issuer> issuers;

    public SeedCatalog() {
        this.issuers = loadIssuers();
        log.info("SeedCatalog loaded {} NYC issuers", issuers.size());
    }

    public List<Issuer> issuers() {
        return issuers;
    }

    private List<Issuer> loadIssuers() {
        List<Issuer> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/seeds/nyc-issuers.csv")) {
            if (in == null) {
                log.warn("seed resource /seeds/nyc-issuers.csv not found");
                return out;
            }
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n");
            // header: id,issuer,type,security,disclosure_url,verified,notes
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split(",", 7); // only `notes` may contain commas; limit keeps it intact
                if (f.length >= 7) {
                    // header: id,issuer,type,security,disclosure_url,verified,notes
                    String verified = f[5];
                    // Don't present an unverified URL as fact — null it so the UI shows "source unverified".
                    String url = "yes".equalsIgnoreCase(verified) ? f[4] : null;
                    out.add(new Issuer(f[0], f[1], f[2], f[3], url, verified, f[6]));
                }
            }
        } catch (IOException e) {
            log.warn("failed to load issuer seed: {}", e.toString());
        }
        return out;
    }
}
