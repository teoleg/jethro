package io.muniworld.audio;

import io.muniworld.seed.SeedCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a {@link Transcript} into <b>leads</b> — the ADR-0014 hard line made concrete. A lead is a pointer
 * ("this issuer was named", "a downgrade was mentioned", "a CUSIP-shaped token appeared") anchored to a
 * transcript timestamp, so a human or a hard-source connector (EMMA, ACFR, refdata) can go <b>verify</b> it.
 *
 * <p>Detection is deliberately <b>deterministic</b> and it never extracts a <b>number</b> as a fact: a spoken
 * "5%" or "$300 million" is not captured as a coupon or a size — at most a {@code KEYWORD} lead points a
 * reviewer at that moment. This preserves invariant 7 / ADR-0011 by construction — the audio can surface a
 * lead but can never set a canonical money/terms/risk value.
 */
@Service
public final class TranscriptLeadService {

    /** A CUSIP-shaped token: 9 chars, alphanumeric, ending in a check digit. Rare in speech, flagged if seen. */
    private static final Pattern CUSIP = Pattern.compile("\\b[0-9A-Z]{8}[0-9]\\b");

    /** Muni credit/market lexicon — a hit is a reason to look, not a fact. Lower-cased for matching. */
    private static final List<String> KEYWORDS = List.of(
            "downgrade", "upgrade", "default", "refunding", "advance refunding", "refinance",
            "new issue", "negative outlook", "positive outlook", "credit watch", "watchlist",
            "bankruptcy", "pension", "deficit", "surplus", "ratio", "spread", "bond sale",
            "moody's", "moody", "s&p", "fitch", "kroll", "downgraded", "upgraded");

    private final SeedCatalog seeds;

    public TranscriptLeadService(SeedCatalog seeds) {
        this.seeds = seeds;
    }

    /** The kind of pointer a lead is. Never a number — always a "go verify this" signal. */
    public enum Kind { ISSUER, CUSIP, KEYWORD }

    /**
     * One lead: what matched, the surrounding text for context, and where in the audio (ms) to listen back.
     * {@code confidence} is the transcript's ASR confidence — a reminder this is unverified.
     */
    public record Lead(Kind kind, String match, String context, long atMs, double confidence) {
    }

    public record Leads(String sourceId, List<Lead> leads) {
    }

    public Leads detect(Transcript t) {
        List<Lead> out = new ArrayList<>();
        double conf = t.asrConfidence();
        for (Transcript.Segment seg : t.segments()) {
            String text = seg.text() == null ? "" : seg.text();
            String lower = text.toLowerCase(Locale.ROOT);

            // issuer named — match the catalog's issuer names (case-insensitive substring)
            for (SeedCatalog.Issuer iss : seeds.issuers()) {
                String name = iss.name();
                if (name != null && !name.isBlank() && lower.contains(name.toLowerCase(Locale.ROOT))) {
                    out.add(new Lead(Kind.ISSUER, name, text.strip(), seg.startMs(), conf));
                }
            }
            // muni credit/market keyword — a reason to listen back and verify
            for (String kw : KEYWORDS) {
                if (lower.contains(kw)) {
                    out.add(new Lead(Kind.KEYWORD, kw, text.strip(), seg.startMs(), conf));
                }
            }
            // CUSIP-shaped token (rare in speech, but unambiguous when present)
            Matcher m = CUSIP.matcher(text);
            while (m.find()) {
                out.add(new Lead(Kind.CUSIP, m.group(), text.strip(), seg.startMs(), conf));
            }
        }
        return new Leads(t.sourceId(), out);
    }
}
