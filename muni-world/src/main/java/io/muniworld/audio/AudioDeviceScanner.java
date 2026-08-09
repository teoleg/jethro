package io.muniworld.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers the audio inputs the HOST actually exposes, in the exact {@code <ffmpeg-format>:<name>} form the
 * registry and {@link FfmpegCaptureSource} expect — so binding a feed is a choice from a real list rather
 * than a hand-typed guess.
 *
 * <p>Two sources, both read-only and best-effort:
 * <ul>
 *   <li><b>PulseAudio / PipeWire</b> via {@code pactl list sources short}. A {@code .monitor} source is the
 *       loopback of what the machine is PLAYING — that is what captures TV audio from a browser tab.</li>
 *   <li><b>ALSA</b> via {@code arecord -l} — physical capture hardware (a USB audio dongle), for a box with
 *       no Pulse/PipeWire.</li>
 * </ul>
 *
 * <p>A missing tool is not an error: it means that subsystem is not present, and the result says so. Nothing
 * here is invented — if the host exposes no input, the list is empty and the UI says exactly that rather
 * than offering a device that cannot work.
 */
@Component
public final class AudioDeviceScanner {

    private static final Logger log = LoggerFactory.getLogger(AudioDeviceScanner.class);

    /** One bindable input: {@code device} is the registry value verbatim. {@code active} = audio is
     *  flowing through this sink RIGHT NOW (PulseAudio state RUNNING), which is the fastest way to tell
     *  which monitor carries the thing you are playing. */
    public record Device(String device, String label, String kind, boolean monitor, boolean active) {
    }

    /** What the host offers, plus which tools answered (so "none" can be explained honestly). */
    public record Scan(List<Device> devices, List<String> tools, String note) {
    }

    public Scan scan() {
        List<Device> devices = new ArrayList<>();
        List<String> tools = new ArrayList<>();

        String pactl = run("pactl", "list", "sources", "short");
        if (pactl != null) {
            tools.add("pactl");
            // Which sinks are RUNNING (something is playing into them) — so the monitor that actually
            // carries your audio can be pointed at, instead of guessed from a list of near-identical names.
            devices.addAll(parsePactl(pactl, runningSinks()));
        }

        String arecord = run("arecord", "-l");
        if (arecord != null) {
            tools.add("arecord");
            devices.addAll(parseArecord(arecord));
        }

        String note;
        if (tools.isEmpty()) {
            note = "neither pactl (PulseAudio/PipeWire) nor arecord (ALSA) is installed — no way to enumerate "
                   + "audio inputs on this host. Install pulseaudio-utils or alsa-utils.";
        } else if (devices.stream().noneMatch(Device::active) && devices.stream().anyMatch(Device::monitor)) {
            note = "audio inputs found, but NO sink is playing anything right now — every monitor would "
                   + "record digital silence. Start the channel playing on THIS machine first, then rescan; "
                   + "the device carrying it will be marked 'AUDIO PLAYING NOW'.";
        } else if (devices.isEmpty()) {
            note = "audio tooling is present (" + String.join(", ", tools) + ") but the host exposes no "
                   + "capture input. On a headless box start a sound server, or plug in a capture device.";
        } else {
            note = null;
        }
        return new Scan(devices, tools, note);
    }

    /** {@code index  NAME  MODULE  SPEC  STATE} — the NAME column is what ffmpeg's pulse input takes. */
    private static List<Device> parsePactl(String out, java.util.Set<String> runningSinks) {
        List<Device> devices = new ArrayList<>();
        for (String line : out.split("\r?\n")) {
            String[] cols = line.strip().split("\\s+");
            if (cols.length < 2 || cols[1].isBlank()) {
                continue;
            }
            String name = cols[1];
            boolean monitor = name.endsWith(".monitor");
            // A monitor belongs to the sink whose name it prefixes: "<sink>.monitor".
            boolean active = monitor
                    && runningSinks.contains(name.substring(0, name.length() - ".monitor".length()));
            devices.add(new Device("pulse:" + name,
                    name + (monitor ? "  (system-audio loopback — captures what this machine plays)" : "")
                         + (active ? "  ← AUDIO PLAYING NOW" : ""),
                    "pulse", monitor, active));
        }
        return devices;
    }

    /** Sink names PulseAudio reports as RUNNING, i.e. something is actively playing into them. */
    private java.util.Set<String> runningSinks() {
        java.util.Set<String> running = new java.util.LinkedHashSet<>();
        String out = run("pactl", "list", "sinks", "short");
        if (out == null) {
            return running;
        }
        for (String line : out.split("\r?\n")) {
            String[] cols = line.strip().split("\\s+");
            if (cols.length >= 2 && line.contains("RUNNING")) {
                running.add(cols[1]);
            }
        }
        return running;
    }

    /** {@code card 1: Device [USB Audio], device 0: USB Audio [USB Audio]} → {@code alsa:hw:1,0}. */
    private static List<Device> parseArecord(String out) {
        List<Device> devices = new ArrayList<>();
        Matcher m = Pattern.compile("card (\\d+): ([^\\[]+)\\[[^\\]]*\\], device (\\d+): ([^\\[]+)")
                .matcher(out);
        while (m.find()) {
            String dev = "alsa:hw:" + m.group(1) + "," + m.group(3);
            devices.add(new Device(dev, m.group(2).strip() + " — " + m.group(4).strip(), "alsa", false, false));
        }
        return devices;
    }

    /** Run a discovery command; null when the tool is absent or fails (never throws — this is diagnostics). */
    private String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? new String(out) : null;
        } catch (IOException e) {
            return null;                    // tool not installed on this host
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Diagnostic map for the API. */
    public Map<String, Object> asMap() {
        Scan s = scan();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", s.devices());
        out.put("tools", s.tools());
        out.put("note", s.note());
        log.info("audio device scan: {} device(s) via {}", s.devices().size(),
                s.tools().isEmpty() ? "no tooling" : String.join("+", s.tools()));
        return out;
    }
}
