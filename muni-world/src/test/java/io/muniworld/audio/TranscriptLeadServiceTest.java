package io.muniworld.audio;

import io.muniworld.seed.SeedCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0014 guardrail, made testable: a transcript yields <b>leads to verify</b> (issuer named, muni
 * keyword, CUSIP-shaped token) — each anchored to a timestamp — and it <b>never</b> yields a number as a
 * fact. A spoken "five percent" surfaces only as context on a keyword lead, never as a coupon/size value.
 */
class TranscriptLeadServiceTest {

    // Supply a real issuer inline — no committed seed rows (the seed file ships empty).
    private final TranscriptLeadService svc = new TranscriptLeadService(new SeedCatalog(List.of(
            new SeedCatalog.Issuer("nyc", "City of New York", "city", "GO", null, "no", ""))));

    private static Transcript transcript(Transcript.Segment... segs) {
        return new Transcript("audio:test", "deadbeef", List.of(segs), 0.5);
    }

    @Test
    void surfacesIssuerKeywordAndCusipLeads() {
        Transcript t = transcript(
                new Transcript.Segment(1000, 4000, null,
                        "The City of New York priced a new issue today near five percent."),
                new Transcript.Segment(5000, 8000, null,
                        "Moody's downgraded the water authority citing pension pressure."),
                new Transcript.Segment(8000, 9000, null, "The bond CUSIP 649122AC9 traded actively."));

        TranscriptLeadService.Leads leads = svc.detect(t);
        List<TranscriptLeadService.Lead> ls = leads.leads();

        assertTrue(has(ls, TranscriptLeadService.Kind.ISSUER, "City of New York"), "issuer named");
        assertTrue(has(ls, TranscriptLeadService.Kind.KEYWORD, "new issue"), "new-issue keyword");
        assertTrue(has(ls, TranscriptLeadService.Kind.KEYWORD, "downgraded"), "downgrade keyword");
        assertTrue(has(ls, TranscriptLeadService.Kind.KEYWORD, "pension"), "pension keyword");
        assertTrue(has(ls, TranscriptLeadService.Kind.CUSIP, "649122AC9"), "CUSIP-shaped token");

        // every lead is anchored and carries the transcript's (un)confidence
        for (TranscriptLeadService.Lead l : ls) {
            assertTrue(l.atMs() >= 0, "lead anchored to an audio offset");
            assertEquals(0.5, l.confidence(), 1e-9, "carries ASR confidence — unverified");
        }
    }

    @Test
    void neverExtractsANumberAsAFact() {
        Transcript t = transcript(new Transcript.Segment(0, 3000, null,
                "The five percent coupon bond priced at 108 with a 300 million dollar par amount."));

        List<TranscriptLeadService.Lead> ls = svc.detect(t).leads();

        // no lead's MATCH is a number/price/size — the spoken figures live only in context, never promoted.
        assertTrue(ls.stream().map(TranscriptLeadService.Lead::match)
                .noneMatch(m -> m.matches(".*\\d.*") || m.contains("percent") || m.contains("million")),
                "a transcript is a lead source, never a source of numbers (invariant 7 / ADR-0011)");
    }

    private static boolean has(List<TranscriptLeadService.Lead> ls, TranscriptLeadService.Kind kind, String match) {
        return ls.stream().anyMatch(l -> l.kind() == kind && l.match().equalsIgnoreCase(match));
    }
}
