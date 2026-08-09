package io.muniworld.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The Pi-side {@link AudioCaptureConnector.CaptureSource}: records a fixed-duration chunk off an audio input
 * with <b>ffmpeg</b>, into Ogg/Opus (small, speech-friendly). The input is whatever the owner's licensed feed
 * plays into — an ALSA line-in / USB capture dongle, or a PulseAudio {@code .monitor} loopback (ADR-0014).
 *
 * <p>This is not a Spring bean: it's constructed by the capture scheduler with the device + duration for a
 * given feed, so one process can record several feeds. It shells out, so it only runs where ffmpeg + an audio
 * device exist (the Pi) — never in the sandbox or tests, which use a stub {@code CaptureSource} instead.
 *
 * <p>Example device strings: {@code alsa:hw:1,0} (USB capture dongle), {@code pulse:default.monitor}
 * (system-audio loopback). The prefix before {@code :} selects the ffmpeg input format {@code -f}.
 */
public final class FfmpegCaptureSource implements AudioCaptureConnector.CaptureSource {

    private static final Logger log = LoggerFactory.getLogger(FfmpegCaptureSource.class);

    private final String ffmpegBin;
    private final String device;     // e.g. "alsa:hw:1,0" or "pulse:default.monitor"
    private final int seconds;

    public FfmpegCaptureSource(String ffmpegBin, String device, int seconds) {
        this.ffmpegBin = ffmpegBin;
        this.device = device;
        this.seconds = seconds;
    }

    @Override
    public byte[] captureChunk() {
        int sep = device.indexOf(':');
        if (sep < 0) {
            throw new IllegalArgumentException("device must be '<format>:<name>', e.g. alsa:hw:1,0; got " + device);
        }
        String format = device.substring(0, sep);   // ffmpeg -f
        String name = device.substring(sep + 1);     // ffmpeg -i
        Path out = null;
        try {
            out = Files.createTempFile("muni-capture", ".wav");
            // -t <seconds> bounded recording, 16 kHz MONO 16-bit PCM WAV.
            //
            // The format is NOT a preference — it is whisper.cpp's input contract: whisper-cli reads 16 kHz
            // mono PCM WAV and nothing else (it bundles no decoder). This used to write Opus in an .ogg
            // container ("tiny artifacts"), which whisper cannot open, so EVERY transcription failed with
            // "transcription failed for audio-…" — the capture half worked, the ASR half could never
            // succeed, and the chunk artifact is transient anyway (transcribed, then deleted), so the size
            // saving bought nothing. ~32 KB/s here: a 300 s chunk is ~9.6 MB, held only until transcription.
            List<String> cmd = List.of(ffmpegBin, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", format, "-i", name, "-t", Integer.toString(seconds),
                    "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", out.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(seconds + 30L, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg capture timed out");
            }
            if (p.exitValue() != 0) {
                throw new IOException("ffmpeg exited " + p.exitValue()
                        + ": " + new String(p.getInputStream().readAllBytes()));
            }
            byte[] bytes = Files.readAllBytes(out);
            log.info("captured {}s from {} ({} bytes)", seconds, device, bytes.length);
            return bytes;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("audio capture failed for " + device, e);
        } finally {
            if (out != null) {
                out.toFile().delete();
            }
        }
    }

    @Override
    public String contentType() {
        return "audio/wav";        // matches what captureChunk() writes (16 kHz mono PCM WAV)
    }

    @Override
    public String descriptor() {
        return "ffmpeg:" + device;
    }
}
