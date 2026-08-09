package io.muniworld.audio;

import io.muniworld.ingest.RawArtifact;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        byte[] fakeAudio = "RIFF....WAVEfmt ".getBytes(StandardCharsets.UTF_8);
        AudioCaptureConnector.CaptureSource stub = new AudioCaptureConnector.CaptureSource() {
            @Override public byte[] captureChunk() {
                return fakeAudio;
            }
            @Override public String contentType() {
                return "audio/wav";
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
        assertEquals("audio/wav", a.contentType());
        assertEquals(RawArtifact.sha256(fakeAudio), a.sha256(), "content-addressed for dedupe");
        assertTrue(a.size() > 0);
    }

    @Test
    void aStreamUrlIsPulledDirectlyAndADeviceIsOpenedAsAFormat() {
        // The owner's actual requirement: pull audio from an ONLINE source — no browser, no sound card,
        // no display. A url: source must go straight to ffmpeg's input with NO -f (the demuxer is detected
        // from the stream) and -vn to drop video; a device source keeps -f <format> -i <name>.
        var stream = new FfmpegCaptureSource("ffmpeg", "url:https://example.com/live.m3u8", 30)
                .buildCommand("/tmp/out.wav");
        assertFalse(stream.contains("-f"), "a URL must not be passed as an ffmpeg input FORMAT");
        assertEquals("https://example.com/live.m3u8", stream.get(stream.indexOf("-i") + 1));
        assertTrue(stream.contains("-vn"), "video track dropped");

        var device = new FfmpegCaptureSource("ffmpeg", "pulse:default.monitor", 30)
                .buildCommand("/tmp/out.wav");
        assertEquals("pulse", device.get(device.indexOf("-f") + 1));
        assertEquals("default.monitor", device.get(device.indexOf("-i") + 1));

        // Both must still produce whisper's only readable format.
        for (var cmd : java.util.List.of(stream, device)) {
            assertEquals("pcm_s16le", cmd.get(cmd.indexOf("-c:a") + 1));
            assertEquals("16000", cmd.get(cmd.indexOf("-ar") + 1));
            assertEquals("1", cmd.get(cmd.indexOf("-ac") + 1));
        }
    }

    @Test
    void aYouTubePageIsNeverHandedToFfmpegUnresolved() {
        // A live stream's CDN URL EXPIRES, so the registry stores the watch page and yt-dlp resolves the
        // current media URL per capture. ffmpeg has no idea what a watch page is: if one ever reached
        // buildCommand it would emit `-f yt -i https://…` and fail with a message naming neither the cause
        // nor the fix. Fail here instead, saying exactly what is wrong.
        var yt = new FfmpegCaptureSource("ffmpeg", "yt:https://www.youtube.com/watch?v=abc123", 30);
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> yt.buildCommand("/tmp/out.wav"));
        assertTrue(e.getMessage().contains("yt-dlp"), "the error must name the resolver: " + e.getMessage());

        // Once resolved, it is an ordinary stream source and takes the url: path.
        var resolved = new FfmpegCaptureSource("ffmpeg", "url:https://cdn.example.com/videoplayback?x=1", 30)
                .buildCommand("/tmp/out.wav");
        assertFalse(resolved.contains("-f"), "a resolved media URL is not an ffmpeg input format");
        assertEquals("https://cdn.example.com/videoplayback?x=1", resolved.get(resolved.indexOf("-i") + 1));
    }

    @Test
    void captureDeclaresTheFormatWhisperCanActuallyRead() {
        // whisper-cli reads 16 kHz mono PCM WAV and bundles no decoder, so the capture stage MUST produce
        // WAV. It once wrote Opus/.ogg, which made every transcription fail while capture looked fine.
        // contentType() is the declared half of that contract; keep it honest about what ffmpeg writes.
        assertEquals("audio/wav", new FfmpegCaptureSource("ffmpeg", "pulse:default.monitor", 5).contentType());
    }

    /** Build a 16-bit mono PCM WAV of `samples`, so the level meter is tested on real bytes. */
    private static byte[] wav(short[] samples) {
        int dataLen = samples.length * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataLen);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
         .putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataLen);
        for (short v : samples) { b.putShort(v); }
        return b.array();
    }

    @Test
    void silenceIsMeasuredAsSilenceNotBlamedOnTheRecogniser() {
        // A PulseAudio monitor with no active stream returns a FULL-LENGTH run of zeros: the capture looks
        // perfect (right size, ffmpeg ok, whisper ok) and contains no sound. That must be reported as a
        // device/routing fact, not as an ASR failure.
        var silent = AudioLevel.of(wav(new short[16000]));
        assertEquals(Boolean.TRUE, silent.get("silent"), "all-zero PCM is digital silence");
        assertEquals("-inf", String.valueOf(silent.get("peakDbfs")));
        assertTrue(String.valueOf(silent.get("note")).contains("DIGITAL SILENCE"));

        // Real signal: half-scale tone → about -6 dBFS peak, and NOT silent.
        short[] tone = new short[16000];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (16384 * Math.sin(2 * Math.PI * 440 * i / 16000.0));
        }
        var loud = AudioLevel.of(wav(tone));
        assertEquals(Boolean.FALSE, loud.get("silent"), "a half-scale tone is audible");
        double peak = Double.parseDouble(String.valueOf(loud.get("peakDbfs")));
        assertTrue(peak > -7.0 && peak < -5.0, "half scale is about -6 dBFS, got " + peak);
    }

    @Test
    void whisperNonSpeechMarkersAreNotTreatedAsHeardSpeech() throws Exception {
        // whisper emits [BLANK_AUDIO] / [MUSIC] / (silence) when it heard NO speech. Carrying those through
        // as segment text puts a fabricated "heard" string in the transcript and in front of the lead
        // scanner — silence must read as silence (zero segments).
        var m = WhisperCliTranscriber.class.getDeclaredMethod("isNonSpeechMarker", String.class);
        m.setAccessible(true);
        for (String marker : new String[] {"[BLANK_AUDIO]", "[MUSIC]", "(silence)", "*laughs*"}) {
            assertTrue((Boolean) m.invoke(null, marker), marker + " is a non-speech marker");
        }
        for (String speech : new String[] {"The City of New York priced a new issue",
                                           "Moody's [sic] downgraded the authority"}) {
            assertFalse((Boolean) m.invoke(null, speech), speech + " is real speech");
        }
    }

    @Test
    void transcriberFailsLoudlyWithoutAModel() {
        // empty model path = not configured on this host → must throw, never return a silent empty transcript.
        WhisperCliTranscriber asr = new WhisperCliTranscriber("whisper-cli", "", 600);
        RawArtifact audio = RawArtifact.of("audio:test", "ffmpeg:x", "audio/wav", new byte[] {1, 2, 3});
        assertThrows(IllegalStateException.class, () -> asr.transcribe(audio));
    }
}
