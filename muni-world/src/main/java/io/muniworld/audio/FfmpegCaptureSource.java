package io.muniworld.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The Pi-side {@link AudioCaptureConnector.CaptureSource}: records a fixed-duration chunk with <b>ffmpeg</b>
 * as 16 kHz mono PCM WAV — whisper.cpp's only readable input (ADR-0014).
 *
 * <p>Three source kinds, one pipeline:
 * <ul>
 *   <li>{@code yt:<page>} — a YouTube watch/live page. yt-dlp resolves the CURRENT media URL per capture
 *       (live CDN URLs expire, so the page is what the registry stores), then it is read as a stream.</li>
 *   <li>{@code url:<stream>} — a direct HLS/DASH/Icecast URL, read straight off the network. No browser, no
 *       sound card, no display: this is the headless path and the default.</li>
 *   <li>{@code <format>:<name>} — a host audio input ({@code pulse:default.monitor}, {@code alsa:hw:1,0}),
 *       the fallback for recording what THIS machine plays.</li>
 * </ul>
 *
 * <p>This is not a Spring bean: it's constructed by the capture scheduler with the source + duration for a
 * given feed, so one process can record several feeds. It shells out, so it only runs where ffmpeg exists
 * (the Pi) — never in the sandbox or tests, which use a stub {@code CaptureSource} instead.
 */
public final class FfmpegCaptureSource implements AudioCaptureConnector.CaptureSource {

    private static final Logger log = LoggerFactory.getLogger(FfmpegCaptureSource.class);

    /** Ordinary player headers — many CDNs 403 a request without them. Overridable via the env if a
     *  particular stream wants different ones. */
    private static final String USER_AGENT = System.getenv().getOrDefault("MUNI_STREAM_UA",
            "Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36");
    private static final String REFERER = System.getenv().getOrDefault("MUNI_STREAM_REFERER",
            "https://www.bloomberg.com/live");
    /** yt-dlp resolves a watch/live page to the CURRENT media URL. Those URLs expire, so this runs per
     *  capture rather than being stored anywhere. */
    private static final String YTDLP = System.getenv().getOrDefault("MUNI_YTDLP_BIN", "yt-dlp");

    private final String ffmpegBin;
    private final String device;     // e.g. "alsa:hw:1,0" or "pulse:default.monitor"
    private final int seconds;

    public FfmpegCaptureSource(String ffmpegBin, String device, int seconds) {
        this.ffmpegBin = ffmpegBin;
        this.device = device;
        this.seconds = seconds;
    }

    /** The exact ffmpeg invocation for this source — package-private so the argument shape is TESTED.
     *  Wrong ffmpeg arguments have silently broken this pipeline twice (an .ogg container whisper cannot
     *  read; a device string passed as a format), so the command is asserted rather than assumed. */
    List<String> buildCommand(String outPath) {
        if (isYtdlp()) {
            // ffmpeg cannot read a watch page, and "yt" is not an input format. A yt: source MUST be
            // resolved to a media URL first (captureChunk does that) — reaching here would otherwise build
            // `-f yt -i https://…`, which fails with an ffmpeg error that names neither cause nor fix.
            throw new IllegalStateException(
                    "a 'yt:' source must be resolved by yt-dlp before ffmpeg runs; got " + device);
        }
        int sep = device.indexOf(':');
        String format = device.substring(0, sep);
        String name = device.substring(sep + 1);
        List<String> cmd = new java.util.ArrayList<>(List.of(
                ffmpegBin, "-hide_banner", "-loglevel", "error", "-y"));
        if (isUrl()) {
            // A broadcaster's CDN generally refuses a request with no browser identity, so send the same
            // User-Agent and Referer a player sends. These are ordinary playback headers for a publicly
            // published manifest, not a bypass of any access control; a stream that requires credentials
            // still refuses, loudly.
            cmd.addAll(List.of("-user_agent", USER_AGENT, "-referer", REFERER, "-rw_timeout", "15000000"));
            cmd.addAll(List.of("-i", device.substring(4), "-t", Integer.toString(seconds), "-vn"));
        } else {
            cmd.addAll(List.of("-f", format, "-i", name, "-t", Integer.toString(seconds)));
        }
        cmd.addAll(List.of("-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", outPath));
        return cmd;
    }

    /** True for a NETWORK stream source ({@code url:https://…}) rather than a host audio device. */
    private boolean isUrl() {
        return device.regionMatches(true, 0, "url:", 0, 4);
    }

    /** True for a page yt-dlp must resolve first ({@code yt:https://youtube.com/watch?v=…}). */
    private boolean isYtdlp() {
        return device.regionMatches(true, 0, "yt:", 0, 3);
    }

    /**
     * Ask yt-dlp for the current audio URL behind a watch/live page.
     *
     * <p>A live stream's media URL is issued by the CDN and EXPIRES, so it must be resolved at capture time
     * and never stored in the registry — storing one would work once and then fail forever with an opaque
     * 403. {@code -f bestaudio} keeps the download to the audio rendition; the page URL is all the registry
     * holds.
     */
    private String resolveViaYtdlp() {
        String page = device.substring(3);
        try {
            Process p = new ProcessBuilder(YTDLP, "-f", "bestaudio/best", "-g", "--no-warnings", page)
                    .redirectErrorStream(true).start();
            // Wait FIRST, then read: reading to EOF on this thread would block forever on a hung yt-dlp and
            // make the timeout below unreachable. The output here is a URL or two, far under the pipe
            // buffer, so nothing can deadlock on an unread pipe.
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("yt-dlp timed out resolving " + page);
            }
            String out = new String(p.getInputStream().readAllBytes()).strip();
            if (p.exitValue() != 0 || out.isBlank()) {
                throw new IOException("yt-dlp could not resolve " + page + ": " + out);
            }
            // -g prints one URL per line (audio first with bestaudio); take the first.
            String url = out.lines().filter(l -> l.startsWith("http")).findFirst().orElse("");
            if (url.isBlank()) {
                throw new IOException("yt-dlp returned no media URL for " + page + ": " + out);
            }
            log.info("yt-dlp resolved {} to a live media URL ({} chars)", page, url.length());
            return url;
        } catch (IOException e) {
            throw new RuntimeException("yt-dlp resolve failed for " + page + " — is yt-dlp installed? "
                    + "(pip install -U yt-dlp). Cause: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("yt-dlp resolve interrupted", e);
        }
    }

    @Override
    public byte[] captureChunk() {
        int sep = device.indexOf(':');
        if (sep < 0) {
            throw new IllegalArgumentException(
                    "source must be '<format>:<name>' (e.g. alsa:hw:1,0), 'url:<stream>' or "
                    + "'yt:<page>'; got " + device);
        }
        if (isYtdlp()) {
            // Resolve the expiring media URL now, then capture it exactly like any other stream.
            return new FfmpegCaptureSource(ffmpegBin, "url:" + resolveViaYtdlp(), seconds).captureChunk();
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
            //
            // Two source kinds, one pipeline:
            //   url:<stream>       — read the STREAM directly (HLS/DASH/Icecast/…). No browser, no sound
            //                        card, no display, no PulseAudio: works headless and unattended, which
            //                        is what "pull audio from an online source" actually needs. `-vn` drops
            //                        any video track; the demuxer is detected from the stream itself.
            //   <format>:<name>    — a host audio input (alsa/pulse), for capturing what THIS box plays.
            List<String> cmd = buildCommand(out.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // A network stream must also CONNECT and buffer before it records, so it gets more headroom
            // than a local device (which starts instantly).
            if (!p.waitFor(seconds + (isUrl() ? 90L : 30L), TimeUnit.SECONDS)) {
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
