package io.muniworld.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Records a fixed-duration chunk of a live stream with <b>ffmpeg</b>, as 16 kHz mono PCM WAV — whisper.cpp's
 * only readable input (ADR-0014).
 *
 * <p><b>One acquisition path: the network.</b> No TV, no HDMI, no browser, no sound card, no display, nothing
 * playing on this box. The earlier host-audio path (ALSA line-in / PulseAudio {@code .monitor} loopback) is
 * gone: it needed a machine that was already playing the channel, and when it wasn't it recorded a
 * full-length run of zeros that looked like a perfectly healthy capture.
 *
 * <p>A source is either:
 * <ul>
 *   <li>{@code yt:<page>} — a publisher's live page. yt-dlp resolves the CURRENT media URL per capture, since
 *       live CDN URLs expire; the registry stores the page, never the resolved URL.</li>
 *   <li>{@code url:<stream>} — a direct HLS/DASH/Icecast URL, read straight off the network.</li>
 * </ul>
 * They are the same pipeline — {@code yt:} is {@code url:} with a resolve step in front.
 *
 * <p>Not a Spring bean: the capture loop constructs one per feed per pass. It shells out to ffmpeg, so it
 * only runs where ffmpeg exists (the Pi) — never in the sandbox or tests, which use a stub instead.
 */
public final class FfmpegCaptureSource implements AudioCaptureConnector.CaptureSource {

    private static final Logger log = LoggerFactory.getLogger(FfmpegCaptureSource.class);

    /** Ordinary player headers — many CDNs 403 a request without them. Overridable via the env if a
     *  particular stream wants different ones. */
    private static final String USER_AGENT = System.getenv().getOrDefault("MUNI_STREAM_UA",
            "Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36");
    private static final String REFERER = System.getenv().getOrDefault("MUNI_STREAM_REFERER",
            "https://www.bloomberg.com/live");
    private static final String YTDLP = System.getenv().getOrDefault("MUNI_YTDLP_BIN", "yt-dlp");

    /**
     * Current yt-dlp needs a JAVASCRIPT RUNTIME to extract YouTube ("YouTube extraction without a JS
     * runtime has been deprecated, and some formats may be missing" — and audio-only formats are exactly
     * what goes missing). Deno is the one it enables by default, but a background service's PATH usually
     * excludes {@code ~/.deno/bin}, so the path is resolved here and passed explicitly.
     */
    private static final String JS_RUNTIME = resolveJsRuntime();

    private static String resolveJsRuntime() {
        String configured = System.getenv("MUNI_JS_RUNTIME");
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        for (String candidate : List.of(System.getProperty("user.home", "") + "/.deno/bin/deno",
                "/usr/local/bin/deno", "/usr/bin/deno")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "";      // maybe already on PATH — let yt-dlp find it itself
    }

    private final String ffmpegBin;
    private final String source;     // "yt:<page>" or "url:<stream>"
    private final int seconds;

    public FfmpegCaptureSource(String ffmpegBin, String source, int seconds) {
        this.ffmpegBin = ffmpegBin;
        this.source = source == null ? "" : source.strip();
        this.seconds = seconds;
    }

    /** True when {@code source} needs yt-dlp before ffmpeg can read it. */
    public static boolean usesYtdlp(String source) {
        return source != null && source.regionMatches(true, 0, "yt:", 0, 3);
    }

    /**
     * Null when yt-dlp is runnable, else WHY it is not — so a missing tool is named as a gate on the status
     * page instead of only surfacing as a failed capture. Cached: the status endpoint is polled every few
     * seconds and this forks a process.
     */
    public static String ytdlpBlocker() {
        long now = System.currentTimeMillis();
        if (now - ytdlpCheckedAt < YTDLP_RECHECK_MS) {
            return ytdlpBlocker;
        }
        String reason;
        try {
            Process p = new ProcessBuilder(YTDLP, "--version").redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                reason = "yt-dlp did not respond to --version";
            } else if (p.exitValue() != 0) {
                reason = "yt-dlp exited " + p.exitValue() + " for --version";
            } else {
                reason = staleReason(new String(p.getInputStream().readAllBytes()).strip());
            }
        } catch (IOException e) {
            reason = "yt-dlp is NOT installed on this host (or not on this process's PATH) — run "
                    + "`scripts/svc.sh setup tv`, or `python3 -m pip install --user -U yt-dlp` and set "
                    + "MUNI_YTDLP_BIN to its absolute path in local.env";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reason = "interrupted while checking yt-dlp";
        }
        ytdlpBlocker = reason;
        ytdlpCheckedAt = now;
        return reason;
    }

    private static final long YTDLP_RECHECK_MS = 60_000;
    private static volatile String ytdlpBlocker;
    private static volatile long ytdlpCheckedAt;

    /**
     * An INSTALLED yt-dlp can still be useless: YouTube changes its player continuously, and a yt-dlp that
     * predates the current one fails every extraction with "No video formats found". The Pi had 2023.03.04
     * from apt — three years stale — and reported "ready" while nothing could ever resolve.
     *
     * <p>yt-dlp versions are dates ({@code YYYY.MM.DD}), so staleness is measured against TODAY rather than
     * a hardcoded floor that would itself rot. A year behind is the threshold: recent enough to avoid false
     * alarms, old enough that YouTube extraction is unreliable. Returns null when the version looks current
     * or cannot be parsed — never block on a version string we do not understand.
     */
    static String staleReason(String version) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^(\\d{4})\\.(\\d{2})\\.(\\d{2})").matcher(version);
        if (!m.find()) {
            return null;
        }
        java.time.LocalDate released = java.time.LocalDate.of(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        if (!released.isBefore(java.time.LocalDate.now().minusYears(1))) {
            return null;
        }
        return "yt-dlp is " + version + " — over a year old, and YouTube extraction breaks well before "
                + "that ('No video formats found'). apt's package is stale by years; install the current "
                + "one: `python3 -m pip install --user -U yt-dlp` (or the standalone binary from "
                + "github.com/yt-dlp/yt-dlp/releases) and point MUNI_YTDLP_BIN at it";
    }

    /**
     * The exact ffmpeg invocation for a resolved stream URL — package-private so the argument shape is
     * TESTED. Wrong ffmpeg arguments have silently broken this pipeline twice (an .ogg container whisper
     * cannot read; a source string passed as an input format), so the command is asserted, not assumed.
     */
    List<String> buildCommand(String url, String outPath) {
        List<String> cmd = new ArrayList<>(List.of(ffmpegBin, "-hide_banner", "-loglevel", "error", "-y"));
        // A broadcaster's CDN generally refuses a request with no browser identity, so send the same
        // User-Agent and Referer a player sends. These are ordinary playback headers for a publicly
        // published stream, not a bypass of any access control; a stream that requires credentials still
        // refuses, loudly.
        cmd.addAll(List.of("-user_agent", USER_AGENT, "-referer", REFERER, "-rw_timeout", "15000000"));
        // No -f: the demuxer is detected from the stream itself. -vn drops any video track.
        cmd.addAll(List.of("-i", url, "-t", Integer.toString(seconds), "-vn"));
        // 16 kHz MONO 16-bit PCM WAV is NOT a preference — it is whisper.cpp's input contract (it bundles
        // no decoder). This once wrote Opus in an .ogg container, which whisper cannot open, so capture
        // looked fine while EVERY transcription failed.
        cmd.addAll(List.of("-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", outPath));
        return cmd;
    }

    /**
     * Ask yt-dlp for the current audio URL behind a live page.
     *
     * <p>A live stream's media URL is issued by the CDN and EXPIRES, so it is resolved at capture time and
     * never stored — storing one would work once and then fail forever with an opaque 403.
     */
    private String resolveViaYtdlp(String page) {
        String last = "";
        for (String[] extra : RESOLVE_ATTEMPTS) {
            try {
                return runYtdlp(page, extra);
            } catch (IOException e) {
                last = e.getMessage();
                if (extra.length > 0) {
                    log.warn("yt-dlp resolve failed with {} — {}", String.join(" ", extra), last);
                }
            }
        }
        // yt-dlp RAN and refused, on every client. Its own message is the useful part. "No video formats
        // found" is nearly always a yt-dlp that predates YouTube's current player, so name the version:
        // without it the reader cannot tell a stale tool from a dead stream, and they have opposite fixes.
        throw new RuntimeException("cannot resolve " + page + " — " + last
                + ". [yt-dlp " + ytdlpVersion() + "] If this says \"No video formats found\", yt-dlp is "
                + "almost certainly out of date: `python3 -m pip install --user -U yt-dlp` (an apt-installed "
                + "yt-dlp is usually months stale). If the stream itself is gone, put the current live page "
                + "in seeds/audio-sources.csv");
    }

    /**
     * YouTube serves different player clients, and a yt-dlp release can be broken for one while working on
     * another. Trying the default first and then a couple of named clients turns a hard failure into a
     * retry that usually succeeds — this is the same knob the yt-dlp issue tracker hands out, not a bypass
     * of anything. Empty = whatever the installed yt-dlp picks by default.
     */
    private static final List<String[]> RESOLVE_ATTEMPTS = List.of(
            new String[] {},
            new String[] {"--extractor-args", "youtube:player_client=tv"},
            new String[] {"--extractor-args", "youtube:player_client=ios"},
            new String[] {"--extractor-args", "youtube:player_client=web_safari"});

    /**
     * Does the installed yt-dlp accept {@code --js-runtimes}? Older releases do not, and passing an unknown
     * option makes EVERY attempt fail on argument parsing — a self-inflicted outage worse than the problem
     * the flag solves. Asked once of {@code --help}, then cached.
     */
    private static synchronized boolean supportsJsRuntimes() {
        if (jsRuntimesSupported == null) {
            jsRuntimesSupported = false;
            try {
                Process p = new ProcessBuilder(YTDLP, "--help").redirectErrorStream(true).start();
                String help = new String(p.getInputStream().readAllBytes());
                p.waitFor(20, TimeUnit.SECONDS);
                jsRuntimesSupported = help.contains("--js-runtimes");
            } catch (IOException e) {
                jsRuntimesSupported = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("yt-dlp JS runtime: {}", !jsRuntimesSupported ? "option unsupported by this yt-dlp"
                    : JS_RUNTIME.isBlank() ? "none found — YouTube may expose no audio-only format"
                    : JS_RUNTIME);
        }
        return jsRuntimesSupported;
    }

    private static volatile Boolean jsRuntimesSupported;

    /** The installed yt-dlp's version, or "unknown" — for error messages, cached with the presence check. */
    private static String ytdlpVersion() {
        try {
            Process p = new ProcessBuilder(YTDLP, "--version").redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "unknown";
            }
            String v = new String(p.getInputStream().readAllBytes()).strip();
            return v.isBlank() ? "unknown" : v;
        } catch (IOException e) {
            return "not installed";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    /** One resolve attempt. Throws {@link IOException} when yt-dlp runs and refuses. */
    private String runYtdlp(String page, String[] extra) throws IOException {
        // bestaudio/best, never bare bestaudio: without a JS runtime YouTube exposes no audio-only
        // rendition for a live stream, and `-f bestaudio` then fails outright with "Requested format is
        // not available". The `/best` fallback takes the combined HLS rendition instead and ffmpeg's -vn
        // drops the video — more bandwidth, but it works with no runtime at all.
        List<String> cmd = new ArrayList<>(List.of(YTDLP, "-f", "bestaudio/best", "-g", "--no-warnings"));
        if (!JS_RUNTIME.isBlank() && supportsJsRuntimes()) {
            cmd.addAll(List.of("--js-runtimes", "deno:" + JS_RUNTIME));
        }
        cmd.addAll(List.of(extra));
        cmd.add(page);
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            // ONLY this branch means the binary is missing. Reporting "is yt-dlp installed?" on every
            // failure was misleading: it printed that while yt-dlp was installed, had run, and had given a
            // perfectly clear answer ("This live stream recording is not available") — sending the reader
            // after the wrong problem.
            throw new RuntimeException("cannot run yt-dlp (" + YTDLP + ") — install it with "
                    + "`python3 -m pip install --user -U yt-dlp` and set MUNI_YTDLP_BIN to its absolute "
                    + "path. Cause: " + e.getMessage(), e);
        }
        try {
            // Wait FIRST, then read: reading to EOF on this thread would block forever on a hung yt-dlp and
            // make the timeout below unreachable. The output is a URL or two, far under the pipe buffer.
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("yt-dlp timed out resolving " + page);
            }
            String out = new String(p.getInputStream().readAllBytes()).strip();
            if (p.exitValue() != 0 || out.isBlank()) {
                throw new IOException("yt-dlp says: " + out.lines().reduce((a, x) -> x).orElse("(no output)"));
            }
            String url = out.lines().filter(l -> l.startsWith("http")).findFirst().orElse("");
            if (url.isBlank()) {
                throw new IOException("yt-dlp returned no media URL: " + out);
            }
            log.info("yt-dlp resolved {} to a live media URL{}", page,
                    extra.length == 0 ? "" : " (" + String.join(" ", extra) + ")");
            return url;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("yt-dlp resolve interrupted", e);
        }
    }

    /** The stream URL ffmpeg should read: a {@code url:} source verbatim, a {@code yt:} source resolved. */
    private String streamUrl() {
        if (usesYtdlp(source)) {
            return resolveViaYtdlp(source.substring(3));
        }
        if (source.regionMatches(true, 0, "url:", 0, 4)) {
            return source.substring(4);
        }
        throw new IllegalArgumentException(
                "a source must be 'yt:<page>' or 'url:<stream>'; got " + source);
    }

    @Override
    public byte[] captureChunk() {
        String url = streamUrl();
        Path out = null;
        try {
            out = Files.createTempFile("muni-capture", ".wav");
            Process p = new ProcessBuilder(buildCommand(url, out.toString()))
                    .redirectErrorStream(true).start();
            // A network stream must CONNECT and buffer before it records, so it gets headroom on top of the
            // chunk length.
            if (!p.waitFor(seconds + 90L, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg capture timed out");
            }
            if (p.exitValue() != 0) {
                throw new IOException("ffmpeg exited " + p.exitValue()
                        + ": " + new String(p.getInputStream().readAllBytes()));
            }
            byte[] bytes = Files.readAllBytes(out);
            log.info("captured {}s from {} ({} bytes)", seconds, source, bytes.length);
            return bytes;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("audio capture failed for " + source, e);
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
        return "ffmpeg:" + source;
    }
}
