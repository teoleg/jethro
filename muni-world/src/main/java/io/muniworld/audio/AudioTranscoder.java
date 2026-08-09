package io.muniworld.audio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Converts arbitrary browser-recorded audio into the ONE format whisper.cpp reads: 16 kHz mono 16-bit PCM
 * WAV. A browser's {@code MediaRecorder} emits WebM/Opus (or MP4/AAC on some platforms) — whisper bundles no
 * decoder, so handing it those bytes fails exactly the way the capture path did before it was fixed to write
 * WAV. This is the boundary where that is guaranteed, once, for every browser-supplied clip.
 *
 * <p>Shells out to ffmpeg, which is already required for capture. A clip that ffmpeg cannot read fails
 * loudly with ffmpeg's own message — never a silent empty transcript.
 */
@Component
public final class AudioTranscoder {

    private final String ffmpegBin;
    private final int timeoutSec;

    public AudioTranscoder(@Value("${muni.audio.ffmpeg.bin:ffmpeg}") String ffmpegBin,
                           @Value("${muni.audio.transcode.timeout-sec:60}") int timeoutSec) {
        this.ffmpegBin = ffmpegBin;
        this.timeoutSec = timeoutSec;
    }

    /** Decode {@code input} (any container ffmpeg reads) to 16 kHz mono PCM WAV bytes. */
    public byte[] toWav16kMono(byte[] input) {
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("muni-clip-in", ".bin");
            out = Files.createTempFile("muni-clip-out", ".wav");
            Files.write(in, input);
            // -vn: a shared browser tab can carry a video track; whisper wants audio only.
            List<String> cmd = List.of(ffmpegBin, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", in.toString(), "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                    out.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] stdout = p.getInputStream().readAllBytes();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("transcode timed out after " + timeoutSec + "s");
            }
            if (p.exitValue() != 0) {
                throw new IOException("ffmpeg exited " + p.exitValue() + ": " + new String(stdout).strip());
            }
            byte[] wav = Files.readAllBytes(out);
            if (wav.length == 0) {
                throw new IOException("transcode produced no audio — the clip carried no audio track");
            }
            return wav;
        } catch (IOException e) {
            throw new RuntimeException("audio transcode failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("audio transcode interrupted", e);
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // best-effort cleanup of a temp file
            }
        }
    }
}
