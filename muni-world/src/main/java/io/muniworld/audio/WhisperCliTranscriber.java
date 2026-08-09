package io.muniworld.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.muniworld.ingest.RawArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The production {@link Transcriber}: a thin wrapper over <b>whisper.cpp</b> running locally on the Pi (the
 * ADR-0014 local-ASR tier). It writes the landed audio bytes to a temp file, invokes the {@code whisper-cli}
 * binary to emit JSON, parses the time-stamped segments, and cleans up. No cloud, no key — private and cheap.
 *
 * <p>Config (provenance in {@code application.properties}): {@code muni.audio.whisper.bin} (default
 * {@code whisper-cli}) and {@code muni.audio.whisper.model} (the .bin model path — <b>required</b> to run;
 * empty means "not configured", so a call fails loudly rather than silently returning nothing). The sandbox
 * has neither the binary nor an audio device, so this never runs here — it runs on the Pi; tests use a stub.
 */
@Component
public final class WhisperCliTranscriber implements Transcriber {

    private static final Logger log = LoggerFactory.getLogger(WhisperCliTranscriber.class);

    private final String bin;
    private final String modelPath;
    private final long timeoutSec;
    private final ObjectMapper mapper = new ObjectMapper();

    public WhisperCliTranscriber(
            @Value("${muni.audio.whisper.bin:whisper-cli}") String bin,
            @Value("${muni.audio.whisper.model:}") String modelPath,
            @Value("${muni.audio.whisper.timeout-sec:600}") long timeoutSec) {
        this.bin = bin;
        this.modelPath = modelPath;
        this.timeoutSec = timeoutSec;
    }

    @Override
    public Transcript transcribe(RawArtifact audio) {
        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalStateException(
                    "muni.audio.whisper.model not set — configure a whisper.cpp model on the Pi to transcribe");
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("muni-asr");
            // .wav extension, not a bare "audio": whisper-cli takes 16 kHz mono PCM WAV, and a name that
            // states the format keeps the failure legible when something upstream hands over another codec.
            Path in = work.resolve("audio.wav");
            Files.write(in, audio.body());
            String outBase = work.resolve("out").toString();

            // whisper-cli -m <model> -f <audio> -oj -of <outBase>  →  <outBase>.json
            Process p = new ProcessBuilder(bin, "-m", modelPath, "-f", in.toString(), "-oj", "-of", outBase)
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("whisper timed out after " + timeoutSec + "s");
            }
            if (p.exitValue() != 0) {
                throw new IOException("whisper exited " + p.exitValue()
                        + ": " + new String(p.getInputStream().readAllBytes()));
            }
            byte[] json = Files.readAllBytes(Path.of(outBase + ".json"));
            List<Transcript.Segment> segs = parseSegments(mapper.readTree(json));
            log.info("transcribed {} ({} bytes) -> {} segments", audio.sourceId(), audio.size(), segs.size());
            // ASR baseline confidence: whisper.cpp doesn't emit a calibrated score here, so we tag a
            // conservative floor (ADR-0014 — a transcript is a lead to verify, never a number).
            return new Transcript(audio.sourceId(), audio.sha256(), segs, 0.5);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Carry the ROOT CAUSE in the message, not just in the cause chain: callers (the API, the UI)
            // surface getMessage(), so a bare "transcription failed for audio-test:tv-bloomberg" told the
            // operator nothing while whisper's own exit code + stderr — the actual diagnosis — were one
            // unread link away. Include the model/binary in play too, since a wrong path is the usual cause.
            throw new RuntimeException("transcription failed for " + audio.sourceId()
                    + " [bin=" + bin + ", model=" + modelPath + "]: " + e.getMessage(), e);
        } finally {
            deleteQuietly(work);
        }
    }

    /** Parse whisper.cpp {@code -oj} output: a {@code transcription[]} of {@code {offsets:{from,to}, text}}. */
    private static List<Transcript.Segment> parseSegments(JsonNode root) {
        List<Transcript.Segment> out = new ArrayList<>();
        JsonNode arr = root.path("transcription");
        for (JsonNode s : arr) {
            long from = s.path("offsets").path("from").asLong(0);
            long to = s.path("offsets").path("to").asLong(0);
            String text = s.path("text").asText("").strip();
            if (!text.isEmpty()) {
                out.add(new Transcript.Segment(from, to, null, text));
            }
        }
        return out;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(pth -> pth.toFile().delete());
        } catch (IOException ignore) {
            // temp cleanup best-effort
        }
    }
}
