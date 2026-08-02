package io.muniworld.audio;

import io.muniworld.ingest.RawArtifact;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture connector lands audio as an immutable {@link RawArtifact} with provenance (ADR-0005), exactly
 * like the HTTP connectors — proven here with a stub {@link AudioCaptureConnector.CaptureSource} so no audio
 * device is needed. And the whisper transcriber <b>fails loudly</b> when no model is configured, rather than
 * silently returning an empty transcript (ADR-0014 — never a silent gap).
 */
class AudioCaptureTest {

    @Test
    void capturesAndLandsAudioWithProvenance() {
        byte[] fakeAudio = "OggS...pretend-opus".getBytes(StandardCharsets.UTF_8);
        AudioCaptureConnector.CaptureSource stub = new AudioCaptureConnector.CaptureSource() {
            @Override public byte[] captureChunk() {
                return fakeAudio;
            }
            @Override public String contentType() {
                return "audio/ogg";
            }
            @Override public String descriptor() {
                return "ffmpeg:pulse:default.monitor";
            }
        };
        AudioCaptureConnector connector = new AudioCaptureConnector("audio:bloomberg-tv", stub);

        List<RawArtifact> landed = connector.fetch();

        assertEquals(1, landed.size());
        RawArtifact a = landed.get(0);
        assertEquals("audio:bloomberg-tv", a.sourceId());
        assertEquals("ffmpeg:pulse:default.monitor", a.url(), "descriptor becomes the provenance url");
        assertEquals("audio/ogg", a.contentType());
        assertEquals(RawArtifact.sha256(fakeAudio), a.sha256(), "content-addressed for dedupe");
        assertTrue(a.size() > 0);
    }

    @Test
    void transcriberFailsLoudlyWithoutAModel() {
        // empty model path = not configured on this host → must throw, never return a silent empty transcript.
        WhisperCliTranscriber asr = new WhisperCliTranscriber("whisper-cli", "", 600);
        RawArtifact audio = RawArtifact.of("audio:test", "ffmpeg:x", "audio/ogg", new byte[] {1, 2, 3});
        assertThrows(IllegalStateException.class, () -> asr.transcribe(audio));
    }
}
