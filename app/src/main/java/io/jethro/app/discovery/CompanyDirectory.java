package io.jethro.app.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves company NAMES in news prose to tickers (ADR-0045/0050 §7) — the deterministic complement to
 * cashtag / exchange-qualified extraction, because headlines say "Nvidia jumps", not "$NVDA" or
 * "(NASDAQ: NVDA)". Loads a curated {@code discovery/company-tickers.csv} directory of distinctive
 * large caps; a match is only a SUGGESTION to track (never added, never traded). The long tail (any
 * company by plain name) is the deferred SLM entity path — this is the no-SLM, no-scraping floor.
 */
public final class CompanyDirectory {

    private static final Logger log = LoggerFactory.getLogger(CompanyDirectory.class);

    /** Name matcher → ticker, ordered longest-name-first so "Goldman Sachs" wins over any "Goldman". */
    private final List<Entry> entries;
    /** All tickers in the directory — the set of US-listed names we can resolve from prose. */
    private final Set<String> tickers;

    private record Entry(Pattern pattern, String ticker, int len) {
    }

    private CompanyDirectory(List<Entry> entries) {
        this.entries = entries;
        Set<String> t = new LinkedHashSet<>();
        for (Entry e : entries) {
            t.add(e.ticker);
        }
        this.tickers = Set.copyOf(t);
    }

    /**
     * Whether this directory knows the ticker. Used by the ADR-0060 promotion gate as a conservative
     * feed-coverage proxy: the directory is the SEC company-tickers list, so a ticker in it is a real
     * US-listed equity that the Alpaca IEX / Finnhub free tiers can stream. Not a live provider-asset
     * confirmation (that is Phase 2) — a deliberately strict floor that never over-admits.
     */
    public boolean covers(String ticker) {
        return ticker != null && tickers.contains(ticker.toUpperCase());
    }

    /** Tickers whose company name appears (as a whole phrase, case-insensitive) in the text. */
    public Set<String> resolve(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (Entry e : entries) {
            if (e.pattern.matcher(text).find()) {
                out.add(e.ticker);
            }
        }
        return out;
    }

    public int size() {
        return entries.size();
    }

    /** Loads the bundled directory; an absent/empty resource yields an empty (no-op) directory. */
    public static CompanyDirectory fromClasspath(String resource) {
        List<Entry> entries = new ArrayList<>();
        try (InputStream in = CompanyDirectory.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("company directory resource {} not found — name resolution disabled", resource);
                return new CompanyDirectory(List.of());
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int comma = line.indexOf(',');
                    if (comma <= 0 || comma == line.length() - 1) {
                        continue;
                    }
                    String ticker = line.substring(0, comma).strip().toUpperCase();
                    for (String name : line.substring(comma + 1).split("\\|")) {
                        String n = name.strip();
                        if (n.length() >= 3) {
                            entries.add(new Entry(wholePhrase(n), ticker, n.length()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("could not load company directory {}: {}", resource, e.toString());
        }
        entries.sort((a, b) -> Integer.compare(b.len, a.len)); // longest phrase first
        return new CompanyDirectory(entries);
    }

    /** Case-insensitive whole-phrase matcher: the name not flanked by letters/digits (so "Intel" does
     *  not fire inside "Intelligence", and punctuated names like "Coca-Cola" still match cleanly). */
    private static Pattern wholePhrase(String name) {
        return Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])");
    }
}
