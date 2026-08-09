package io.muniworld.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Grabs a SINGLE still frame of the machine's screen, so the operator can see what was on the TV next to
 * what the ASR heard — the visual half of the ADR-0014 validation surface ("compare it to what's on the
 * TV"), which until now could only be done by looking at the physical screen.
 *
 * <p><b>Stills, not a stream, and nothing is kept.</b> One JPEG is grabbed on request, returned, and the
 * temp file deleted — the same posture as the audio chunk. This is a private local viewfinder for verifying
 * capture; it neither records nor redistributes the broadcast (ADR-0014).
 *
 * <p>Only meaningful where the feed is actually playing ON this machine — which is exactly the case that
 * makes a PulseAudio {@code .monitor} device work, since that captures what this machine plays. A headless
 * box with no display has nothing to grab and says so rather than returning a black frame.
 */
@Component
public final class ScreenFrameGrabber {

    private static final Logger log = LoggerFactory.getLogger(ScreenFrameGrabber.class);

    private final String ffmpegBin;
    private final String configuredDevice;
    private final int timeoutSec;

    public ScreenFrameGrabber(
            @Value("${muni.audio.ffmpeg.bin:ffmpeg}") String ffmpegBin,
            @Value("${muni.video.screen.device:}") String device,
            @Value("${muni.video.screen.timeout-sec:15}") int timeoutSec) {
        this.ffmpegBin = ffmpegBin;
        this.configuredDevice = device == null ? "" : device.strip();
        this.timeoutSec = timeoutSec;
    }

    /** The ffmpeg input to grab: the configured one, else X11 on this session's DISPLAY (default {@code :0}). */
    public String device() {
        if (!configuredDevice.isBlank()) {
            return configuredDevice;
        }
        String display = System.getenv("DISPLAY");
        return "x11grab:" + (display == null || display.isBlank() ? ":0" : display);
    }

    /** Why a grab cannot work here, or null when it should. Cheap — no capture attempted. */
    public String blocker() {
        String dev = device();
        if (dev.startsWith("x11grab:") && (System.getenv("DISPLAY") == null || System.getenv("DISPLAY").isBlank())
                && configuredDevice.isBlank()) {
            return "no DISPLAY in this process's environment — screen capture needs a desktop session "
                   + "(this box may be headless, or muni-world was started outside it). Set "
                   + "MUNI_SCREEN_DEVICE, e.g. x11grab::0, if a display does exist.";
        }
        return null;
    }

    /** Grab one JPEG frame. Throws with ffmpeg's own message when it cannot — never a blank image. */
    public byte[] grab() {
        String dev = device();
        int sep = dev.indexOf(':');
        if (sep < 0) {
            throw new IllegalArgumentException("screen device must be '<ffmpeg-format>:<input>', got " + dev);
        }
        String format = dev.substring(0, sep);
        String input = dev.substring(sep + 1);
        Path out = null;
        try {
            out = Files.createTempFile("muni-frame", ".jpg");
            // -frames:v 1 → exactly one frame; -q:v 4 keeps it small enough to poll in a page.
            List<String> cmd = List.of(ffmpegBin, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", format, "-i", input, "-frames:v", "1", "-q:v", "4", out.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] stdout = p.getInputStream().readAllBytes();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("screen grab timed out after " + timeoutSec + "s");
            }
            if (p.exitValue() != 0) {
                throw new IOException("ffmpeg exited " + p.exitValue() + " for " + dev + ": "
                        + new String(stdout).strip());
            }
            byte[] jpeg = Files.readAllBytes(out);
            log.debug("screen frame grabbed from {} ({} bytes)", dev, jpeg.length);
            return jpeg;
        } catch (IOException e) {
            throw new RuntimeException("screen grab failed for " + dev + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("screen grab interrupted", e);
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);       // stills are never kept (ADR-0014)
                } catch (IOException ignored) {
                    // best-effort cleanup of a temp file
                }
            }
        }
    }

    /** Status for the UI: the device in play and whether a grab is possible. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("device", device());
        out.put("blocker", blocker());
        out.put("available", blocker() == null);
        return out;
    }
}
