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

    private final List<AudioSource> sources;

    public AudioSourceCatalog(@Value("${muni.audio.sources.file:}") String file) {
        this.sources = load(file);
        log.info("AudioSourceCatalog loaded {} feed(s) ({} capturable) from {}",
                sources.size(), sources.stream().filter(AudioSource::capturable).count(),
                (file == null || file.isBlank()) ? "classpath default" : file);
    }

    /** All registered feeds (capturable or not) — for the API/UI. */
    public List<AudioSource> all() {
        return sources;
    }

    /** The feeds the capture loop should actually record (enabled AND device-bound). */
    public List<AudioSource> capturable() {
        return sources.stream().filter(AudioSource::capturable).toList();
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
