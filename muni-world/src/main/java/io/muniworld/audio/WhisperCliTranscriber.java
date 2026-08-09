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
import java.util.Locale;
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

    /** Where {@code muni-world/scripts/setup-audio-pi.sh} builds whisper.cpp (its {@code WHISPER_DIR}). */
    private static final Path WHISPER_DIR = Path.of(System.getProperty("user.home", "/root"), "whisper.cpp");

    private final String bin;
    private final String modelPath;
    private final String configuredBin;
    private final String configuredModel;
    private final long timeoutSec;
    private final ObjectMapper mapper = new ObjectMapper();

    public WhisperCliTranscriber(
            @Value("${muni.audio.whisper.bin:whisper-cli}") String bin,
            @Value("${muni.audio.whisper.model:}") String modelPath,
            @Value("${muni.audio.whisper.timeout-sec:600}") long timeoutSec) {
        this.configuredBin = bin == null ? "" : bin.strip();
        this.configuredModel = modelPath == null ? "" : modelPath.strip();
        this.bin = resolveBin(this.configuredBin);
        this.modelPath = resolveModel(this.configuredModel);
        this.timeoutSec = timeoutSec;
        if (!this.bin.equals(this.configuredBin) || !this.modelPath.equals(this.configuredModel)) {
            log.info("whisper resolved: bin '{}' -> '{}', model '{}' -> '{}'",
                    configuredBin, this.bin, configuredModel, this.modelPath);
        }
    }

    /**
     * Turn the configured binary into something executable. A bare name (the shipped default
     * {@code whisper-cli}) only works if it is on the PATH of the muni-world PROCESS — which it is not when
     * whisper.cpp was built into {@code ~/whisper.cpp} by our own setup script, the usual case. So: use an
     * explicit path as given, else search PATH, else fall back to the layout that script produces. Resolution
     * is logged; nothing is guessed beyond the location we ourselves created.
     */
    private static String resolveBin(String configured) {
        if (configured.isEmpty()) {
            return configured;
        }
        if (configured.contains("/")) {
            return configured;                       // explicit path — the operator's choice, used verbatim
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, configured))) {
                return configured;                   // on PATH — the bare name works
            }
        }
        for (String candidate : new String[] {configured, "whisper-cli", "main"}) {
            Path p = WHISPER_DIR.resolve("build/bin").resolve(candidate);
            if (Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return configured;                           // unresolved — transcribe() reports it precisely
    }

    /**
     * Turn the configured model into a real {@code .bin} file. Operators naturally write the model NAME
     * ({@code tiny.en}, {@code base.en}) because that is what the setup script's WHISPER_MODEL_NAME takes,
     * but whisper-cli wants the ggml file. Map a bare name onto the file the setup script downloaded.
     */
    private static String resolveModel(String configured) {
        if (configured.isEmpty() || Files.isRegularFile(Path.of(configured))) {
            return configured;                       // empty (= not configured) or already a real file
        }
        if (!configured.contains("/")) {
            Path models = WHISPER_DIR.resolve("models");
            for (Path p : new Path[] {models.resolve("ggml-" + configured + ".bin"),
                                      models.resolve(configured),
                                      models.resolve(configured + ".bin")}) {
                if (Files.isRegularFile(p)) {
                    return p.toString();
                }
            }
        }
        return configured;                           // unresolved — reported, never silently substituted
    }

    /** Null when transcription can run; otherwise the single reason it cannot, naming the value in play. */
    public String blocker() {
        if (configuredModel.isEmpty()) {
            return "no whisper model configured (MUNI_WHISPER_MODEL) — run `svc.sh setup tv`";
        }
        if (!Files.isRegularFile(Path.of(modelPath))) {
            return "whisper model file not found: '" + configuredModel + "'"
                   + (modelPath.equals(configuredModel) ? "" : " (tried '" + modelPath + "')")
                   + " — MUNI_WHISPER_MODEL must be the PATH to a ggml-*.bin file";
        }
        if (bin.contains("/") ? !Files.isExecutable(Path.of(bin)) : !onPath(bin)) {
            return "whisper binary not found: '" + configuredBin + "'"
                   + (bin.equals(configuredBin) ? "" : " (tried '" + bin + "')")
                   + " — set MUNI_WHISPER_BIN to the whisper-cli path";
        }
        return null;
    }

    private static boolean onPath(String name) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
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
            if (!text.isEmpty() && !isNonSpeechMarker(text)) {
                out.add(new Transcript.Segment(from, to, null, text));
            }
        }
        return out;
    }

    /**
     * True for whisper's own non-speech annotations — {@code [BLANK_AUDIO]}, {@code [MUSIC]},
     * {@code (silence)} and friends. These are the recogniser SAYING IT HEARD NOTHING, so carrying them
     * through as segment text would put a fabricated "heard" string in the transcript and in front of the
     * lead scanner. Dropping them makes silence read as silence: zero segments, which the caller reports
     * as "captured N bytes but recognised no speech".
     */
    private static boolean isNonSpeechMarker(String text) {
        String t = text.strip();
        int len = t.length();
        boolean bracketed = len >= 2
                && ((t.charAt(0) == '[' && t.charAt(len - 1) == ']')
                    || (t.charAt(0) == '(' && t.charAt(len - 1) == ')')
                    || (t.charAt(0) == '*' && t.charAt(len - 1) == '*'));
        if (!bracketed) {
            return false;
        }
        // Only a WHOLE-segment annotation counts: real speech can contain a bracketed aside, but a segment
        // that is nothing but one bracket pair is whisper's marker, never words that were spoken.
        String inner = t.substring(1, len - 1).toLowerCase(Locale.ROOT);
        return inner.indexOf('[') < 0 && inner.indexOf('(') < 0;
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
