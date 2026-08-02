package io.muniworld.audio;

import java.util.List;

/**
 * The output of the ADR-0014 transcription stage — the audio analogue of an ADR-0010 document extraction.
 * Time-stamped text {@link Segment}s lifted from a landed audio {@link io.muniworld.ingest.RawArtifact} by a
 * local ASR (whisper.cpp). {@code asrConfidence} is a baseline 0..1 reminder that this is ASR-derived, not
 * ground truth: a transcript is a <b>lead to verify</b>, never a source of canonical numbers (ADR-0014).
 *
 * @param sourceId   the audio source this came from (e.g. {@code audio:bloomberg-tv})
 * @param artifactSha256 provenance link back to the immutable audio artifact (ADR-0005)
 * @param segments   the ordered, time-stamped text segments
 * @param asrConfidence baseline recogniser confidence (0..1); low → treat with extra suspicion
 */
public record Transcript(String sourceId, String artifactSha256, List<Segment> segments, double asrConfidence) {

    /** One recognised span of speech: [{@code startMs}, {@code endMs}] with optional speaker turn + text. */
    public record Segment(long startMs, long endMs, String speaker, String text) {
    }

    /** The whole transcript as one string — for keyword/lead scanning and for a Claude triage window. */
    public String fullText() {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            if (s.text() != null && !s.text().isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(s.text().strip());
            }
        }
        return sb.toString();
    }
}
