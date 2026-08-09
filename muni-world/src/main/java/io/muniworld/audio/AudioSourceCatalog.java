package io.muniworld.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The TV/audio source registry (ADR-0014) — loads the editable feed catalog so feeds are configured like the
 * market-data/social sources (ADR-0003), not as a single env var. Reads a <b>host file</b> when
 * {@code muni.audio.sources.file} points at one (edit it, then {@code svc.sh restart tv}); otherwise the
 * committed classpath default ({@code /seeds/audio-sources.csv}). Lines starting with {@code #} are comments.
 *
 * <p><b>Pipe-delimited</b> ({@code id|label|publisher|category|device|chunk_seconds|enabled|notes}) because a
 * device value can itself contain a comma (ALSA {@code hw:1,0}). Read-only at runtime; the API and UI list
 * it, and the capture loop drives from {@link #capturable()}.
 */
@Component
public final class AudioSourceCatalog {

    private static final Logger log = LoggerFactory.getLogger(AudioSourceCatalog.class);

    private final String file;
    private volatile List<AudioSource> sources;

    public AudioSourceCatalog(@Value("${muni.audio.sources.file:}") String file) {
        this.file = redirectIfTracked(file);
        seedIfMissing(this.file);
        // Load from the REDIRECTED path, not the argument — otherwise the live copy is seeded and then
        // ignored, and the app reads the template it must never touch.
        this.sources = load(this.file);
        log.info("AudioSourceCatalog loaded {} feed(s) ({} capturable) from {}",
                sources.size(), sources.stream().filter(AudioSource::capturable).count(),
                (this.file == null || this.file.isBlank()) ? "classpath default" : this.file);
    }

    /**
     * Never write to the COMMITTED template, whatever the config says.
     *
     * <p>Binding a device rewrites the registry. If that registry is the tracked
     * {@code muni-world/seeds/audio-sources.csv}, every later {@code git pull} aborts with "your local
     * changes would be overwritten" — which happened twice to the owner, because an older local.env still
     * pointed there and no amount of fixing the default reaches a machine that already has the old value.
     * So the redirect happens in CODE: a configured path that IS the template silently becomes the
     * untracked local copy, seeded from it.
     */
    private static String redirectIfTracked(String file) {
        if (file == null || file.isBlank()) {
            return file;
        }
        Path p = Path.of(file).normalize();
        boolean isTemplate = p.endsWith(Path.of("muni-world", "seeds", "audio-sources.csv"))
                || p.endsWith(Path.of("seeds", "audio-sources.csv"));
        if (!isTemplate) {
            return file;
        }
        String live = "local/audio-sources.csv";
        log.warn("registry {} is the COMMITTED template and would collide with every git pull — using {} "
                + "instead (seeded from the template). Update MUNI_AUDIO_SOURCES_FILE to silence this.",
                file, live);
        return live;
    }

    /**
     * Create the host registry from the committed default the first time it is needed.
     *
     * <p>This is what lets the LIVE registry live OUTSIDE git. Binding a device rewrites this file, so if it
     * is a tracked file every subsequent {@code git pull} aborts with "local changes would be overwritten" —
     * the operator's own working configuration fighting the repo on every update. Seeding on first use means
     * the committed CSV stays a pure template and the working copy is the operator's, untracked.
     */
    private static void seedIfMissing(String file) {
        if (file == null || file.isBlank()) {
            return;                                   // classpath default, read-only — nothing to seed
        }
        Path p = Path.of(file);
        if (Files.exists(p)) {
            return;
        }
        try (InputStream in = AudioSourceCatalog.class.getResourceAsStream("/seeds/audio-sources.csv")) {
            if (in == null) {
                return;
            }
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.write(p, in.readAllBytes());
            log.info("seeded a fresh TV source registry at {} from the committed default", p.toAbsolutePath());
        } catch (IOException e) {
            log.warn("could not seed the TV source registry at {}: {}", file, e.toString());
        }
    }

    /** All registered feeds (capturable or not) — for the API/UI. */
    public List<AudioSource> all() {
        return sources;
    }

    /** The feeds the capture loop should actually record (enabled AND device-bound). */
    public List<AudioSource> capturable() {
        return sources.stream().filter(AudioSource::capturable).toList();
    }

    /** The registry file backing this catalog, or empty when it is the read-only classpath default. */
    public String file() {
        return file == null ? "" : file;
    }

    /**
     * Bind {@code feedId} to {@code device} and enable it, by rewriting that row of the registry file, then
     * reload. This is the same edit the operator would make by hand — persisted to the same host file, so it
     * survives a restart and stays the single source of truth (there is no second, in-memory registry).
     *
     * <p>Requires a host registry file: the classpath default lives inside the jar and cannot be written, so
     * that case fails loudly rather than pretending to save. The device is stored verbatim — validating it is
     * ffmpeg's job at capture time, and a bad device fails loudly there (never silently).
     */
    public synchronized void bind(String feedId, String device, boolean enabled) {
        if (file == null || file.isBlank()) {
            throw new IllegalStateException(
                    "no host registry file configured (muni.audio.sources.file) — the classpath default is "
                    + "inside the jar and cannot be edited. Point MUNI_AUDIO_SOURCES_FILE at a writable copy.");
        }
        Path p = Path.of(file);
        String csv = readHostFile(file);
        if (csv == null) {
            throw new IllegalStateException("registry file not readable: " + p.toAbsolutePath());
        }
        String[] lines = csv.split("\r?\n", -1);
        boolean found = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("id|")) {
                continue;
            }
            String[] f = line.split("\\|", 8);
            if (f.length < 7 || !f[0].strip().equals(feedId)) {
                continue;
            }
            f[4] = device == null ? "" : device.strip();      // device column
            f[6] = Boolean.toString(enabled);                  // enabled column
            lines[i] = String.join("|", f);
            found = true;
            break;
        }
        if (!found) {
            throw new IllegalArgumentException("no feed '" + feedId + "' in the registry " + p);
        }
        try {
            Files.writeString(p, String.join("\n", lines), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not write the registry " + p.toAbsolutePath() + ": " + e, e);
        }
        this.sources = load(file);
        log.info("registry: feed {} bound to '{}' (enabled={}) — {} capturable now",
                feedId, device, enabled, capturable().size());
    }

    private List<AudioSource> load(String file) {
        String csv = readHostFile(file);
        if (csv == null) {
            csv = readClasspath();
        }
        return csv == null ? List.of() : parse(csv);
    }

    private String readHostFile(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(file);
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            log.warn("could not read audio sources file {}: {}", file, e.toString());
            return null;
        }
    }

    private String readClasspath() {
        try (InputStream in = getClass().getResourceAsStream("/seeds/audio-sources.csv")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<AudioSource> parse(String csv) {
        List<AudioSource> out = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("id|")) {
                continue;   // skip header + comments + blanks
            }
            String[] f = line.split("\\|", 8);   // pipe-delimited: a device may contain a comma (ALSA hw:1,0)
            if (f.length < 7) {
                continue;
            }
            out.add(new AudioSource(f[0].strip(), f[1].strip(), f[2].strip(), f[3].strip(),
                    f[4].strip(), parseInt(f[5].strip(), 300), "true".equalsIgnoreCase(f[6].strip())));
        }
        return out;
    }

    private static int parseInt(String s, int dflt) {
        try {
            return s.isEmpty() ? dflt : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
