package io.muniworld.audio;

import io.muniworld.ingest.RawArtifact;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void theStreamIsReadStraightOffTheNetworkInWhispersFormat() {
        // The owner's requirement: pull audio from an online source — no browser, no sound card, no
        // display. The URL goes straight to ffmpeg's input with NO -f (the demuxer is detected from the
        // stream itself) and -vn to drop video.
        var cmd = new FfmpegCaptureSource("ffmpeg", "url:https://example.com/live.m3u8", 30)
                .buildCommand("https://example.com/live.m3u8", "/tmp/out.wav");

        assertFalse(cmd.contains("-f"), "a URL must not be passed as an ffmpeg input FORMAT");
        assertEquals("https://example.com/live.m3u8", cmd.get(cmd.indexOf("-i") + 1));
        assertTrue(cmd.contains("-vn"), "video track dropped");
        assertEquals("pcm_s16le", cmd.get(cmd.indexOf("-c:a") + 1));
        assertEquals("16000", cmd.get(cmd.indexOf("-ar") + 1));
        assertEquals("1", cmd.get(cmd.indexOf("-ac") + 1));
    }

    @Test
    void onlyStreamSourcesAreAccepted() {
        // Host-device sources (pulse:/alsa:) are GONE: they needed a machine already playing the channel
        // and recorded a full-length run of zeros when it wasn't. Anything but yt:/url: must fail by name,
        // not be silently handed to ffmpeg as an input format.
        var bad = new FfmpegCaptureSource("ffmpeg", "pulse:default.monitor", 30);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, bad::captureChunk);
        assertTrue(e.getMessage().contains("yt:") && e.getMessage().contains("url:"),
                "the error must name the two valid forms: " + e.getMessage());

        assertTrue(FfmpegCaptureSource.usesYtdlp("yt:https://www.youtube.com/watch?v=abc"),
                "a yt: source is resolved by yt-dlp before ffmpeg runs");
        assertFalse(FfmpegCaptureSource.usesYtdlp("url:https://example.com/live.m3u8"),
                "a direct stream needs no resolver");
    }

    @Test
    void aFailingFeedBacksOffInsteadOfRetryingEverySecond() {
        // The loop's gap is ~1s because a real capture BLOCKS for its chunk and paces the loop itself. A
        // failure that returns immediately (missing yt-dlp, dead URL) does not pace anything, so without a
        // backoff the same warning printed once per second forever and buried the rest of the log.
        var b = new AudioCaptureScheduler.Backoff();
        long t0 = 1_000_000L;
        assertTrue(b.due(t0), "a feed with no failures is due immediately");

        b.fail(t0);
        assertEquals(30_000L, b.waitMs(), "first failure waits 30s");
        assertFalse(b.due(t0 + 29_999L), "not retried before the backoff elapses");
        assertTrue(b.due(t0 + 30_000L), "retried once it does");

        b.fail(t0 + 30_000L);
        assertEquals(60_000L, b.waitMs(), "backoff doubles");
        for (int i = 0; i < 10; i++) {
            b.fail(t0);
        }
        assertEquals(300_000L, b.waitMs(), "capped at 5 minutes — a broken feed is retried, never abandoned");

        b.reset();
        assertEquals(0L, b.waitMs(), "a success clears the backoff");
        assertEquals(0, b.failures());
        assertTrue(b.due(t0));
    }

    @Test
    void anAncientYtdlpIsReportedAsStaleRatherThanReady() {
        // An INSTALLED yt-dlp can still be useless: the Pi had apt's 2023.03.04, which failed every
        // extraction with "No video formats found" while the status page happily said yt-dlp was ready.
        // Staleness is measured against TODAY so the rule cannot rot into a stale constant itself.
        java.time.LocalDate today = java.time.LocalDate.now();
        String ancient = today.minusYears(3).toString().replace('-', '.');
        assertTrue(String.valueOf(FfmpegCaptureSource.staleReason(ancient)).contains("over a year old"),
                "a three-year-old yt-dlp must be reported as stale");

        // Current, and just inside the threshold: not a blocker.
        assertNull(FfmpegCaptureSource.staleReason(today.toString().replace('-', '.')));
        assertNull(FfmpegCaptureSource.staleReason(today.minusMonths(11).toString().replace('-', '.')));

        // An unparseable version is never a blocker — do not gate on a string we do not understand.
        assertNull(FfmpegCaptureSource.staleReason("nightly"));
        assertNull(FfmpegCaptureSource.staleReason(""));
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
